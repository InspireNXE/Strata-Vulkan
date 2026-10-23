/*
 * Copyright (c) InspireNXE <https://github.com/InspireNXE>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.inspirenxe.strata.vulkan;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * What changed between one view's frames, as at most {@link #MAX_RECTS} rectangles that do not overlap, in device
 * pixels from the view's top left.
 *
 * <p>Each primitive of a frame is hashed with its scissor, texture and the stamp of the texture's pixels, and its
 * bounds are kept, clipped to its scissor and the view. A path's coverage counts by its bytes, not by where they lie
 * in the frame's coverage buffer. The primitives of two frames are matched in order: the common start and end first,
 * then each primitive in between with the next equal one of the last frame within {@link #WINDOW} primitives. Every
 * primitive left unmatched, in either frame, damages its bounds, and so does every primitive that samples a
 * {@linkplain DrawList#LIVE live} texture. Redrawing every primitive that touches the damage, in order, then gives what
 * a full redraw gives there.</p>
 *
 * <p>The first frame, a frame of another size, and the frame after {@link #invalidate()} damage the whole view.</p>
 */
final class Damage {
    /** The most rectangles the damage is kept in. */
    static final int MAX_RECTS = 4;
    /** How far ahead in the last frame a primitive is looked for. */
    private static final int WINDOW = 64;
    private static final long PRIME = 0x9E3779B97F4A7C15L;

    // The last frame's primitives, and those of the frame being compared: hash, and bounds as x0, y0, x1, y1
    private long[] hashes = new long[256];
    private int[] bounds = new int[1024];
    private int count;
    private long[] nextHashes = new long[256];
    private int[] nextBounds = new int[1024];
    private int nextCount;
    private int width = -1;
    private int height = -1;
    private boolean valid;

    // The damage, as x0, y0, x1, y1
    private final int[] rects = new int[(MAX_RECTS + 1) * 4];
    private int rectCount;

    /**
     * Makes the next comparison damage the whole view.
     */
    void invalidate() {
        this.valid = false;
    }

    /**
     * Compares a frame's draws with the last frame's, keeps the damage, and makes the frame the last one.
     *
     * @param draws the frame's draws
     * @param width the view's width in device pixels
     * @param height the view's height in device pixels
     * @return how many rectangles the damage has; 0 when the frame draws what the last one did
     */
    int compare(final DrawList draws, final int width, final int height) {
        this.hash(draws, width, height);
        this.rectCount = 0;
        if (!this.valid || width != this.width || height != this.height) {
            if (width > 0 && height > 0) {
                this.add(0, 0, width, height);
            }
        } else {
            this.match();
        }
        // Live textures change without the canvas knowing
        for (int i = 0; i < this.nextCount; i++) {
            if (this.nextHashes[i] == DrawList.LIVE) {
                this.addBounds(this.nextBounds, i);
            }
        }
        this.swap();
        this.width = width;
        this.height = height;
        this.valid = true;
        return this.rectCount;
    }

    /**
     * Returns part of a damage rectangle.
     *
     * @param rect the rectangle, below the count {@link #compare} returned
     * @param field 0 to 3 for its left, top, right and bottom edges
     * @return the edge in device pixels from the view's top left
     */
    int rect(final int rect, final int field) {
        return this.rects[rect * 4 + field];
    }

    int rectCount() {
        return this.rectCount;
    }

    /**
     * Hashes the frame's primitives into the next arrays, leaving out those that draw nothing.
     */
    private void hash(final DrawList draws, final int width, final int height) {
        final int primitives = draws.primitiveCount();
        if (this.nextHashes.length < primitives) {
            this.nextHashes = new long[primitives * 2];
            this.nextBounds = new int[primitives * 8];
        }
        final ByteBuffer vertices = draws.vertices();
        final ByteBuffer masks = draws.masks();
        int kept = 0;
        for (int p = 0; p < primitives; p++) {
            final int start = draws.primitiveStart(p);
            final int end = draws.primitiveEnd(p);
            if (end <= start) {
                continue;
            }
            final int batch = draws.primitiveBatch(p);
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int v = start; v < end; v++) {
                final int at = v * DrawList.STRIDE;
                final float x = vertices.getFloat(at);
                final float y = vertices.getFloat(at + 4);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            final int scissorX = draws.batch(batch, 0);
            final int scissorY = draws.batch(batch, 1);
            final int x0 = Math.max(Math.max(0, scissorX), (int) Math.floor(minX));
            final int y0 = Math.max(Math.max(0, scissorY), (int) Math.floor(minY));
            final int x1 = Math.min(Math.min(width, scissorX + draws.batch(batch, 2)), (int) Math.ceil(maxX));
            final int y1 = Math.min(Math.min(height, scissorY + draws.batch(batch, 3)), (int) Math.ceil(maxY));
            if (x1 <= x0 || y1 <= y0) {
                continue;
            }
            final long stamp = draws.primitiveStamp(p);
            long hash;
            if (stamp == DrawList.LIVE) {
                hash = DrawList.LIVE;
            } else {
                hash = mix(mix(mix(17L, stamp), draws.batchSet(batch)), scissorX);
                hash = mix(mix(mix(hash, scissorY), draws.batch(batch, 2)), draws.batch(batch, 3));
                final boolean mask = (vertices.getInt(start * DrawList.STRIDE + 20) & 0xFF) == DrawList.MASK;
                final long maskHash = mask ? maskHash(vertices, masks, start) : 0L;
                for (int v = start; v < end; v++) {
                    final int at = v * DrawList.STRIDE;
                    for (int field = 0; field < DrawList.STRIDE; field += 4) {
                        // A mask's place in the coverage buffer moves when an earlier path changes; its bytes count
                        hash = mix(hash, mask && field == 36 ? maskHash : vertices.getInt(at + field));
                    }
                }
                if (hash == DrawList.LIVE) {
                    hash++;
                }
            }
            this.nextHashes[kept] = hash;
            this.nextBounds[kept * 4] = x0;
            this.nextBounds[kept * 4 + 1] = y0;
            this.nextBounds[kept * 4 + 2] = x1;
            this.nextBounds[kept * 4 + 3] = y1;
            kept++;
        }
        this.nextCount = kept;
    }

    /**
     * Hashes the coverage bytes of a mask quad, whose first vertex gives the mask's width and its offset in words,
     * and whose third the bottom right corner.
     */
    private static long maskHash(final ByteBuffer vertices, final ByteBuffer masks, final int start) {
        final int at = start * DrawList.STRIDE;
        final int width = (int) vertices.getFloat(at + 32);
        final int height = Math.round(vertices.getFloat(at + 2 * DrawList.STRIDE + 4) - vertices.getFloat(at + 4));
        final int offset = Math.round(vertices.getFloat(at + 36)) * 4;
        final int size = Math.max(0, width * height);
        long hash = mix(31L, size);
        int i = 0;
        for (; i + 8 <= size; i += 8) {
            hash = mix(hash, masks.getLong(offset + i));
        }
        for (; i < size; i++) {
            hash = mix(hash, masks.get(offset + i));
        }
        return hash;
    }

    private static long mix(final long hash, final long value) {
        final long h = (hash ^ value) * PRIME;
        return h ^ (h >>> 29);
    }

    /**
     * Matches the next frame's primitives with the last frame's, and damages the bounds of every one left over.
     */
    private void match() {
        int prefix = 0;
        while (prefix < this.count && prefix < this.nextCount && this.hashes[prefix] == this.nextHashes[prefix]
                && this.hashes[prefix] != DrawList.LIVE) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < this.count - prefix && suffix < this.nextCount - prefix
                && this.hashes[this.count - 1 - suffix] == this.nextHashes[this.nextCount - 1 - suffix]
                && this.hashes[this.count - 1 - suffix] != DrawList.LIVE) {
            suffix++;
        }
        final int oldEnd = this.count - suffix;
        final int newEnd = this.nextCount - suffix;
        int cursor = prefix;
        for (int i = prefix; i < newEnd; i++) {
            final long hash = this.nextHashes[i];
            int found = -1;
            if (hash != DrawList.LIVE) {
                for (int j = cursor; j < oldEnd && j < cursor + WINDOW; j++) {
                    if (this.hashes[j] == hash) {
                        found = j;
                        break;
                    }
                }
            }
            if (found < 0) {
                this.addBounds(this.nextBounds, i);
                continue;
            }
            // The last frame's primitives skipped on the way are gone
            for (int j = cursor; j < found; j++) {
                this.addBounds(this.bounds, j);
            }
            cursor = found + 1;
        }
        for (int j = cursor; j < oldEnd; j++) {
            this.addBounds(this.bounds, j);
        }
    }

    private void addBounds(final int[] from, final int primitive) {
        this.add(from[primitive * 4], from[primitive * 4 + 1], from[primitive * 4 + 2], from[primitive * 4 + 3]);
    }

    /**
     * Adds a rectangle to the damage, joining it with every rectangle it overlaps, and merging the pair whose union
     * adds the least area while there are more than {@link #MAX_RECTS}.
     */
    void add(final int x0, final int y0, final int x1, final int y1) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int ax0 = x0;
        int ay0 = y0;
        int ax1 = x1;
        int ay1 = y1;
        // A rectangle inside one already kept adds nothing
        for (int i = 0; i < this.rectCount; i++) {
            final int at = i * 4;
            if (this.rects[at] <= ax0 && this.rects[at + 1] <= ay0 && this.rects[at + 2] >= ax1
                    && this.rects[at + 3] >= ay1) {
                return;
            }
        }
        boolean joined = true;
        while (joined) {
            joined = false;
            for (int i = 0; i < this.rectCount; i++) {
                final int at = i * 4;
                if (this.rects[at] < ax1 && ax0 < this.rects[at + 2] && this.rects[at + 1] < ay1
                        && ay0 < this.rects[at + 3]) {
                    ax0 = Math.min(ax0, this.rects[at]);
                    ay0 = Math.min(ay0, this.rects[at + 1]);
                    ax1 = Math.max(ax1, this.rects[at + 2]);
                    ay1 = Math.max(ay1, this.rects[at + 3]);
                    this.removeRect(i);
                    joined = true;
                    break;
                }
            }
        }
        final int at = this.rectCount * 4;
        this.rects[at] = ax0;
        this.rects[at + 1] = ay0;
        this.rects[at + 2] = ax1;
        this.rects[at + 3] = ay1;
        this.rectCount++;
        if (this.rectCount > MAX_RECTS) {
            this.mergeCheapest();
        }
    }

    private void mergeCheapest() {
        int bestA = 0;
        int bestB = 1;
        long bestCost = Long.MAX_VALUE;
        for (int a = 0; a < this.rectCount; a++) {
            for (int b = a + 1; b < this.rectCount; b++) {
                final long cost = this.unionArea(a, b) - this.area(a) - this.area(b);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestA = a;
                    bestB = b;
                }
            }
        }
        final int a = bestA * 4;
        final int b = bestB * 4;
        final int x0 = Math.min(this.rects[a], this.rects[b]);
        final int y0 = Math.min(this.rects[a + 1], this.rects[b + 1]);
        final int x1 = Math.max(this.rects[a + 2], this.rects[b + 2]);
        final int y1 = Math.max(this.rects[a + 3], this.rects[b + 3]);
        this.removeRect(bestB);
        this.removeRect(bestA);
        // The union may overlap the others, which add joins
        this.add(x0, y0, x1, y1);
    }

    private long area(final int rect) {
        final int at = rect * 4;
        return (long) (this.rects[at + 2] - this.rects[at]) * (this.rects[at + 3] - this.rects[at + 1]);
    }

    private long unionArea(final int a, final int b) {
        final int x0 = Math.min(this.rects[a * 4], this.rects[b * 4]);
        final int y0 = Math.min(this.rects[a * 4 + 1], this.rects[b * 4 + 1]);
        final int x1 = Math.max(this.rects[a * 4 + 2], this.rects[b * 4 + 2]);
        final int y1 = Math.max(this.rects[a * 4 + 3], this.rects[b * 4 + 3]);
        return (long) (x1 - x0) * (y1 - y0);
    }

    private void removeRect(final int rect) {
        System.arraycopy(this.rects, (rect + 1) * 4, this.rects, rect * 4, (this.rectCount - rect - 1) * 4);
        this.rectCount--;
    }

    private void swap() {
        final long[] hashes = this.hashes;
        this.hashes = this.nextHashes;
        this.nextHashes = hashes;
        final int[] bounds = this.bounds;
        this.bounds = this.nextBounds;
        this.nextBounds = bounds;
        this.count = this.nextCount;
        if (this.nextHashes.length < this.hashes.length) {
            this.nextHashes = Arrays.copyOf(this.nextHashes, this.hashes.length);
            this.nextBounds = Arrays.copyOf(this.nextBounds, this.bounds.length);
        }
    }
}

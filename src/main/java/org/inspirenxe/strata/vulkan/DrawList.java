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

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The vertices, path coverage and batches of one frame, built on the host while the canvas draws and copied to the
 * device when the frame ends. A batch is a run of triangles with one scissor and one texture, drawn in order.
 *
 * <p>Each vertex is 60 bytes: position and uv (4 floats), color (RGBA, 8 bits each), mode, four shape parameters,
 * the round clip's center and half size (4 floats) and its radius, negative for none.</p>
 *
 * <p>Each {@link #begin} starts a primitive: a quad, or the triangles of one polygon fill, with the stamp of the
 * texture it samples. {@link Damage} compares the primitives of two frames.</p>
 */
final class DrawList implements AutoCloseable {
    static final int STRIDE = 60;

    // What each draw is, as the fragment shader reads it from the low byte of the mode
    static final int SOLID = 0;
    static final int TEXTURE = 1;
    static final int GLYPH = 2;
    static final int ROUND_RECT = 3;
    static final int ROUND_RECT_STROKE = 4;
    static final int ELLIPSE = 5;
    static final int ELLIPSE_STROKE = 6;
    static final int SHADOW = 7;
    static final int MASK = 8;
    /** Coverage is all or nothing. */
    static final int HARD = 0x100;
    /** The stamp of a texture whose pixels can change at any time, such as an application's image. */
    static final long LIVE = Long.MIN_VALUE;

    private ByteBuffer vertices;
    private int vertexCount;
    private ByteBuffer masks;
    private int maskBytes;

    // Per batch: scissor x, y, width, height, first vertex, vertex count
    private int[] batches;
    private long[] sets;
    private int batchCount;

    // Per primitive: first vertex and batch, and the stamp of its texture
    private int[] primitives;
    private long[] stamps;
    private int primitiveCount;

    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;
    private long set;
    private long stamp;

    private float clipX;
    private float clipY;
    private float clipHalfWidth;
    private float clipHalfHeight;
    private float clipRadius = -1f;

    DrawList() {
        this.vertices = MemoryUtil.memAlloc(STRIDE * 1024);
        this.masks = MemoryUtil.memAlloc(4096);
        this.batches = new int[6 * 64];
        this.sets = new long[64];
        this.primitives = new int[2 * 256];
        this.stamps = new long[256];
    }

    /**
     * Empties the list for a new frame.
     */
    void reset() {
        this.vertexCount = 0;
        this.maskBytes = 0;
        this.batchCount = 0;
        this.primitiveCount = 0;
        this.clipRadius = -1f;
    }

    /**
     * Sets the scissor later draws use, in device pixels from the viewport's top left.
     */
    void scissor(final int x, final int y, final int width, final int height) {
        this.scissorX = x;
        this.scissorY = y;
        this.scissorWidth = width;
        this.scissorHeight = height;
    }

    /**
     * Sets the descriptor set of a texture whose pixels never change, which later draws sample.
     *
     * @param set the set
     */
    void texture(final long set) {
        this.texture(set, 0L);
    }

    /**
     * Sets the descriptor set of the texture later draws sample, and the stamp of its pixels.
     *
     * @param set the set
     * @param stamp a value that changes whenever the texture's pixels do, or {@link #LIVE}
     */
    void texture(final long set, final long stamp) {
        this.set = set;
        this.stamp = stamp;
    }

    /**
     * Sets the rounded clip later vertices carry, in device pixels.
     */
    void roundClip(final float centerX, final float centerY, final float halfWidth, final float halfHeight,
                   final float radius) {
        this.clipX = centerX;
        this.clipY = centerY;
        this.clipHalfWidth = halfWidth;
        this.clipHalfHeight = halfHeight;
        this.clipRadius = radius;
    }

    /**
     * Stops clipping later vertices to a rounded rectangle.
     */
    void noRoundClip() {
        this.clipRadius = -1f;
    }

    /**
     * Starts a primitive of vertices drawn with the current scissor and texture, opening a batch if either changed.
     *
     * @param count how many vertices follow
     */
    void begin(final int count) {
        final int needed = (this.vertexCount + count) * STRIDE;
        if (needed > this.vertices.capacity()) {
            this.vertices = MemoryUtil.memRealloc(this.vertices, Math.max(needed, this.vertices.capacity() * 2));
        }
        this.openBatch();
        if (this.primitiveCount == this.stamps.length) {
            this.stamps = Arrays.copyOf(this.stamps, this.primitiveCount * 2);
            this.primitives = Arrays.copyOf(this.primitives, this.primitiveCount * 4);
        }
        this.primitives[this.primitiveCount * 2] = this.vertexCount;
        this.primitives[this.primitiveCount * 2 + 1] = this.batchCount - 1;
        this.stamps[this.primitiveCount] = this.stamp;
        this.primitiveCount++;
    }

    private void openBatch() {
        final int last = this.batchCount - 1;
        if (last >= 0) {
            final int at = last * 6;
            if (this.batches[at] == this.scissorX && this.batches[at + 1] == this.scissorY
                    && this.batches[at + 2] == this.scissorWidth && this.batches[at + 3] == this.scissorHeight
                    && this.sets[last] == this.set) {
                return;
            }
        }
        if (this.batchCount == this.sets.length) {
            this.sets = Arrays.copyOf(this.sets, this.sets.length * 2);
            this.batches = Arrays.copyOf(this.batches, this.batches.length * 2);
        }
        final int at = this.batchCount * 6;
        this.batches[at] = this.scissorX;
        this.batches[at + 1] = this.scissorY;
        this.batches[at + 2] = this.scissorWidth;
        this.batches[at + 3] = this.scissorHeight;
        this.batches[at + 4] = this.vertexCount;
        this.batches[at + 5] = 0;
        this.sets[this.batchCount] = this.set;
        this.batchCount++;
    }

    /**
     * Adds a vertex to the open batch. Call {@link #begin} first for the vertices of each primitive.
     */
    void vertex(final float x, final float y, final float u, final float v, final int rgba, final int mode,
                final float p0, final float p1, final float p2, final float p3) {
        final ByteBuffer out = this.vertices;
        final int at = this.vertexCount * STRIDE;
        out.putFloat(at, x);
        out.putFloat(at + 4, y);
        out.putFloat(at + 8, u);
        out.putFloat(at + 12, v);
        out.putInt(at + 16, rgba);
        out.putInt(at + 20, mode);
        out.putFloat(at + 24, p0);
        out.putFloat(at + 28, p1);
        out.putFloat(at + 32, p2);
        out.putFloat(at + 36, p3);
        out.putFloat(at + 40, this.clipX);
        out.putFloat(at + 44, this.clipY);
        out.putFloat(at + 48, this.clipHalfWidth);
        out.putFloat(at + 52, this.clipHalfHeight);
        out.putFloat(at + 56, this.clipRadius);
        this.vertexCount++;
        this.batches[(this.batchCount - 1) * 6 + 5]++;
    }

    /**
     * Adds a rectangle as two triangles, with one color and the same shape parameters at every corner.
     */
    void quad(final float x0, final float y0, final float x1, final float y1, final float u0, final float v0,
              final float u1, final float v1, final int rgba, final int mode, final float p0, final float p1,
              final float p2, final float p3) {
        this.begin(6);
        this.vertex(x0, y0, u0, v0, rgba, mode, p0, p1, p2, p3);
        this.vertex(x1, y0, u1, v0, rgba, mode, p0, p1, p2, p3);
        this.vertex(x1, y1, u1, v1, rgba, mode, p0, p1, p2, p3);
        this.vertex(x0, y0, u0, v0, rgba, mode, p0, p1, p2, p3);
        this.vertex(x1, y1, u1, v1, rgba, mode, p0, p1, p2, p3);
        this.vertex(x0, y1, u0, v1, rgba, mode, p0, p1, p2, p3);
    }

    /**
     * Reserves space for path coverage, one byte per pixel.
     *
     * @param bytes how many bytes
     * @return the offset of the space, a multiple of 4
     */
    int reserveMask(final int bytes) {
        final int offset = (this.maskBytes + 3) & ~3;
        final int needed = offset + bytes;
        if (needed > this.masks.capacity()) {
            this.masks = MemoryUtil.memRealloc(this.masks, Math.max(needed, this.masks.capacity() * 2));
        }
        this.maskBytes = needed;
        return offset;
    }

    /**
     * Returns the coverage buffer, to write into space {@link #reserveMask} gave. The buffer can move when more space
     * is reserved.
     *
     * @return the buffer
     */
    ByteBuffer masks() {
        return this.masks;
    }

    int vertexCount() {
        return this.vertexCount;
    }

    ByteBuffer vertices() {
        return this.vertices;
    }

    int maskBytes() {
        return this.maskBytes;
    }

    int batchCount() {
        return this.batchCount;
    }

    /**
     * Returns part of a batch.
     *
     * @param batch the batch
     * @param field 0 to 3 for the scissor's x, y, width and height, 4 for the first vertex, 5 for the vertex count
     * @return the value
     */
    int batch(final int batch, final int field) {
        return this.batches[batch * 6 + field];
    }

    long batchSet(final int batch) {
        return this.sets[batch];
    }

    int primitiveCount() {
        return this.primitiveCount;
    }

    /**
     * Returns a primitive's first vertex.
     */
    int primitiveStart(final int primitive) {
        return this.primitives[primitive * 2];
    }

    /**
     * Returns the vertex after a primitive's last.
     */
    int primitiveEnd(final int primitive) {
        return primitive + 1 < this.primitiveCount ? this.primitives[primitive * 2 + 2] : this.vertexCount;
    }

    int primitiveBatch(final int primitive) {
        return this.primitives[primitive * 2 + 1];
    }

    long primitiveStamp(final int primitive) {
        return this.stamps[primitive];
    }

    @Override
    public void close() {
        MemoryUtil.memFree(this.vertices);
        MemoryUtil.memFree(this.masks);
    }

    /**
     * Packs an ARGB color, with its alpha multiplied by an opacity, as the RGBA bytes a vertex holds.
     *
     * @param argb the color
     * @param opacity the factor for its alpha, from 0 to 1
     * @return the packed color
     */
    static int rgba(final int argb, final float opacity) {
        int alpha = argb >>> 24;
        if (opacity < 1f) {
            alpha = Math.round(alpha * opacity);
        }
        return (alpha << 24) | ((argb & 0xFF) << 16) | (argb & 0xFF00) | ((argb >> 16) & 0xFF);
    }
}

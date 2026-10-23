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
 * Rasterizes polygons into a coverage mask with one byte per device pixel, by signed area accumulation: each edge adds
 * the area it covers in every pixel it crosses, and a running sum along the rows gives each pixel's winding times its
 * coverage. Clamping its size to 1 fills by the nonzero rule. Polygons are clipped to the area drawn first, so shapes
 * far larger than the screen cost no more than the screen.
 */
final class Coverage {
    private float[] accumulation = new float[1024];
    private float[] from = new float[64];
    private float[] to = new float[64];

    /**
     * Rasterizes polygons inside a clip and stores the mask in a draw list.
     *
     * @param polygons the polygons, in device pixels
     * @param clipX0 the clip's left edge, in device pixels
     * @param clipY0 the clip's top edge
     * @param clipX1 the clip's right edge
     * @param clipY1 the clip's bottom edge
     * @param draws where the mask goes
     * @param result receives the mask's left, top, width, height and offset in the draw list's coverage
     * @return false if nothing is covered
     */
    boolean rasterize(final Polygons polygons, final int clipX0, final int clipY0, final int clipX1, final int clipY1,
                      final DrawList draws, final int[] result) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int p = 0; p < polygons.polygons(); p++) {
            for (int i = polygons.start(p); i < polygons.end(p); i++) {
                minX = Math.min(minX, polygons.x(i));
                minY = Math.min(minY, polygons.y(i));
                maxX = Math.max(maxX, polygons.x(i));
                maxY = Math.max(maxY, polygons.y(i));
            }
        }
        if (!(minX <= maxX) || !(minY <= maxY)) {
            return false;
        }
        final int x0 = (int) Math.max(clipX0, Math.floor(minX));
        final int y0 = (int) Math.max(clipY0, Math.floor(minY));
        final int x1 = (int) Math.min(clipX1, Math.ceil(maxX));
        final int y1 = (int) Math.min(clipY1, Math.ceil(maxY));
        if (x1 <= x0 || y1 <= y0) {
            return false;
        }
        final int width = x1 - x0;
        final int height = y1 - y0;
        final int size = width * height;
        if (this.accumulation.length < size + 2) {
            this.accumulation = new float[size + 2];
        } else {
            Arrays.fill(this.accumulation, 0, size + 2, 0f);
        }

        for (int p = 0; p < polygons.polygons(); p++) {
            final int count = this.clip(polygons, p, x0, y0, x1, y1);
            for (int i = 0; i < count; i++) {
                final int j = i + 1 < count ? i + 1 : 0;
                this.line(this.from[i * 2] - x0, this.from[i * 2 + 1] - y0, this.from[j * 2] - x0,
                        this.from[j * 2 + 1] - y0, width, height);
            }
        }

        final int offset = draws.reserveMask(size);
        final ByteBuffer masks = draws.masks();
        float sum = 0f;
        boolean any = false;
        for (int i = 0; i < size; i++) {
            sum += this.accumulation[i];
            final float cover = Math.min(1f, Math.abs(sum));
            final int value = (int) (cover * 255f + 0.5f);
            any |= value != 0;
            masks.put(offset + i, (byte) value);
        }
        result[0] = x0;
        result[1] = y0;
        result[2] = width;
        result[3] = height;
        result[4] = offset;
        return any;
    }

    /**
     * Clips one polygon to a rectangle, Sutherland and Hodgman's way, into {@link #from}. Clipping each polygon on
     * its own keeps every winding number inside the rectangle.
     *
     * @return how many corners the clipped polygon has
     */
    private int clip(final Polygons polygons, final int polygon, final float x0, final float y0, final float x1,
                     final float y1) {
        final int start = polygons.start(polygon);
        int count = polygons.end(polygon) - start;
        this.ensure(count * 2 + 8);
        for (int i = 0; i < count; i++) {
            this.from[i * 2] = polygons.x(start + i);
            this.from[i * 2 + 1] = polygons.y(start + i);
        }
        count = this.clipEdge(count, 0, x0, false);
        count = this.clipEdge(count, 0, x1, true);
        count = this.clipEdge(count, 1, y0, false);
        return this.clipEdge(count, 1, y1, true);
    }

    /**
     * Keeps the part of the polygon in {@link #from} on one side of a line, writing it back to {@link #from}.
     *
     * @param axis 0 for a vertical line at x = edge, 1 for a horizontal one at y = edge
     * @param below true to keep coordinates at or below the edge, false for those at or above it
     */
    private int clipEdge(final int count, final int axis, final float edge, final boolean below) {
        if (count == 0) {
            return 0;
        }
        this.ensure(count * 2 + 8);
        int out = 0;
        for (int i = 0; i < count; i++) {
            final int j = i + 1 < count ? i + 1 : 0;
            final float ax = this.from[i * 2];
            final float ay = this.from[i * 2 + 1];
            final float bx = this.from[j * 2];
            final float by = this.from[j * 2 + 1];
            final float a = axis == 0 ? ax : ay;
            final float b = axis == 0 ? bx : by;
            final boolean aIn = below ? a <= edge : a >= edge;
            final boolean bIn = below ? b <= edge : b >= edge;
            if (aIn) {
                this.to[out * 2] = ax;
                this.to[out * 2 + 1] = ay;
                out++;
            }
            if (aIn != bIn) {
                final float t = (edge - a) / (b - a);
                this.to[out * 2] = axis == 0 ? edge : ax + (bx - ax) * t;
                this.to[out * 2 + 1] = axis == 0 ? ay + (by - ay) * t : edge;
                out++;
            }
        }
        final float[] swap = this.from;
        this.from = this.to;
        this.to = swap;
        return out;
    }

    private void ensure(final int floats) {
        if (this.from.length < floats * 2) {
            this.from = Arrays.copyOf(this.from, floats * 2);
            this.to = Arrays.copyOf(this.to, floats * 2);
        }
    }

    /**
     * Adds one edge's area, in mask pixels, after the rasterizer of font-rs.
     */
    private void line(float x0, float y0, float x1, float y1, final int width, final int height) {
        if (y0 == y1) {
            return;
        }
        final float direction;
        if (y0 < y1) {
            direction = 1f;
        } else {
            direction = -1f;
            final float x = x0;
            x0 = x1;
            x1 = x;
            final float y = y0;
            y0 = y1;
            y1 = y;
        }
        final float slope = (x1 - x0) / (y1 - y0);
        float x = x0;
        final int yEnd = Math.min(height, (int) Math.ceil(y1));
        final float[] acc = this.accumulation;
        for (int y = Math.max(0, (int) y0); y < yEnd; y++) {
            final int row = y * width;
            final float dy = Math.min(y + 1f, y1) - Math.max(y, y0);
            final float xNext = x + slope * dy;
            final float d = dy * direction;
            final float left = Math.max(0f, Math.min(width, Math.min(x, xNext)));
            final float right = Math.max(0f, Math.min(width, Math.max(x, xNext)));
            final float leftFloor = (float) Math.floor(left);
            final int leftIndex = (int) leftFloor;
            final float rightCeil = (float) Math.ceil(right);
            final int rightIndex = (int) rightCeil;
            if (rightIndex <= leftIndex + 1) {
                final float middle = 0.5f * (left + right) - leftFloor;
                acc[row + leftIndex] += d - d * middle;
                acc[row + leftIndex + 1] += d * middle;
            } else {
                final float s = 1f / (right - left);
                final float leftFraction = left - leftFloor;
                final float a0 = 0.5f * s * (1f - leftFraction) * (1f - leftFraction);
                final float rightFraction = right - rightCeil + 1f;
                final float am = 0.5f * s * rightFraction * rightFraction;
                acc[row + leftIndex] += d * a0;
                if (rightIndex == leftIndex + 2) {
                    acc[row + leftIndex + 1] += d * (1f - a0 - am);
                } else {
                    final float a1 = s * (1.5f - leftFraction);
                    acc[row + leftIndex + 1] += d * (a1 - a0);
                    for (int xi = leftIndex + 2; xi < rightIndex - 1; xi++) {
                        acc[row + xi] += d * s;
                    }
                    final float a2 = a1 + (rightIndex - leftIndex - 3) * s;
                    acc[row + rightIndex - 1] += d * (1f - a2 - am);
                }
                acc[row + rightIndex] += d * am;
            }
            x = xNext;
        }
    }
}

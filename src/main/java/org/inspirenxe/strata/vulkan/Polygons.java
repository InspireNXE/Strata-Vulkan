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

import java.util.Arrays;

/**
 * Closed polygons in device pixels, the input of {@link Coverage}. Coverage fills them by the nonzero rule, so
 * polygons that all turn the same way fill their union.
 */
final class Polygons {
    private float[] points = new float[256];
    private int count;
    private int[] ends = new int[16];
    private int polygons;
    private int open = -1;

    void reset() {
        this.count = 0;
        this.polygons = 0;
        this.open = -1;
    }

    /**
     * Starts a polygon.
     */
    void begin() {
        this.open = this.count;
    }

    /**
     * Adds a corner to the polygon begun last.
     */
    void add(final float x, final float y) {
        if (this.count * 2 + 2 > this.points.length) {
            this.points = Arrays.copyOf(this.points, this.points.length * 2);
        }
        this.points[this.count * 2] = x;
        this.points[this.count * 2 + 1] = y;
        this.count++;
    }

    /**
     * Ends the polygon begun last, dropping it if it has fewer than three corners. When {@code positive} is true the
     * polygon is reversed if needed so its signed area is positive, which lets strokes made of overlapping pieces
     * fill their union.
     *
     * @param positive true to make the polygon turn the positive way
     */
    void end(final boolean positive) {
        final int first = this.open;
        this.open = -1;
        if (this.count - first < 3) {
            this.count = first;
            return;
        }
        if (positive && this.area(first, this.count) < 0f) {
            for (int i = first, j = this.count - 1; i < j; i++, j--) {
                final float x = this.points[i * 2];
                final float y = this.points[i * 2 + 1];
                this.points[i * 2] = this.points[j * 2];
                this.points[i * 2 + 1] = this.points[j * 2 + 1];
                this.points[j * 2] = x;
                this.points[j * 2 + 1] = y;
            }
        }
        if (this.polygons == this.ends.length) {
            this.ends = Arrays.copyOf(this.ends, this.polygons * 2);
        }
        this.ends[this.polygons++] = this.count;
    }

    /**
     * Adds a circle as a polygon whose sides stray no more than {@link DevicePath#TOLERANCE} from it.
     */
    void circle(final float cx, final float cy, final float radius) {
        if (!(radius > 0f)) {
            return;
        }
        final int sides = Polygons.sides(radius, 2.0 * Math.PI);
        this.begin();
        for (int i = 0; i < sides; i++) {
            final double angle = 2.0 * Math.PI * i / sides;
            this.add(cx + (float) (Math.cos(angle) * radius), cy + (float) (Math.sin(angle) * radius));
        }
        this.end(true);
    }

    int polygons() {
        return this.polygons;
    }

    int start(final int polygon) {
        return polygon == 0 ? 0 : this.ends[polygon - 1];
    }

    int end(final int polygon) {
        return this.ends[polygon];
    }

    float x(final int index) {
        return this.points[index * 2];
    }

    float y(final int index) {
        return this.points[index * 2 + 1];
    }

    /**
     * Returns how many straight sides approximate an arc of a circle closely enough.
     *
     * @param radius the circle's radius in device pixels
     * @param sweep the arc's angle in radians, positive
     * @return the number of sides, at least 2 per quarter turn
     */
    static int sides(final float radius, final double sweep) {
        final double step = radius > DevicePath.TOLERANCE
                ? 2.0 * Math.acos(1.0 - DevicePath.TOLERANCE / radius)
                : Math.PI / 2.0;
        final int sides = (int) Math.ceil(sweep / Math.min(step, Math.PI / 4.0));
        return Math.max(2, Math.min(sides, 1024));
    }

    private float area(final int first, final int end) {
        double area = 0.0;
        for (int i = first; i < end; i++) {
            final int j = i + 1 < end ? i + 1 : first;
            area += (double) this.points[i * 2] * this.points[j * 2 + 1]
                    - (double) this.points[j * 2] * this.points[i * 2 + 1];
        }
        return (float) area;
    }
}

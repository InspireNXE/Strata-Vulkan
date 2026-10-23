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

import org.inspirenxe.strata.api.extension.Shape;

import java.util.Arrays;

/**
 * A path flattened to straight lines in device pixels. It takes a shape's segments in UI units through
 * {@link Shape.Sink}, moves and scales them by the canvas's transform, and splits curves until no piece strays more
 * than a quarter of a pixel from the curve. Each point remembers whether it is a corner the path was given, where a
 * stroke's join applies, or a point inside a curve, where strokes always join with a miter.
 */
final class DevicePath implements Shape.Sink {
    /** How far a flattened curve may stray from the true curve, in device pixels. */
    static final float TOLERANCE = 0.25f;

    private float[] points = new float[64];
    private boolean[] corners = new boolean[32];
    private int count;
    private int[] starts = new int[8];
    private boolean[] closed = new boolean[8];
    private int subpaths;

    private float originX;
    private float originY;
    private float scale = 1f;
    private float startX;
    private float startY;
    private float lastX;
    private float lastY;
    private boolean open;

    /**
     * Empties the path and sets the transform from UI units to device pixels.
     *
     * @param originX where unit 0 lands horizontally, in device pixels
     * @param originY where unit 0 lands vertically
     * @param scale device pixels per unit
     */
    void reset(final float originX, final float originY, final float scale) {
        this.count = 0;
        this.subpaths = 0;
        this.originX = originX;
        this.originY = originY;
        this.scale = scale;
        this.open = false;
    }

    @Override
    public void moveTo(final float x, final float y) {
        this.startDevice(this.x(x), this.y(y));
    }

    @Override
    public void lineTo(final float x, final float y) {
        this.ensureOpen();
        this.point(this.x(x), this.y(y), true);
    }

    @Override
    public void quadTo(final float cx, final float cy, final float x, final float y) {
        this.ensureOpen();
        final float x0 = this.lastX;
        final float y0 = this.lastY;
        final float x1 = this.x(cx);
        final float y1 = this.y(cy);
        final float x2 = this.x(x);
        final float y2 = this.y(y);
        final float ddx = x0 - 2f * x1 + x2;
        final float ddy = y0 - 2f * y1 + y2;
        final int steps = DevicePath.steps((float) Math.sqrt(Math.hypot(ddx, ddy) / (4f * TOLERANCE)));
        for (int i = 1; i < steps; i++) {
            final float t = (float) i / steps;
            final float u = 1f - t;
            this.point(u * u * x0 + 2f * u * t * x1 + t * t * x2, u * u * y0 + 2f * u * t * y1 + t * t * y2, false);
        }
        this.point(x2, y2, true);
    }

    @Override
    public void cubicTo(final float c1x, final float c1y, final float c2x, final float c2y, final float x,
                        final float y) {
        this.ensureOpen();
        final float x0 = this.lastX;
        final float y0 = this.lastY;
        final float x1 = this.x(c1x);
        final float y1 = this.y(c1y);
        final float x2 = this.x(c2x);
        final float y2 = this.y(c2y);
        final float x3 = this.x(x);
        final float y3 = this.y(y);
        final double bend = Math.max(Math.hypot(x0 - 2f * x1 + x2, y0 - 2f * y1 + y2),
                Math.hypot(x1 - 2f * x2 + x3, y1 - 2f * y2 + y3));
        final int steps = DevicePath.steps((float) Math.sqrt(0.75 * bend / TOLERANCE));
        for (int i = 1; i < steps; i++) {
            final float t = (float) i / steps;
            final float u = 1f - t;
            final float a = u * u * u;
            final float b = 3f * u * u * t;
            final float c = 3f * u * t * t;
            final float d = t * t * t;
            this.point(a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3, false);
        }
        this.point(x3, y3, true);
    }

    @Override
    public void close() {
        if (this.open) {
            this.closed[this.subpaths - 1] = true;
            this.open = false;
            this.lastX = this.startX;
            this.lastY = this.startY;
        }
    }

    /**
     * Starts a subpath at a point already in device pixels.
     */
    void startDevice(final float x, final float y) {
        if (this.subpaths == this.starts.length) {
            this.starts = Arrays.copyOf(this.starts, this.subpaths * 2);
            this.closed = Arrays.copyOf(this.closed, this.subpaths * 2);
        }
        this.starts[this.subpaths] = this.count;
        this.closed[this.subpaths] = false;
        this.subpaths++;
        this.open = true;
        this.startX = x;
        this.startY = y;
        this.point(x, y, true);
    }

    /**
     * Adds a point in device pixels to the open subpath.
     *
     * @param corner true for a corner of the path, false for a point inside a curve
     */
    void point(final float x, final float y, final boolean corner) {
        if (this.count * 2 + 2 > this.points.length) {
            this.points = Arrays.copyOf(this.points, this.points.length * 2);
            this.corners = Arrays.copyOf(this.corners, this.corners.length * 2);
        }
        this.points[this.count * 2] = x;
        this.points[this.count * 2 + 1] = y;
        this.corners[this.count] = corner;
        this.count++;
        this.lastX = x;
        this.lastY = y;
    }

    int subpaths() {
        return this.subpaths;
    }

    /**
     * Returns the index of a subpath's first point.
     */
    int start(final int subpath) {
        return this.starts[subpath];
    }

    /**
     * Returns the index one past a subpath's last point.
     */
    int end(final int subpath) {
        return subpath + 1 < this.subpaths ? this.starts[subpath + 1] : this.count;
    }

    boolean isClosed(final int subpath) {
        return this.closed[subpath];
    }

    float pointX(final int index) {
        return this.points[index * 2];
    }

    float pointY(final int index) {
        return this.points[index * 2 + 1];
    }

    boolean isCorner(final int index) {
        return this.corners[index];
    }

    float scale() {
        return this.scale;
    }

    private void ensureOpen() {
        if (!this.open) {
            // A segment after close() starts a new subpath where the closed one started
            this.startDevice(this.lastX, this.lastY);
        }
    }

    private float x(final float x) {
        return this.originX + x * this.scale;
    }

    private float y(final float y) {
        return this.originY + y * this.scale;
    }

    private static int steps(final float wanted) {
        if (!(wanted >= 1f)) {
            return 1;
        }
        return (int) Math.min(1000f, Math.ceil(wanted));
    }
}

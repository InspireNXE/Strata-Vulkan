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

import org.inspirenxe.strata.api.extension.Stroke;

import java.util.Arrays;

/**
 * Turns a flattened path into the polygons its stroke covers: a rectangle along each segment, a piece at each join and
 * a piece at each end, all turning the positive way so that coverage fills their union. Joins and caps follow Java2D,
 * with its miter limit of 10.
 */
final class Stroker {
    /** The longest a miter may be, as a multiple of the stroke's width, before it is cut off as a bevel. */
    private static final float MITER_LIMIT = 10f;
    /** Points closer than this, in device pixels, count as one. */
    private static final float SAME = 1e-4f;

    private float[] xs = new float[64];
    private float[] ys = new float[64];
    private boolean[] corners = new boolean[64];

    /**
     * Strokes every subpath of a path.
     *
     * @param path the path, in device pixels
     * @param width the stroke's width in device pixels, greater than 0
     * @param cap the caps of open subpaths
     * @param join the joins at the path's corners
     * @param out where the polygons go
     */
    void stroke(final DevicePath path, final float width, final Stroke.Cap cap, final Stroke.Join join,
                final Polygons out) {
        final float half = width / 2f;
        for (int subpath = 0; subpath < path.subpaths(); subpath++) {
            final int n = this.collect(path, subpath);
            if (n == 0) {
                continue;
            }
            if (n == 1) {
                this.dot(this.xs[0], this.ys[0], half, cap, out);
                continue;
            }
            final boolean closed = path.isClosed(subpath) && n > 2;
            final int segments = closed ? n : n - 1;
            for (int i = 0; i < segments; i++) {
                final int j = (i + 1) % n;
                float ax = this.xs[i];
                float ay = this.ys[i];
                float bx = this.xs[j];
                float by = this.ys[j];
                final float length = (float) Math.hypot(bx - ax, by - ay);
                final float dx = (bx - ax) / length;
                final float dy = (by - ay) / length;
                if (!closed && cap == Stroke.Cap.SQUARE) {
                    if (i == 0) {
                        ax -= dx * half;
                        ay -= dy * half;
                    }
                    if (i == segments - 1) {
                        bx += dx * half;
                        by += dy * half;
                    }
                }
                final float nx = -dy * half;
                final float ny = dx * half;
                out.begin();
                out.add(ax + nx, ay + ny);
                out.add(bx + nx, by + ny);
                out.add(bx - nx, by - ny);
                out.add(ax - nx, ay - ny);
                out.end(true);
            }
            final int firstJoin = closed ? 0 : 1;
            final int lastJoin = closed ? n - 1 : n - 2;
            for (int i = firstJoin; i <= lastJoin; i++) {
                final int previous = (i - 1 + n) % n;
                final int next = (i + 1) % n;
                this.join(this.xs[previous], this.ys[previous], this.xs[i], this.ys[i], this.xs[next], this.ys[next],
                        half, this.corners[i] ? join : Stroke.Join.MITER, out);
            }
            if (!closed && cap == Stroke.Cap.ROUND) {
                out.circle(this.xs[0], this.ys[0], half);
                out.circle(this.xs[n - 1], this.ys[n - 1], half);
            }
        }
    }

    /**
     * Copies a subpath's points without repeats, dropping a closed subpath's last point when it is its first.
     *
     * @return how many points are left
     */
    private int collect(final DevicePath path, final int subpath) {
        final int start = path.start(subpath);
        final int end = path.end(subpath);
        if (end - start > this.xs.length) {
            this.xs = Arrays.copyOf(this.xs, end - start);
            this.ys = Arrays.copyOf(this.ys, end - start);
            this.corners = Arrays.copyOf(this.corners, end - start);
        }
        int n = 0;
        for (int i = start; i < end; i++) {
            final float x = path.pointX(i);
            final float y = path.pointY(i);
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                continue;
            }
            if (n > 0 && Math.abs(x - this.xs[n - 1]) < SAME && Math.abs(y - this.ys[n - 1]) < SAME) {
                this.corners[n - 1] |= path.isCorner(i);
                continue;
            }
            this.xs[n] = x;
            this.ys[n] = y;
            this.corners[n] = path.isCorner(i);
            n++;
        }
        if (path.isClosed(subpath) && n > 1 && Math.abs(this.xs[n - 1] - this.xs[0]) < SAME
                && Math.abs(this.ys[n - 1] - this.ys[0]) < SAME) {
            n--;
        }
        return n;
    }

    /**
     * Draws a subpath of one point: a round cap's circle or a square cap's square, and nothing for a butt cap, as
     * Java2D does.
     */
    private void dot(final float x, final float y, final float half, final Stroke.Cap cap, final Polygons out) {
        if (cap == Stroke.Cap.ROUND) {
            out.circle(x, y, half);
        } else if (cap == Stroke.Cap.SQUARE) {
            out.begin();
            out.add(x - half, y - half);
            out.add(x + half, y - half);
            out.add(x + half, y + half);
            out.add(x - half, y + half);
            out.end(true);
        }
    }

    /**
     * Fills the outer side of the corner at {@code (bx, by)}, between the segment from {@code a} and the one to
     * {@code c}.
     */
    private void join(final float ax, final float ay, final float bx, final float by, final float cx, final float cy,
                      final float half, final Stroke.Join join, final Polygons out) {
        final float l0 = (float) Math.hypot(bx - ax, by - ay);
        final float l1 = (float) Math.hypot(cx - bx, cy - by);
        final float d0x = (bx - ax) / l0;
        final float d0y = (by - ay) / l0;
        final float d1x = (cx - bx) / l1;
        final float d1y = (cy - by) / l1;
        final float cross = d0x * d1y - d0y * d1x;
        final float dot = d0x * d1x + d0y * d1y;
        if (Math.abs(cross) < 1e-6f && dot > 0f) {
            return;
        }
        if (join == Stroke.Join.ROUND) {
            out.circle(bx, by, half);
            return;
        }
        // The normals point to the left of each segment; the outer side of the turn is the other way when it turns
        // right on screen
        final float side = cross > 0f ? -1f : 1f;
        final float n0x = -d0y * side;
        final float n0y = d0x * side;
        final float n1x = -d1y * side;
        final float n1y = d1x * side;
        out.begin();
        out.add(bx, by);
        out.add(bx + n0x * half, by + n0y * half);
        if (join == Stroke.Join.MITER) {
            final float mx = n0x + n1x;
            final float my = n0y + n1y;
            final float length = (float) Math.hypot(mx, my);
            if (length > 1e-6f) {
                final float cosHalf = (mx * n0x + my * n0y) / length;
                if (cosHalf > 0f && 1f / cosHalf <= MITER_LIMIT) {
                    final float reach = half / cosHalf / length;
                    out.add(bx + mx * reach, by + my * reach);
                }
            }
        }
        out.add(bx + n1x * half, by + n1y * half);
        out.end(true);
    }
}

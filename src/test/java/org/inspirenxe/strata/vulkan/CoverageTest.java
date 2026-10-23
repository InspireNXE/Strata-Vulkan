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
import org.inspirenxe.strata.api.extension.Stroke;
import org.inspirenxe.strata.api.geometry.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the host-side path pipeline without a device: flattening, stroking and coverage.
 */
class CoverageTest {
    private final DrawList draws = new DrawList();
    private final Polygons polygons = new Polygons();
    private final Coverage coverage = new Coverage();
    private final int[] mask = new int[5];

    @AfterEach
    void free() {
        this.draws.close();
    }

    @Test
    void aSquareOnThePixelGridCoversWholePixels() {
        this.square(2, 2, 6, 6, true);
        assertTrue(this.rasterize(0, 0, 10, 10));
        assertEquals(2, this.mask[0]);
        assertEquals(2, this.mask[1]);
        assertEquals(4, this.mask[2]);
        assertEquals(4, this.mask[3]);
        assertEquals(255, this.at(0, 0));
        assertEquals(255, this.at(3, 3));
    }

    @Test
    void aHalfCoveredPixelIsHalfCovered() {
        this.square(0.5f, 0, 2, 2, true);
        assertTrue(this.rasterize(0, 0, 4, 4));
        assertEquals(128, this.at(0, 0), 1);
        assertEquals(255, this.at(1, 0));
    }

    @Test
    void oppositeWindingCutsAHoleAndTheSameWindingDoesNot() {
        this.square(0, 0, 8, 8, true);
        this.square(2, 2, 6, 6, false);
        assertTrue(this.rasterize(0, 0, 8, 8));
        assertEquals(255, this.at(1, 1));
        assertEquals(0, this.at(4, 4), "a hole");

        this.polygons.reset();
        this.square(0, 0, 8, 8, true);
        this.square(2, 2, 6, 6, true);
        assertTrue(this.rasterize(0, 0, 8, 8));
        assertEquals(255, this.at(4, 4), "winding 2 fills by the nonzero rule");
    }

    @Test
    void geometryFarOutsideIsClipped() {
        this.square(-100_000, -100_000, 100_000, 100_000, true);
        assertTrue(this.rasterize(0, 0, 16, 16));
        assertEquals(16, this.mask[2]);
        assertEquals(16, this.mask[3]);
        assertEquals(255, this.at(15, 15));
    }

    @Test
    void nothingInsideTheClipDrawsNothing() {
        this.square(20, 20, 30, 30, true);
        assertFalse(this.rasterize(0, 0, 10, 10));
    }

    @Test
    void overlappingStrokePiecesFillTheirUnionOnce() {
        final DevicePath path = new DevicePath();
        path.reset(0f, 0f, 1f);
        path.moveTo(2, 2);
        path.lineTo(12, 2);
        path.lineTo(12, 12);
        new Stroker().stroke(path, 2f, Stroke.Cap.ROUND, Stroke.Join.ROUND, this.polygons);
        assertTrue(this.rasterize(0, 0, 16, 16));
        assertEquals(255, this.at(12 - this.mask[0], 2 - this.mask[1]), "the corner, covered by three pieces");
        assertEquals(255, this.at(7 - this.mask[0], 1 - this.mask[1]));
        assertEquals(0, this.at(7 - this.mask[0], 7 - this.mask[1]));
    }

    @Test
    void curvesFlattenWithinTolerance() {
        final DevicePath path = new DevicePath();
        path.reset(10f, 20f, 2f);
        Shape.builder().moveTo(0, 0).cubicTo(0, 10, 10, 10, 10, 0).arc(new Rect(0, 0, 20, 20), 0f, 1f).build()
                .walk(path);
        assertTrue(path.end(0) - path.start(0) > 8, "the curve is split");
        assertEquals(10f, path.pointX(path.start(0)), "the transform applies");
        assertEquals(20f, path.pointY(path.start(0)));
    }

    private void square(final float x0, final float y0, final float x1, final float y1, final boolean clockwise) {
        this.polygons.begin();
        if (clockwise) {
            this.polygons.add(x0, y0);
            this.polygons.add(x1, y0);
            this.polygons.add(x1, y1);
            this.polygons.add(x0, y1);
        } else {
            this.polygons.add(x0, y0);
            this.polygons.add(x0, y1);
            this.polygons.add(x1, y1);
            this.polygons.add(x1, y0);
        }
        this.polygons.end(false);
    }

    private boolean rasterize(final int x0, final int y0, final int x1, final int y1) {
        this.draws.reset();
        return this.coverage.rasterize(this.polygons, x0, y0, x1, y1, this.draws, this.mask);
    }

    /**
     * Returns a mask pixel's coverage, from the mask's top left.
     */
    private int at(final int x, final int y) {
        return this.draws.masks().get(this.mask[4] + y * this.mask[2] + x) & 0xFF;
    }
}

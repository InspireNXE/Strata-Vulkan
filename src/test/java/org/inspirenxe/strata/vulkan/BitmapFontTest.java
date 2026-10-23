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

import org.inspirenxe.strata.api.text.TextMetrics;
import org.inspirenxe.strata.api.text.TextSpan;
import org.inspirenxe.strata.api.text.TextStyle;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.inspirenxe.strata.spi.testing.TextMetricsContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures and draws a bitmap font made from a sheet built in code: "A" is a solid 3 by 4 block in an 8 by 8 cell,
 * "B" a solid 5 by 8 block, and space a fixed advance.
 */
class BitmapFontTest {
    private static VulkanRenderer renderer;

    @AfterAll
    static void closeRenderer() {
        if (renderer != null) {
            TestDevice.get().idle();
            renderer.close();
            renderer = null;
        }
    }

    static BitmapFont font() {
        final PixelBuffer sheet = PixelBuffer.of(16, 8);
        for (int y = 4; y < 8; y++) {
            for (int x = 0; x < 3; x++) {
                sheet.set(x, y, 0xFFFFFFFF);
            }
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 8; x < 13; x++) {
                sheet.set(x, y, 0xFFFFFFFF);
            }
        }
        return BitmapFont.builder(9f, 7f)
                .sheet(sheet.changed(), 8f, 7f, List.of("AB"))
                .advance(' ', 4f)
                .fallback(TestFonts.metrics().font(TextStyle.DEFAULT))
                .build();
    }

    @Test
    void advancesAreTheInkWidthPlusOne() {
        final BitmapFont font = BitmapFontTest.font();
        assertEquals(4f, font.width(TextSpan.of("A")), "3 columns of ink, then a unit of spacing");
        assertEquals(6f, font.width(TextSpan.of("B")));
        assertEquals(14f, font.width(TextSpan.of("A B")));
        assertEquals(17f, font.width(new TextSpan("A B", TextStyle.DEFAULT.withBold(true))),
                "bold adds a unit to every advance");
        assertTrue(font.has('A'));
        assertTrue(font.has(' '));
        assertFalse(font.has('z'));
        assertTrue(font.width(TextSpan.of("Az")) > 4f, "the fallback measures what the sheets lack");
        assertEquals(9f, font.lineHeight());
        assertEquals(7f, font.ascent());
    }

    @Test
    void sheetsMustDivideIntoCells() {
        final PixelBuffer sheet = PixelBuffer.of(10, 8);
        assertThrows(IllegalArgumentException.class, () -> BitmapFont.builder(9f, 7f)
                .sheet(sheet, 8f, 7f, List.of("ABC")));
        assertThrows(IllegalArgumentException.class, () -> BitmapFont.builder(9f, 7f)
                .sheet(sheet, 8f, 7f, List.of("AB", "C")));
        assertThrows(IllegalArgumentException.class, () -> BitmapFont.builder(0f, 7f));
    }

    @Test
    void glyphsDrawTheirInkInTheTextColorAtTheScale() {
        final BitmapFont font = BitmapFontTest.font();
        final TextMetrics metrics = new VulkanTextMetrics(font);
        final TestDevice device = TestDevice.get();
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        final OffscreenImage image = new OffscreenImage(device, renderer, 64, 40);
        final FrameStream frames = renderer.openStream();
        try {
            // Two device pixels per unit: the line's top at 0, the baseline at 14
            final VulkanCanvas canvas = frames.begin(0, 0, 64, 40, 2f);
            canvas.configure((VulkanTextMetrics) metrics, null, null);
            canvas.text(new TextSpan("AB", TextStyle.DEFAULT.withColor(0xFFFF0000)), 0f, 0f);
            image.render(frames::end);
            // The cell's top sits 7 units above the baseline, at the line's top; A's ink is its bottom half
            assertEquals(0, image.argb(2, 2), "the empty top of A's cell");
            assertEquals(0xFFFF0000, image.argb(2, 10), "A's ink, in the text's color");
            assertEquals(0xFFFF0000, image.argb(5, 15), "A's ink ends 3 units right, at pixel 6");
            assertEquals(0, image.argb(6, 15), "past A's ink");
            assertEquals(0xFFFF0000, image.argb(8, 1), "B starts after A's advance of 4 units");
            assertEquals(0, image.argb(30, 10), "nothing past the text");
        } finally {
            frames.close();
            image.close();
        }
    }

    @Test
    void boldDrawsAgainOneUnitRight() {
        final BitmapFont font = BitmapFontTest.font();
        final TestDevice device = TestDevice.get();
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        final OffscreenImage image = new OffscreenImage(device, renderer, 32, 20);
        final FrameStream frames = renderer.openStream();
        try {
            final VulkanCanvas canvas = frames.begin(0, 0, 32, 20, 1f);
            canvas.configure(new VulkanTextMetrics(font), null, null);
            canvas.text(new TextSpan("A", TextStyle.DEFAULT.withBold(true)), 0f, 0f);
            image.render(frames::end);
            assertNotEquals(0, image.argb(3, 6), "the second copy reaches a unit past the first");
            assertEquals(0, image.argb(4, 6));
        } finally {
            frames.close();
            image.close();
        }
    }

    @Nested
    class Contract extends TextMetricsContract {
        @Override
        protected TextMetrics metrics() {
            return new VulkanTextMetrics(BitmapFontTest.font());
        }
    }
}

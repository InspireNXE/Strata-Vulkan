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

import org.inspirenxe.strata.api.text.FontId;
import org.inspirenxe.strata.api.text.TextSpan;
import org.inspirenxe.strata.api.text.TextStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The text metrics pick registered families by id and scale every font to the size a style asks for. Needs the
 * system's fonts, not a device.
 */
class VulkanFontsTest {
    private static final FontId TITLE = FontId.of("app", "title");

    private FreeTypeFont text;
    private FreeTypeFont mono;
    private VulkanTextMetrics metrics;

    @BeforeEach
    void open() {
        final Optional<FreeTypeFont> sans = FreeTypeFont.system(9f);
        final Optional<FreeTypeFont> monospace = FreeTypeFont.systemMonospace(12f);
        assumeTrue(sans.isPresent() && monospace.isPresent(), "No system fonts found");
        this.text = sans.get();
        this.mono = monospace.get();
        this.metrics = new VulkanTextMetrics(this.text);
    }

    @AfterEach
    void close() {
        if (this.text != null) {
            this.text.close();
        }
        if (this.mono != null) {
            this.mono.close();
        }
    }

    private float width(final TextStyle style) {
        return this.metrics.width(new TextSpan("Strata", style));
    }

    @Test
    void aSizeScalesTheDefaultFont() {
        assertEquals(9f, this.metrics.defaultSize());
        assertEquals(this.width(TextStyle.DEFAULT), this.width(TextStyle.DEFAULT.withSize(9f)), 0.001f);
        assertEquals(this.width(TextStyle.DEFAULT) * 2f, this.width(TextStyle.DEFAULT.withSize(18f)), 0.01f);
        assertEquals(this.metrics.lineHeight(TextStyle.DEFAULT) * 2f,
                this.metrics.lineHeight(TextStyle.DEFAULT.withSize(18f)), 0.01f);
        assertEquals(this.metrics.ascent(TextStyle.DEFAULT) * 2f,
                this.metrics.ascent(TextStyle.DEFAULT.withSize(18f)), 0.01f);
    }

    @Test
    void aRegisteredFamilyDrawsAtTheDefaultSizeUntilAStyleAsksForAnother() {
        this.metrics.register(TITLE, this.mono);
        final TextStyle title = TextStyle.DEFAULT.withFont(TITLE);
        assertSame(this.mono, this.metrics.font(title));
        // The family was made at 12 units; size 0 is the metrics' default of 9
        assertEquals(this.mono.width(new TextSpan("Strata", title)) * 0.75f, this.width(title), 0.01f);
        assertEquals(this.mono.width(new TextSpan("Strata", title)) * 1.5f, this.width(title.withSize(18f)), 0.01f);
    }

    @Test
    void aNamedFamilyWinsOverMonospaceAndAMissingOneFallsBack() {
        final VulkanTextMetrics both = new VulkanTextMetrics(this.text, this.mono);
        both.register(TITLE, this.text);
        assertSame(this.text, both.font(TextStyle.DEFAULT.withMonospace(true).withFont(TITLE)));
        final TextStyle missing = TextStyle.DEFAULT.withFont(FontId.of("app", "missing"));
        assertSame(this.text, both.font(missing));
        assertSame(this.mono, both.font(missing.withMonospace(true)));
    }

    @Test
    void registeringChangesTheVersionAndHandsBackTheOldFamily() {
        final long start = this.metrics.version();
        assertEquals(Optional.empty(), this.metrics.register(TITLE, this.mono));
        final long registered = this.metrics.version();
        assertNotEquals(start, registered);
        assertEquals(Optional.of(this.mono), this.metrics.register(TITLE, this.mono));
        assertEquals(registered, this.metrics.version(), "registering the same family again changes nothing");
        assertEquals(Optional.of(this.mono), this.metrics.register(TITLE, this.text));
        assertNotEquals(registered, this.metrics.version());
        assertEquals(Optional.of(this.text), this.metrics.registered(TITLE));

        final long replaced = this.metrics.version();
        assertEquals(Optional.of(this.text), this.metrics.unregister(TITLE));
        assertNotEquals(replaced, this.metrics.version());
        assertEquals(Optional.empty(), this.metrics.unregister(TITLE));
        assertEquals(Optional.empty(), this.metrics.registered(TITLE));
    }

    @Test
    void aBitmapFontsSizeIsItsLineHeight() {
        try (BitmapFont font = BitmapFont.builder(9f, 7f).fallback(this.text).build()) {
            assertEquals(9f, font.size());
        }
    }
}

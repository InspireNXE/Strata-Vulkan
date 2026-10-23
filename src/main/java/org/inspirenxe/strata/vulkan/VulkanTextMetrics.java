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

import java.util.Objects;

/**
 * Measures text the way the Vulkan canvas draws it, with a {@link VulkanFont} for ordinary text and another for
 * monospaced text. A span's width is the sum of its glyphs' advances plus the kerning between neighbors from the same
 * face, in UI units at the font's size.
 *
 * <p>Use it on the UI thread only.</p>
 */
public final class VulkanTextMetrics implements TextMetrics {
    /** The size {@link #defaults()} uses, in UI units, the same as the Swing adapter's default font. */
    public static final float DEFAULT_SIZE = 9f;

    private final VulkanFont text;
    private final VulkanFont monospace;

    /**
     * Creates metrics with one font for every style; monospaced spans use it too.
     *
     * @param text the font
     */
    public VulkanTextMetrics(final VulkanFont text) {
        this.text = Objects.requireNonNull(text, "text");
        this.monospace = null;
    }

    /**
     * Creates metrics with a font for monospaced spans.
     *
     * @param text the font for ordinary spans
     * @param monospace the font for spans whose style asks for monospace
     */
    public VulkanTextMetrics(final VulkanFont text, final VulkanFont monospace) {
        this.text = Objects.requireNonNull(text, "text");
        this.monospace = Objects.requireNonNull(monospace, "monospace");
    }

    /**
     * Returns metrics for the operating system's sans-serif and monospaced fonts at {@value #DEFAULT_SIZE} units, as
     * {@link VulkanFont#system} and {@link VulkanFont#systemMonospace} find them. The fonts are read once per call;
     * keep the result.
     *
     * @return the metrics, without a monospaced font if the system has none of the ones looked for
     * @throws IllegalStateException if no sans-serif font is found; give the metrics a font file instead
     */
    public static VulkanTextMetrics defaults() {
        final VulkanFont text = VulkanFont.system(DEFAULT_SIZE).orElseThrow(() -> new IllegalStateException(
                "No system font found; give VulkanTextMetrics a font file with VulkanFont.load"));
        return VulkanFont.systemMonospace(DEFAULT_SIZE).map(mono -> new VulkanTextMetrics(text, mono))
                .orElseGet(() -> new VulkanTextMetrics(text));
    }

    /**
     * Returns the font a style is measured and drawn with.
     *
     * @param style the style; only monospace chooses between the fonts
     * @return the font
     */
    public VulkanFont font(final TextStyle style) {
        return style.monospace() && this.monospace != null ? this.monospace : this.text;
    }

    /**
     * Returns whether these metrics have a monospaced font, which the platform reports as
     * {@link org.inspirenxe.strata.spi.Capabilities#monospaceFont()}.
     *
     * @return true when a monospaced font was given
     */
    public boolean hasMonospace() {
        return this.monospace != null;
    }

    @Override
    public float width(final TextSpan span) {
        final String text = span.text();
        if (text.isEmpty()) {
            return 0f;
        }
        final TextStyle style = span.style();
        final VulkanFont font = this.font(style);
        float ems = 0f;
        VulkanFont.Glyph previous = null;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            final VulkanFont.Glyph glyph = font.glyph(codePoint, style.bold(), style.italic());
            if (previous != null) {
                // Kerning never pulls a glyph back past its own advance, so text never narrows as it grows
                ems += Math.max(font.kerning(previous, glyph), -glyph.advance);
            }
            ems += glyph.advance;
            previous = glyph;
        }
        return Math.max(0f, ems * font.size());
    }

    @Override
    public float lineHeight(final TextStyle style) {
        return this.font(style).lineHeight();
    }

    @Override
    public float ascent(final TextStyle style) {
        final VulkanFont font = this.font(style);
        return Math.min(font.ascent(), font.lineHeight());
    }
}

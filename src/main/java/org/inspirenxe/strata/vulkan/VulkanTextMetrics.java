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
import org.inspirenxe.strata.api.text.TextMetrics;
import org.inspirenxe.strata.api.text.TextSpan;
import org.inspirenxe.strata.api.text.TextStyle;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Measures text the way the Vulkan canvas draws it, with a {@link VulkanFont} for ordinary text, another for
 * monospaced text, and the families the application {@linkplain #register registers} under a {@link FontId}.
 *
 * <p>A style's {@linkplain TextStyle#font() font} picks a registered family, which wins over {@code monospace}; a
 * family that is not registered, or no font at all, picks the monospaced font for a monospaced style when there is
 * one and the ordinary font otherwise. A style's {@linkplain TextStyle#size() size} scales the chosen font from its
 * own size, and a size of 0 is the {@linkplain #defaultSize() default size}, the ordinary font's. Widths, line heights
 * and ascents scale alike, and the canvas draws the glyphs at the scaled size rather than stretching them.</p>
 *
 * <p>Registering, replacing or removing a family changes {@link #version()}, so open screens lay out again. The
 * metrics never close a registered font: close it after removing it, or after the views that draw with it.</p>
 *
 * <p>Use it on the UI thread only.</p>
 */
public final class VulkanTextMetrics implements TextMetrics {
    /** The size {@link #defaults()} uses, in UI units, the same as the Swing adapter's default font. */
    public static final float DEFAULT_SIZE = 9f;

    private final VulkanFont text;
    private final VulkanFont monospace;
    private final Map<FontId, VulkanFont> families = new HashMap<>();
    private long version;

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
     * {@link FreeTypeFont#system} and {@link FreeTypeFont#systemMonospace} find them. The fonts are read once per call;
     * keep the result.
     *
     * @return the metrics, without a monospaced font if the system has none of the ones looked for
     * @throws IllegalStateException if no sans-serif font is found; give the metrics a font file instead
     */
    public static VulkanTextMetrics defaults() {
        final FreeTypeFont text = FreeTypeFont.system(DEFAULT_SIZE).orElseThrow(() -> new IllegalStateException(
                "No system font found; give VulkanTextMetrics a font file with FreeTypeFont.load"));
        return FreeTypeFont.systemMonospace(DEFAULT_SIZE).<VulkanTextMetrics>map(mono -> new VulkanTextMetrics(text,
                mono)).orElseGet(() -> new VulkanTextMetrics(text));
    }

    /**
     * Registers a family under an id, replacing any family registered under it before, so styles that name the id
     * draw with it.
     *
     * @param id the id styles name
     * @param font the family
     * @return the family registered under the id before, if any, which the caller closes when nothing draws with it
     */
    public Optional<VulkanFont> register(final FontId id, final VulkanFont font) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(font, "font");
        final VulkanFont previous = this.families.put(id, font);
        if (previous != font) {
            this.version++;
        }
        return Optional.ofNullable(previous);
    }

    /**
     * Removes the family registered under an id, so styles that name it draw with the default font again.
     *
     * @param id the id
     * @return the family that was registered, if any, which the caller closes when nothing draws with it
     */
    public Optional<VulkanFont> unregister(final FontId id) {
        final VulkanFont removed = this.families.remove(Objects.requireNonNull(id, "id"));
        if (removed != null) {
            this.version++;
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Returns the family registered under an id.
     *
     * @param id the id
     * @return the family, or empty if none is registered under it
     */
    public Optional<VulkanFont> registered(final FontId id) {
        return Optional.ofNullable(this.families.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * Returns the size text of size 0 measures and draws at: the ordinary font's own size.
     *
     * @return the size in UI units
     */
    public float defaultSize() {
        return this.text.size();
    }

    /**
     * Returns the font a style is measured and drawn with, at the font's own size.
     *
     * @param style the style; its font and monospace choose between the fonts
     * @return the font
     */
    public VulkanFont font(final TextStyle style) {
        final FontId id = style.font();
        if (id != null) {
            final VulkanFont family = this.families.get(id);
            if (family != null) {
                return family;
            }
        }
        return style.monospace() && this.monospace != null ? this.monospace : this.text;
    }

    /**
     * Returns how much larger than its font's own size a style draws.
     *
     * @param style the style
     * @param font the font {@link #font} chose for it
     * @return the factor, positive
     */
    float scale(final TextStyle style, final VulkanFont font) {
        final float size = style.size() > 0f ? style.size() : this.text.size();
        return size / font.size();
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
        if (span.text().isEmpty()) {
            return 0f;
        }
        final VulkanFont font = this.font(span.style());
        return Math.max(0f, font.width(span)) * this.scale(span.style(), font);
    }

    @Override
    public float lineHeight(final TextStyle style) {
        final VulkanFont font = this.font(style);
        return font.lineHeight() * this.scale(style, font);
    }

    @Override
    public float ascent(final TextStyle style) {
        final VulkanFont font = this.font(style);
        return Math.min(font.ascent(), font.lineHeight()) * this.scale(style, font);
    }

    @Override
    public long version() {
        return this.version;
    }
}

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

import org.inspirenxe.strata.api.text.TextSpan;

/**
 * A font the Vulkan canvas measures and draws text with: a {@link FreeTypeFont}, read from font files, or a
 * {@link BitmapFont}, cut from images. {@link VulkanTextMetrics} holds one for ordinary text and one for monospaced
 * text. Use a font on the UI thread only.
 */
public abstract sealed class VulkanFont implements AutoCloseable permits FreeTypeFont, BitmapFont {

    VulkanFont() {
    }

    /**
     * Measures how wide a span draws. The style's bold and italic count; its color and lines do not.
     *
     * @param span one line of text
     * @return the width in UI units, 0 for empty text and never negative
     */
    public abstract float width(TextSpan span);

    /**
     * Returns the distance from one line's top to the next line's top.
     *
     * @return the line height in UI units, positive
     */
    public abstract float lineHeight();

    /**
     * Returns the distance from the top of a line to its baseline.
     *
     * @return the ascent in UI units
     */
    public abstract float ascent();

    /**
     * Frees what the font holds outside the Java heap. Text drawn with it afterwards shows nothing. Calling it again
     * does nothing.
     */
    @Override
    public abstract void close();

    /**
     * Returns whether {@link #close()} ran.
     */
    abstract boolean isClosed();

    /**
     * Draws a span's glyphs, without its lines or shadow.
     *
     * @param canvas the canvas, for its transform and glyph atlas
     * @param span the text and style
     * @param x the left edge in UI units
     * @param baseline the baseline in UI units
     * @param argb the color
     */
    abstract void draw(VulkanCanvas canvas, TextSpan span, float x, float baseline, int argb);
}

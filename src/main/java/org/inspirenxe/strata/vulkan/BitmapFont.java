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
import org.inspirenxe.strata.api.text.TextStyle;
import org.inspirenxe.strata.api.visual.PixelBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A font of glyphs cut from images, for engines and looks whose fonts are bitmaps. It measures and draws as the Swing
 * adapter's bitmap font does, so a screen lays out the same on both.
 *
 * <p>A sheet is an image split into a grid, one character per cell, named row by row. Each glyph is drawn with its
 * cell scaled to the sheet's glyph height, without smoothing, at the size it lands on screen. A glyph advances by its
 * rightmost column that has any opaque pixel, plus one, scaled the same way and rounded to the nearest unit, plus one
 * unit of spacing. A cell with no opaque pixel advances one unit. Characters can also be given a fixed advance with
 * nothing drawn, as spaces usually are.</p>
 *
 * <p>Glyphs draw in the text's color, through their pixels' alpha: a sheet's own colors do not show. Bold draws each
 * glyph again one unit to the right and adds one unit to every advance. Italic slants glyphs by a quarter unit per unit
 * above the baseline. Characters with no glyph and no fixed advance are measured and drawn with the fallback font,
 * the system's sans-serif font at the line height unless the builder is given another. When several sheets or
 * advances name the same character, the first one added wins.</p>
 */
public final class BitmapFont extends VulkanFont {
    private static final float ITALIC_SLANT = 0.25f;

    private final float lineHeight;
    private final float ascent;
    private final List<Sheet> sheets;
    private final Map<Integer, Glyph> glyphs;
    private final VulkanFont fallback;
    private final boolean ownsFallback;
    private boolean closed;

    private BitmapFont(final Builder builder) {
        this.lineHeight = builder.lineHeight;
        this.ascent = builder.ascent;
        this.sheets = List.copyOf(builder.sheets);
        this.glyphs = Map.copyOf(builder.glyphs);
        if (builder.fallback != null) {
            this.fallback = builder.fallback;
            this.ownsFallback = false;
        } else {
            this.fallback = FreeTypeFont.system(builder.lineHeight).orElse(null);
            this.ownsFallback = this.fallback != null;
        }
    }

    /**
     * Starts a font.
     *
     * @param lineHeight the height of one line, in UI units
     * @param ascent how far the baseline sits below the top of a line, in UI units
     * @return the builder
     * @throws IllegalArgumentException if the line height is not positive
     */
    public static Builder builder(final float lineHeight, final float ascent) {
        return new Builder(lineHeight, ascent);
    }

    /**
     * Returns whether the font has a glyph or a fixed advance for a character, so the fallback is not used for it.
     *
     * @param codePoint the character
     * @return true if this font draws it
     */
    public boolean has(final int codePoint) {
        return this.glyphs.containsKey(codePoint);
    }

    @Override
    public float width(final TextSpan span) {
        final String text = span.text();
        float width = 0f;
        int index = 0;
        while (index < text.length()) {
            final int codePoint = text.codePointAt(index);
            final Glyph glyph = this.glyphs.get(codePoint);
            if (glyph != null) {
                width += glyph.advance + (span.style().bold() ? 1f : 0f);
                index += Character.charCount(codePoint);
            } else {
                final int end = this.runEnd(text, index);
                if (this.fallback != null) {
                    width += this.fallback.width(new TextSpan(text.substring(index, end), span.style()));
                }
                index = end;
            }
        }
        return width;
    }

    @Override
    public float lineHeight() {
        return this.lineHeight;
    }

    @Override
    public float ascent() {
        return this.ascent;
    }

    /**
     * Marks the font closed, and closes the fallback when it is the default one the font found itself. A fallback
     * given to the builder stays open.
     */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.ownsFallback) {
                this.fallback.close();
            }
        }
    }

    @Override
    boolean isClosed() {
        return this.closed;
    }

    @Override
    void draw(final VulkanCanvas canvas, final TextSpan span, final float x, final float baseline, final int argb) {
        if (this.closed) {
            return;
        }
        final TextStyle style = span.style();
        final String text = span.text();
        final int color = canvas.color(argb);
        float pen = x;
        int index = 0;
        while (index < text.length()) {
            final int codePoint = text.codePointAt(index);
            final Glyph glyph = this.glyphs.get(codePoint);
            if (glyph != null) {
                if (glyph.sheet >= 0) {
                    final GlyphAtlas.Entry entry = this.entry(canvas, glyph, style.italic());
                    // The cell's top snaps to the pixel grid as a clip's edge does, so a cell at the top of its line
                    // never pokes above a clip there
                    final int top = Math.round(canvas.deviceY(baseline - glyph.top));
                    canvas.glyph(entry, Math.round(canvas.deviceX(pen)), top + entry.top(), color);
                    if (style.bold()) {
                        canvas.glyph(entry, Math.round(canvas.deviceX(pen + 1f)), top + entry.top(), color);
                    }
                }
                pen += glyph.advance + (style.bold() ? 1f : 0f);
                index += Character.charCount(codePoint);
            } else {
                final int end = this.runEnd(text, index);
                if (this.fallback != null) {
                    final TextSpan run = new TextSpan(text.substring(index, end), style);
                    this.fallback.draw(canvas, run, pen, baseline, argb);
                    pen += this.fallback.width(run);
                }
                index = end;
            }
        }
    }

    /**
     * Returns where a run of characters this font cannot draw ends.
     */
    private int runEnd(final String text, final int start) {
        int index = start;
        while (index < text.length()) {
            final int codePoint = text.codePointAt(index);
            if (this.glyphs.containsKey(codePoint)) {
                break;
            }
            index += Character.charCount(codePoint);
        }
        return index;
    }

    /**
     * Returns a glyph's atlas entry at the canvas's scale, rendering it the first time.
     */
    private GlyphAtlas.Entry entry(final VulkanCanvas canvas, final Glyph glyph, final boolean italic) {
        final float scale = canvas.scale();
        if (glyph.scale != scale || glyph.atlas != canvas.atlas()) {
            glyph.scale = scale;
            glyph.atlas = canvas.atlas();
            glyph.plain = null;
            glyph.italic = null;
        }
        GlyphAtlas.Entry entry = italic ? glyph.italic : glyph.plain;
        if (entry == null) {
            entry = canvas.atlas().get(new CellKey(this, glyph, Float.floatToIntBits(scale), italic),
                    bitmap -> this.rasterize(glyph, scale, italic, bitmap));
            if (italic) {
                glyph.italic = entry;
            } else {
                glyph.plain = entry;
            }
        }
        return entry;
    }

    /**
     * Scales a glyph's cell to device pixels with nearest sampling, slanting it for italic.
     */
    private boolean rasterize(final Glyph glyph, final float pixelsPerUnit, final boolean italic,
                              final GlyphAtlas.Bitmap out) {
        final float pixelsPerCell = glyph.cellScale * pixelsPerUnit;
        final int height = Math.round(glyph.height * pixelsPerCell);
        final int body = Math.round(glyph.width * pixelsPerCell);
        if (height <= 0 || body <= 0) {
            return false;
        }
        final int top = Math.round(glyph.top * pixelsPerUnit);
        // A row's shift to the right, in device pixels, is a quarter of its height above the baseline
        final float highest = italic ? ITALIC_SLANT * (top - 0.5f) : 0f;
        final float lowest = italic ? ITALIC_SLANT * (top - height + 0.5f) : 0f;
        final int left = (int) Math.floor(Math.min(0f, lowest));
        final int width = (int) Math.ceil(body + Math.max(0f, highest)) - left;
        final Sheet sheet = this.sheets.get(glyph.sheet);
        final byte[] pixels = new byte[width * height];
        for (int row = 0; row < height; row++) {
            final float shift = italic ? ITALIC_SLANT * (top - row - 0.5f) : 0f;
            final int sourceY = Math.min(glyph.height - 1, (int) ((row + 0.5f) / pixelsPerCell));
            for (int column = 0; column < width; column++) {
                final float inCell = column + left + 0.5f - shift;
                if (inCell < 0f || inCell >= body) {
                    continue;
                }
                final int sourceX = Math.min(glyph.width - 1, (int) (inCell / pixelsPerCell));
                pixels[row * width + column] =
                        sheet.alpha[(glyph.y + sourceY) * sheet.width + glyph.x + sourceX];
            }
        }
        out.width = width;
        out.height = height;
        out.pitch = width;
        out.left = left;
        out.top = top;
        out.pixels = ByteBuffer.wrap(pixels);
        return true;
    }

    /**
     * A sheet's alpha channel.
     */
    private record Sheet(int width, int height, byte[] alpha) {
    }

    /**
     * What a glyph at a scale is filed under in the atlas.
     */
    private record CellKey(BitmapFont font, Glyph glyph, int scaleBits, boolean italic) {
    }

    /**
     * One character: a cell of a sheet, or a fixed advance with nothing to draw when {@code sheet} is negative.
     */
    private static final class Glyph {
        final int sheet;
        final int x;
        final int y;
        final int width;
        final int height;
        /** UI units per pixel of the cell. */
        final float cellScale;
        /** How far the cell's top sits above the baseline, in UI units. */
        final float top;
        final float advance;
        // The entries last drawn, their atlas and the scale they were drawn at. A font can serve several renderers,
        // each with an atlas of its own.
        float scale = Float.NaN;
        GlyphAtlas atlas;
        GlyphAtlas.Entry plain;
        GlyphAtlas.Entry italic;

        Glyph(final int sheet, final int x, final int y, final int width, final int height, final float cellScale,
              final float top, final float advance) {
            this.sheet = sheet;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.cellScale = cellScale;
            this.top = top;
            this.advance = advance;
        }
    }

    /**
     * Collects a font's sheets and advances.
     */
    public static final class Builder {
        private final float lineHeight;
        private final float ascent;
        private final List<Sheet> sheets;
        private final Map<Integer, Glyph> glyphs;
        private VulkanFont fallback;

        private Builder(final float lineHeight, final float ascent) {
            if (!(lineHeight > 0f)) {
                throw new IllegalArgumentException("Line height must be positive: " + lineHeight);
            }
            this.lineHeight = lineHeight;
            this.ascent = ascent;
            this.sheets = new ArrayList<>();
            this.glyphs = new HashMap<>();
        }

        /**
         * Adds a sheet of glyphs from an image file, decoded with stb_image, which reads PNG, JPEG, BMP, TGA and GIF.
         * Everything else is as in {@link #sheet(PixelBuffer, float, float, List)}.
         *
         * @param imageFile the image file's bytes
         * @param height the height in UI units that each cell is drawn at
         * @param ascent how far each cell's top sits above the baseline, in UI units
         * @param rows the characters in each row of cells, top to bottom
         * @return this builder
         * @throws IllegalArgumentException if the file cannot be decoded, there are no rows, the rows differ in
         *     length, or the image does not divide into whole cells
         */
        public Builder sheet(final byte[] imageFile, final float height, final float ascent, final List<String> rows) {
            final VulkanSprites.SpriteImage image = VulkanSprites.decode(imageFile, 1f)
                    .orElseThrow(() -> new IllegalArgumentException("The sheet's image cannot be decoded"));
            final byte[] alpha = new byte[image.width() * image.height()];
            for (int i = 0; i < alpha.length; i++) {
                alpha[i] = image.rgba()[i * 4 + 3];
            }
            return this.sheet(new Sheet(image.width(), image.height(), alpha), height, ascent, rows);
        }

        /**
         * Adds a sheet of glyphs. The image is split into as many rows as {@code rows} has strings and as many
         * columns as the first string has characters; every string must have the same number. The character
         * {@code U+0000} marks a cell with no glyph.
         *
         * @param image the sheet, whose pixels' alpha is the glyphs' shape; read now, so later changes do not show
         * @param height the height in UI units that each cell is drawn at
         * @param ascent how far each cell's top sits above the baseline, in UI units
         * @param rows the characters in each row of cells, top to bottom
         * @return this builder
         * @throws IllegalArgumentException if there are no rows, the rows differ in length, or the image does not
         *     divide into whole cells
         */
        public Builder sheet(final PixelBuffer image, final float height, final float ascent, final List<String> rows) {
            final int[] argb = image.array();
            final byte[] alpha = new byte[image.width() * image.height()];
            for (int i = 0; i < alpha.length; i++) {
                alpha[i] = (byte) (argb[i] >>> 24);
            }
            return this.sheet(new Sheet(image.width(), image.height(), alpha), height, ascent, rows);
        }

        /**
         * Gives a character a fixed advance with nothing drawn, unless it already has a glyph or advance.
         *
         * @param codePoint the character
         * @param advance how far it advances, in UI units, before bold adds its unit
         * @return this builder
         */
        public Builder advance(final int codePoint, final float advance) {
            this.glyphs.putIfAbsent(codePoint, new Glyph(-1, 0, 0, 0, 0, 1f, 0f, advance));
            return this;
        }

        /**
         * Sets the font for characters with no glyph. The bitmap font does not close it.
         *
         * @param fallback the font
         * @return this builder
         */
        public Builder fallback(final VulkanFont fallback) {
            this.fallback = Objects.requireNonNull(fallback, "fallback");
            return this;
        }

        /**
         * Builds the font. Without a fallback given, it reads the system's sans-serif font at the line height, and
         * characters with no glyph are left out where the system has none.
         *
         * @return the font
         */
        public BitmapFont build() {
            return new BitmapFont(this);
        }

        private Builder sheet(final Sheet sheet, final float height, final float ascent, final List<String> rows) {
            if (rows.isEmpty() || !(height > 0f)) {
                throw new IllegalArgumentException("A sheet needs rows and a positive height");
            }
            final int columns = rows.getFirst().codePointCount(0, rows.getFirst().length());
            for (final String row : rows) {
                if (row.codePointCount(0, row.length()) != columns) {
                    throw new IllegalArgumentException("Every row of a sheet needs " + columns + " characters: " + row);
                }
            }
            if (columns == 0 || sheet.width() % columns != 0 || sheet.height() % rows.size() != 0) {
                throw new IllegalArgumentException("A " + sheet.width() + " by " + sheet.height()
                        + " image does not divide into " + columns + " by " + rows.size() + " cells");
            }
            final int index = this.sheets.size();
            this.sheets.add(sheet);
            final int cellWidth = sheet.width() / columns;
            final int cellHeight = sheet.height() / rows.size();
            final float scale = height / cellHeight;
            for (int row = 0; row < rows.size(); row++) {
                final int[] characters = rows.get(row).codePoints().toArray();
                for (int column = 0; column < columns; column++) {
                    if (characters[column] == 0 || this.glyphs.containsKey(characters[column])) {
                        continue;
                    }
                    final int x = column * cellWidth;
                    final int y = row * cellHeight;
                    final int ink = Builder.inkWidth(sheet, x, y, cellWidth, cellHeight);
                    final float advance = (int) (0.5f + ink * scale) + 1f;
                    this.glyphs.put(characters[column],
                            new Glyph(index, x, y, cellWidth, cellHeight, scale, ascent, advance));
                }
            }
            return this;
        }

        /**
         * Returns one more than the rightmost column of a cell that has any opaque pixel, or 0 for an empty cell.
         */
        private static int inkWidth(final Sheet sheet, final int x, final int y, final int width, final int height) {
            for (int column = width - 1; column >= 0; column--) {
                for (int row = 0; row < height; row++) {
                    if (sheet.alpha()[(y + row) * sheet.width() + x + column] != 0) {
                        return column + 1;
                    }
                }
            }
            return 0;
        }
    }
}

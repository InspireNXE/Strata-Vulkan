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

import org.lwjgl.CLongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FT_Vector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.lwjgl.util.freetype.FreeType.FT_Done_Face;
import static org.lwjgl.util.freetype.FreeType.FT_Done_FreeType;
import static org.lwjgl.util.freetype.FreeType.FT_ENCODING_UNICODE;
import static org.lwjgl.util.freetype.FreeType.FT_Err_Ok;
import static org.lwjgl.util.freetype.FreeType.FT_FACE_FLAG_KERNING;
import static org.lwjgl.util.freetype.FreeType.FT_GLYPH_FORMAT_OUTLINE;
import static org.lwjgl.util.freetype.FreeType.FT_Get_Advance;
import static org.lwjgl.util.freetype.FreeType.FT_Get_Char_Index;
import static org.lwjgl.util.freetype.FreeType.FT_Get_Kerning;
import static org.lwjgl.util.freetype.FreeType.FT_GlyphSlot_Embolden;
import static org.lwjgl.util.freetype.FreeType.FT_GlyphSlot_Oblique;
import static org.lwjgl.util.freetype.FreeType.FT_Init_FreeType;
import static org.lwjgl.util.freetype.FreeType.FT_KERNING_UNSCALED;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_NO_BITMAP;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_NO_SCALE;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_TARGET_LIGHT;
import static org.lwjgl.util.freetype.FreeType.FT_New_Memory_Face;
import static org.lwjgl.util.freetype.FreeType.FT_PIXEL_MODE_GRAY;
import static org.lwjgl.util.freetype.FreeType.FT_RENDER_MODE_NORMAL;
import static org.lwjgl.util.freetype.FreeType.FT_Load_Glyph;
import static org.lwjgl.util.freetype.FreeType.FT_Render_Glyph;
import static org.lwjgl.util.freetype.FreeType.FT_STYLE_FLAG_BOLD;
import static org.lwjgl.util.freetype.FreeType.FT_STYLE_FLAG_ITALIC;
import static org.lwjgl.util.freetype.FreeType.FT_Select_Charmap;
import static org.lwjgl.util.freetype.FreeType.FT_Set_Char_Size;

/**
 * A family of fonts at one size, read with FreeType: a regular face, optionally bold, italic and bold italic faces,
 * and fallback faces for characters the family lacks. Any font file FreeType reads works, TrueType and OpenType alike,
 * and collections ({@code .ttc}) with a face index.
 *
 * <p>A style the family has no face for is made from the nearest face: bold by thickening its outlines, italic by
 * slanting them, as FreeType's synthesis does. A character no face has draws as the regular face's missing glyph box.
 * Widths come from the fonts' unhinted advances and kerning tables, so text measures the same at every scale; glyphs
 * are drawn with light hinting, which keeps their widths and sharpens their horizontal strokes.</p>
 *
 * <p>Text shaping is not done: each character is drawn with its own glyph, so ligatures and the joining of Arabic
 * letters do not appear, and kerning comes only from the fonts' {@code kern} tables.</p>
 *
 * <p>A font holds native memory until {@link #close()}. Use it on the UI thread only.</p>
 */
public final class VulkanFont implements AutoCloseable {
    private static final int REGULAR = 0;
    private static final int BOLD = 1;
    private static final int ITALIC = 2;
    private static final int BOLD_ITALIC = 3;

    private final float size;
    private final long library;
    // Indexed by REGULAR, BOLD, ITALIC and BOLD_ITALIC; null where the family has no face
    private final Face[] styles;
    private final List<Face> fallbacks;
    private final List<Face> faces;
    private final Map<Long, Glyph> glyphs;
    private boolean closed;

    private VulkanFont(final float size, final long library, final Face[] styles, final List<Face> fallbacks,
                       final List<Face> faces) {
        this.size = size;
        this.library = library;
        this.styles = styles;
        this.fallbacks = fallbacks;
        this.faces = faces;
        this.glyphs = new HashMap<>();
    }

    /**
     * Starts a family at a size.
     *
     * @param size the size in UI units: the em square's height, as a point size is
     * @return the builder
     * @throws IllegalArgumentException if the size is not finite and greater than 0
     */
    public static Builder builder(final float size) {
        return new Builder(size);
    }

    /**
     * Reads one font file as a family, with bold and italic made from it.
     *
     * @param regular the font file
     * @param size the size in UI units
     * @return the font
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if FreeType cannot read the file, or the size is not finite and greater than 0
     */
    public static VulkanFont load(final Path regular, final float size) throws IOException {
        return VulkanFont.builder(size).regular(regular).build();
    }

    /**
     * Finds the operating system's sans-serif family: Segoe UI on Windows, Arial on macOS, and DejaVu Sans, Noto Sans
     * or Liberation Sans on Linux, with the system's symbol and CJK fonts as fallbacks where they are found.
     *
     * @param size the size in UI units
     * @return the font, or empty if none of those fonts is installed
     * @throws IllegalArgumentException if the size is not finite and greater than 0
     */
    public static Optional<VulkanFont> system(final float size) {
        return SystemFonts.sans(size);
    }

    /**
     * Finds the operating system's monospaced family: Consolas on Windows, Menlo on macOS, and DejaVu Sans Mono, Noto
     * Sans Mono or Liberation Mono on Linux.
     *
     * @param size the size in UI units
     * @return the font, or empty if none of those fonts is installed
     * @throws IllegalArgumentException if the size is not finite and greater than 0
     */
    public static Optional<VulkanFont> systemMonospace(final float size) {
        return SystemFonts.monospace(size);
    }

    /**
     * Returns the size.
     *
     * @return the em square's height in UI units
     */
    public float size() {
        return this.size;
    }

    /**
     * Returns the distance from the top of a line to its baseline, from the regular face.
     *
     * @return the ascent in UI units, positive
     */
    public float ascent() {
        final Face face = this.styles[REGULAR];
        return Math.max(face.ascender, 1f / 64f) * this.size;
    }

    /**
     * Returns the distance from one line's top to the next line's top, from the regular face: its line spacing, or
     * its ascent plus descent when that is larger.
     *
     * @return the line height in UI units, positive
     */
    public float lineHeight() {
        final Face face = this.styles[REGULAR];
        return Math.max(Math.max(face.lineSpacing, face.ascender - face.descender), 1f / 64f) * this.size;
    }

    /**
     * Frees the fonts' native memory. Text drawn with the font afterwards shows nothing. Calling it again does
     * nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (final Face face : this.faces) {
            FT_Done_Face(face.face);
            MemoryUtil.memFree(face.data);
        }
        FT_Done_FreeType(this.library);
        this.glyphs.clear();
    }

    /**
     * Returns the line under the baseline that underlines text, as an offset below the baseline and a thickness.
     */
    float underlineOffset() {
        return -this.styles[REGULAR].underlinePosition * this.size;
    }

    float underlineThickness() {
        return Math.max(this.styles[REGULAR].underlineThickness, 1f / 20f) * this.size;
    }

    boolean isClosed() {
        return this.closed;
    }

    /**
     * Returns the glyph that draws a character in a style.
     *
     * @param codePoint the character
     * @param bold true for bold
     * @param italic true for italic
     * @return the glyph, which may be the missing glyph box
     */
    Glyph glyph(final int codePoint, final boolean bold, final boolean italic) {
        final long key = ((long) codePoint << 2) | (bold ? 1L : 0L) | (italic ? 2L : 0L);
        Glyph glyph = this.glyphs.get(key);
        if (glyph == null) {
            glyph = this.find(codePoint, bold, italic);
            this.glyphs.put(key, glyph);
        }
        return glyph;
    }

    /**
     * Returns the kerning between two glyphs drawn side by side, in ems.
     */
    float kerning(final Glyph left, final Glyph right) {
        if (left.face != right.face || !left.face.kerning || this.closed) {
            return 0f;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final FT_Vector vector = FT_Vector.malloc(stack);
            if (FT_Get_Kerning(left.face.face, left.index, right.index, FT_KERNING_UNSCALED, vector) != FT_Err_Ok) {
                return 0f;
            }
            return vector.x() / left.face.unitsPerEm;
        }
    }

    /**
     * Rasterizes a glyph at a size in device pixels.
     *
     * @param glyph the glyph
     * @param pixels the em square's height in device pixels
     * @param out receives the bitmap
     * @return false if the glyph has no outline to draw
     */
    boolean rasterize(final Glyph glyph, final float pixels, final GlyphBitmap out) {
        if (this.closed) {
            return false;
        }
        final Face face = glyph.face;
        final long size64 = Math.max(1L, Math.round(pixels * 64.0));
        if (face.pixelSize64 != size64) {
            if (FT_Set_Char_Size(face.face, 0L, size64, 72, 72) != FT_Err_Ok) {
                return false;
            }
            face.pixelSize64 = size64;
        }
        if (FT_Load_Glyph(face.face, glyph.index, FT_LOAD_NO_BITMAP | FT_LOAD_TARGET_LIGHT) != FT_Err_Ok) {
            return false;
        }
        final FT_GlyphSlot slot = face.face.glyph();
        if (slot == null || slot.format() != FT_GLYPH_FORMAT_OUTLINE) {
            return false;
        }
        if (glyph.syntheticBold) {
            FT_GlyphSlot_Embolden(slot);
        }
        if (glyph.syntheticItalic) {
            FT_GlyphSlot_Oblique(slot);
        }
        if (FT_Render_Glyph(slot, FT_RENDER_MODE_NORMAL) != FT_Err_Ok) {
            return false;
        }
        final FT_Bitmap bitmap = slot.bitmap();
        if (bitmap.pixel_mode() != FT_PIXEL_MODE_GRAY) {
            return false;
        }
        out.width = bitmap.width();
        out.height = bitmap.rows();
        out.pitch = bitmap.pitch();
        out.left = slot.bitmap_left();
        out.top = slot.bitmap_top();
        out.pixels = out.width == 0 || out.height == 0 ? null : bitmap.buffer(Math.abs(out.pitch) * out.height);
        return true;
    }

    private Glyph find(final int codePoint, final boolean bold, final boolean italic) {
        final int wanted = (bold ? BOLD : REGULAR) | (italic ? ITALIC : REGULAR);
        Face face = this.styles[wanted];
        if (face == null && wanted == BOLD_ITALIC) {
            face = this.styles[BOLD] != null ? this.styles[BOLD] : this.styles[ITALIC];
        }
        if (face == null) {
            face = this.styles[REGULAR];
        }
        int index = this.closed ? 0 : FT_Get_Char_Index(face.face, codePoint);
        if (index == 0 && !this.closed) {
            for (final Face fallback : this.fallbacks) {
                final int found = FT_Get_Char_Index(fallback.face, codePoint);
                if (found != 0) {
                    face = fallback;
                    index = found;
                    break;
                }
            }
        }
        final boolean syntheticBold = bold && !face.bold;
        final boolean syntheticItalic = italic && !face.italic;
        float advance = 0f;
        if (!this.closed) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final CLongBuffer units = stack.mallocCLong(1);
                if (FT_Get_Advance(face.face, index, FT_LOAD_NO_SCALE, units) == FT_Err_Ok) {
                    advance = units.get(0) / face.unitsPerEm;
                }
            }
        }
        // FreeType's emboldening widens each glyph by a 24th of the em
        if (syntheticBold) {
            advance += 1f / 24f;
        }
        return new Glyph(face, index, advance, syntheticBold, syntheticItalic);
    }

    /**
     * One face of a font file, with its metrics in ems.
     */
    static final class Face {
        final FT_Face face;
        final ByteBuffer data;
        final float unitsPerEm;
        final float ascender;
        final float descender;
        final float lineSpacing;
        final float underlinePosition;
        final float underlineThickness;
        final boolean kerning;
        final boolean bold;
        final boolean italic;
        long pixelSize64;

        Face(final FT_Face face, final ByteBuffer data) {
            this.face = face;
            this.data = data;
            this.unitsPerEm = Math.max(1, face.units_per_EM() & 0xFFFF);
            this.ascender = face.ascender() / this.unitsPerEm;
            this.descender = face.descender() / this.unitsPerEm;
            this.lineSpacing = face.height() / this.unitsPerEm;
            this.underlinePosition = face.underline_position() / this.unitsPerEm;
            this.underlineThickness = face.underline_thickness() / this.unitsPerEm;
            this.kerning = (face.face_flags() & FT_FACE_FLAG_KERNING) != 0;
            this.bold = (face.style_flags() & FT_STYLE_FLAG_BOLD) != 0;
            this.italic = (face.style_flags() & FT_STYLE_FLAG_ITALIC) != 0;
        }
    }

    /**
     * A character's glyph in a face, with its advance in ems and the styles made for it.
     */
    static final class Glyph {
        final Face face;
        final int index;
        final float advance;
        final boolean syntheticBold;
        final boolean syntheticItalic;

        Glyph(final Face face, final int index, final float advance, final boolean syntheticBold,
              final boolean syntheticItalic) {
            this.face = face;
            this.index = index;
            this.advance = advance;
            this.syntheticBold = syntheticBold;
            this.syntheticItalic = syntheticItalic;
        }
    }

    /**
     * A rendered glyph: 8-bit coverage rows, and where its top left sits from the pen on the baseline.
     */
    static final class GlyphBitmap {
        int width;
        int height;
        int pitch;
        int left;
        int top;
        ByteBuffer pixels;
    }

    /**
     * Builds a {@link VulkanFont} from font files or bytes. Every family needs a regular face; the others are
     * optional.
     */
    public static final class Builder {
        private final float size;
        private final Source[] styles = new Source[4];
        private final List<Source> collections = new ArrayList<>();
        private final List<Source> fallbacks = new ArrayList<>();

        private Builder(final float size) {
            if (!(size > 0f) || Float.isInfinite(size)) {
                throw new IllegalArgumentException("Font size must be finite and greater than 0: " + size);
            }
            this.size = size;
        }

        /**
         * Sets the regular face from a file.
         *
         * @param file the font file
         * @return this builder
         */
        public Builder regular(final Path file) {
            return this.style(REGULAR, new Source(Objects.requireNonNull(file, "file"), null, 0));
        }

        /**
         * Sets the regular face from bytes, such as a font read from the class path. The bytes are copied.
         *
         * @param data the font file's bytes
         * @return this builder
         */
        public Builder regular(final byte[] data) {
            return this.style(REGULAR, new Source(null, data.clone(), 0));
        }

        /**
         * Sets the bold face from a file.
         *
         * @param file the font file
         * @return this builder
         */
        public Builder bold(final Path file) {
            return this.style(BOLD, new Source(Objects.requireNonNull(file, "file"), null, 0));
        }

        /**
         * Sets the bold face from bytes. The bytes are copied.
         *
         * @param data the font file's bytes
         * @return this builder
         */
        public Builder bold(final byte[] data) {
            return this.style(BOLD, new Source(null, data.clone(), 0));
        }

        /**
         * Sets the italic face from a file.
         *
         * @param file the font file
         * @return this builder
         */
        public Builder italic(final Path file) {
            return this.style(ITALIC, new Source(Objects.requireNonNull(file, "file"), null, 0));
        }

        /**
         * Sets the italic face from bytes. The bytes are copied.
         *
         * @param data the font file's bytes
         * @return this builder
         */
        public Builder italic(final byte[] data) {
            return this.style(ITALIC, new Source(null, data.clone(), 0));
        }

        /**
         * Sets the bold italic face from a file.
         *
         * @param file the font file
         * @return this builder
         */
        public Builder boldItalic(final Path file) {
            return this.style(BOLD_ITALIC, new Source(Objects.requireNonNull(file, "file"), null, 0));
        }

        /**
         * Sets the bold italic face from bytes. The bytes are copied.
         *
         * @param data the font file's bytes
         * @return this builder
         */
        public Builder boldItalic(final byte[] data) {
            return this.style(BOLD_ITALIC, new Source(null, data.clone(), 0));
        }

        /**
         * Reads every face of a font collection ({@code .ttc}) and uses each for the style its own flags name, unless
         * that style was set another way. The first regular face found becomes the regular face.
         *
         * @param file the collection
         * @return this builder
         */
        public Builder collection(final Path file) {
            this.collections.add(new Source(Objects.requireNonNull(file, "file"), null, 0));
            return this;
        }

        /**
         * Adds a fallback face, searched in the order added for characters the family's faces lack. Fallbacks draw
         * in every style, bold and italic made from them.
         *
         * @param file the font file
         * @param faceIndex which face of a collection; 0 for a plain font file
         * @return this builder
         */
        public Builder fallback(final Path file, final int faceIndex) {
            this.fallbacks.add(new Source(Objects.requireNonNull(file, "file"), null, faceIndex));
            return this;
        }

        /**
         * Adds a fallback face from bytes. The bytes are copied.
         *
         * @param data the font file's bytes
         * @return this builder
         */
        public Builder fallback(final byte[] data) {
            this.fallbacks.add(new Source(null, data.clone(), 0));
            return this;
        }

        /**
         * Reads the fonts.
         *
         * @return the font
         * @throws IOException if a file cannot be read
         * @throws IllegalArgumentException if FreeType cannot read a font, or no regular face was given or found
         */
        public VulkanFont build() throws IOException {
            final long library;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final PointerBuffer handle = stack.mallocPointer(1);
                if (FT_Init_FreeType(handle) != FT_Err_Ok) {
                    throw new IllegalStateException("FreeType failed to start");
                }
                library = handle.get(0);
            }
            final List<Face> all = new ArrayList<>();
            try {
                final Face[] faces = new Face[4];
                for (int style = 0; style < 4; style++) {
                    if (this.styles[style] != null) {
                        faces[style] = Builder.open(library, this.styles[style], all);
                    }
                }
                for (final Source collection : this.collections) {
                    final byte[] bytes = collection.bytes();
                    final Face first = Builder.open(library, collection.withData(bytes, 0), all);
                    final long count = first.face.num_faces();
                    Builder.assign(faces, first);
                    for (int index = 1; index < count; index++) {
                        Builder.assign(faces, Builder.open(library, collection.withData(bytes, index), all));
                    }
                }
                if (faces[REGULAR] == null) {
                    throw new IllegalArgumentException("A font family needs a regular face");
                }
                final List<Face> fallbacks = new ArrayList<>();
                for (final Source fallback : this.fallbacks) {
                    fallbacks.add(Builder.open(library, fallback, all));
                }
                return new VulkanFont(this.size, library, faces, List.copyOf(fallbacks), List.copyOf(all));
            } catch (final IOException | RuntimeException e) {
                for (final Face face : all) {
                    FT_Done_Face(face.face);
                    MemoryUtil.memFree(face.data);
                }
                FT_Done_FreeType(library);
                throw e;
            }
        }

        private Builder style(final int style, final Source source) {
            this.styles[style] = source;
            return this;
        }

        private static void assign(final Face[] faces, final Face face) {
            final int style = (face.bold ? BOLD : REGULAR) | (face.italic ? ITALIC : REGULAR);
            if (faces[style] == null) {
                faces[style] = face;
            }
        }

        private static Face open(final long library, final Source source, final List<Face> all) throws IOException {
            final byte[] bytes = source.bytes();
            final ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
            data.put(0, bytes);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final PointerBuffer handle = stack.mallocPointer(1);
                if (FT_New_Memory_Face(library, data, source.index(), handle) != FT_Err_Ok) {
                    MemoryUtil.memFree(data);
                    throw new IllegalArgumentException("FreeType cannot read the font "
                            + (source.file() != null ? source.file() : "given as bytes")
                            + (source.index() != 0 ? " at face " + source.index() : ""));
                }
                final FT_Face face = FT_Face.create(handle.get(0));
                FT_Select_Charmap(face, FT_ENCODING_UNICODE);
                final Face opened = new Face(face, data);
                all.add(opened);
                return opened;
            }
        }

        /**
         * Where a face comes from: a file or bytes, and its index in a collection.
         */
        private record Source(Path file, byte[] data, int index) {
            byte[] bytes() throws IOException {
                return this.data != null ? this.data : Files.readAllBytes(this.file);
            }

            Source withData(final byte[] bytes, final int faceIndex) {
                return new Source(this.file, bytes, faceIndex);
            }
        }
    }
}

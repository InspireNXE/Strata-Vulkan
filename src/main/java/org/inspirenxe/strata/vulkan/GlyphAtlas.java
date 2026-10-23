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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;

/**
 * Glyphs rendered at the sizes drawn, packed in rows into pages of 8-bit coverage. A page keeps its pixels on the host
 * too, and the part changed since the last upload goes to the device when the frame ends.
 */
final class GlyphAtlas {
    static final int PAGE = 1024;

    private final VulkanRenderer renderer;
    private final List<Page> pages;
    private final Map<Key, Entry> entries;
    private final VulkanFont.GlyphBitmap bitmap;

    GlyphAtlas(final VulkanRenderer renderer) {
        this.renderer = renderer;
        this.pages = new ArrayList<>();
        this.entries = new HashMap<>();
        this.bitmap = new VulkanFont.GlyphBitmap();
    }

    /**
     * Returns a glyph at a size, rendering it the first time.
     *
     * @param font the font the glyph is from
     * @param glyph the glyph
     * @param pixels the em square's height in device pixels
     * @return the entry; {@link Entry#EMPTY} for a glyph that draws nothing
     */
    Entry get(final VulkanFont font, final VulkanFont.Glyph glyph, final float pixels) {
        final Key key = new Key(glyph, Math.max(1L, Math.round(pixels * 64.0)));
        Entry entry = this.entries.get(key);
        if (entry == null) {
            entry = this.render(font, glyph, key.size64() / 64f);
            this.entries.put(key, entry);
        }
        return entry;
    }

    /**
     * Queues an upload of every page's changed part.
     */
    void upload() {
        for (final Page page : this.pages) {
            if (page.dirtyX1 > page.dirtyX0 && page.dirtyY1 > page.dirtyY0) {
                this.renderer.queueUpload(page.texture, page.dirtyX0, page.dirtyY0, page.dirtyX1 - page.dirtyX0,
                        page.dirtyY1 - page.dirtyY0, page.pixels, page.dirtyY0 * PAGE + page.dirtyX0, PAGE);
                page.dirtyX0 = PAGE;
                page.dirtyY0 = PAGE;
                page.dirtyX1 = 0;
                page.dirtyY1 = 0;
            }
        }
    }

    /**
     * Destroys every page at once. The device must be done with them.
     */
    void destroy() {
        for (final Page page : this.pages) {
            page.texture.destroy(this.renderer);
        }
        this.pages.clear();
        this.entries.clear();
    }

    private Entry render(final VulkanFont font, final VulkanFont.Glyph glyph, final float pixels) {
        final VulkanFont.GlyphBitmap bitmap = this.bitmap;
        if (!font.rasterize(glyph, pixels, bitmap) || bitmap.pixels == null) {
            return Entry.EMPTY;
        }
        final int width = bitmap.width;
        final int height = bitmap.height;
        if (width + 1 > PAGE || height + 1 > PAGE) {
            return Entry.EMPTY;
        }
        Page page = this.pages.isEmpty() ? null : this.pages.get(this.pages.size() - 1);
        if (page == null || !page.fits(width, height)) {
            page = new Page(Texture.create(this.renderer, PAGE, PAGE, VK_FORMAT_R8_UNORM, 1,
                    this.renderer.nearestSampler()));
            this.pages.add(page);
        }
        final int[] at = page.place(width, height);
        final ByteBuffer source = bitmap.pixels;
        final int stride = Math.abs(bitmap.pitch);
        for (int row = 0; row < height; row++) {
            // A negative pitch stores the rows from the bottom up
            final int sourceRow = bitmap.pitch >= 0 ? row : height - 1 - row;
            source.get(sourceRow * stride, page.pixels, (at[1] + row) * PAGE + at[0], width);
        }
        page.dirty(at[0], at[1], width, height);
        return new Entry(page.texture, at[0] / (float) PAGE, at[1] / (float) PAGE, (at[0] + width) / (float) PAGE,
                (at[1] + height) / (float) PAGE, width, height, bitmap.left, bitmap.top);
    }

    private record Key(VulkanFont.Glyph glyph, long size64) {
    }

    /**
     * Where a glyph sits in a page, and where its pixels go from the pen on the baseline.
     */
    record Entry(Texture texture, float u0, float v0, float u1, float v1, int width, int height, int left, int top) {
        static final Entry EMPTY = new Entry(null, 0f, 0f, 0f, 0f, 0, 0, 0, 0);
    }

    /**
     * A page and its packing: rows of glyphs, each row as tall as its tallest glyph, with a pixel between glyphs.
     */
    private static final class Page {
        final Texture texture;
        final byte[] pixels;
        int rowX;
        int rowY;
        int rowHeight;
        // The whole page starts dirty, so its first upload covers every pixel
        int dirtyX0;
        int dirtyY0;
        int dirtyX1 = PAGE;
        int dirtyY1 = PAGE;

        Page(final Texture texture) {
            this.texture = texture;
            this.pixels = new byte[PAGE * PAGE];
        }

        boolean fits(final int width, final int height) {
            if (this.rowX + width + 1 <= PAGE && this.rowY + height + 1 <= PAGE) {
                return true;
            }
            return this.rowY + this.rowHeight + 1 + height + 1 <= PAGE && width + 1 <= PAGE;
        }

        int[] place(final int width, final int height) {
            if (this.rowX + width + 1 > PAGE) {
                this.rowY += this.rowHeight + 1;
                this.rowX = 0;
                this.rowHeight = 0;
            }
            final int[] at = {this.rowX + 1, this.rowY + 1};
            this.rowX += width + 1;
            this.rowHeight = Math.max(this.rowHeight, height);
            return at;
        }

        void dirty(final int x, final int y, final int width, final int height) {
            this.dirtyX0 = Math.min(this.dirtyX0, x);
            this.dirtyY0 = Math.min(this.dirtyY0, y);
            this.dirtyX1 = Math.max(this.dirtyX1, x + width);
            this.dirtyY1 = Math.max(this.dirtyY1, y + height);
        }
    }
}

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

import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.theme.SpriteId;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load_from_memory;

/**
 * Loads sprites for the Vulkan canvas from a {@link SpriteSource}: by default the class path, at the resource a
 * {@link SpriteResolver} names ({@link SpriteResolver#DEFAULT} unless one is given). Images are decoded once and
 * cached, including the fact that one is missing, until {@link #clear()}, and the renderer keeps a texture of each
 * image drawn.
 *
 * <p>Where one UI unit covers 2 device pixels or more the canvas draws the source's image of density 2 if it has one,
 * from 1.5 its image of density 1.5, and otherwise the plain image, each at the same size in UI units.</p>
 *
 * <p>A sprite can instead live in an application's atlas, a {@link VulkanAtlas} made for this set by
 * {@link VulkanRenderer#atlas}: it then draws from the atlas, at the size of its region, and the source is not
 * asked for it.</p>
 *
 * <p>Use it on the UI thread only.</p>
 */
public final class VulkanSprites {
    private static final float[] DENSITIES = {2f, 1.5f};

    private final SpriteSource source;
    private final Map<SpriteId, Optional<SpriteImage>> cache;
    private final Map<DenseKey, Optional<SpriteImage>> dense;
    private final Map<SpriteId, AtlasRegion> regions;
    private long version;

    /**
     * Creates a loader that reads the class path with {@link SpriteResolver#DEFAULT}.
     *
     * @param loader the class loader to find images with
     */
    public VulkanSprites(final ClassLoader loader) {
        this(loader, SpriteResolver.DEFAULT);
    }

    /**
     * Creates a loader that reads the class path.
     *
     * @param loader the class loader to find images with
     * @param resolver where each sprite's image lives on the class path
     */
    public VulkanSprites(final ClassLoader loader, final SpriteResolver resolver) {
        this(SpriteSource.classPath(loader, resolver));
    }

    /**
     * Creates a loader that reads from any source.
     *
     * @param source where image files come from
     */
    public VulkanSprites(final SpriteSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.cache = new HashMap<>();
        this.dense = new HashMap<>();
        this.regions = new HashMap<>();
    }

    /**
     * Returns whether a sprite has a region in an atlas, or the source has an image for it that can be decoded.
     *
     * @param sprite the sprite
     * @return true if the sprite draws as its image, false if it draws as the placeholder
     */
    public boolean has(final SpriteId sprite) {
        return this.regions.containsKey(sprite) || this.plain(sprite).isPresent();
    }

    /**
     * Returns a sprite's size in UI units: its region's width and height in an atlas, or else its plain image's width
     * and height in pixels, one pixel to a unit. Views give it to their screens, so an image showing a sprite takes
     * the sprite's size. The first call for a sprite with no region loads its image, as drawing it does.
     *
     * @param sprite the sprite
     * @return the size, or empty if the sprite has no region and the source has no image for it that can be decoded
     */
    public Optional<Size> size(final SpriteId sprite) {
        final AtlasRegion region = this.regions.get(sprite);
        if (region != null) {
            return Optional.of(new Size(region.width(), region.height()));
        }
        return this.plain(sprite).map(image -> new Size(image.width(), image.height()));
    }

    /**
     * Forgets every loaded image, so each is loaded from the source again when next drawn. Renderers destroy their
     * textures of the old images once the device is done with them. Regions in atlases stay.
     */
    public void clear() {
        this.cache.clear();
        this.dense.clear();
        this.version++;
    }

    /**
     * Returns a number that changes on every {@link #clear()}, so a renderer knows when its textures are stale.
     *
     * @return the version
     */
    public long version() {
        return this.version;
    }

    /**
     * Returns the region a sprite has in an atlas.
     *
     * @return the region, or {@code null} if it has none
     */
    AtlasRegion region(final SpriteId sprite) {
        return this.regions.get(sprite);
    }

    /**
     * Gives a sprite a region, and moves the version on so views size and draw it again.
     */
    void putRegion(final SpriteId sprite, final AtlasRegion region) {
        this.regions.put(sprite, region);
        this.touch();
    }

    /**
     * Forgets the regions of an atlas that is closing, and moves the version on if it had any.
     */
    void removeRegions(final VulkanAtlas atlas) {
        if (this.regions.values().removeIf(region -> region.atlas() == atlas)) {
            this.touch();
        }
    }

    /**
     * Moves the version on without forgetting any image, for what the sprites draw from outside this set changing.
     */
    void touch() {
        this.version++;
    }

    /**
     * Returns the densest image of a sprite that a density can show.
     *
     * @param sprite the sprite
     * @param pixelsPerUnit device pixels per UI unit where it is drawn
     * @return the image, or empty if the source has none at all
     */
    Optional<SpriteImage> image(final SpriteId sprite, final float pixelsPerUnit) {
        for (final float density : DENSITIES) {
            // A little below, so a scale of 1.5 stored as 1.4999 still counts
            if (pixelsPerUnit >= density - 0.01f) {
                final Optional<SpriteImage> image = this.dense.computeIfAbsent(new DenseKey(sprite, density),
                        key -> this.source.loadDense(key.sprite(), key.density())
                                .flatMap(bytes -> VulkanSprites.decode(bytes, key.density())));
                if (image.isPresent()) {
                    return image;
                }
            }
        }
        return this.plain(sprite);
    }

    private Optional<SpriteImage> plain(final SpriteId sprite) {
        return this.cache.computeIfAbsent(sprite, key -> this.source.load(key)
                .flatMap(bytes -> VulkanSprites.decode(bytes, 1f)));
    }

    /**
     * Decodes an image file to 8-bit RGBA.
     */
    static Optional<SpriteImage> decode(final byte[] file, final float density) {
        if (file.length == 0) {
            return Optional.empty();
        }
        final ByteBuffer encoded = MemoryUtil.memAlloc(file.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoded.put(0, file);
            final IntBuffer width = stack.mallocInt(1);
            final IntBuffer height = stack.mallocInt(1);
            final IntBuffer channels = stack.mallocInt(1);
            final ByteBuffer pixels = stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) {
                return Optional.empty();
            }
            try {
                final byte[] rgba = new byte[width.get(0) * height.get(0) * 4];
                pixels.get(0, rgba);
                return Optional.of(new SpriteImage(width.get(0), height.get(0), rgba, density));
            } finally {
                stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    /**
     * A decoded image, and how many of its pixels cover one pixel of the plain image.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param rgba the pixels, 8 bits per channel, red first, alpha not premultiplied
     * @param density 1 for the plain image
     */
    record SpriteImage(int width, int height, byte[] rgba, float density) {
    }

    /**
     * Where a sprite is in an atlas: its size in pixels, and its rectangle in texture coordinates, with v growing
     * downward as the rows of the image do.
     */
    record AtlasRegion(VulkanAtlas atlas, int width, int height, float u0, float v0, float u1, float v1) {
        /**
         * Maps a horizontal position across the sprite, 0 to 1, to a texture coordinate of the atlas.
         */
        float u(final float t) {
            return this.u0 + t * (this.u1 - this.u0);
        }

        /**
         * Maps a vertical position down the sprite, 0 to 1, to a texture coordinate of the atlas.
         */
        float v(final float t) {
            return this.v0 + t * (this.v1 - this.v0);
        }
    }

    private record DenseKey(SpriteId sprite, float density) {
    }
}

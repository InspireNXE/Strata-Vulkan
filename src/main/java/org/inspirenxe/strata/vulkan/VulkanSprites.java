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
 * <p>Use it on the UI thread only.</p>
 */
public final class VulkanSprites {
    private static final float[] DENSITIES = {2f, 1.5f};

    private final SpriteSource source;
    private final Map<SpriteId, Optional<SpriteImage>> cache;
    private final Map<DenseKey, Optional<SpriteImage>> dense;
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
    }

    /**
     * Returns whether the source has an image for a sprite that can be decoded.
     *
     * @param sprite the sprite
     * @return true if the sprite draws as its image, false if it draws as the placeholder
     */
    public boolean has(final SpriteId sprite) {
        return this.plain(sprite).isPresent();
    }

    /**
     * Returns a sprite's size in UI units: its plain image's width and height in pixels, one pixel to a unit. Views
     * give it to their screens, so an image showing a sprite takes the sprite's size. The first call for a sprite
     * loads its image, as drawing it does.
     *
     * @param sprite the sprite
     * @return the size, or empty if the source has no image for the sprite that can be decoded
     */
    public Optional<Size> size(final SpriteId sprite) {
        return this.plain(sprite).map(image -> new Size(image.width(), image.height()));
    }

    /**
     * Forgets every loaded image, so each is loaded from the source again when next drawn. Renderers destroy their
     * textures of the old images once the device is done with them.
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

    private record DenseKey(SpriteId sprite, float density) {
    }
}

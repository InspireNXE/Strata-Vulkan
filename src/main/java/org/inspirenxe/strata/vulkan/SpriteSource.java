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

import org.inspirenxe.strata.api.theme.SpriteId;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads the image file for a sprite id, from wherever the images live: the class path, a folder, a zip file, or images
 * made in code. {@link VulkanSprites} asks once per sprite and caches the answer, missing images included, until
 * {@link VulkanSprites#clear()}. Files are decoded with stb_image, which reads PNG, JPEG, BMP, TGA and GIF.
 *
 * <p>Implementations are called on the UI thread. They should not throw: a file that cannot be read is reported as
 * empty, and the canvas draws its placeholder.</p>
 */
@FunctionalInterface
public interface SpriteSource {
    /**
     * Loads a sprite's image file.
     *
     * @param sprite the sprite
     * @return the file's bytes, or empty if there is none or it cannot be read
     */
    Optional<byte[]> load(SpriteId sprite);

    /**
     * Loads a sprite's image drawn for a denser display, with {@code density} image pixels for each pixel of the
     * plain image, so it shows the same sprite at the same size in UI units, only sharper. {@link VulkanSprites} asks
     * for density 2 where one UI unit covers at least 2 device pixels and for 1.5 where it covers at least 1.5, and
     * uses the plain image from {@link #load} when the answer is empty. The default has no denser images.
     *
     * @param sprite the sprite
     * @param density how many times denser than the plain image, 1.5 or 2
     * @return the file's bytes, or empty if there is none for the density or it cannot be read
     */
    default Optional<byte[]> loadDense(final SpriteId sprite, final float density) {
        return Optional.empty();
    }

    /**
     * Returns a source that reads image files from the class path, at the resource a resolver names. Denser images
     * sit beside them with {@code @2x} or {@code @1.5x} before the extension, such as
     * {@code assets/<namespace>/sprites/<path>@2x.png}.
     *
     * @param loader the class loader to find images with
     * @param resolver where each sprite's image lives on the class path
     * @return the source
     */
    static SpriteSource classPath(final ClassLoader loader, final SpriteResolver resolver) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(resolver, "resolver");
        return new SpriteSource() {
            @Override
            public Optional<byte[]> load(final SpriteId sprite) {
                return SpriteSource.read(loader, resolver.resourcePath(sprite));
            }

            @Override
            public Optional<byte[]> loadDense(final SpriteId sprite, final float density) {
                return SpriteSource.read(loader, SpriteSource.densePath(resolver.resourcePath(sprite), density));
            }
        };
    }

    /**
     * Returns the path of a denser image: {@code @2x} or {@code @1.5x} put before the file's extension, or after the
     * name when it has none.
     *
     * @param path the plain image's path
     * @param density how many times denser
     * @return the denser image's path
     */
    static String densePath(final String path, final float density) {
        final String suffix = "@" + (density == Math.rint(density) ? Integer.toString((int) density)
                : Float.toString(density)) + "x";
        final int dot = path.lastIndexOf('.');
        if (dot <= path.lastIndexOf('/')) {
            return path + suffix;
        }
        return path.substring(0, dot) + suffix + path.substring(dot);
    }

    private static Optional<byte[]> read(final ClassLoader loader, final String path) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (final IOException e) {
            // A broken file is treated like a missing one, so the canvas draws its placeholder instead of failing
            return Optional.empty();
        }
    }
}

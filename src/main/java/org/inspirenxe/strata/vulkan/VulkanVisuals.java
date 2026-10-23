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
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.inspirenxe.strata.api.visual.Visual;

import java.util.Objects;

/**
 * Makes visuals of pixels held on the host, as the Swing adapter's {@code SwingVisuals} does for images. The canvas
 * draws one as it draws a pixel buffer: a texture copied again when the buffer's version moves, sampled nearest. Any
 * renderer draws them, unlike the visuals {@link VulkanRenderer#image} makes.
 */
public final class VulkanVisuals {

    private VulkanVisuals() {
    }

    /**
     * Makes a live visual of a pixel buffer at its pixel size, one UI unit per pixel. It holds the buffer without
     * copying it, so a change the caller marks with {@link PixelBuffer#changed()} shows on the next frame. Two such
     * visuals are equal only when they hold the same buffer at the same size.
     *
     * @param pixels the pixels
     * @return the visual
     */
    public static Visual image(final PixelBuffer pixels) {
        return VulkanVisuals.image(pixels, pixels.width(), pixels.height());
    }

    /**
     * Makes a live visual of a pixel buffer with a size of its own, for pixels meant to draw smaller or larger than
     * one UI unit each. Everything else is as in {@link #image(PixelBuffer)}.
     *
     * @param pixels the pixels
     * @param width the natural width in UI units
     * @param height the natural height in UI units
     * @return the visual
     * @throws IllegalArgumentException if a size is negative or not finite
     */
    public static Visual image(final PixelBuffer pixels, final float width, final float height) {
        if (!(width >= 0f) || !(height >= 0f) || Float.isInfinite(width) || Float.isInfinite(height)) {
            throw new IllegalArgumentException("A visual's size must be finite and not negative: " + width + " by "
                    + height);
        }
        return new PixelVisual(Objects.requireNonNull(pixels, "pixels"), new Size(width, height));
    }

    /**
     * A pixel buffer as a visual.
     *
     * @param pixels the buffer, held by identity
     * @param size the natural size
     */
    record PixelVisual(PixelBuffer pixels, Size size) implements Visual {
        @Override
        public boolean equals(final Object other) {
            return other instanceof final PixelVisual visual && visual.pixels == this.pixels
                    && visual.size.equals(this.size);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this.pixels) * 31 + this.size.hashCode();
        }
    }
}

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

/**
 * A rectangle of whole framebuffer pixels, such as part of a {@link VulkanFrame}'s damage.
 *
 * @param x the left edge
 * @param y the top edge
 * @param width the width, 0 or more
 * @param height the height, 0 or more
 */
public record PixelRect(int x, int y, int width, int height) {
    /**
     * A rectangle with no area at the origin.
     */
    public static final PixelRect EMPTY = new PixelRect(0, 0, 0, 0);

    /**
     * Checks the size.
     *
     * @throws IllegalArgumentException if the width or height is negative
     */
    public PixelRect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("A rectangle's size must not be negative: " + width + " by " + height);
        }
    }

    /**
     * Returns the right edge.
     *
     * @return {@code x + width}
     */
    public int right() {
        return this.x + this.width;
    }

    /**
     * Returns the bottom edge.
     *
     * @return {@code y + height}
     */
    public int bottom() {
        return this.y + this.height;
    }

    /**
     * Returns whether the rectangle covers no pixel.
     *
     * @return true if the width or height is 0
     */
    public boolean isEmpty() {
        return this.width == 0 || this.height == 0;
    }

    /**
     * Returns the smallest rectangle that holds this one and another. An empty rectangle adds nothing.
     *
     * @param other the other rectangle
     * @return the union
     */
    public PixelRect union(final PixelRect other) {
        if (other.isEmpty()) {
            return this;
        }
        if (this.isEmpty()) {
            return other;
        }
        final int x0 = Math.min(this.x, other.x);
        final int y0 = Math.min(this.y, other.y);
        return new PixelRect(x0, y0, Math.max(this.right(), other.right()) - x0,
                Math.max(this.bottom(), other.bottom()) - y0);
    }
}

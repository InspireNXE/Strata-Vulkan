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
package org.inspirenxe.strata.vulkan.glfw;

/**
 * How big a window is, in the three measures a view needs: the window's own coordinates, which pointer events use, the
 * framebuffer's pixels, which the view draws into, and the content scale of the display.
 *
 * <p>The two sizes differ on displays where GLFW's coordinates are not pixels, such as macOS and Wayland, and are equal
 * on Windows and most of Linux.</p>
 *
 * @param windowWidth the width in the window's coordinates, which GLFW's pointer positions use
 * @param windowHeight the height in the window's coordinates
 * @param framebufferWidth the width of the framebuffer in pixels
 * @param framebufferHeight the height of the framebuffer in pixels
 * @param contentScale the display's content scale, 1 when it has none; a value that is not positive counts as 1
 */
public record WindowMetrics(int windowWidth, int windowHeight, int framebufferWidth, int framebufferHeight, float contentScale) {

    /**
     * Returns how many framebuffer pixels one unit of the window's coordinates covers horizontally.
     *
     * @return the ratio, 1 when the window has no width
     */
    public double framebufferRatioX() {
        return this.windowWidth > 0 ? (double) this.framebufferWidth / this.windowWidth : 1.0;
    }

    /**
     * Returns how many framebuffer pixels one unit of the window's coordinates covers vertically.
     *
     * @return the ratio, 1 when the window has no height
     */
    public double framebufferRatioY() {
        return this.windowHeight > 0 ? (double) this.framebufferHeight / this.windowHeight : 1.0;
    }

    /**
     * Returns the content scale to draw with.
     *
     * @return the display's scale, or 1 when it reports none
     */
    public float scale() {
        return this.contentScale > 0f ? this.contentScale : 1f;
    }
}

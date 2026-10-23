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

import org.inspirenxe.strata.api.theme.Theme;

/**
 * Gives the GLFW bridge's tests, in another package, a view on the test device that renders offscreen.
 */
public final class GlfwTestSupport {

    private GlfwTestSupport() {
    }

    /**
     * Opens a view on the test device, skipping the calling test when there is none.
     *
     * @param theme the theme
     * @return the view
     */
    public static View open(final Theme theme) {
        final TestDevice device = TestDevice.get();
        final VulkanRenderer renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily,
                OffscreenImage.TARGET, 1);
        final StrataVulkan strata = new StrataVulkan(new VulkanPlatform(TestFonts.metrics()), renderer, theme, 1f);
        strata.setBounds(0, 0, 256, 128);
        return new View(renderer, strata, new OffscreenImage(device, renderer, 256, 128));
    }

    /**
     * A view, its renderer and the image it draws into.
     */
    public static final class View implements AutoCloseable {
        private final VulkanRenderer renderer;
        private final StrataVulkan strata;
        private final OffscreenImage image;

        View(final VulkanRenderer renderer, final StrataVulkan strata, final OffscreenImage image) {
            this.renderer = renderer;
            this.strata = strata;
            this.image = image;
        }

        /**
         * Returns the view.
         *
         * @return the view
         */
        public StrataVulkan strata() {
            return this.strata;
        }

        /**
         * Renders a frame of the view's bounds into the image.
         */
        public void render() {
            this.strata.setBounds(0, 0, 256, 128);
            this.image.render(this.strata::render);
        }

        @Override
        public void close() {
            this.image.close();
            this.strata.close();
            this.renderer.close();
        }
    }
}

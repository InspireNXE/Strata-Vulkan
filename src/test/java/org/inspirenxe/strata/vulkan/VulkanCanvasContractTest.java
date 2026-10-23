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

import org.inspirenxe.strata.api.text.TextMetrics;
import org.inspirenxe.strata.spi.Canvas;
import org.inspirenxe.strata.spi.testing.CanvasContract;
import org.junit.jupiter.api.AfterAll;

/**
 * Runs the SPI's canvas contract on the Vulkan canvas, drawing into an image on a real device and reading it back, with
 * the shape and pixel buffer checks on.
 */
class VulkanCanvasContractTest extends CanvasContract {
    private static VulkanRenderer renderer;

    @AfterAll
    static void closeRenderer() {
        if (renderer != null) {
            TestDevice.get().idle();
            renderer.close();
            renderer = null;
        }
    }

    static VulkanRenderer renderer(final TestDevice device) {
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        return renderer;
    }

    @Override
    protected Surface surface(final int width, final int height) {
        final TestDevice device = TestDevice.get();
        final VulkanRenderer renderer = VulkanCanvasContractTest.renderer(device);
        final OffscreenImage image = new OffscreenImage(device, renderer, width, height);
        final FrameStream frames = renderer.openStream();
        final VulkanCanvas canvas = frames.begin(0, 0, width, height, 1f);
        canvas.configure(TestFonts.metrics(), new VulkanSprites(VulkanCanvasContractTest.class.getClassLoader()), null);
        return new Surface() {
            @Override
            public Canvas canvas() {
                return canvas;
            }

            @Override
            public void finish() {
                image.render(frames::end);
                frames.close();
                image.close();
            }

            @Override
            public boolean readable() {
                return true;
            }

            @Override
            public int argb(final int x, final int y) {
                return image.argb(x, y);
            }
        };
    }

    @Override
    protected TextMetrics metrics() {
        return TestFonts.metrics();
    }

    @Override
    protected boolean drawsShapes() {
        return true;
    }

    @Override
    protected boolean drawsPixels() {
        return true;
    }
}

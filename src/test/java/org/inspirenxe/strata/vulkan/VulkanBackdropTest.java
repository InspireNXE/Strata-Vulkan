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

import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * Frosted glass over an application's backdrop image, drawn on the device.
 */
class VulkanBackdropTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static VulkanRenderer renderer;

    @AfterAll
    static void closeRenderer() {
        if (renderer != null) {
            TestDevice.get().idle();
            renderer.close();
            renderer = null;
        }
        ImageEncodingTest.closeRenderers();
    }

    /**
     * Draws with a backdrop whose left half is red and right half blue, 40 by 40.
     */
    private OffscreenImage draw(final boolean withBackdrop, final Consumer<VulkanCanvas> draw) {
        final TestDevice device = TestDevice.get();
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        final PixelBuffer halves = PixelBuffer.of(40, 40);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                halves.set(x, y, x < 20 ? RED : BLUE);
            }
        }
        halves.changed();
        final VulkanImage backdrop = renderer.image(renderer.pixels(halves).view, 40, 40);
        final OffscreenImage image = new OffscreenImage(device, renderer, 40, 40);
        final FrameStream frames = renderer.openStream();
        final VulkanCanvas canvas = frames.begin(0, 0, 40, 40, 1f);
        canvas.configure(null, null, null);
        canvas.setBackdrop(withBackdrop ? backdrop : null);
        draw.accept(canvas);
        image.render(frames::end);
        frames.close();
        backdrop.close();
        return image;
    }

    private static int red(final int argb) {
        return argb >> 16 & 0xFF;
    }

    private static int blue(final int argb) {
        return argb & 0xFF;
    }

    @Test
    void blursTheBackdropUnderTheRectangle() {
        final OffscreenImage image = this.draw(true, canvas -> canvas.backdrop(new Rect(0, 0, 40, 40), 0f, 8f,
                0x00000000));
        final int edge = image.argb(19, 20);
        assertTrue(red(edge) > 40 && blue(edge) > 40, "the edge between the halves blends: " + Integer.toHexString(edge));
        final int left = image.argb(2, 20);
        assertTrue(red(left) > 200 && blue(left) < 30, "far from the edge stays red: " + Integer.toHexString(left));
        assertEquals(0xFF, image.argb(20, 20) >>> 24, "the glass is opaque");
        image.close();
    }

    @Test
    void cutsTheGlassToItsCornersAndTintsIt() {
        final OffscreenImage image = this.draw(true, canvas -> canvas.backdrop(new Rect(0, 0, 40, 40), 12f, 8f,
                0x80FFFFFF));
        assertEquals(0, image.argb(0, 0) >>> 24, "outside the rounded corner");
        final int tinted = image.argb(2, 20);
        assertTrue((tinted >> 8 & 0xFF) > 90, "half white over red lifts green: " + Integer.toHexString(tinted));
        image.close();
    }

    @Test
    void withoutABackdropOnlyTheTintIsDrawn() {
        final OffscreenImage image = this.draw(false, canvas -> canvas.backdrop(new Rect(0, 0, 40, 40), 0f, 8f,
                0xFF00FF00));
        assertEquals(0xFF00FF00, image.argb(20, 20));
        image.close();
    }

    @Test
    void drawsGlassOverAScene() {
        final TestDevice device = TestDevice.get();
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        final int width = 240;
        final int height = 140;
        final PixelBuffer scene = PixelBuffer.of(width, height).fill(0xFF1B2A41);
        final int[] colors = {0xFFE05050, 0xFF4C86E0, 0xFF43A047, 0xFFE0A030};
        for (int i = 0; i < 12; i++) {
            final int cx = i * 53 % width;
            final int cy = i * 37 % height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < 400) {
                        scene.set(x, y, colors[i % colors.length]);
                    }
                }
            }
        }
        scene.changed();
        final VulkanImage backdrop = renderer.image(renderer.pixels(scene).view, width, height);
        final OffscreenImage image = new OffscreenImage(device, renderer, width, height);
        final FrameStream frames = renderer.openStream();
        final VulkanCanvas canvas = frames.begin(0, 0, width, height, 1f);
        canvas.configure(null, null, null);
        canvas.setBackdrop(backdrop);
        // The scene as the application draws it, then a pane of glass over its middle
        canvas.visual(backdrop, new Rect(0, 0, width, height), 0xFFFFFFFF);
        canvas.backdrop(new Rect(40, 30, 160, 80), 10f, 10f, 0x60181A1F);
        image.render(frames::end);
        frames.close();
        Snapshots.save(image, java.nio.file.Path.of("build", "snapshots", "backdrop.png"), width, height);
        final int sharp = image.argb(30, 30);
        final int glass = image.argb(120, 70);
        assertTrue(glass != sharp, "the glass changes what is under it");
        assertEquals(0xFF, glass >>> 24);
        image.close();
        backdrop.close();
    }

    /**
     * Blurs a grey image of the given format behind clear glass on an sRGB target, and returns the pixel at the
     * glass's center as stored in the target.
     */
    private int glassOver(final int imageFormat, final ImageEncoding encoding) {
        final TestDevice device = TestDevice.get();
        final VulkanRenderer srgb = ImageEncodingTest.renderer(VK_FORMAT_R8G8B8A8_SRGB);
        final int size = ImageEncodingTest.SIZE;
        final Texture texture = ImageEncodingTest.grey(srgb, imageFormat);
        final VulkanImage backdrop = srgb.image(texture.view, encoding, size, size);
        final OffscreenImage image = new OffscreenImage(device, srgb, size, size, VK_FORMAT_R8G8B8A8_SRGB);
        final FrameStream frames = srgb.openStream();
        final VulkanCanvas canvas = frames.begin(0, 0, size, size, 1f);
        canvas.configure(null, null, null);
        canvas.setBackdrop(backdrop);
        canvas.backdrop(new Rect(0, 0, size, size), 0f, 8f, 0x00000000);
        image.render(frames::end);
        final int center = image.argb(size / 2, size / 2);
        frames.close();
        backdrop.close();
        device.idle();
        image.close();
        texture.destroy(srgb);
        return center;
    }

    @Test
    void glassIsAsBrightAsTheSceneOfEitherEncoding() {
        ImageEncodingTest.assertChannels(0x80, this.glassOver(VK_FORMAT_R8G8B8A8_UNORM, ImageEncoding.ENCODED),
                "over a _UNORM image of sRGB bytes");
        ImageEncodingTest.assertChannels(0x80, this.glassOver(VK_FORMAT_R8G8B8A8_SRGB, ImageEncoding.LINEAR),
                "over a _SRGB image");
        // Taken as encoded, the _SRGB image's samples are decoded a second time
        ImageEncodingTest.assertChannels(ImageEncodingTest.TWICE_DECODED,
                this.glassOver(VK_FORMAT_R8G8B8A8_SRGB, ImageEncoding.ENCODED), "over a _SRGB image taken as encoded");
    }

    @Test
    void thePlatformSaysItBlurs() {
        assertTrue(VulkanPlatform.builder(VulkanTextMetrics.defaults()).build().capabilities().blursBackdrop());
    }
}

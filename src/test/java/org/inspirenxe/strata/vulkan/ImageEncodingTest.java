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

import org.inspirenxe.strata.api.theme.Themes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * Application images of either encoding, shown through an image control on targets that encode to sRGB and on ones
 * that do not.
 */
class ImageEncodingTest {
    /** Mid grey as stored, sRGB 0x80, which is 0.216 in linear light. */
    static final int GREY = 0xFF808080;
    /** What mid grey comes out as when it is decoded twice. */
    static final int TWICE_DECODED = 0x37;
    static final int SIZE = 40;
    private static VulkanRenderer srgbRenderer;
    private static VulkanRenderer unormRenderer;

    @AfterAll
    static void closeRenderers() {
        if (srgbRenderer != null || unormRenderer != null) {
            TestDevice.get().idle();
        }
        if (srgbRenderer != null) {
            srgbRenderer.close();
            srgbRenderer = null;
        }
        if (unormRenderer != null) {
            unormRenderer.close();
            unormRenderer = null;
        }
    }

    /**
     * Returns a renderer for the given target format, made once for the class.
     */
    static VulkanRenderer renderer(final int targetFormat) {
        final TestDevice device = TestDevice.get();
        if (targetFormat == VK_FORMAT_R8G8B8A8_SRGB) {
            if (srgbRenderer == null) {
                srgbRenderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily,
                        VulkanTarget.dynamic(targetFormat), 1);
            }
            return srgbRenderer;
        }
        if (unormRenderer == null) {
            unormRenderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily,
                    VulkanTarget.dynamic(targetFormat), 1);
        }
        return unormRenderer;
    }

    /**
     * Makes an image of the given format that the application filled with mid grey, uploaded with the next frame.
     * Destroy it after the device is idle.
     */
    static Texture grey(final VulkanRenderer renderer, final int format) {
        final Texture texture = Texture.create(renderer, SIZE, SIZE, format, 4, renderer.nearestSampler());
        final int[] pixels = new int[SIZE * SIZE];
        Arrays.fill(pixels, GREY);
        renderer.queueUpload(texture, 0, 0, SIZE, SIZE, pixels, 0, SIZE);
        return texture;
    }

    static void assertChannels(final int expected, final int argb, final String what) {
        for (int shift = 0; shift <= 16; shift += 8) {
            final int channel = argb >> shift & 0xFF;
            assertTrue(Math.abs(channel - expected) <= 2, what + ": " + Integer.toHexString(argb));
        }
    }

    /**
     * Shows a grey image of the given format through an image control on a target of the given format, and returns
     * the pixel at its center as stored in the target.
     *
     * @param encoding the encoding to make the visual with, or {@code null} for the form without one
     */
    private int shown(final int targetFormat, final int imageFormat, final ImageEncoding encoding) {
        final TestDevice device = TestDevice.get();
        final VulkanRenderer renderer = ImageEncodingTest.renderer(targetFormat);
        final Texture texture = ImageEncodingTest.grey(renderer, imageFormat);
        final VulkanImage visual = encoding == null ? renderer.image(texture.view, SIZE, SIZE)
                : renderer.image(texture.view, encoding, SIZE, SIZE);
        final StrataVulkan strata = new StrataVulkan(new VulkanPlatform(TestFonts.metrics()), renderer,
                Themes.flat(), 1f);
        strata.setBounds(0, 0, SIZE, SIZE);
        strata.screens().push(factory -> factory.absolutePanel().add(factory.image(visual), 0, 0));
        final OffscreenImage image = new OffscreenImage(device, renderer, SIZE, SIZE, targetFormat);
        image.render(strata::render);
        final int center = image.argb(SIZE / 2, SIZE / 2);
        device.idle();
        image.close();
        strata.close();
        visual.close();
        device.idle();
        texture.destroy(renderer);
        return center;
    }

    @Test
    void anSrgbImageShowsAsBrightAsAnEncodedOneOnAnSrgbTarget() {
        assertChannels(0x80, this.shown(VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM, ImageEncoding.ENCODED),
                "a _UNORM image of sRGB bytes");
        assertChannels(0x80, this.shown(VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_SRGB, ImageEncoding.LINEAR),
                "a _SRGB image");
    }

    @Test
    void linearSamplesAreEncodedForATargetThatDoesNotEncode() {
        assertChannels(0x80, this.shown(VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB, ImageEncoding.LINEAR),
                "a _SRGB image on a _UNORM target");
        assertChannels(0x80, this.shown(VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM, ImageEncoding.ENCODED),
                "a _UNORM image on a _UNORM target");
    }

    @Test
    void theFormsWithoutAnEncodingTakeSamplesAsEncoded() {
        // A _SRGB image given as encoded is decoded twice, as it was before images could say what they hold
        assertChannels(TWICE_DECODED, this.shown(VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_SRGB, null),
                "a _SRGB image taken as encoded");
        final VulkanRenderer renderer = ImageEncodingTest.renderer(VK_FORMAT_R8G8B8A8_SRGB);
        final Texture texture = ImageEncodingTest.grey(renderer, VK_FORMAT_R8G8B8A8_UNORM);
        try (VulkanImage plain = renderer.image(texture.view, SIZE, SIZE);
             VulkanImage sampled = renderer.image(texture.view, renderer.linearSampler(), SIZE, SIZE)) {
            assertEquals(ImageEncoding.ENCODED, plain.encoding());
            assertEquals(ImageEncoding.ENCODED, sampled.encoding());
        }
        // Never drawn, so its upload is dropped
        texture.retired = true;
        TestDevice.get().idle();
        texture.destroy(renderer);
    }

    @Test
    void formatsNameTheirUsualEncoding() {
        assertEquals(ImageEncoding.LINEAR, ImageEncoding.forFormat(VK_FORMAT_B8G8R8A8_SRGB));
        assertEquals(ImageEncoding.LINEAR, ImageEncoding.forFormat(VK_FORMAT_R8G8B8A8_SRGB));
        assertEquals(ImageEncoding.LINEAR, ImageEncoding.forFormat(VK_FORMAT_R16G16B16A16_SFLOAT));
        assertEquals(ImageEncoding.ENCODED, ImageEncoding.forFormat(VK_FORMAT_B8G8R8A8_UNORM));
        assertEquals(ImageEncoding.ENCODED, ImageEncoding.forFormat(VK_FORMAT_R8G8B8A8_UNORM));
    }
}

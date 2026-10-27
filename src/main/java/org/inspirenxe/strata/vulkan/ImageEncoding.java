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

import static org.lwjgl.vulkan.VK10.VK_FORMAT_A8B8G8R8_SRGB_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_E5B9G9R9_UFLOAT_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_SRGB;

/**
 * What the shader reads when it samples an image of the application's, so Strata can bring the colors into the space
 * its target blends in: linear light when the target encodes to sRGB, sRGB-encoded values otherwise. The format
 * decides it, along with what the application wrote: a {@code _SRGB} format holds encoded bytes but the device
 * decodes them as it samples, so the shader reads linear values, while a {@code _UNORM} format gives back the bytes
 * as they are stored. Strata's own textures, the ones it makes for sprites, pixel buffers and glyphs, are
 * {@code _UNORM} images holding sRGB bytes, so they are always {@link #ENCODED}.
 */
public enum ImageEncoding {
    /**
     * Samples are sRGB-encoded, as from a {@code _UNORM} format holding sRGB bytes, the usual way 8-bit images are
     * stored. Strata converts them to linear when its target encodes to sRGB, and uses them as they are otherwise.
     * This is what {@link VulkanRenderer#image(long, float, float)} and
     * {@link VulkanRenderer#image(long, long, float, float)} assume.
     */
    ENCODED,
    /**
     * Samples are linear, as from a {@code _SRGB} format, which the device decodes as it samples, or from a
     * floating-point format holding linear light, such as a scene before tone mapping. Strata uses them as they are
     * when its target encodes to sRGB, and encodes them to sRGB otherwise.
     */
    LINEAR;

    /**
     * Returns the encoding a view of the given format usually has. The 8-bit {@code _SRGB} color formats and the
     * floating-point color formats ({@code R16G16B16A16_SFLOAT}, {@code R16G16B16_SFLOAT},
     * {@code R32G32B32A32_SFLOAT}, {@code R32G32B32_SFLOAT}, {@code B10G11R11_UFLOAT_PACK32} and
     * {@code E5B9G9R9_UFLOAT_PACK32}) are {@link #LINEAR}; every other format is {@link #ENCODED}. Name the encoding
     * yourself for an image that breaks the rule, such as a {@code _UNORM} image holding linear values or a
     * compressed {@code _SRGB} one.
     *
     * @param format the image view's format, a {@code VK_FORMAT_*} value
     * @return the encoding
     */
    public static ImageEncoding forFormat(final int format) {
        return switch (format) {
            case VK_FORMAT_R8_SRGB, VK_FORMAT_R8G8_SRGB, VK_FORMAT_R8G8B8_SRGB, VK_FORMAT_B8G8R8_SRGB,
                 VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_A8B8G8R8_SRGB_PACK32,
                 VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT,
                 VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_B10G11R11_UFLOAT_PACK32, VK_FORMAT_E5B9G9R9_UFLOAT_PACK32 ->
                    ImageEncoding.LINEAR;
            default -> ImageEncoding.ENCODED;
        };
    }
}

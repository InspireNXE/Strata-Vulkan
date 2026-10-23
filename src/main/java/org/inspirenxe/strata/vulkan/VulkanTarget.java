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
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

/**
 * What Strata draws into: the attachments of the render pass, or of the dynamic rendering, that the application has
 * begun when it calls {@link StrataVulkan#render}. A {@link VulkanRenderer} builds its pipeline for one target, and
 * the pipeline must be compatible with the pass it draws in, as Vulkan's rules for pipelines and passes say.
 *
 * <p>Strata draws to color attachment 0 only, without depth or stencil tests, blending premultiplied colors over what
 * is there. When the target's color format encodes to sRGB, Strata converts its colors to linear before blending, so
 * they come out as given.</p>
 *
 * @param colorFormat the format of color attachment 0 under dynamic rendering, a {@code VK_FORMAT_*} value; ignored
 *                    for a render pass
 * @param depthFormat the depth attachment's format under dynamic rendering, or {@code VK_FORMAT_UNDEFINED} for none
 * @param stencilFormat the stencil attachment's format under dynamic rendering, or {@code VK_FORMAT_UNDEFINED} for
 *                      none
 * @param renderPass the render pass handle, or {@code VK_NULL_HANDLE} for dynamic rendering
 * @param subpass the subpass Strata draws in, for a render pass; 0 under dynamic rendering
 * @param samples the attachments' sample count, a {@code VK_SAMPLE_COUNT_*} value
 * @param srgb true when the color attachment encodes to sRGB
 */
public record VulkanTarget(int colorFormat, int depthFormat, int stencilFormat, long renderPass, int subpass,
                           int samples, boolean srgb) {

    /**
     * Checks the arguments.
     *
     * @throws IllegalArgumentException if a render pass and a color format are both missing, the subpass is negative,
     *                                  or the sample count is not a power of two from 1 to 64
     */
    public VulkanTarget {
        if (renderPass == VK_NULL_HANDLE && colorFormat == VK_FORMAT_UNDEFINED) {
            throw new IllegalArgumentException("Dynamic rendering needs the color attachment's format");
        }
        if (subpass < 0) {
            throw new IllegalArgumentException("Subpass must not be negative: " + subpass);
        }
        if (samples < 1 || samples > 64 || Integer.bitCount(samples) != 1) {
            throw new IllegalArgumentException("Sample count must be a power of two from 1 to 64: " + samples);
        }
    }

    /**
     * Returns a target for dynamic rendering with one color attachment and no depth or stencil attachment. The device
     * must have dynamic rendering enabled: Vulkan 1.3's {@code dynamicRendering} feature or
     * {@code VK_KHR_dynamic_rendering}. Whether the format encodes to sRGB is read from the format.
     *
     * @param colorFormat the color attachment's format
     * @return the target, single sampled
     * @throws IllegalArgumentException if the format is {@code VK_FORMAT_UNDEFINED}
     */
    public static VulkanTarget dynamic(final int colorFormat) {
        return VulkanTarget.dynamic(colorFormat, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED);
    }

    /**
     * Returns a target for dynamic rendering whose pass also has depth or stencil attachments, which Strata leaves
     * alone but its pipeline must name.
     *
     * @param colorFormat the color attachment's format
     * @param depthFormat the depth attachment's format, or {@code VK_FORMAT_UNDEFINED}
     * @param stencilFormat the stencil attachment's format, or {@code VK_FORMAT_UNDEFINED}
     * @return the target, single sampled
     * @throws IllegalArgumentException if the color format is {@code VK_FORMAT_UNDEFINED}
     */
    public static VulkanTarget dynamic(final int colorFormat, final int depthFormat, final int stencilFormat) {
        return new VulkanTarget(colorFormat, depthFormat, stencilFormat, VK_NULL_HANDLE, 0, 1,
                VulkanTarget.isSrgb(colorFormat));
    }

    /**
     * Returns a target for a subpass of a render pass. Vulkan does not tell the pipeline the attachment's format, so
     * say whether it encodes to sRGB.
     *
     * @param renderPass the render pass, or one compatible with it
     * @param subpass the subpass Strata draws in
     * @param srgb true when color attachment 0 of the subpass encodes to sRGB
     * @return the target, single sampled
     * @throws IllegalArgumentException if the render pass is {@code VK_NULL_HANDLE} or the subpass is negative
     */
    public static VulkanTarget renderPass(final long renderPass, final int subpass, final boolean srgb) {
        if (renderPass == VK_NULL_HANDLE) {
            throw new IllegalArgumentException("A render pass target needs a render pass");
        }
        return new VulkanTarget(VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, VK_FORMAT_UNDEFINED, renderPass, subpass, 1,
                srgb);
    }

    /**
     * Returns this target with another sample count, for multisampled attachments.
     *
     * @param samples the sample count, a {@code VK_SAMPLE_COUNT_*} value
     * @return the target
     * @throws IllegalArgumentException if the count is not a power of two from 1 to 64
     */
    public VulkanTarget withSamples(final int samples) {
        return new VulkanTarget(this.colorFormat, this.depthFormat, this.stencilFormat, this.renderPass, this.subpass,
                samples, this.srgb);
    }

    /**
     * Returns whether this target uses dynamic rendering rather than a render pass.
     *
     * @return true for dynamic rendering
     */
    public boolean isDynamic() {
        return this.renderPass == VK_NULL_HANDLE;
    }

    /**
     * Returns whether a color format of 8 bits per channel encodes to sRGB.
     *
     * @param format a {@code VK_FORMAT_*} value
     * @return true for the sRGB formats
     */
    static boolean isSrgb(final int format) {
        return switch (format) {
            case VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_A8B8G8R8_SRGB_PACK32, VK_FORMAT_B8G8R8_SRGB,
                 VK_FORMAT_R8G8B8_SRGB -> true;
            default -> false;
        };
    }
}

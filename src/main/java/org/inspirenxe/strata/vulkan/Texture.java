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

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/**
 * An image the renderer samples, with its view and descriptor set. It is filled through the renderer's uploads; until
 * its first upload has been recorded its layout is undefined.
 */
final class Texture {
    private static final AtomicLong IDS = new AtomicLong();

    final long image;
    final long allocation;
    final long view;
    final long set;
    final int width;
    final int height;
    final int bytesPerPixel;
    // The set that samples the texture with linear filtering, or 0 until one is drawn that way
    private long linearSet;
    /** True once an upload has moved the image to the shader read layout. */
    boolean ready;
    /** True once the texture waits to be destroyed, so queued uploads to it are dropped. */
    boolean retired;
    /** Counts the times its pixels were replaced after the first upload. */
    long version;
    private final long id;

    private Texture(final long image, final long allocation, final long view, final long set, final int width,
                    final int height, final int bytesPerPixel) {
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.set = set;
        this.width = width;
        this.height = height;
        this.bytesPerPixel = bytesPerPixel;
        this.id = IDS.incrementAndGet();
    }

    /**
     * Returns the descriptor set that samples this texture with nearest or linear filtering. The texture's own set
     * samples it the way it was made; the linear one is made the first time it is asked for.
     */
    long set(final VulkanRenderer renderer, final boolean linear) {
        if (!linear) {
            return this.set;
        }
        if (this.linearSet == 0L) {
            this.linearSet = renderer.descriptors().image(renderer.imageLayout(), this.view, renderer.linearSampler());
        }
        return this.linearSet;
    }

    /**
     * Returns a value that differs between textures, and changes when the {@link #version} does, for draws to tell
     * whether the pixels they sample changed since the last frame.
     *
     * @return the stamp
     */
    long stamp() {
        return this.id * 0x9E3779B97F4A7C15L + this.version;
    }

    /**
     * Creates a texture. Fill it with an upload covering the whole image before anything draws it.
     *
     * @param renderer the renderer that owns it
     * @param width the width in pixels
     * @param height the height in pixels
     * @param format the format: {@code VK_FORMAT_R8_UNORM} or {@code VK_FORMAT_B8G8R8A8_UNORM}
     * @param bytesPerPixel the format's size
     * @param sampler the sampler its set samples with
     * @return the texture
     */
    static Texture create(final VulkanRenderer renderer, final int width, final int height, final int format,
                          final int bytesPerPixel, final long sampler) {
        final VkDevice device = renderer.device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            final VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            final LongBuffer image = stack.mallocLong(1);
            final PointerBuffer handle = stack.mallocPointer(1);
            Vk.check(vmaCreateImage(renderer.allocator(), info, allocation, image, handle, null), "create an image");

            final VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image.get(0))
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            final LongBuffer view = stack.mallocLong(1);
            Vk.check(vkCreateImageView(device, viewInfo, null, view), "create an image view");

            final long set = renderer.descriptors().image(renderer.imageLayout(), view.get(0), sampler);
            return new Texture(image.get(0), handle.get(0), view.get(0), set, width, height, bytesPerPixel);
        }
    }

    /**
     * Destroys the texture. The device must be done with every command that uses it.
     *
     * @param renderer the renderer that owns it
     */
    void destroy(final VulkanRenderer renderer) {
        renderer.descriptors().free(this.set);
        if (this.linearSet != 0L) {
            renderer.descriptors().free(this.linearSet);
        }
        vkDestroyImageView(renderer.device(), this.view, null);
        vmaDestroyImage(renderer.allocator(), this.image, this.allocation);
    }
}

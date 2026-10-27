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
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;

/**
 * An image to draw into with dynamic rendering and read back, for snapshots and tests.
 */
final class OffscreenImage implements AutoCloseable {
    /** The format of every offscreen image, so a renderer for {@link #TARGET} draws into any of them. */
    static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    static final VulkanTarget TARGET = VulkanTarget.dynamic(FORMAT);

    private final Submitter submitter;
    private final long allocator;
    private final int width;
    private final int height;
    private final long image;
    private final long allocation;
    private final long view;
    private final GpuBuffer readBack;
    private final int[] pixels;

    OffscreenImage(final Submitter submitter, final VulkanRenderer renderer, final int width, final int height) {
        this(submitter, renderer, width, height, OffscreenImage.FORMAT);
    }

    /**
     * Makes an image of another 8-bit RGBA format, such as {@code VK_FORMAT_R8G8B8A8_SRGB}, for a renderer made for a
     * target of that format. Its pixels read back as they are stored.
     */
    OffscreenImage(final Submitter submitter, final VulkanRenderer renderer, final int width, final int height,
                   final int format) {
        this.submitter = submitter;
        this.allocator = renderer.allocator();
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            final VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            final LongBuffer image = stack.mallocLong(1);
            final PointerBuffer handle = stack.mallocPointer(1);
            Vk.check(vmaCreateImage(this.allocator, info, allocation, image, handle, null), "create an offscreen image");
            this.image = image.get(0);
            this.allocation = handle.get(0);
            final VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(this.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            final LongBuffer view = stack.mallocLong(1);
            Vk.check(vkCreateImageView(this.submitter.device(), viewInfo, null, view), "create an offscreen image view");
            this.view = view.get(0);
        }
        this.readBack = GpuBuffer.create(this.allocator, (long) width * height * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                true);
    }

    long view() {
        return this.view;
    }

    long image() {
        return this.image;
    }

    /**
     * Clears the image to transparent, records a frame between the start and end of dynamic rendering, then copies
     * the image to the host and waits for it.
     *
     * @param inside what to record inside the rendering
     */
    void render(final Consumer<VkCommandBuffer> inside) {
        this.render(0, inside);
    }

    /**
     * Clears the image to a color, records a frame between the start and end of dynamic rendering, then copies the
     * image to the host and waits for it.
     *
     * @param clearArgb the color the image starts with
     * @param inside what to record inside the rendering
     */
    void render(final int clearArgb, final Consumer<VkCommandBuffer> inside) {
        final float alpha = (clearArgb >>> 24) / 255f;
        this.submitter.submit(commands -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                this.barrier(commands, stack, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                final VkClearValue clear = VkClearValue.calloc(stack);
                // Premultiplied, as Strata blends
                clear.color().float32(0, ((clearArgb >> 16) & 0xFF) / 255f * alpha)
                        .float32(1, ((clearArgb >> 8) & 0xFF) / 255f * alpha)
                        .float32(2, (clearArgb & 0xFF) / 255f * alpha)
                        .float32(3, alpha);
                final VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                        .imageView(this.view)
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .clearValue(clear);
                final VkRenderingInfo rendering = VkRenderingInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                        .layerCount(1)
                        .pColorAttachments(color);
                rendering.renderArea().extent().set(this.width, this.height);
                final boolean core = this.submitter.device().getCapabilities().Vulkan13;
                if (core) {
                    VK13.vkCmdBeginRendering(commands, rendering);
                } else {
                    KHRDynamicRendering.vkCmdBeginRenderingKHR(commands, rendering);
                }
                inside.accept(commands);
                if (core) {
                    VK13.vkCmdEndRendering(commands);
                } else {
                    KHRDynamicRendering.vkCmdEndRenderingKHR(commands);
                }
                this.copyOut(commands, stack);
            }
        });
        this.read();
    }

    /**
     * Copies the image, already drawn and in the color attachment layout, to the host buffer.
     */
    void copyOut(final VkCommandBuffer commands, final MemoryStack stack) {
        this.barrier(commands, stack, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.imageExtent().set(this.width, this.height, 1);
        vkCmdCopyImageToBuffer(commands, this.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, this.readBack.buffer,
                region);
    }

    /**
     * Reads what {@link #copyOut} copied, turning premultiplied RGBA into ARGB.
     */
    void read() {
        this.readBack.invalidate(this.allocator);
        final ByteBuffer bytes = MemoryUtil.memByteBuffer(this.readBack.address, this.pixels.length * 4);
        for (int i = 0; i < this.pixels.length; i++) {
            final int r = bytes.get(i * 4) & 0xFF;
            final int g = bytes.get(i * 4 + 1) & 0xFF;
            final int b = bytes.get(i * 4 + 2) & 0xFF;
            final int a = bytes.get(i * 4 + 3) & 0xFF;
            this.pixels[i] = a == 0 ? 0 : (a << 24) | (OffscreenImage.straight(r, a) << 16) | (OffscreenImage.straight(g, a) << 8)
                    | OffscreenImage.straight(b, a);
        }
    }

    /**
     * Returns a pixel of the last frame.
     *
     * @return the pixel as ARGB, alpha not premultiplied
     */
    int argb(final int x, final int y) {
        return this.pixels[y * this.width + x];
    }

    /**
     * Returns the last frame's pixels.
     *
     * @return a copy, ARGB with alpha not premultiplied, row by row from the top
     */
    int[] pixels() {
        return this.pixels.clone();
    }

    @Override
    public void close() {
        this.submitter.idle();
        this.readBack.destroy(this.allocator);
        vkDestroyImageView(this.submitter.device(), this.view, null);
        vmaDestroyImage(this.allocator, this.image, this.allocation);
    }

    private void barrier(final VkCommandBuffer commands, final MemoryStack stack, final int from, final int to,
                         final int sourceAccess, final int destinationAccess, final int sourceStage,
                         final int destinationStage) {
        final VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(from)
                .newLayout(to)
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(this.image);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0, null, null, barrier);
    }

    private static int straight(final int channel, final int alpha) {
        return Math.min(255, (channel * 255 + alpha / 2) / alpha);
    }
}

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

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkFreeCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;

/**
 * The frames of one view: a canvas that records them, and for each frame in flight a slot with the vertex, coverage
 * and staging buffers and the upload command buffer that frame uses. A frame begins with {@link #begin}, which hands
 * out the canvas. {@link #end} then uploads, copies the frame to its slot and records every draw into the
 * application's command buffer. A view that redraws only what changed calls {@link #damage()} instead, then
 * {@link #commit()} and {@link #record} when something did, or {@link #abandon()} when nothing did, so the frame
 * takes no slot and the device sees nothing of it.
 */
final class FrameStream implements AutoCloseable {
    /** The push constants' size: the viewport's width and height, and the flags. */
    static final int PUSH_CONSTANTS = 12;
    private static final long NO_WAIT_LIMIT = Long.MAX_VALUE;

    private final VulkanRenderer renderer;
    private final DrawList draws;
    private final VulkanCanvas canvas;
    private final Slot[] slots;
    private final Damage damage;
    private int slot = -1;
    private long frames;
    private boolean building;
    private boolean closed;
    private int originX;
    private int originY;
    private int width;
    private int height;

    FrameStream(final VulkanRenderer renderer) {
        this.renderer = renderer;
        this.draws = new DrawList();
        this.canvas = new VulkanCanvas(renderer, this.draws);
        this.damage = new Damage();
        this.slots = new Slot[renderer.framesInFlight()];
        for (int i = 0; i < this.slots.length; i++) {
            this.slots[i] = new Slot(renderer);
        }
    }

    /**
     * Begins a frame.
     *
     * @param x the viewport's left edge in framebuffer pixels
     * @param y the viewport's top edge in framebuffer pixels
     * @param width the viewport's width in framebuffer pixels
     * @param height the viewport's height in framebuffer pixels
     * @param pixelsPerUnit framebuffer pixels per UI unit
     * @return the canvas to draw the frame with
     */
    VulkanCanvas begin(final int x, final int y, final int width, final int height, final float pixelsPerUnit) {
        if (this.closed) {
            throw new IllegalStateException("The view is closed");
        }
        this.building = true;
        this.originX = x;
        this.originY = y;
        this.width = width;
        this.height = height;
        this.draws.reset();
        this.canvas.begin(width, height, pixelsPerUnit);
        return this.canvas;
    }

    /**
     * Ends the frame: submits the uploads it needs, then records all its draws into the application's command buffer,
     * which must be inside a pass of the renderer's target. The next {@link #damage()} covers the whole view.
     *
     * @param commands the command buffer
     */
    void end(final VkCommandBuffer commands) {
        this.canvas.end();
        this.damage.invalidate();
        this.commit();
        this.record(commands, false);
    }

    /**
     * Compares the frame's draws with the last compared frame's.
     *
     * @return how many damage rectangles the frame has; 0 when it draws what the last one did
     */
    int damage() {
        this.canvas.end();
        return this.damage.compare(this.draws, this.width, this.height);
    }

    /**
     * Returns part of a damage rectangle of the last {@link #damage()}, in framebuffer pixels.
     *
     * @param rect the rectangle
     * @param field 0 to 3 for its left, top, right and bottom edges
     * @return the edge
     */
    int damageRect(final int rect, final int field) {
        return this.damage.rect(rect, field) + (field % 2 == 0 ? this.originX : this.originY);
    }

    /**
     * Makes the next {@link #damage()} cover the whole view.
     */
    void invalidate() {
        this.damage.invalidate();
    }

    /**
     * Drops a frame that changed nothing: it takes no slot, and its uploads wait for the next frame.
     */
    void abandon() {
        this.building = false;
    }

    /**
     * Gives the frame a slot: waits for the slot's last uploads, lets go of what the device is done with, submits the
     * frame's uploads and copies its draws to the slot.
     */
    void commit() {
        this.building = false;
        this.frames++;
        this.slot = (this.slot + 1) % this.slots.length;
        final Slot slot = this.slots[this.slot];
        slot.await(this.renderer);
        this.renderer.beginFrame();
        this.renderer.flushUploads(slot);
        if (this.draws.vertexCount() > 0) {
            slot.write(this.renderer, this.draws);
        }
    }

    /**
     * Records the committed frame's draws into the application's command buffer, which must be inside a pass of the
     * renderer's target.
     *
     * @param commands the command buffer
     * @param damaged true to draw only inside the last {@link #damage()}'s rectangles
     */
    void record(final VkCommandBuffer commands, final boolean damaged) {
        final Slot slot = this.slots[this.slot];
        final int vertices = this.draws.vertexCount();
        if (vertices == 0 || this.width <= 0 || this.height <= 0) {
            return;
        }
        final int rects = damaged ? this.damage.rectCount() : 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, this.renderer.pipeline());
            final VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(this.originX)
                    .y(this.originY)
                    .width(this.width)
                    .height(this.height)
                    .minDepth(0f)
                    .maxDepth(1f);
            vkCmdSetViewport(commands, 0, viewport);
            final ByteBuffer push = stack.malloc(PUSH_CONSTANTS);
            push.putFloat(0, this.width).putFloat(4, this.height)
                    .putInt(8, this.renderer.target().srgb() ? 1 : 0);
            vkCmdPushConstants(commands, this.renderer.pipelineLayout(),
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
            vkCmdBindVertexBuffers(commands, 0, stack.longs(slot.vertices.buffer), stack.longs(0L));
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, this.renderer.pipelineLayout(), 1,
                    stack.longs(slot.maskSet), null);
            final VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            long bound = 0L;
            // The damage rectangles do not overlap, so each pixel is drawn once
            for (int rect = 0; rect < rects; rect++) {
                final int limitX0 = damaged ? this.damageRect(rect, 0) : Integer.MIN_VALUE;
                final int limitY0 = damaged ? this.damageRect(rect, 1) : Integer.MIN_VALUE;
                final int limitX1 = damaged ? this.damageRect(rect, 2) : Integer.MAX_VALUE;
                final int limitY1 = damaged ? this.damageRect(rect, 3) : Integer.MAX_VALUE;
                for (int batch = 0; batch < this.draws.batchCount(); batch++) {
                    final int count = this.draws.batch(batch, 5);
                    if (count == 0) {
                        continue;
                    }
                    final int x0 = Math.max(Math.max(0, limitX0), this.originX + this.draws.batch(batch, 0));
                    final int y0 = Math.max(Math.max(0, limitY0), this.originY + this.draws.batch(batch, 1));
                    final int x1 = Math.min(limitX1, this.originX + this.draws.batch(batch, 0)
                            + this.draws.batch(batch, 2));
                    final int y1 = Math.min(limitY1, this.originY + this.draws.batch(batch, 1)
                            + this.draws.batch(batch, 3));
                    if (x1 <= x0 || y1 <= y0) {
                        continue;
                    }
                    scissor.offset().set(x0, y0);
                    scissor.extent().set(x1 - x0, y1 - y0);
                    vkCmdSetScissor(commands, 0, scissor);
                    final long set = this.draws.batchSet(batch);
                    if (set != bound) {
                        vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                this.renderer.pipelineLayout(), 0, stack.longs(set), null);
                        bound = set;
                    }
                    vkCmdDraw(commands, count, 1, this.draws.batch(batch, 4), 0);
                }
            }
        }
    }

    /**
     * Returns how many frames have been committed, counting a frame being drawn, which may still commit.
     */
    long frames() {
        return this.frames + (this.building ? 1 : 0);
    }

    /**
     * Returns how many frames have been committed.
     */
    long committed() {
        return this.frames;
    }

    boolean isClosed() {
        return this.closed;
    }

    VulkanCanvas canvas() {
        return this.canvas;
    }

    /**
     * Waits for this stream's uploads and frees its buffers. The application must have waited for the device to
     * finish the frames that used them.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (final Slot slot : this.slots) {
            slot.destroy(this.renderer);
        }
        this.draws.close();
        this.renderer.streamClosed(this);
    }

    /**
     * What one frame in flight uses on the device.
     */
    static final class Slot {
        GpuBuffer vertices;
        GpuBuffer masks;
        GpuBuffer staging;
        final long maskSet;
        private final VkCommandBuffer commands;
        private final long fence;
        private boolean pending;

        Slot(final VulkanRenderer renderer) {
            this.vertices = GpuBuffer.create(renderer.allocator(), 64L * DrawList.STRIDE,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, false);
            this.masks = GpuBuffer.create(renderer.allocator(), 4096L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
            this.maskSet = renderer.descriptors().allocate(renderer.masksLayout());
            renderer.descriptors().storage(this.maskSet, this.masks.buffer);
            this.commands = renderer.createCommandBuffer();
            this.fence = renderer.createFence();
        }

        /**
         * Waits until the device has finished this slot's last uploads.
         */
        void await(final VulkanRenderer renderer) {
            if (this.pending) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    Vk.check(vkWaitForFences(renderer.device(), stack.longs(this.fence), true, NO_WAIT_LIMIT),
                            "wait for uploads");
                }
                this.pending = false;
            }
        }

        VkCommandBuffer commands() {
            return this.commands;
        }

        long fence() {
            return this.fence;
        }

        void submitted() {
            this.pending = true;
        }

        /**
         * Returns a staging buffer of at least a size, replacing a smaller one.
         */
        GpuBuffer staging(final VulkanRenderer renderer, final long size) {
            if (this.staging == null || this.staging.size < size) {
                if (this.staging != null) {
                    this.staging.destroy(renderer.allocator());
                }
                this.staging = GpuBuffer.create(renderer.allocator(), Math.max(size, 64L * 1024L),
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false);
            }
            return this.staging;
        }

        /**
         * Copies a frame's vertices and coverage into this slot's buffers, growing them as needed.
         */
        void write(final VulkanRenderer renderer, final DrawList draws) {
            final long vertexBytes = (long) draws.vertexCount() * DrawList.STRIDE;
            if (this.vertices.size < vertexBytes) {
                this.vertices.destroy(renderer.allocator());
                this.vertices = GpuBuffer.create(renderer.allocator(), Math.max(vertexBytes, this.vertices.size * 2),
                        VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, false);
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(draws.vertices()), this.vertices.address, vertexBytes);
            this.vertices.flush(renderer.allocator(), vertexBytes);

            final long maskBytes = draws.maskBytes();
            if (this.masks.size < maskBytes) {
                this.masks.destroy(renderer.allocator());
                this.masks = GpuBuffer.create(renderer.allocator(), Math.max(maskBytes, this.masks.size * 2),
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
                renderer.descriptors().storage(this.maskSet, this.masks.buffer);
            }
            if (maskBytes > 0) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(draws.masks()), this.masks.address, maskBytes);
                this.masks.flush(renderer.allocator(), maskBytes);
            }
        }

        void destroy(final VulkanRenderer renderer) {
            this.await(renderer);
            this.vertices.destroy(renderer.allocator());
            this.masks.destroy(renderer.allocator());
            if (this.staging != null) {
                this.staging.destroy(renderer.allocator());
            }
            renderer.descriptors().free(this.maskSet);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkFreeCommandBuffers(renderer.device(), renderer.commandPool(), stack.pointers(this.commands));
            }
            vkDestroyFence(renderer.device(), this.fence, null);
        }
    }
}

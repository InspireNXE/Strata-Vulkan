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

import org.inspirenxe.strata.api.extension.Stroke;
import org.inspirenxe.strata.api.geometry.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;

import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_CONTENTS_INLINE;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_EXTERNAL;
import static org.lwjgl.vulkan.VK10.vkCmdBeginRenderPass;
import static org.lwjgl.vulkan.VK10.vkCmdEndRenderPass;
import static org.lwjgl.vulkan.VK10.vkCreateFramebuffer;
import static org.lwjgl.vulkan.VK10.vkCreateRenderPass;
import static org.lwjgl.vulkan.VK10.vkDestroyFramebuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyRenderPass;

/**
 * Draws through a pipeline made for a render pass instead of dynamic rendering, with a view placed inside the
 * framebuffer rather than covering it.
 */
class RenderPassTargetTest {
    private static final int SIZE = 64;

    @Test
    void drawsInsideASubpassAtTheViewsBounds() {
        final TestDevice device = TestDevice.get();
        final long renderPass = RenderPassTargetTest.renderPass(device);
        final VulkanRenderer renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily,
                VulkanTarget.renderPass(renderPass, 0, false), 1);
        final Offscreen image = new Offscreen(device, renderer, SIZE, SIZE);
        final long framebuffer = RenderPassTargetTest.framebuffer(device, renderPass, image.view());
        final FrameStream frames = renderer.openStream();
        try {
            // The view's top left sits at 16, 16, and one unit is two pixels
            final VulkanCanvas canvas = frames.begin(16, 16, 32, 32, 2f);
            canvas.configure(TestFonts.metrics(), null, null);
            canvas.fill(new Rect(0, 0, 8, 8), 0xFFFF0000);
            canvas.fillEllipse(new Rect(8, 8, 8, 8), 0xFF0000FF);
            canvas.line(0, 15, 15, 15, Stroke.of(1f), 0xFF00FF00);
            device.submit(commands -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    final VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
                    final VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                            .renderPass(renderPass)
                            .framebuffer(framebuffer)
                            .pClearValues(clear);
                    begin.renderArea().extent().set(SIZE, SIZE);
                    vkCmdBeginRenderPass(commands, begin, VK_SUBPASS_CONTENTS_INLINE);
                    frames.end(commands);
                    vkCmdEndRenderPass(commands);
                    image.copyOut(commands, stack);
                }
            });
            image.read();
            assertEquals(0xFFFF0000, image.argb(20, 20), "the fill, moved to the view's corner and scaled");
            assertEquals(0, image.argb(10, 10), "nothing outside the view");
            assertEquals(0xFF0000FF, image.argb(40, 40), "the ellipse's middle");
            assertNotEquals(0, image.argb(30, 46), "the line near the view's bottom");
            assertEquals(0, image.argb(30, 50), "and the view clips it");
        } finally {
            frames.close();
            image.close();
            vkDestroyFramebuffer(device.logical, framebuffer, null);
            renderer.close();
            vkDestroyRenderPass(device.logical, renderPass, null);
        }
    }

    private static long renderPass(final TestDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack)
                    .format(Offscreen.FORMAT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            final VkAttachmentReference.Buffer reference = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            final VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(reference);
            final VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            final VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachment)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);
            final LongBuffer pass = stack.mallocLong(1);
            Vk.check(vkCreateRenderPass(device.logical, info, null, pass), "create a render pass");
            return pass.get(0);
        }
    }

    private static long framebuffer(final TestDevice device, final long renderPass, final long view) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(view))
                    .width(SIZE)
                    .height(SIZE)
                    .layers(1);
            final LongBuffer framebuffer = stack.mallocLong(1);
            Vk.check(vkCreateFramebuffer(device.logical, info, null, framebuffer), "create a framebuffer");
            return framebuffer.get(0);
        }
    }
}

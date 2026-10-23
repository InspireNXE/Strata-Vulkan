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

import org.inspirenxe.strata.api.ComponentFactory;
import org.inspirenxe.strata.api.control.display.Label;
import org.inspirenxe.strata.api.control.display.ProgressBar;
import org.inspirenxe.strata.api.extension.CustomComponent;
import org.inspirenxe.strata.api.extension.MeasureContext;
import org.inspirenxe.strata.api.extension.Painter;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.layout.AbsolutePanel;
import org.inspirenxe.strata.api.text.Text;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.vkCmdClearAttachments;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;

/**
 * Redrawing only what changed (design 4.60), on a real device: what a frame's damage holds, and that frames drawn
 * over a kept image match a full redraw pixel for pixel.
 */
class DamageTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final float SCALE = 2f;
    private static final int BACKGROUND = 0xFF1E1F22;

    private TestDevice device;
    private VulkanRenderer renderer;
    private StrataVulkan strata;
    private OffscreenImage kept;
    private OffscreenImage full;

    @BeforeEach
    void open() {
        this.device = TestDevice.get();
        this.renderer = new VulkanRenderer(this.device.logical, this.device.queue, this.device.queueFamily,
                OffscreenImage.TARGET, 2);
        this.strata = new StrataVulkan(new VulkanPlatform(TestFonts.metrics()), this.renderer, Themes.flat(), SCALE);
        this.strata.setBounds(0, 0, WIDTH, HEIGHT);
        this.kept = new OffscreenImage(this.device, this.renderer, WIDTH, HEIGHT);
        this.full = new OffscreenImage(this.device, this.renderer, WIDTH, HEIGHT);
    }

    @AfterEach
    void close() {
        if (this.kept != null) {
            this.kept.close();
            this.full.close();
            this.strata.close();
            this.renderer.close();
        }
    }

    /**
     * A box of one color, written as a developer would.
     */
    static final class Box extends CustomComponent<Box> {
        private int argb;

        Box(final ComponentFactory factory, final int argb) {
            super(factory);
            this.argb = argb;
        }

        Box setColor(final int argb) {
            this.argb = argb;
            this.repaint();
            return this;
        }

        @Override
        protected Size measure(final MeasureContext context, final float availableWidth, final float availableHeight) {
            return new Size(40, 30);
        }

        @Override
        protected void paint(final Painter painter, final Rect bounds) {
            painter.fill(bounds, this.argb);
        }
    }

    /**
     * Prepares a frame and, when something changed, draws it over the kept image: the damage cleared to the
     * background, then the frame's draws.
     */
    private VulkanFrame frame() {
        final VulkanFrame frame = this.strata.prepare();
        if (!frame.isEmpty()) {
            this.redraw(frame);
        }
        return frame;
    }

    private void redraw(final VulkanFrame frame) {
        this.device.submit(commands -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(this.loaded ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : 0)
                        .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(this.kept.image());
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, barrier);
                final VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                        .imageView(this.kept.view())
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                final PixelRect area = frame.bounds();
                final VkRenderingInfo rendering = VkRenderingInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                        .layerCount(1)
                        .pColorAttachments(color);
                rendering.renderArea().offset().set(area.x(), area.y());
                rendering.renderArea().extent().set(area.width(), area.height());
                final boolean core = this.device.logical.getCapabilities().Vulkan13;
                if (core) {
                    VK13.vkCmdBeginRendering(commands, rendering);
                } else {
                    KHRDynamicRendering.vkCmdBeginRenderingKHR(commands, rendering);
                }
                this.clear(commands, stack, frame.damage());
                frame.record(commands);
                if (core) {
                    VK13.vkCmdEndRendering(commands);
                } else {
                    KHRDynamicRendering.vkCmdEndRenderingKHR(commands);
                }
                this.kept.copyOut(commands, stack);
            }
        });
        this.kept.read();
        this.loaded = true;
    }

    private boolean loaded;

    private void clear(final VkCommandBuffer commands, final MemoryStack stack, final List<PixelRect> damage) {
        final VkClearAttachment.Buffer attachment = VkClearAttachment.calloc(1, stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .colorAttachment(0);
        attachment.clearValue().color().float32(0, ((BACKGROUND >> 16) & 0xFF) / 255f)
                .float32(1, ((BACKGROUND >> 8) & 0xFF) / 255f).float32(2, (BACKGROUND & 0xFF) / 255f).float32(3, 1f);
        final VkClearRect.Buffer rects = VkClearRect.calloc(damage.size(), stack);
        for (int i = 0; i < damage.size(); i++) {
            final PixelRect rect = damage.get(i);
            rects.get(i).baseArrayLayer(0).layerCount(1);
            rects.get(i).rect().offset().set(rect.x(), rect.y());
            rects.get(i).rect().extent().set(rect.width(), rect.height());
        }
        vkCmdClearAttachments(commands, attachment, rects);
    }

    private void assertMatchesAFullRedraw() {
        this.full.render(BACKGROUND, this.strata::render);
        assertArrayEquals(this.full.pixels(), this.kept.pixels(), "the kept image matches a full redraw");
    }

    private static long area(final VulkanFrame frame) {
        long area = 0L;
        for (final PixelRect rect : frame.damage()) {
            area += (long) rect.width() * rect.height();
        }
        return area;
    }

    @Test
    void theFirstFrameDamagesTheViewAndAnUnchangedOneNothing() {
        this.strata.screens().push(ui -> ui.absolutePanel().add(ui.label(Text.of("Ana")), 10, 10));
        final VulkanFrame first = this.frame();
        assertEquals(List.of(new PixelRect(0, 0, WIDTH, HEIGHT)), first.damage());
        final VulkanFrame second = this.frame();
        assertTrue(second.isEmpty(), "nothing changed: " + second.damage());
        assertEquals(PixelRect.EMPTY, second.bounds());
        assertMatchesAFullRedraw();
    }

    @Test
    void oneControlChangingDamagesOnlyItsBounds() {
        final ProgressBar[] bar = new ProgressBar[1];
        this.strata.screens().push(ui -> {
            bar[0] = ui.progressBar().setValue(0.2).setWidth(80);
            return ui.absolutePanel().add(ui.label(Text.of("Ben")), 10, 10).add(bar[0], 20, 60);
        });
        this.frame();
        bar[0].setValue(0.7);
        final VulkanFrame changed = this.frame();
        assertFalse(changed.isEmpty());
        final Rect bounds = bar[0].bounds();
        final PixelRect area = changed.bounds();
        assertTrue(area.x() >= Math.floor(bounds.x() * SCALE) - 1 && area.right() <= Math.ceil(bounds.right() * SCALE) + 1
                && area.y() >= Math.floor(bounds.y() * SCALE) - 1
                && area.bottom() <= Math.ceil(bounds.bottom() * SCALE) + 1, "inside the bar: " + area + " " + bounds);
        assertTrue(area(changed) < WIDTH * HEIGHT / 10, "a small part of the view: " + changed.damage());
        assertMatchesAFullRedraw();
    }

    @Test
    void framesDrawnOverAKeptImageMatchAFullRedraw() {
        final Box[] boxes = new Box[3];
        final Label[] label = new Label[1];
        final AbsolutePanel[] panel = new AbsolutePanel[1];
        this.strata.screens().push(ui -> {
            boxes[0] = new Box(ui, 0xFFCC3333);
            boxes[1] = new Box(ui, 0x8033CC33);
            boxes[2] = new Box(ui, 0xFF3333CC);
            label[0] = ui.label(Text.of("Cleo"));
            panel[0] = ui.absolutePanel().add(boxes[0], 10, 10).add(boxes[1], 30, 20).add(boxes[2], 150, 100)
                    .add(label[0], 100, 20);
            return panel[0];
        });
        this.frame();
        boxes[1].setColor(0xC0CCCC33);
        assertTrue(area(this.frame()) < WIDTH * HEIGHT / 4);
        // The translucent box moves under the red one
        boxes[1].setZIndex(-1);
        final VulkanFrame reordered = this.frame();
        assertFalse(reordered.isEmpty(), "the draw order changed");
        assertTrue(area(reordered) < WIDTH * HEIGHT / 4, "only where they lie: " + reordered.damage());
        label[0].setText(Text.of("Dev"));
        boxes[2].setColor(0xFF33CCCC);
        assertTrue(this.frame().damage().size() >= 2, "two places far apart");
        panel[0].remove(boxes[0]);
        this.frame();
        // A full redraw is drawn last, since it damages the whole view for the next frame
        assertMatchesAFullRedraw();
    }

    @Test
    void aPixelBufferThatChangesDamagesItsImage() {
        final PixelBuffer pixels = PixelBuffer.of(4, 4);
        java.util.Arrays.fill(pixels.array(), 0xFFFF0000);
        pixels.changed();
        this.strata.screens().push(ui -> ui.absolutePanel()
                .add(ui.image(VulkanVisuals.image(pixels, 20, 20)), 50, 50));
        this.frame();
        assertTrue(this.frame().isEmpty(), "the same pixels");
        java.util.Arrays.fill(pixels.array(), 0xFF00FF00);
        pixels.changed();
        final VulkanFrame changed = this.frame();
        assertFalse(changed.isEmpty(), "new pixels");
        assertEquals(new PixelRect(100, 100, 40, 40), changed.bounds());
        assertMatchesAFullRedraw();
    }

    @Test
    void anApplicationImageIsDamagedEveryFrame() {
        try (VulkanImage image = this.renderer.image(this.full.view(), 10, 10)) {
            this.strata.screens().push(ui -> ui.absolutePanel().add(ui.image(image), 5, 5));
            this.strata.prepare();
            final VulkanFrame again = this.strata.prepare();
            assertEquals(List.of(new PixelRect(10, 10, 20, 20)), again.damage());
        }
    }

    @Test
    void aResizeOrInvalidateDamagesEverything() {
        this.strata.screens().push(ui -> ui.label(Text.of("Ana")));
        this.frame();
        this.strata.invalidate();
        assertEquals(List.of(new PixelRect(0, 0, WIDTH, HEIGHT)), this.strata.prepare().damage());
        this.strata.setBounds(0, 0, WIDTH / 2, HEIGHT / 2);
        assertEquals(List.of(new PixelRect(0, 0, WIDTH / 2, HEIGHT / 2)), this.strata.prepare().damage());
        this.strata.setBounds(10, 20, WIDTH / 2, HEIGHT / 2);
        assertEquals(List.of(new PixelRect(10, 20, WIDTH / 2, HEIGHT / 2)), this.strata.prepare().damage(),
                "in framebuffer pixels");
    }

    @Test
    void aFrameIsRecordedOnceAndOnlyUntilTheNext() {
        this.strata.screens().push(ui -> ui.label(Text.of("Ana")));
        final VulkanFrame first = this.strata.prepare();
        this.strata.prepare();
        assertThrows(IllegalStateException.class, () -> first.record(null), "another frame came since");
        this.strata.invalidate();
        final VulkanFrame frame = this.frame();
        assertThrows(IllegalStateException.class, () -> frame.record(null), "recorded already");
    }

    @Test
    void theDamageKeepsAtMostFourRectanglesThatDoNotOverlap() {
        final Damage damage = new Damage();
        damage.add(0, 0, 10, 10);
        damage.add(100, 0, 110, 10);
        damage.add(200, 0, 210, 10);
        damage.add(0, 100, 10, 110);
        damage.add(5, 5, 20, 20);
        damage.add(300, 300, 310, 310);
        assertTrue(damage.rectCount() <= Damage.MAX_RECTS, "merged: " + damage.rectCount());
        for (int a = 0; a < damage.rectCount(); a++) {
            for (int b = a + 1; b < damage.rectCount(); b++) {
                final boolean overlap = damage.rect(a, 0) < damage.rect(b, 2) && damage.rect(b, 0) < damage.rect(a, 2)
                        && damage.rect(a, 1) < damage.rect(b, 3) && damage.rect(b, 1) < damage.rect(a, 3);
                assertFalse(overlap, "rectangles " + a + " and " + b + " overlap");
            }
        }
        for (final int[] point : new int[][]{{0, 0}, {19, 19}, {105, 5}, {205, 5}, {5, 105}, {305, 305}}) {
            boolean covered = false;
            for (int r = 0; r < damage.rectCount(); r++) {
                covered |= point[0] >= damage.rect(r, 0) && point[0] < damage.rect(r, 2)
                        && point[1] >= damage.rect(r, 1) && point[1] < damage.rect(r, 3);
            }
            assertTrue(covered, "every added pixel stays damaged: " + point[0] + ", " + point[1]);
        }
    }
}

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

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * A frame {@link StrataVulkan#prepare()} drew and compared with the view's last one, for an application that keeps
 * its image between frames and redraws only what changed.
 *
 * <p>The {@linkplain #damage() damage} is at most four rectangles of framebuffer pixels that do not overlap, inside the
 * view's bounds. Drawing the frame's {@link #record} over the last frame's image, with each rectangle cleared to what
 * the application draws under Strata first, gives the image a full redraw would.</p>
 *
 * <p>Use a frame on the render thread, before the view prepares or renders the next one.</p>
 */
public final class VulkanFrame {
    private final StrataVulkan view;
    private final long frame;
    private final List<PixelRect> damage;
    private final PixelRect bounds;
    private boolean recorded;

    VulkanFrame(final StrataVulkan view, final long frame, final List<PixelRect> damage) {
        this.view = view;
        this.frame = frame;
        this.damage = List.copyOf(damage);
        PixelRect union = PixelRect.EMPTY;
        for (final PixelRect rect : this.damage) {
            union = union.union(rect);
        }
        this.bounds = union;
    }

    /**
     * Returns what changed since the view's last frame.
     *
     * @return the rectangles, in framebuffer pixels; empty when the frame draws what the last one did
     */
    public List<PixelRect> damage() {
        return this.damage;
    }

    /**
     * Returns whether nothing changed, so the application can record and present nothing for this frame.
     *
     * @return true if the damage is empty
     */
    public boolean isEmpty() {
        return this.damage.isEmpty();
    }

    /**
     * Returns the smallest rectangle that holds the damage, for a render area.
     *
     * @return the rectangle, or {@link PixelRect#EMPTY} when nothing changed
     */
    public PixelRect bounds() {
        return this.bounds;
    }

    /**
     * Records the frame's draws that touch the damage, each held to the damage rectangles, into a command buffer
     * recording inside a pass of the renderer's target, as {@link StrataVulkan#render} does with all of them. Strata
     * leaves the pipeline, viewport, scissor, vertex buffer, descriptor sets and push constants bound as it last set
     * them. Submit the command buffer to the renderer's queue afterwards. A frame with no damage records nothing.
     *
     * @param commands the command buffer
     * @throws IllegalStateException if the frame was recorded already, the view prepared or rendered another frame
     *     since, or the view is closed
     */
    public void record(final VkCommandBuffer commands) {
        if (this.recorded) {
            throw new IllegalStateException("The frame was recorded already");
        }
        this.recorded = true;
        this.view.record(this, this.frame, commands);
    }
}

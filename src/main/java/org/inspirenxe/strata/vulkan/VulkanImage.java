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

import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.visual.Visual;

/**
 * A visual that shows an image the application owns, made by {@link VulkanRenderer#image}. Screens show it with an
 * {@code Image} control or a painter's {@code visual} call. It draws only with the renderer that made it; any other
 * canvas draws its placeholder.
 *
 * <p>The visual is live: it holds the image without copying it, so each frame shows what the image holds when the
 * frame executes, and two visuals are equal only when they are the same object.</p>
 *
 * <p>It carries its {@link ImageEncoding}, what the shader reads when it samples the image. Strata uses it to bring
 * those colors into the space the target blends in, whether it draws the image or blurs it behind frosted glass.</p>
 *
 * <p>The application keeps the image alive while frames that draw it may run, and keeps it in
 * {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL} whenever they execute. {@link #close()} lets go of the descriptor
 * set once the device is done with it; the image itself stays the application's.</p>
 */
public final class VulkanImage implements Visual, AutoCloseable {
    private final VulkanRenderer renderer;
    private final long set;
    private final Size size;
    private final ImageEncoding encoding;
    private boolean closed;

    VulkanImage(final VulkanRenderer renderer, final long set, final ImageEncoding encoding, final float width,
                final float height) {
        if (!(width >= 0f) || !(height >= 0f) || Float.isInfinite(width) || Float.isInfinite(height)) {
            renderer.descriptors().free(set);
            throw new IllegalArgumentException("A visual's size must be finite and not negative: " + width + " by "
                    + height);
        }
        this.renderer = renderer;
        this.set = set;
        this.size = new Size(width, height);
        this.encoding = encoding;
    }

    @Override
    public Size size() {
        return this.size;
    }

    /**
     * Returns what the shader reads when it samples the image, as the application named it when making the visual.
     *
     * @return the encoding
     */
    public ImageEncoding encoding() {
        return this.encoding;
    }

    /**
     * Lets go of the descriptor set once every view has rendered the frames in flight since. Drawing the visual
     * afterwards draws its placeholder. Calling it again does nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.renderer.retire(() -> this.renderer.descriptors().free(this.set));
    }

    /**
     * Returns whether a renderer can draw this visual: the one that made it, while neither is closed.
     */
    boolean belongsTo(final VulkanRenderer renderer) {
        return this.renderer == renderer && !this.closed && !renderer.isClosed();
    }

    long set() {
        return this.set;
    }

    /**
     * Returns the mode bits that draws sampling this image add, so the shader converts what it reads.
     */
    int modeBits() {
        return this.encoding == ImageEncoding.LINEAR ? DrawList.LINEAR_SAMPLES : 0;
    }
}

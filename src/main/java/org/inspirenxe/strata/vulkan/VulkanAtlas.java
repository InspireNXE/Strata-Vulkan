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

import org.inspirenxe.strata.api.theme.SpriteId;

import java.util.Objects;

/**
 * An image the application owns that holds many sprites, each at a rectangle of it, made by
 * {@link VulkanRenderer#atlas}. A sprite with a region here draws from the atlas, so a run of such sprites is one
 * draw under one descriptor set, however many images they were; a sprite without one loads its own image from its
 * {@link VulkanSprites}, as it always did.
 *
 * <p>Regions count the atlas's pixels, one pixel to a UI unit, so a sprite's {@linkplain VulkanSprites#size size}
 * is its region's, and its nine-slice borders count the region's pixels. An atlas has one image, so it has no denser
 * variants: the region draws at every density. Sprites are sampled with nearest filtering, or with linear filtering
 * inside {@code pushSampling(LINEAR)}; the application leaves a few pixels around each region that repeat its edge,
 * so linear filtering near an edge does not take in the next sprite. The atlas samples level 0 only.</p>
 *
 * <p>The atlas is live, like a {@link VulkanImage}: it holds the image without copying it, so each frame shows what
 * the image holds when the frame executes. The application keeps the image and its view alive, in
 * {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}, while frames that draw the atlas may run, and calls
 * {@link #changed()} after it replaces pixels that a frame already drew, so a view that keeps its image draws them
 * again. {@link #close(Runnable)} says when the device is done with the image.</p>
 *
 * <p>Use it on the UI thread only.</p>
 */
public final class VulkanAtlas implements AutoCloseable {
    private final VulkanRenderer renderer;
    private final VulkanSprites sprites;
    private final long view;
    private final long set;
    private final int width;
    private final int height;
    private final ImageEncoding encoding;
    private final long id;
    // The set that samples the atlas with linear filtering, or 0 until one is drawn that way
    private long linearSet;
    private long version;
    private boolean closed;

    VulkanAtlas(final VulkanRenderer renderer, final VulkanSprites sprites, final long view, final long set,
                final ImageEncoding encoding, final int width, final int height) {
        this.renderer = renderer;
        this.sprites = sprites;
        this.view = view;
        this.set = set;
        this.encoding = encoding;
        this.width = width;
        this.height = height;
        this.id = Texture.nextId();
    }

    /**
     * Says that a sprite is at a rectangle of the atlas, replacing any earlier region of the sprite from this or
     * another atlas of the same {@link VulkanSprites}. The sprite's size becomes the region's, and views draw again.
     *
     * @param sprite the sprite
     * @param x the rectangle's left edge in pixels, from the image's left
     * @param y the rectangle's top edge in pixels, from the image's top
     * @param width the rectangle's width in pixels
     * @param height the rectangle's height in pixels
     * @throws IllegalArgumentException if the rectangle is empty or reaches outside the atlas
     * @throws IllegalStateException if the atlas is closed
     */
    public void region(final SpriteId sprite, final int x, final int y, final int width, final int height) {
        Objects.requireNonNull(sprite, "sprite");
        if (this.closed) {
            throw new IllegalStateException("The atlas is closed");
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || x > this.width - width || y > this.height - height) {
            throw new IllegalArgumentException("A region of " + width + " by " + height + " at " + x + ", " + y
                    + " does not fit an atlas of " + this.width + " by " + this.height);
        }
        this.sprites.putRegion(sprite, new VulkanSprites.AtlasRegion(this, width, height, x / (float) this.width,
                y / (float) this.height, (x + width) / (float) this.width, (y + height) / (float) this.height));
    }

    /**
     * Says that the image now holds other pixels than a frame that drew the atlas saw, so a view that keeps its image
     * draws them again. A new {@linkplain #region region} needs no call; it draws again on its own. Like a region, it
     * moves the {@linkplain VulkanSprites#version() version} of the sprites on, so the textures made from their own
     * images are made again.
     */
    public void changed() {
        this.version++;
        this.sprites.touch();
    }

    /**
     * Forgets the atlas's regions, so its sprites draw from their own images again, and lets go of its descriptor
     * sets once every view has rendered the frames in flight since. Calling it again does nothing.
     */
    @Override
    public void close() {
        this.close(null);
    }

    /**
     * Closes the atlas as {@link #close()} does, then runs a callback once the device is done with every frame that
     * drew it, on the thread that renders. The application destroys the image and its view there, or reuses them. If
     * the atlas is already closed, nothing happens and the callback does not run.
     *
     * @param released what to run, or {@code null} for nothing
     */
    public void close(final Runnable released) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.sprites.removeRegions(this);
        this.renderer.retire(() -> {
            this.renderer.descriptors().free(this.set);
            if (this.linearSet != 0L) {
                this.renderer.descriptors().free(this.linearSet);
            }
            if (released != null) {
                released.run();
            }
        });
    }

    /**
     * Returns whether a renderer can draw from this atlas: the one that made it, while neither is closed.
     */
    boolean belongsTo(final VulkanRenderer renderer) {
        return this.renderer == renderer && !this.closed && !renderer.isClosed();
    }

    /**
     * Returns the descriptor set that samples the atlas with nearest or linear filtering; the linear one is made the
     * first time it is asked for.
     */
    long set(final boolean linear) {
        if (!linear) {
            return this.set;
        }
        if (this.linearSet == 0L) {
            this.linearSet = this.renderer.descriptors().image(this.renderer.imageLayout(), this.view,
                    this.renderer.linearSampler());
        }
        return this.linearSet;
    }

    /**
     * Returns a value that differs between textures and atlases and changes with {@link #changed()}, for draws to
     * tell whether the pixels they sample changed since the last frame.
     */
    long stamp() {
        return this.id * 0x9E3779B97F4A7C15L + this.version;
    }

    /**
     * Returns the mode bits that draws sampling this atlas add, so the shader converts what it reads.
     */
    int modeBits() {
        return this.encoding == ImageEncoding.LINEAR ? DrawList.LINEAR_SAMPLES : 0;
    }
}

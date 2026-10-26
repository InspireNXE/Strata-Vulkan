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

import org.inspirenxe.strata.api.geometry.Insets;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.visual.Sampling;
import org.inspirenxe.strata.api.theme.SpriteId;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.inspirenxe.strata.api.visual.Visual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draws sprites from a source made in code: the denser image where the density allows, nine-slices that keep their
 * corners, tints, and textures made again after the sprites are cleared.
 */
class VulkanSpritesTest {
    private static final SpriteId FRAME = SpriteId.of("test", "frame");
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static VulkanRenderer renderer;

    @AfterAll
    static void closeRenderer() {
        if (renderer != null) {
            TestDevice.get().idle();
            renderer.close();
            renderer = null;
        }
    }

    @Test
    void decodesAndCachesImages() {
        final int[] loads = {0};
        final VulkanSprites sprites = new VulkanSprites(sprite -> {
            loads[0]++;
            return sprite.equals(FRAME) ? Optional.of(VulkanSpritesTest.png(4, 4, RED, BLUE, 1)) : Optional.empty();
        });
        assertTrue(sprites.has(FRAME));
        assertTrue(sprites.has(FRAME));
        assertFalse(sprites.has(SpriteId.of("test", "missing")));
        assertEquals(2, loads[0], "each sprite is read once, a missing one included");
        final long version = sprites.version();
        sprites.clear();
        assertNotEquals(version, sprites.version());
        assertTrue(sprites.has(FRAME));
        assertEquals(3, loads[0], "clear reads again");
    }

    @Test
    void sizesASpriteByItsPlainImage() {
        final VulkanSprites sprites = new VulkanSprites(new SpriteSource() {
            @Override
            public Optional<byte[]> load(final SpriteId sprite) {
                return sprite.equals(FRAME) ? Optional.of(VulkanSpritesTest.png(6, 4, RED, BLUE, 1)) : Optional.empty();
            }

            @Override
            public Optional<byte[]> loadDense(final SpriteId sprite, final float density) {
                return Optional.of(VulkanSpritesTest.png(12, 8, RED, BLUE, 2));
            }
        });
        assertEquals(Optional.of(new Size(6, 4)), sprites.size(FRAME), "a denser image does not change the size");
        assertTrue(sprites.size(SpriteId.of("test", "missing")).isEmpty());
    }

    @Test
    void nineSliceKeepsItsCornersAndStretchesItsMiddle() {
        // A 6 by 6 frame: a red border 2 pixels wide around a blue middle
        final VulkanSprites sprites = new VulkanSprites(sprite -> Optional.of(VulkanSpritesTest.png(6, 6, RED, BLUE,
                2)));
        final OffscreenImage image = this.draw(sprites, 40, 40, 1f, canvas ->
                canvas.nineSlice(FRAME, new Rect(0, 0, 40, 40), Insets.of(2), 0xFFFFFFFF));
        assertEquals(RED, image.argb(0, 0));
        assertEquals(RED, image.argb(1, 20), "the left edge stays 2 pixels wide");
        assertEquals(BLUE, image.argb(2, 20));
        assertEquals(BLUE, image.argb(20, 20), "the middle stretches");
        assertEquals(RED, image.argb(38, 39));
        image.close();
    }

    @Test
    void theDenserImageDrawsFromTwoPixelsPerUnit() {
        final VulkanSprites sprites = new VulkanSprites(new SpriteSource() {
            @Override
            public Optional<byte[]> load(final SpriteId sprite) {
                return Optional.of(VulkanSpritesTest.png(2, 2, RED, RED, 0));
            }

            @Override
            public Optional<byte[]> loadDense(final SpriteId sprite, final float density) {
                return density == 2f ? Optional.of(VulkanSpritesTest.png(4, 4, GREEN, GREEN, 0)) : Optional.empty();
            }
        });
        final OffscreenImage plain = this.draw(sprites, 8, 8, 1f, canvas -> canvas.sprite(FRAME, new Rect(0, 0, 8, 8),
                0xFFFFFFFF));
        assertEquals(RED, plain.argb(4, 4));
        plain.close();
        final OffscreenImage dense = this.draw(sprites, 16, 16, 2f, canvas -> canvas.sprite(FRAME, new Rect(0, 0, 8, 8),
                0xFFFFFFFF));
        assertEquals(GREEN, dense.argb(8, 8), "the @2x image at two pixels per unit");
        dense.close();
    }

    @Test
    void linearSamplingBlendsAndNearestStaysSharp() {
        // A 4 by 4 image: a red border one pixel wide around a blue middle, drawn ten times larger
        final VulkanSprites sprites = new VulkanSprites(sprite -> Optional.of(VulkanSpritesTest.png(4, 4, RED, BLUE,
                1)));
        final OffscreenImage nearest = this.draw(sprites, 40, 40, 1f, canvas -> canvas.sprite(FRAME,
                new Rect(0, 0, 40, 40), 0xFFFFFFFF));
        assertEquals(BLUE, nearest.argb(10, 20));
        nearest.close();
        final OffscreenImage linear = this.draw(sprites, 40, 40, 1f, canvas -> {
            canvas.pushSampling(Sampling.LINEAR);
            canvas.sprite(FRAME, new Rect(0, 0, 40, 40), 0xFFFFFFFF);
            canvas.popSampling();
        });
        final int blend = linear.argb(10, 20);
        assertTrue((blend >> 16 & 0xFF) > 0 && (blend & 0xFF) > 0, Integer.toHexString(blend));
        assertEquals(BLUE, linear.argb(20, 20), "the middle is still blue");
        linear.close();
    }

    @Test
    void tintMultipliesTheSpriteAndClearingMakesNewTextures() {
        final int[] color = {0xFFFFFFFF};
        final VulkanSprites sprites = new VulkanSprites(sprite -> Optional.of(VulkanSpritesTest.png(2, 2, color[0],
                color[0], 0)));
        final OffscreenImage tinted = this.draw(sprites, 8, 8, 1f, canvas -> canvas.sprite(FRAME, new Rect(0, 0, 8, 8),
                GREEN));
        assertEquals(GREEN, tinted.argb(4, 4));
        tinted.close();
        color[0] = BLUE;
        sprites.clear();
        final OffscreenImage cleared = this.draw(sprites, 8, 8, 1f, canvas -> canvas.sprite(FRAME, new Rect(0, 0, 8, 8),
                0xFFFFFFFF));
        assertEquals(BLUE, cleared.argb(4, 4), "the image read again after clear");
        cleared.close();
    }

    @Test
    void pixelVisualsDrawTheirPixels() {
        final PixelBuffer pixels = PixelBuffer.of(2, 2).fill(BLUE).changed();
        final Visual visual = VulkanVisuals.image(pixels);
        assertEquals(new Size(2, 2), visual.size());
        assertEquals(visual, VulkanVisuals.image(pixels));
        assertNotEquals(visual, VulkanVisuals.image(PixelBuffer.of(2, 2)));
        final OffscreenImage image = this.draw(null, 8, 8, 1f, canvas -> canvas.visual(visual, new Rect(0, 0, 8, 8),
                0xFFFFFFFF));
        assertEquals(BLUE, image.argb(4, 4));
        image.close();
    }

    private OffscreenImage draw(final VulkanSprites sprites, final int width, final int height, final float scale,
                           final Consumer<VulkanCanvas> draw) {
        final TestDevice device = TestDevice.get();
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        final OffscreenImage image = new OffscreenImage(device, renderer, width, height);
        final FrameStream frames = renderer.openStream();
        final VulkanCanvas canvas = frames.begin(0, 0, width, height, scale);
        canvas.configure(null, sprites, null);
        draw.accept(canvas);
        image.render(frames::end);
        frames.close();
        return image;
    }

    /**
     * Encodes an image with a border of one color around another as PNG.
     */
    static byte[] png(final int width, final int height, final int border, final int middle, final int thickness) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final boolean edge = x < thickness || y < thickness || x >= width - thickness
                        || y >= height - thickness;
                image.setRGB(x, y, edge ? border : middle);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

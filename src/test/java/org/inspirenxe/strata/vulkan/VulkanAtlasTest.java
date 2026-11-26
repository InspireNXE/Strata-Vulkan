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
import org.inspirenxe.strata.api.theme.SpriteId;
import org.inspirenxe.strata.api.visual.Sampling;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;

/**
 * Sprites that draw from an application's atlas: where each region maps, what the sprites measure, and that a run of
 * them is one batch.
 */
class VulkanAtlasTest {
    private static final SpriteId RED_SPRITE = SpriteId.of("test", "red");
    private static final SpriteId BLUE_SPRITE = SpriteId.of("test", "blue");
    private static final SpriteId FRAME_SPRITE = SpriteId.of("test", "frame");
    private static final SpriteId PLAIN_SPRITE = SpriteId.of("test", "plain");
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int MAGENTA = 0xFFFF00FF;
    private static final int WIDTH = 24;
    private static final int HEIGHT = 12;
    private static VulkanRenderer renderer;

    private final Map<VulkanAtlas, Texture> textures = new IdentityHashMap<>();

    @AfterAll
    static void closeRenderer() {
        if (renderer != null) {
            TestDevice.get().idle();
            renderer.close();
            renderer = null;
        }
    }

    @Test
    void aRegionMapsItsPixelsToTextureCoordinates() {
        final VulkanSprites sprites = new VulkanSprites(sprite -> Optional.empty());
        final VulkanAtlas atlas = new VulkanAtlas(null, sprites, 0L, 0L, ImageEncoding.ENCODED, WIDTH, HEIGHT);
        atlas.region(BLUE_SPRITE, 6, 3, 12, 6);
        final VulkanSprites.AtlasRegion region = sprites.region(BLUE_SPRITE);
        assertEquals(0.25f, region.u0());
        assertEquals(0.25f, region.v0());
        assertEquals(0.75f, region.u1());
        assertEquals(0.75f, region.v1());
        assertEquals(0.5f, region.u(0.5f), "halfway across the sprite is halfway across the atlas");
        assertEquals(0.25f, region.v(0f));
        assertEquals(0.75f, region.v(1f));
        assertNull(sprites.region(RED_SPRITE));
    }

    @Test
    void aSpriteWithARegionTakesItsSizeAndNeedsNoImage() {
        final int[] loads = {0};
        final VulkanSprites sprites = new VulkanSprites(sprite -> {
            loads[0]++;
            return Optional.empty();
        });
        final VulkanAtlas atlas = new VulkanAtlas(null, sprites, 0L, 0L, ImageEncoding.ENCODED, WIDTH, HEIGHT);
        assertFalse(sprites.has(RED_SPRITE));
        final long before = sprites.version();
        atlas.region(RED_SPRITE, 1, 1, 4, 5);
        assertNotEquals(before, sprites.version(), "views size and draw a new region");
        assertTrue(sprites.has(RED_SPRITE));
        assertEquals(Optional.of(new Size(4, 5)), sprites.size(RED_SPRITE));
        assertEquals(1, loads[0], "only the lookup before the region asked the source");
        atlas.region(RED_SPRITE, 0, 0, 8, 2);
        assertEquals(Optional.of(new Size(8, 2)), sprites.size(RED_SPRITE), "a later region replaces the earlier");
    }

    @Test
    void aRegionOutsideTheAtlasIsRefused() {
        final VulkanSprites sprites = new VulkanSprites(sprite -> Optional.empty());
        final VulkanAtlas atlas = new VulkanAtlas(null, sprites, 0L, 0L, ImageEncoding.ENCODED, WIDTH, HEIGHT);
        atlas.region(RED_SPRITE, WIDTH - 4, HEIGHT - 4, 4, 4);
        assertThrows(IllegalArgumentException.class, () -> atlas.region(RED_SPRITE, WIDTH - 3, 0, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> atlas.region(RED_SPRITE, 0, HEIGHT - 3, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> atlas.region(RED_SPRITE, -1, 0, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> atlas.region(RED_SPRITE, 0, 0, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> atlas.region(RED_SPRITE, 0, 0, 4, -2));
        assertThrows(IllegalArgumentException.class, () -> atlas.region(RED_SPRITE, Integer.MAX_VALUE, 0, 2, 2));
        assertEquals(Optional.of(new Size(4, 4)), sprites.size(RED_SPRITE), "a refused region changes nothing");
    }

    @Test
    void closingForgetsOnlyThatAtlasesRegions() {
        final VulkanSprites sprites = new VulkanSprites(sprite -> Optional.empty());
        final VulkanAtlas first = new VulkanAtlas(null, sprites, 0L, 0L, ImageEncoding.ENCODED, WIDTH, HEIGHT);
        final VulkanAtlas second = new VulkanAtlas(null, sprites, 0L, 0L, ImageEncoding.ENCODED, WIDTH, HEIGHT);
        first.region(RED_SPRITE, 0, 0, 2, 2);
        second.region(BLUE_SPRITE, 0, 0, 2, 2);
        final long before = sprites.version();
        sprites.removeRegions(first);
        assertNotEquals(before, sprites.version());
        assertNull(sprites.region(RED_SPRITE));
        assertEquals(second, sprites.region(BLUE_SPRITE).atlas());
        final long after = sprites.version();
        sprites.removeRegions(first);
        assertEquals(after, sprites.version(), "an atlas with no regions left changes nothing");
    }

    @Test
    void anAtlasSpriteDrawsItsRegionAndNothingAround() {
        final VulkanSprites sprites = this.sprites();
        final VulkanAtlas atlas = this.atlas(sprites);
        final OffscreenImage whole = this.draw(sprites, 16, 16, 4f, canvas ->
                canvas.sprite(RED_SPRITE, new Rect(0, 0, 4, 4), 0xFFFFFFFF));
        assertEquals(RED, whole.argb(0, 0));
        assertEquals(RED, whole.argb(15, 15), "the sprite's far corner stays inside its region");
        assertEquals(RED, whole.argb(8, 8));
        whole.close();
        final OffscreenImage part = this.draw(sprites, 8, 8, 1f, canvas ->
                canvas.spriteRegion(BLUE_SPRITE, new Rect(0, 0, 8, 8), 0.5f, 0.5f, 1f, 1f, 0xFFFFFFFF));
        assertEquals(BLUE, part.argb(0, 0));
        assertEquals(BLUE, part.argb(7, 7));
        part.close();
        this.release(atlas);
    }

    @Test
    void aTintMultipliesAnAtlasSprite() {
        final VulkanSprites sprites = this.sprites();
        final VulkanAtlas atlas = this.atlas(sprites);
        final OffscreenImage tinted = this.draw(sprites, 8, 8, 1f, canvas ->
                canvas.sprite(BLUE_SPRITE, new Rect(0, 0, 8, 8), GREEN));
        assertEquals(0xFF000000, tinted.argb(4, 4), "blue under green leaves black");
        tinted.close();
        this.release(atlas);
    }

    @Test
    void nineSliceKeepsItsCornersWithinTheRegion() {
        final VulkanSprites sprites = this.sprites();
        final VulkanAtlas atlas = this.atlas(sprites);
        final OffscreenImage image = this.draw(sprites, 40, 40, 1f, canvas ->
                canvas.nineSlice(FRAME_SPRITE, new Rect(0, 0, 40, 40), Insets.of(2), 0xFFFFFFFF));
        assertEquals(RED, image.argb(0, 0));
        assertEquals(RED, image.argb(1, 20), "the left edge stays 2 pixels wide");
        assertEquals(BLUE, image.argb(2, 20));
        assertEquals(BLUE, image.argb(20, 20), "the middle stretches");
        assertEquals(RED, image.argb(39, 39), "the far corner shows no neighbor of the region");
        image.close();
        this.release(atlas);
    }

    @Test
    void linearSamplingDrawsFromTheAtlasToo() {
        final VulkanSprites sprites = this.sprites();
        final VulkanAtlas atlas = this.atlas(sprites);
        final OffscreenImage image = this.draw(sprites, 40, 40, 1f, canvas -> {
            canvas.pushSampling(Sampling.LINEAR);
            canvas.nineSlice(FRAME_SPRITE, new Rect(0, 0, 40, 40), Insets.of(2), 0xFFFFFFFF);
            canvas.popSampling();
        });
        assertEquals(RED, image.argb(0, 0));
        assertEquals(BLUE, image.argb(20, 20));
        image.close();
        this.release(atlas);
    }

    @Test
    void aRunOfAtlasSpritesIsOneBatchAndAnotherImageBreaksIt() {
        final VulkanSprites sprites = this.sprites();
        final VulkanAtlas atlas = this.atlas(sprites);
        final int[] batches = new int[2];
        final OffscreenImage image = this.draw(sprites, 16, 16, 1f, canvas -> {
            final Rect rect = new Rect(0, 0, 4, 4);
            canvas.sprite(RED_SPRITE, rect, 0xFFFFFFFF);
            canvas.sprite(BLUE_SPRITE, rect, 0xFFFFFFFF);
            canvas.nineSlice(FRAME_SPRITE, rect, Insets.of(1), 0xFFFFFFFF);
            canvas.sprite(RED_SPRITE, rect, 0xFFFFFFFF);
            batches[0] = VulkanAtlasTest.draws(canvas).batchCount();
            canvas.sprite(PLAIN_SPRITE, rect, 0xFFFFFFFF);
            canvas.sprite(RED_SPRITE, rect, 0xFFFFFFFF);
            batches[1] = VulkanAtlasTest.draws(canvas).batchCount();
        });
        assertEquals(1, batches[0], "sprites and nine-slices of the atlas share one batch");
        assertEquals(3, batches[1], "a sprite with an image of its own is a batch between two of the atlas");
        image.close();
        this.release(atlas);
    }

    @Test
    void aSpriteWithoutARegionDrawsFromItsOwnImage() {
        final VulkanSprites sprites = this.sprites();
        final VulkanAtlas atlas = this.atlas(sprites);
        final OffscreenImage image = this.draw(sprites, 8, 8, 1f, canvas ->
                canvas.sprite(PLAIN_SPRITE, new Rect(0, 0, 8, 8), 0xFFFFFFFF));
        assertEquals(GREEN, image.argb(4, 4));
        image.close();
        this.release(atlas);
    }

    @Test
    void closedAtlasSpritesDrawFromTheirOwnImagesAgain() {
        final VulkanSprites sprites = this.sprites();
        final VulkanAtlas atlas = this.atlas(sprites);
        atlas.close();
        assertEquals(Optional.of(new Size(2, 2)), sprites.size(RED_SPRITE), "the size is the image's again");
        final OffscreenImage image = this.draw(sprites, 8, 8, 1f, canvas ->
                canvas.sprite(RED_SPRITE, new Rect(0, 0, 8, 8), 0xFFFFFFFF));
        assertEquals(GREEN, image.argb(4, 4));
        image.close();
        assertThrows(IllegalStateException.class, () -> atlas.region(RED_SPRITE, 0, 0, 1, 1));
        this.release(atlas);
    }

    @Test
    void theImageIsReleasedOnlyAfterTheFramesInFlight() {
        final VulkanSprites sprites = this.sprites();
        final Texture texture = this.texture();
        final VulkanAtlas atlas = renderer.atlas(sprites, texture.view, ImageEncoding.ENCODED, WIDTH, HEIGHT);
        final FrameStream frames = renderer.openStream();
        final OffscreenImage image = new OffscreenImage(TestDevice.get(), renderer, 8, 8);
        final boolean[] released = {false, false};
        atlas.region(RED_SPRITE, 1, 1, 4, 4);
        frames.begin(0, 0, 8, 8, 1f).configure(null, sprites, null);
        image.render(frames::end);
        atlas.close(() -> released[0] = true);
        atlas.close(() -> released[1] = true);
        assertFalse(released[0], "a frame that drew the atlas may still run");
        for (int frame = 0; frame < 3; frame++) {
            frames.begin(0, 0, 8, 8, 1f);
            image.render(frames::end);
        }
        assertTrue(released[0], "after the frames in flight the device is done with it");
        assertFalse(released[1], "closing again does nothing");
        frames.close();
        TestDevice.get().idle();
        image.close();
        texture.destroy(renderer);
    }

    /**
     * Returns sprites whose own images are a green 2 by 2 for the plain sprite and for the red one, which the atlas
     * overrides, so a sprite that falls back shows green.
     */
    private VulkanSprites sprites() {
        return new VulkanSprites(sprite -> sprite.equals(PLAIN_SPRITE) || sprite.equals(RED_SPRITE)
                ? Optional.of(VulkanSpritesTest.png(2, 2, GREEN, GREEN, 0)) : Optional.empty());
    }

    /**
     * Makes an atlas 24 by 12 that is magenta outside three regions: a red 4 by 4 at 1, 1, a blue 4 by 4 at 7, 1 and
     * a frame of 6 by 6 with a red border 2 wide round blue at 13, 1.
     */
    private VulkanAtlas atlas(final VulkanSprites sprites) {
        final Texture texture = this.texture();
        final VulkanAtlas atlas = renderer.atlas(sprites, texture.view, ImageEncoding.ENCODED, WIDTH, HEIGHT);
        atlas.region(RED_SPRITE, 1, 1, 4, 4);
        atlas.region(BLUE_SPRITE, 7, 1, 4, 4);
        atlas.region(FRAME_SPRITE, 13, 1, 6, 6);
        this.textures.put(atlas, texture);
        return atlas;
    }

    private Texture texture() {
        final TestDevice device = TestDevice.get();
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        final int[] pixels = new int[WIDTH * HEIGHT];
        Arrays.fill(pixels, MAGENTA);
        VulkanAtlasTest.fill(pixels, 1, 1, 4, 4, RED, RED, 0);
        VulkanAtlasTest.fill(pixels, 7, 1, 4, 4, BLUE, BLUE, 0);
        VulkanAtlasTest.fill(pixels, 13, 1, 6, 6, RED, BLUE, 2);
        final Texture texture = Texture.create(renderer, WIDTH, HEIGHT, VK_FORMAT_B8G8R8A8_UNORM, 4,
                renderer.nearestSampler());
        renderer.queueUpload(texture, 0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        return texture;
    }

    /**
     * Closes an atlas and destroys its image once the device is idle.
     */
    private void release(final VulkanAtlas atlas) {
        atlas.close();
        TestDevice.get().idle();
        this.textures.remove(atlas).destroy(renderer);
    }

    private static void fill(final int[] pixels, final int x, final int y, final int width, final int height,
                             final int border, final int middle, final int thickness) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                final boolean edge = column < thickness || row < thickness || column >= width - thickness
                        || row >= height - thickness;
                pixels[(y + row) * WIDTH + x + column] = edge ? border : middle;
            }
        }
    }

    private OffscreenImage draw(final VulkanSprites sprites, final int width, final int height, final float scale,
                                final Consumer<VulkanCanvas> draw) {
        final TestDevice device = TestDevice.get();
        final OffscreenImage image = new OffscreenImage(device, renderer, width, height);
        final FrameStream frames = renderer.openStream();
        final VulkanCanvas canvas = frames.begin(0, 0, width, height, scale);
        canvas.configure(null, sprites, null);
        draw.accept(canvas);
        image.render(frames::end);
        frames.close();
        return image;
    }

    private static DrawList draws(final VulkanCanvas canvas) {
        try {
            final Field field = VulkanCanvas.class.getDeclaredField("draws");
            field.setAccessible(true);
            return (DrawList) field.get(canvas);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

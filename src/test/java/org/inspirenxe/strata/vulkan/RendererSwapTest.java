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
import org.inspirenxe.strata.api.component.Component;
import org.inspirenxe.strata.api.control.button.Button;
import org.inspirenxe.strata.api.control.field.TextBox;
import org.inspirenxe.strata.api.extension.CustomComponent;
import org.inspirenxe.strata.api.extension.MeasureContext;
import org.inspirenxe.strata.api.extension.Painter;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.input.Modifiers;
import org.inspirenxe.strata.api.input.MouseButton;
import org.inspirenxe.strata.api.screen.Screen;
import org.inspirenxe.strata.api.theme.SpriteId;
import org.inspirenxe.strata.api.theme.Themes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetFences;

/**
 * A view moves to another renderer with its screens open: their state and the stack stay, the next frame draws every
 * screen again on the new renderer, and what the old renderer made is forgotten. A lost device's renderer closes
 * without waiting for the device.
 */
class RendererSwapTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;
    private static final float SCALE = 2f;
    private static final int BACKGROUND = 0xFF1E1F22;
    private static final SpriteId TILE = SpriteId.of("test", "tile");
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int ATLAS_SIZE = 24;

    private final long[] nanos = {1L};
    private TestDevice device;
    private VulkanRenderer renderer;
    private StrataVulkan strata;
    private OffscreenImage image;
    private int paints;

    @BeforeEach
    void open() {
        this.device = TestDevice.get();
        this.renderer = this.newRenderer();
        final VulkanPlatform platform = VulkanPlatform.builder(TestFonts.metrics()).clock(() -> this.nanos[0])
                .build();
        this.strata = new StrataVulkan(platform, this.renderer, Themes.flat(), SCALE);
        this.strata.setBounds(0, 0, WIDTH, HEIGHT);
        this.image = new OffscreenImage(this.device, this.renderer, WIDTH, HEIGHT);
    }

    @AfterEach
    void close() {
        if (this.image != null) {
            this.image.close();
            this.strata.close();
            this.renderer.close();
        }
    }

    private VulkanRenderer newRenderer() {
        return new VulkanRenderer(this.device.logical, this.device.queue, this.device.queueFamily,
                OffscreenImage.TARGET, 2);
    }

    private void render() {
        this.nanos[0] += 1_000_000L;
        this.image.render(BACKGROUND, this.strata::render);
    }

    /**
     * Loses the device as far as the view can tell: the renderer closes without waiting, and the view moves to a new
     * renderer, with an image of its own to draw into.
     */
    private void swap() {
        this.swapTo(this.newRenderer());
    }

    private void swapTo(final VulkanRenderer next) {
        this.image.close();
        this.renderer.closeLost();
        this.renderer = next;
        this.strata.setRenderer(next);
        this.image = new OffscreenImage(this.device, next, WIDTH, HEIGHT);
    }

    private <T extends Component<T>> T counted(final T component) {
        return component.onEvent(Component.PAINTED, event -> this.paints++);
    }

    @Test
    void theScreensStayOpenWithTheirStateAndDrawAgainOnTheNewRenderer() {
        final List<String> presses = new ArrayList<>();
        final TextBox[] box = new TextBox[1];
        final Button[] button = new Button[1];
        final Screen first = ui -> ui.label("Below");
        final Screen second = ui -> {
            box[0] = this.counted(ui.textBox().setText("Ana's order").setWidth(120));
            button[0] = ui.button("Save").onEvent(Button.PRESSED, event -> presses.add("pressed"));
            return ui.absolutePanel().add(box[0], 10, 10).add(button[0], 10, 40);
        };
        this.strata.screens().push(first);
        this.strata.screens().push(second);
        this.render();
        this.render();
        final int[] before = this.image.pixels().clone();
        assertEquals(1, this.paints);

        this.swap();
        this.render();

        assertEquals(2, this.paints, "every screen is drawn again, not repeated from the old renderer's frame");
        assertArrayEquals(before, this.image.pixels(), "the new renderer draws what the old one did");
        assertSame(second, this.strata.screens().top().orElseThrow());
        assertEquals("Ana's order", box[0].text());
        final Rect bounds = button[0].bounds();
        final float x = bounds.x() + bounds.width() / 2f;
        final float y = bounds.y() + bounds.height() / 2f;
        this.strata.screens().pointerMoved(x, y, Modifiers.NONE);
        this.strata.screens().pointerButton(x, y, MouseButton.LEFT, true, Modifiers.NONE);
        this.strata.screens().pointerButton(x, y, MouseButton.LEFT, false, Modifiers.NONE);
        this.render();
        assertEquals(List.of("pressed"), presses, "the screen's own handlers still run");
        assertSame(second, this.strata.screens().pop().orElseThrow());
        assertSame(first, this.strata.screens().top().orElseThrow(), "the stack below is as it was");
    }

    @Test
    void theViewMovesOffARendererThatIsStillOpenAndLeavesItsOtherViewsAlone() {
        final VulkanPlatform platform = VulkanPlatform.builder(TestFonts.metrics()).clock(() -> this.nanos[0])
                .build();
        final StrataVulkan other = new StrataVulkan(platform, this.renderer, Themes.flat(), SCALE);
        other.setBounds(0, 0, WIDTH, HEIGHT);
        other.screens().push(ui -> ui.absolutePanel().add(ui.label("Other"), 10, 10));
        this.strata.screens().push(ui -> ui.absolutePanel().add(ui.label("Ana"), 10, 10));
        this.render();
        final int[] before = this.image.pixels().clone();

        this.image.close();
        final VulkanRenderer old = this.renderer;
        this.renderer = this.newRenderer();
        this.strata.setRenderer(this.renderer);
        this.image = new OffscreenImage(this.device, this.renderer, WIDTH, HEIGHT);
        this.render();
        assertArrayEquals(before, this.image.pixels());

        final OffscreenImage otherImage = new OffscreenImage(this.device, old, WIDTH, HEIGHT);
        otherImage.render(BACKGROUND, other::render);
        assertTrue(Arrays.stream(otherImage.pixels()).anyMatch(pixel -> pixel != BACKGROUND),
                "the view that stayed draws on the old renderer");
        otherImage.close();
        other.close();
        old.close();
    }

    @Test
    void aViewThatKeepsItsImageDamagesAllOfItAfterTheSwapAndOldFramesCannotBeRecorded() {
        this.strata.screens().push(ui -> ui.absolutePanel().add(ui.label("Ana"), 10, 10));
        final VulkanFrame first = this.strata.prepare();
        this.image.render(BACKGROUND, first::record);
        final int[] before = this.image.pixels().clone();
        this.nanos[0] += 1_000_000L;
        assertTrue(this.strata.prepare().isEmpty(), "nothing changed on the old renderer");
        final VulkanFrame stale = this.strata.prepare();

        this.swap();
        assertThrows(IllegalStateException.class, () -> stale.record(null));
        final VulkanFrame after = this.strata.prepare();
        assertEquals(new PixelRect(0, 0, WIDTH, HEIGHT), after.bounds(), "the new image holds nothing yet");
        this.image.render(BACKGROUND, after::record);
        assertArrayEquals(before, this.image.pixels(), "the whole view is drawn again");
    }

    @Test
    void aRendererThatIsClosedIsRefusedAndTheViewKeepsItsOwn() {
        this.strata.screens().push(ui -> ui.absolutePanel().add(ui.label("Ana"), 10, 10));
        this.render();
        final int[] before = this.image.pixels().clone();
        final VulkanRenderer closed = this.newRenderer();
        closed.close();
        assertThrows(IllegalStateException.class, () -> this.strata.setRenderer(closed));
        this.render();
        assertArrayEquals(before, this.image.pixels(), "the view still draws on its renderer");
    }

    @Test
    void regionsOfTheOldRenderersAtlasAreForgottenAndANewAtlasDrawsAfterTheSwap() {
        final VulkanSprites sprites = new VulkanSprites(sprite -> sprite.equals(TILE)
                ? Optional.of(VulkanSpritesTest.png(2, 2, GREEN, GREEN, 0)) : Optional.empty());
        this.strata.setSprites(sprites);
        this.strata.screens().push(ui -> ui.absolutePanel().add(new Tile(ui), 0, 0));
        final Texture oldTexture = this.atlasTexture(this.renderer);
        final VulkanAtlas oldAtlas = this.renderer.atlas(sprites, oldTexture.view, ImageEncoding.ENCODED, ATLAS_SIZE,
                ATLAS_SIZE);
        oldAtlas.region(TILE, 1, 1, 4, 4);
        this.render();
        assertEquals(RED, this.image.argb(8, 8), "the sprite draws from the old atlas");
        assertEquals(new Size(4, 4), sprites.size(TILE).orElseThrow());

        this.device.idle();
        oldTexture.destroy(this.renderer);
        this.swap();
        assertNull(sprites.region(TILE), "the region of an atlas the new renderer cannot draw from is forgotten");
        assertEquals(new Size(2, 2), sprites.size(TILE).orElseThrow(), "the sprite measures as its own image again");
        this.render();
        assertEquals(GREEN, this.image.argb(8, 8), "the sprite draws from its own image until it has a new region");

        final boolean[] released = {false};
        oldAtlas.close(() -> released[0] = true);
        assertTrue(released[0], "the old atlas's image is let go of at once, as the device that had it is gone");

        final Texture texture = this.atlasTexture(this.renderer);
        final VulkanAtlas atlas = this.renderer.atlas(sprites, texture.view, ImageEncoding.ENCODED, ATLAS_SIZE,
                ATLAS_SIZE);
        atlas.region(TILE, 1, 1, 4, 4);
        this.render();
        assertEquals(RED, this.image.argb(8, 8), "the new atlas draws");
        atlas.close();
        this.device.idle();
        texture.destroy(this.renderer);
    }

    @Test
    void theBackdropStaysOnlyIfTheNewRendererMadeIt() {
        final Texture oldTexture = this.atlasTexture(this.renderer);
        final VulkanImage old = this.renderer.image(oldTexture.view, 1f, 1f);
        this.strata.setBackdrop(old);
        this.render();
        this.device.idle();
        oldTexture.destroy(this.renderer);
        final VulkanRenderer next = this.newRenderer();
        final Texture texture = this.atlasTexture(next);
        final VulkanImage made = next.image(texture.view, 1f, 1f);
        this.swapTo(next);
        assertEquals(Optional.empty(), this.strata.backdrop(), "the old renderer's image is dropped");
        old.close();

        final VulkanRenderer last = this.newRenderer();
        final Texture lastTexture = this.atlasTexture(last);
        final VulkanImage kept = last.image(lastTexture.view, 1f, 1f);
        this.strata.setBackdrop(kept);
        this.render();
        this.device.idle();
        texture.destroy(next);
        made.close();
        this.swapTo(last);
        assertEquals(Optional.of(kept), this.strata.backdrop(), "an image of the renderer the view moves to stays");
        this.render();
        this.strata.setBackdrop(null);
        kept.close();
        this.device.idle();
        lastTexture.destroy(last);
    }

    @Test
    void closingALostRendererWaitsForNoUpload() throws InterruptedException {
        final VulkanRenderer lost = this.newRenderer();
        final VulkanPlatform platform = VulkanPlatform.builder(TestFonts.metrics()).clock(() -> this.nanos[0])
                .build();
        final StrataVulkan view = new StrataVulkan(platform, lost, Themes.flat(), SCALE);
        view.setBounds(0, 0, WIDTH, HEIGHT);
        view.screens().push(ui -> ui.absolutePanel().add(ui.label("Ana"), 10, 10));
        final OffscreenImage target = new OffscreenImage(this.device, lost, WIDTH, HEIGHT);
        target.render(BACKGROUND, view::render);
        target.close();
        final FrameStream.Slot slot = RendererSwapTest.slot(view.stream());
        // An upload the device never finishes: close would wait for this fence for good
        this.device.idle();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkResetFences(this.device.logical, stack.longs(slot.fence()));
        }
        slot.submitted();

        final Thread closing = new Thread(lost::closeLost, "close a lost renderer");
        closing.setDaemon(true);
        closing.start();
        closing.join(10_000L);
        if (closing.isAlive()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkQueueSubmit(this.device.queue, VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO),
                        slot.fence());
            }
            closing.join(10_000L);
            fail("Closing a lost renderer waited for an upload the device never finished");
        }
        assertFalse(closing.isAlive());
        this.device.checkValidation();
        view.close();
    }

    private Texture atlasTexture(final VulkanRenderer renderer) {
        final int[] pixels = new int[ATLAS_SIZE * ATLAS_SIZE];
        Arrays.fill(pixels, RED);
        final Texture texture = Texture.create(renderer, ATLAS_SIZE, ATLAS_SIZE, VK_FORMAT_B8G8R8A8_UNORM, 4,
                renderer.nearestSampler());
        renderer.queueUpload(texture, 0, 0, ATLAS_SIZE, ATLAS_SIZE, pixels, 0, ATLAS_SIZE);
        return texture;
    }

    private static FrameStream.Slot slot(final FrameStream stream) {
        try {
            final Field field = FrameStream.class.getDeclaredField("slots");
            field.setAccessible(true);
            return ((FrameStream.Slot[]) field.get(stream))[0];
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A component that draws one sprite.
     */
    private static final class Tile extends CustomComponent<Tile> {
        Tile(final ComponentFactory factory) {
            super(factory);
        }

        @Override
        protected Size measure(final MeasureContext context, final float availableWidth, final float availableHeight) {
            return new Size(8, 8);
        }

        @Override
        protected void paint(final Painter painter, final Rect bounds) {
            painter.sprite(TILE, bounds, 0xFFFFFFFF);
        }
    }
}

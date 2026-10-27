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
import org.inspirenxe.strata.api.control.display.Label;
import org.inspirenxe.strata.api.extension.CustomComponent;
import org.inspirenxe.strata.api.extension.MeasureContext;
import org.inspirenxe.strata.api.extension.Painter;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.text.Text;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A view whose screens would draw what they drew for the last frame draws the last frame again: the screens are not
 * painted, nothing is built or copied, and the image comes out the same. Anything that changes the frame builds it
 * again.
 */
class RetainedFrameTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;
    private static final float SCALE = 2f;
    private static final int BACKGROUND = 0xFF1E1F22;

    private final long[] nanos = {1L};
    private TestDevice device;
    private VulkanRenderer renderer;
    private StrataVulkan strata;
    private OffscreenImage image;
    private int paints;

    @BeforeEach
    void open() {
        this.device = TestDevice.get();
        this.renderer = new VulkanRenderer(this.device.logical, this.device.queue, this.device.queueFamily,
                OffscreenImage.TARGET, 2);
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

    /**
     * Renders a frame a millisecond after the last, sooner than the frame rate would ask for one.
     */
    private void render() {
        this.nanos[0] += 1_000_000L;
        this.image.render(BACKGROUND, this.strata::render);
    }

    /**
     * Renders a frame a whole frame interval after the last, when the frame rate allows the screens to draw again.
     */
    private void renderLater() {
        this.nanos[0] += this.strata.screens().frameIntervalNanos();
        this.image.render(BACKGROUND, this.strata::render);
    }

    private long built() {
        return this.strata.stream().committed();
    }

    private <T extends Component<T>> T counted(final T component) {
        return component.onEvent(Component.PAINTED, event -> this.paints++);
    }

    @Test
    void anUnchangedFrameIsDrawnAgainWithoutPaintingOrBuilding() {
        this.strata.screens().push(ui -> ui.absolutePanel().add(this.counted(ui.label("Ana")), 10, 10));
        this.render();
        final int[] first = this.image.pixels().clone();
        final long built = this.built();
        assertEquals(1, this.paints);
        for (int frame = 0; frame < 4; frame++) {
            this.render();
        }
        assertEquals(1, this.paints, "the screens were not painted again");
        assertEquals(built, this.built(), "no frame was built or copied");
        assertArrayEquals(first, this.image.pixels(), "every frame shows the same image");
    }

    @Test
    void newTextBuildsTheFrameAgain() {
        final Label[] label = new Label[1];
        this.strata.screens().push(ui -> ui.absolutePanel().add(label[0] = this.counted(ui.label("Ana")), 10, 10));
        this.render();
        this.render();
        final int[] before = this.image.pixels().clone();
        final long built = this.built();
        label[0].setText(Text.of("Ben"));
        this.render();
        assertEquals(1, this.paints, "a change inside the frame interval waits for it");
        this.renderLater();
        assertEquals(2, this.paints);
        assertEquals(built + 1, this.built());
        assertFalse(Arrays.equals(before, this.image.pixels()), "the new text shows");
        this.render();
        assertEquals(built + 1, this.built(), "and is then drawn again as it is");
    }

    @Test
    void aChangedPixelBufferBuildsTheFrameAgain() {
        final PixelBuffer pixels = PixelBuffer.of(4, 4);
        pixels.fill(0xFFFF0000);
        pixels.changed();
        this.strata.screens().push(ui -> ui.absolutePanel()
                .add(ui.image().setPixels(pixels).setWidth(20).setHeight(20), 10, 10));
        this.render();
        this.render();
        assertEquals(0xFFFF0000, this.image.argb(40, 40));
        final long built = this.built();
        pixels.fill(0xFF0000FF);
        pixels.changed();
        this.render();
        assertEquals(built + 1, this.built(), "the screens ask for nothing, but the buffer moved on");
        assertEquals(0xFF0000FF, this.image.argb(40, 40));
    }

    @Test
    void somethingMovingBuildsAtTheFrameRateNotTheApplicationsRate() {
        this.strata.screens().push(ui -> ui.absolutePanel().add(new Spinner(ui), 10, 10));
        this.render();
        final long built = this.built();
        for (int frame = 0; frame < 3; frame++) {
            this.render();
        }
        assertEquals(built, this.built(), "frames inside the interval repeat the last one");
        for (int frame = 0; frame < 3; frame++) {
            this.renderLater();
        }
        assertEquals(built + 3, this.built());
    }

    @Test
    void newBoundsOrSpritesBuildTheFrameAgain() {
        this.strata.screens().push(ui -> ui.absolutePanel().add(this.counted(ui.label("Ana")), 10, 10));
        this.render();
        this.strata.setBounds(0, 0, WIDTH / 2, HEIGHT / 2);
        this.render();
        assertEquals(2, this.paints);
        this.strata.setSprites(new VulkanSprites(RetainedFrameTest.class.getClassLoader()));
        this.render();
        assertEquals(3, this.paints);
        this.render();
        assertEquals(3, this.paints);
    }

    @Test
    void anUnchangedPreparedFrameIsEmptyAndBuildsNothing() {
        this.strata.screens().push(ui -> ui.absolutePanel().add(this.counted(ui.label("Ana")), 10, 10));
        final VulkanFrame first = this.strata.prepare();
        this.image.render(BACKGROUND, first::record);
        final long built = this.built();
        this.nanos[0] += 1_000_000L;
        final VulkanFrame second = this.strata.prepare();
        assertTrue(second.isEmpty());
        assertEquals(1, this.paints);
        assertEquals(built, this.built());
        this.strata.invalidate();
        final VulkanFrame lost = this.strata.prepare();
        assertEquals(1, this.paints, "a lost image needs the frame drawn, not built");
        assertEquals(1, lost.damage().size());
        this.image.render(BACKGROUND, lost::record);
        assertTrue(this.image.argb(0, 0) != 0, "the whole view is drawn");
    }

    /**
     * A component that moves with the clock, so it asks for the next frame each time it paints.
     */
    static final class Spinner extends CustomComponent<Spinner> {
        Spinner(final ComponentFactory factory) {
            super(factory);
        }

        @Override
        protected Size measure(final MeasureContext context, final float availableWidth, final float availableHeight) {
            return new Size(20, 20);
        }

        @Override
        protected void paint(final Painter painter, final Rect bounds) {
            painter.fill(bounds, 0xFF3A6EA5);
            this.repaintNextFrame();
        }
    }
}

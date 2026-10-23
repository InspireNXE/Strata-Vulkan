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
import org.inspirenxe.strata.api.component.CursorKind;
import org.inspirenxe.strata.api.control.button.Button;
import org.inspirenxe.strata.api.control.field.TextBox;
import org.inspirenxe.strata.api.extension.CustomComponent;
import org.inspirenxe.strata.api.extension.Gradient;
import org.inspirenxe.strata.api.extension.MeasureContext;
import org.inspirenxe.strata.api.extension.Painter;
import org.inspirenxe.strata.api.extension.Shape;
import org.inspirenxe.strata.api.extension.Stroke;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.input.Key;
import org.inspirenxe.strata.api.input.KeyAction;
import org.inspirenxe.strata.api.input.Modifiers;
import org.inspirenxe.strata.api.input.MouseButton;
import org.inspirenxe.strata.api.layout.Orientation;
import org.inspirenxe.strata.api.layout.StackPanel;
import org.inspirenxe.strata.api.text.TextSpan;
import org.inspirenxe.strata.api.text.TextStyle;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shows screens through {@link StrataVulkan} on a real device, with two frames in flight, and saves what it drew to
 * {@code build/snapshots} for a person to look at.
 */
class StrataVulkanTest {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 480;
    private static final float SCALE = 2f;

    private TestDevice device;
    private VulkanRenderer renderer;
    private StrataVulkan strata;
    private Offscreen image;

    @BeforeEach
    void open() {
        this.device = TestDevice.get();
        this.renderer = new VulkanRenderer(this.device.logical, this.device.queue, this.device.queueFamily,
                Offscreen.TARGET, 2);
        this.strata = new StrataVulkan(new VulkanPlatform(TestFonts.metrics()), this.renderer, Themes.flat(), SCALE);
        this.strata.setBounds(0, 0, WIDTH, HEIGHT);
        this.image = new Offscreen(this.device, this.renderer, WIDTH, HEIGHT);
    }

    @AfterEach
    void close() {
        if (this.image != null) {
            this.image.close();
            this.strata.close();
            this.renderer.close();
        }
    }

    @Test
    void drawsAScreenOfControls() {
        this.strata.screens().push(StrataVulkanTest::controls);
        this.render();
        this.image.save(Path.of("build", "snapshots", "controls.png"));

        int drawn = 0;
        for (int y = 0; y < HEIGHT; y += 4) {
            for (int x = 0; x < WIDTH; x += 4) {
                if (this.image.argb(x, y) != 0xFF1E1F22) {
                    drawn++;
                }
            }
        }
        assertTrue(drawn > 1000, "the screen drew its controls: " + drawn + " samples differ from the background");
    }

    @Test
    void clicksReachAButtonAndFramesKeepComing() {
        final List<String> presses = new ArrayList<>();
        final Button[] button = new Button[1];
        this.strata.screens().push(factory -> {
            button[0] = factory.button("Press me").onEvent(Button.PRESSED, event -> presses.add("pressed"));
            return factory.absolutePanel().add(button[0], 20, 20);
        });
        this.render();
        final Rect bounds = button[0].bounds();
        final float x = bounds.x() + bounds.width() / 2f;
        final float y = bounds.y() + bounds.height() / 2f;
        this.strata.screens().pointerMoved(x, y, Modifiers.NONE);
        this.strata.screens().pointerButton(x, y, MouseButton.LEFT, true, Modifiers.NONE);
        this.strata.screens().pointerButton(x, y, MouseButton.LEFT, false, Modifiers.NONE);
        // More frames than are in flight, so every slot is used again and retired resources go
        for (int frame = 0; frame < 6; frame++) {
            this.render();
        }
        assertEquals(List.of("pressed"), presses);
        final int face = this.image.argb(Math.round(x * SCALE), Math.round(y * SCALE));
        assertNotEquals(0xFF1E1F22, face, "the button's face is drawn under the pointer");
    }

    @Test
    void typingShowsNewGlyphsInLaterFrames() {
        final TextBox[] box = new TextBox[1];
        this.strata.screens().push(factory -> {
            box[0] = factory.textBox().setWidth(200);
            return factory.absolutePanel().add(box[0], 10, 10);
        });
        this.render();
        box[0].requestFocus();
        for (final char c : "Vulkan glyphs".toCharArray()) {
            this.strata.screens().charTyped(c, Modifiers.NONE);
            this.render();
        }
        this.strata.screens().key(Key.HOME, KeyAction.PRESS, Modifiers.NONE);
        this.render();
        assertEquals("Vulkan glyphs", box[0].text());
        this.image.save(Path.of("build", "snapshots", "text-box.png"));
    }

    @Test
    void reportsTheCursorAndTitleToItsWindow() {
        final List<CursorKind> cursors = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        this.strata.setWindow(new VulkanWindow() {
            @Override
            public boolean showsCursor(final CursorKind cursor) {
                return cursor == CursorKind.ARROW || cursor == CursorKind.TEXT;
            }

            @Override
            public void showCursor(final CursorKind cursor) {
                cursors.add(cursor);
            }

            @Override
            public void showTitle(final String title) {
                titles.add(title);
            }
        });
        final TextBox[] box = new TextBox[1];
        this.strata.screens().push(factory -> {
            box[0] = factory.textBox().setWidth(200);
            return factory.absolutePanel().add(box[0], 10, 10);
        });
        this.render();
        final Rect bounds = box[0].bounds();
        this.strata.screens().pointerMoved(bounds.x() + 5, bounds.y() + 5, Modifiers.NONE);
        this.render();
        this.render();
        assertEquals(List.of(CursorKind.ARROW, CursorKind.TEXT), cursors, "shown once per change");
        assertEquals(List.of(""), titles, "the untitled screen's title, once");
    }

    private void render() {
        // The Swing panel's background, which the flat theme is made for
        this.image.render(0xFF1E1F22, this.strata::render);
    }

    private static Component<?> controls(final ComponentFactory ui) {
        final PixelBuffer checker = PixelBuffer.of(8, 8);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                checker.set(x, y, (x + y) % 2 == 0 ? 0xFF3A6EA5 : 0xFFE8EEF5);
            }
        }
        checker.changed();
        final StackPanel row = ui.stackPanel().setOrientation(Orientation.HORIZONTAL).setSpacing(8)
                .add(ui.button("Save"))
                .add(ui.button("Disabled").setEnabled(false))
                .add(ui.checkBox("Remember me").setSelected(true))
                .add(ui.toggleSwitch().setSelected(true));
        final StackPanel fields = ui.stackPanel().setOrientation(Orientation.HORIZONTAL).setSpacing(8)
                .add(ui.textBox().setText("Ana's order").setWidth(120))
                .add(ui.slider().setValue(0.6).setWidth(100))
                .add(ui.progressBar().setValue(0.35).setWidth(80));
        return ui.stackPanel().setSpacing(6)
                .add(ui.label("Strata on Vulkan"))
                .add(row)
                .add(fields)
                .add(new Board(ui, 340, 120, (painter, bounds) -> {
                    painter.fill(bounds, 0xFF1E1F22);
                    final Rect card = new Rect(bounds.x() + 10, bounds.y() + 10, 90, 60);
                    painter.shadow(card, 6f, 8f, 0x80000000);
                    painter.fillRoundRect(card, 6f, 0xFFFFFFFF);
                    painter.strokeRect(card, 6f, Stroke.of(1f), 0xFF8A9099);
                    painter.fillGradient(new Rect(bounds.x() + 110, bounds.y() + 10, 90, 60),
                            new Gradient((float) Math.toRadians(30), List.of(new Gradient.Stop(0f, 0xFFE53935),
                                    new Gradient.Stop(0.5f, 0xFFFDD835), new Gradient.Stop(1f, 0xFF1E88E5))));
                    painter.fillEllipse(new Rect(bounds.x() + 210, bounds.y() + 10, 60, 60), 0xFF43A047);
                    painter.strokeArc(new Rect(bounds.x() + 280, bounds.y() + 10, 50, 50), (float) -Math.PI / 2f,
                            (float) Math.PI * 1.4f, new Stroke(6f, Stroke.Cap.ROUND, Stroke.Join.ROUND), 0xFFFFB300);
                    final float[] points = {bounds.x() + 10, bounds.y() + 110, bounds.x() + 60, bounds.y() + 80,
                            bounds.x() + 110, bounds.y() + 100, bounds.x() + 160, bounds.y() + 78};
                    painter.polyline(points, 4, new Stroke(2f, Stroke.Cap.ROUND, Stroke.Join.ROUND), 0xFF64B5F6);
                    final Shape star = StrataVulkanTest.star(bounds.x() + 200, bounds.y() + 95, 22f, 9f);
                    painter.fillShape(star, 0xFFFDD835);
                    painter.strokeShape(star, Stroke.of(1f), 0xFF5D4037);
                    painter.pixels(checker, new Rect(bounds.x() + 240, bounds.y() + 76, 40, 40), 0xFFFFFFFF);
                    painter.text(new TextSpan("Bold, shadowed", TextStyle.DEFAULT.withBold(true).withShadow(true)
                            .withColor(0xFFFFFFFF)), bounds.x() + 288, bounds.y() + 80);
                }));
    }

    private static Shape star(final float cx, final float cy, final float outer, final float inner) {
        final Shape.Builder builder = Shape.builder();
        for (int i = 0; i < 10; i++) {
            final double angle = -Math.PI / 2 + i * Math.PI / 5;
            final float radius = i % 2 == 0 ? outer : inner;
            final float x = cx + (float) (Math.cos(angle) * radius);
            final float y = cy + (float) (Math.sin(angle) * radius);
            if (i == 0) {
                builder.moveTo(x, y);
            } else {
                builder.lineTo(x, y);
            }
        }
        return builder.close().build();
    }

    /**
     * A component that paints with a function, as the gallery's drawing pages do.
     */
    private static final class Board extends CustomComponent<Board> {
        private final float width;
        private final float height;
        private final Draw draw;

        Board(final ComponentFactory ui, final float width, final float height, final Draw draw) {
            super(ui);
            this.width = width;
            this.height = height;
            this.draw = draw;
        }

        @Override
        protected Size measure(final MeasureContext context, final float availableWidth, final float availableHeight) {
            return new Size(this.width, this.height);
        }

        @Override
        protected void paint(final Painter painter, final Rect bounds) {
            this.draw.paint(painter, bounds);
        }
    }

    @FunctionalInterface
    private interface Draw {
        void paint(Painter painter, Rect bounds);
    }
}

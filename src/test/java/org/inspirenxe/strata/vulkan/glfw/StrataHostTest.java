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
package org.inspirenxe.strata.vulkan.glfw;

import org.inspirenxe.strata.api.component.CursorKind;
import org.inspirenxe.strata.api.control.button.Button;
import org.inspirenxe.strata.api.control.field.TextBox;
import org.inspirenxe.strata.api.event.input.InputEvents;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.vulkan.GlfwTestSupport;
import org.inspirenxe.strata.vulkan.StrataVulkan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Drives the host with the events and metrics an application's window thread would hand over, on a view that renders
 * offscreen. Needs no GLFW and no window. Skipped without a Vulkan device.
 */
class StrataHostTest {
    private static final Pattern GLFW_CALL = Pattern.compile("glfw[A-Z][A-Za-z]+");

    private GlfwTestSupport.View view;
    private FakeWindow window;

    @BeforeEach
    void open() {
        this.view = GlfwTestSupport.open(Themes.flat());
        this.window = new FakeWindow();
    }

    @AfterEach
    void close() {
        if (this.view != null) {
            this.view.close();
        }
    }

    @Test
    void sizesTheViewFromTheHostsMetricsAndReadsThemAgainOnlyWhenAsked() {
        this.window.metrics = new WindowMetrics(150, 75, 300, 150, 1.5f);
        final StrataVulkan strata = this.view.strata();
        try (StrataHost host = new StrataHost(strata, this.window, 2f)) {
            assertEquals(300, strata.width());
            assertEquals(150, strata.height());
            assertEquals(3f, strata.scale());

            this.window.metrics = new WindowMetrics(300, 150, 600, 300, 1f);
            assertEquals(300, strata.width(), "the view keeps the size it was given until it is told to read it again");

            host.updateSize();
            assertEquals(600, strata.width());
            assertEquals(300, strata.height());
            assertEquals(2f, strata.scale());

            host.setZoom(3f);
            assertEquals(3f, host.zoom());
            assertEquals(3f, strata.scale());
        }
    }

    @Test
    void refusesAZoomThatIsNotPositive() {
        assertThrows(IllegalArgumentException.class, () -> new StrataHost(this.view.strata(), this.window, 0f));
        assertThrows(IllegalArgumentException.class, () -> new StrataHost(this.view.strata(), this.window, Float.NaN));
    }

    @Test
    void pointerPositionsAreInTheWindowsCoordinatesNotTheFramebuffers() {
        this.window.metrics = new WindowMetrics(256, 128, 512, 256, 1f);
        final List<String> presses = new ArrayList<>();
        final Button[] button = new Button[1];
        this.view.strata().screens().push(factory -> {
            button[0] = factory.button("Press me").onEvent(Button.PRESSED, event -> presses.add("pressed"));
            return factory.absolutePanel().add(button[0], 120, 60);
        });
        try (StrataHost host = new StrataHost(this.view.strata(), this.window, 1f)) {
            this.view.render();
            host.updateSize();
            final Rect bounds = button[0].bounds();
            final double unitsX = bounds.x() + bounds.width() / 2f;
            final double unitsY = bounds.y() + bounds.height() / 2f;

            final double missX = unitsX / 2.0 + 60;
            host.cursorPos(missX, unitsY / 2.0, 0);
            assertFalse(host.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0, missX, unitsY / 2.0));
            host.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0, missX, unitsY / 2.0);

            host.cursorPos(unitsX / 2.0, unitsY / 2.0, 0);
            assertTrue(host.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0, unitsX / 2.0, unitsY / 2.0));
            host.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0, unitsX / 2.0, unitsY / 2.0);
            this.view.render();
        }
        assertEquals(List.of("pressed"), presses);
    }

    @Test
    void keysAndCharactersReachAFocusedTextBox() {
        final TextBox[] box = new TextBox[1];
        this.view.strata().screens().push(factory -> {
            box[0] = factory.textBox().setWidth(100);
            return factory.absolutePanel().add(box[0], 0, 0);
        });
        this.view.render();
        try (StrataHost host = new StrataHost(this.view.strata(), this.window, 1f)) {
            box[0].requestFocus();
            assertTrue(host.charTyped('h', 0));
            assertTrue(host.charTyped('i', 0));
            assertTrue(host.charTyped('j', 0));
            assertFalse(host.charTyped('\b', 0), "a control character is not text");
            assertTrue(host.key(GLFW_KEY_BACKSPACE, 0, GLFW_PRESS, 0));
            host.key(GLFW_KEY_BACKSPACE, 0, GLFW_RELEASE, 0);
            this.view.render();
        }
        assertEquals("hi", box[0].text());
    }

    @Test
    void theModifiersAnEventCarriesAreTheOnesTheScreensSee() {
        final List<Boolean> controls = new ArrayList<>();
        final TextBox[] box = new TextBox[1];
        this.view.strata().screens().push(factory -> {
            box[0] = factory.textBox().setWidth(100).onEvent(InputEvents.KEY, event -> controls.add(event.modifiers().control()));
            return factory.absolutePanel().add(box[0], 0, 0);
        });
        this.view.render();
        try (StrataHost host = new StrataHost(this.view.strata(), this.window, 1f)) {
            box[0].requestFocus();
            host.key(GLFW_KEY_BACKSPACE, 0, GLFW_PRESS, GLFW_MOD_CONTROL);
            host.key(GLFW_KEY_BACKSPACE, 0, GLFW_PRESS, 0);
        }
        assertEquals(List.of(true, false), controls);
    }

    @Test
    void theCursorAndTheTitleGoToTheHostWindow() {
        final TextBox[] box = new TextBox[1];
        this.view.strata().screens().push(factory -> {
            box[0] = factory.textBox().setWidth(200);
            return factory.absolutePanel().add(box[0], 10, 10);
        });
        this.window.metrics = new WindowMetrics(256, 128, 256, 128, 1f);
        try (StrataHost host = new StrataHost(this.view.strata(), this.window, 1f)) {
            this.view.render();
            final Rect bounds = box[0].bounds();
            host.cursorPos(bounds.x() + 5, bounds.y() + 5, 0);
            this.renderLater();
            this.renderLater();
        }
        assertEquals(List.of(CursorKind.ARROW, CursorKind.TEXT), this.window.cursors, "shown once per change");
        assertEquals(List.of(""), this.window.titles, "the untitled screen's title, once");
    }

    @Test
    void closingDisconnectsTheWindow() {
        final StrataHost host = new StrataHost(this.view.strata(), this.window, 1f);
        this.view.render();
        final int shown = this.window.cursors.size();
        host.close();
        host.close();
        this.view.strata().screens().pointerMoved(5, 5, org.inspirenxe.strata.api.input.Modifiers.NONE);
        this.view.render();
        assertEquals(shown, this.window.cursors.size());
    }

    @Test
    void makesNoCallToGlfw() throws IOException {
        for (final Class<?> type : List.of(StrataHost.class, WindowHost.class, WindowMetrics.class, GlfwInput.class)) {
            try (InputStream bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                final String contents = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(GLFW_CALL.matcher(contents).find(), type.getSimpleName() + " calls GLFW, which only the thread that owns the window may do");
            }
        }
    }

    private void renderLater() {
        try {
            Thread.sleep(50);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.view.render();
    }

    private static final class FakeWindow implements WindowHost {
        private final List<CursorKind> cursors = new ArrayList<>();
        private final List<String> titles = new ArrayList<>();
        private WindowMetrics metrics = new WindowMetrics(256, 128, 256, 128, 1f);

        @Override
        public WindowMetrics metrics() {
            return this.metrics;
        }

        @Override
        public boolean showsCursor(final CursorKind cursor) {
            return cursor == CursorKind.ARROW || cursor == CursorKind.TEXT;
        }

        @Override
        public void showCursor(final CursorKind cursor) {
            this.cursors.add(cursor);
        }

        @Override
        public void showTitle(final String title) {
            this.titles.add(title);
        }
    }
}

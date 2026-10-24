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
import org.inspirenxe.strata.api.control.field.TextBox;
import org.inspirenxe.strata.api.event.input.InputEvents;
import org.inspirenxe.strata.api.event.input.KeyEvent;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.vulkan.GlfwTestSupport;
import org.inspirenxe.strata.vulkan.StrataVulkan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

/**
 * Drives the bridge through a hidden GLFW window, calling the callbacks it installed the way GLFW would. Skipped where
 * GLFW cannot start, such as on a machine without a display, or without a Vulkan device.
 */
class StrataGlfwTest {
    private GlfwTestSupport.View view;
    private long window;

    @BeforeEach
    void open() {
        assumeTrue(glfwInit(), "GLFW cannot start here");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        this.window = glfwCreateWindow(400, 300, "Strata GLFW test", 0L, 0L);
        assumeTrue(this.window != 0L, "GLFW cannot open a window here");
        this.view = GlfwTestSupport.open(Themes.flat());
    }

    @AfterEach
    void close() {
        if (this.view != null) {
            this.view.close();
        }
        if (this.window != 0L) {
            glfwDestroyWindow(this.window);
        }
        glfwTerminate();
    }

    @Test
    void attachingSizesTheViewToTheFramebuffer() {
        final StrataVulkan strata = this.view.strata();
        try (StrataGlfw glfw = StrataGlfw.attach(this.window, strata, 2f);
             MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer width = stack.mallocInt(1);
            final IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(this.window, width, height);
            assertEquals(width.get(0), strata.width());
            assertEquals(height.get(0), strata.height());
            assertTrue(strata.scale() >= 2f, "the zoom times the content scale: " + strata.scale());
            assertEquals(2f, glfw.zoom());
            assertTrue(glfw.showsCursor(CursorKind.ARROW));
            assertTrue(glfw.showsCursor(CursorKind.TEXT));
            glfw.showCursor(CursorKind.TEXT);
            glfw.showCursor(CursorKind.ARROW);
        }
    }

    @Test
    void inputStrataDoesNotUseGoesOnToTheApplicationsCallback() {
        final List<Integer> heard = new ArrayList<>();
        final GLFWKeyCallback application = GLFWKeyCallback.create((window, key, scancode, action, mods) ->
                heard.add(key));
        glfwSetKeyCallback(this.window, application);
        final TextBox[] box = new TextBox[1];
        this.view.strata().screens().push(factory -> {
            box[0] = factory.textBox().setWidth(100);
            return factory.absolutePanel().add(box[0], 0, 0);
        });
        this.view.render();
        try (StrataGlfw glfw = StrataGlfw.attach(this.window, this.view.strata())) {
            // GLFW would call these; take them out to call them, then put them back
            final GLFWKeyCallback keys = glfwSetKeyCallback(this.window, null);
            final GLFWCharCallback chars = glfwSetCharCallback(this.window, null);
            keys.invoke(this.window, GLFW_KEY_F5, 0, GLFW_PRESS, 0);
            assertEquals(List.of(GLFW_KEY_F5), heard, "nothing has focus, so the key goes on");

            box[0].requestFocus();
            chars.invoke(this.window, 'h');
            chars.invoke(this.window, 'i');
            chars.invoke(this.window, '\b');
            keys.invoke(this.window, GLFW_KEY_A, 0, GLFW_PRESS, 0);
            this.view.render();
            assertEquals("hi", box[0].text(), "characters reach the text box, a control character does not");
            glfwSetKeyCallback(this.window, keys);
            glfwSetCharCallback(this.window, chars);
            assertTrue(glfw.zoom() > 0f);
        }
        final GLFWKeyCallback restored = glfwSetKeyCallback(this.window, null);
        assertEquals(application.address(), restored.address(), "closing puts the application's callback back");
        application.free();
    }

    @Test
    void releasesGoOnEvenWhenStrataUsesThem() {
        final List<String> heard = new ArrayList<>();
        final GLFWKeyCallback application = GLFWKeyCallback.create((window, key, scancode, action, mods) ->
                heard.add("key " + key + " " + action));
        final GLFWMouseButtonCallback buttons = GLFWMouseButtonCallback.create((window, button, action, mods) ->
                heard.add("button " + button + " " + action));
        glfwSetKeyCallback(this.window, application);
        glfwSetMouseButtonCallback(this.window, buttons);
        final TextBox[] box = new TextBox[1];
        this.view.strata().screens().push(factory -> {
            // A screen that takes every key while its box has focus, releases included
            box[0] = factory.textBox().setWidth(100).onEvent(InputEvents.KEY, KeyEvent::consume);
            return factory.absolutePanel().add(box[0], 0, 0);
        });
        this.view.render();
        try (StrataGlfw glfw = StrataGlfw.attach(this.window, this.view.strata())) {
            final GLFWKeyCallback keys = glfwSetKeyCallback(this.window, null);
            final GLFWMouseButtonCallback mouse = glfwSetMouseButtonCallback(this.window, null);
            // W goes down while nothing has focus, as when the application starts moving something
            keys.invoke(this.window, GLFW_KEY_W, 0, GLFW_PRESS, 0);
            box[0].requestFocus();
            // The screen takes W now, but its release still goes on
            keys.invoke(this.window, GLFW_KEY_W, 0, GLFW_REPEAT, 0);
            keys.invoke(this.window, GLFW_KEY_W, 0, GLFW_RELEASE, 0);
            // Whoever the press went to, the release goes on
            mouse.invoke(this.window, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0);
            mouse.invoke(this.window, GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0);
            glfwSetKeyCallback(this.window, keys);
            glfwSetMouseButtonCallback(this.window, mouse);
            assertTrue(glfw.zoom() > 0f);
        }
        assertEquals(List.of("key " + GLFW_KEY_W + " " + GLFW_PRESS, "key " + GLFW_KEY_W + " " + GLFW_RELEASE),
                heard.subList(0, 2), "the screen keeps the repeat, the release goes on");
        assertEquals("button " + GLFW_MOUSE_BUTTON_LEFT + " " + GLFW_RELEASE, heard.get(heard.size() - 1));
        glfwSetKeyCallback(this.window, null);
        glfwSetMouseButtonCallback(this.window, null);
        application.free();
        buttons.free();
    }
}

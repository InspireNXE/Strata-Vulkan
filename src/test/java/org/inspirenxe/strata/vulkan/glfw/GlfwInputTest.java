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

import org.inspirenxe.strata.api.input.Key;
import org.inspirenxe.strata.api.input.KeyAction;
import org.inspirenxe.strata.api.input.Modifier;
import org.inspirenxe.strata.api.input.Modifiers;
import org.inspirenxe.strata.api.input.MouseButton;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_0;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F12;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MENU;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_CAPS_LOCK;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_4;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

class GlfwInputTest {

    @Test
    void lettersDigitsAndFunctionKeysMapInOrder() {
        assertEquals(Key.A, GlfwInput.key(GLFW_KEY_A));
        assertEquals(Key.Z, GlfwInput.key(GLFW_KEY_Z));
        assertEquals(Key.NUM_0, GlfwInput.key(GLFW_KEY_0));
        assertEquals(Key.NUM_9, GlfwInput.key(GLFW_KEY_9));
        assertEquals(Key.F1, GlfwInput.key(GLFW_KEY_F1));
        assertEquals(Key.F12, GlfwInput.key(GLFW_KEY_F12));
        assertEquals(Key.KP_7, GlfwInput.key(GLFW_KEY_KP_7));
        assertEquals(Key.GRAVE, GlfwInput.key(GLFW_KEY_GRAVE_ACCENT));
        assertEquals(Key.ESCAPE, GlfwInput.key(GLFW_KEY_ESCAPE));
        assertEquals(Key.RIGHT_SUPER, GlfwInput.key(GLFW_KEY_RIGHT_SUPER));
        assertEquals(Key.MENU, GlfwInput.key(GLFW_KEY_MENU));
        assertEquals(Key.UNKNOWN, GlfwInput.key(GLFW_KEY_WORLD_1));
    }

    @Test
    void everyStrataKeyHasAGlfwKey() {
        final Set<Key> mapped = EnumSet.noneOf(Key.class);
        for (int code = 0; code <= GLFW_KEY_LAST; code++) {
            mapped.add(GlfwInput.key(code));
        }
        assertEquals(EnumSet.allOf(Key.class), mapped);
    }

    @Test
    void actionsButtonsAndModifiers() {
        assertEquals(KeyAction.PRESS, GlfwInput.action(GLFW_PRESS));
        assertEquals(KeyAction.REPEAT, GlfwInput.action(GLFW_REPEAT));
        assertEquals(KeyAction.RELEASE, GlfwInput.action(GLFW_RELEASE));
        assertEquals(MouseButton.LEFT, GlfwInput.button(GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(MouseButton.RIGHT, GlfwInput.button(GLFW_MOUSE_BUTTON_RIGHT));
        assertEquals(MouseButton.BACK, GlfwInput.button(GLFW_MOUSE_BUTTON_4));
        assertEquals(Modifiers.of(Modifier.SHIFT, Modifier.ALT, Modifier.SUPER),
                GlfwInput.modifiers(GLFW_MOD_SHIFT | GLFW_MOD_ALT | GLFW_MOD_SUPER | GLFW_MOD_CAPS_LOCK));
    }

    @Test
    void controlCharactersAreNotText() {
        assertTrue(GlfwInput.isText('a'));
        assertTrue(GlfwInput.isText(0x1F600));
        assertFalse(GlfwInput.isText('\n'));
        assertFalse(GlfwInput.isText(0x7F));
        assertFalse(GlfwInput.isText(0x85));
    }
}

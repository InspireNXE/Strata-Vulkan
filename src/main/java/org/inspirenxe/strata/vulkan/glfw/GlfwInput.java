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

import static org.lwjgl.glfw.GLFW.*;

/**
 * Translates GLFW's key, button and modifier codes to Strata's. GLFW names keys by where they sit on a US keyboard,
 * as Strata's {@link Key} does, so the mapping does not depend on the layout. Every method is a pure function and may
 * be called from any thread.
 */
public final class GlfwInput {

    private GlfwInput() {
    }

    /**
     * Translates a key.
     *
     * @param key a {@code GLFW_KEY_*} code
     * @return the key, or {@link Key#UNKNOWN} for codes Strata has no key for, such as {@code GLFW_KEY_WORLD_1}
     */
    public static Key key(final int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) {
            return Key.values()[Key.A.ordinal() + key - GLFW_KEY_A];
        }
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9) {
            return Key.values()[Key.NUM_0.ordinal() + key - GLFW_KEY_0];
        }
        if (key >= GLFW_KEY_F1 && key <= GLFW_KEY_F12) {
            return Key.values()[Key.F1.ordinal() + key - GLFW_KEY_F1];
        }
        if (key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9) {
            return Key.values()[Key.KP_0.ordinal() + key - GLFW_KEY_KP_0];
        }
        return switch (key) {
            case GLFW_KEY_SPACE -> Key.SPACE;
            case GLFW_KEY_APOSTROPHE -> Key.APOSTROPHE;
            case GLFW_KEY_COMMA -> Key.COMMA;
            case GLFW_KEY_MINUS -> Key.MINUS;
            case GLFW_KEY_PERIOD -> Key.PERIOD;
            case GLFW_KEY_SLASH -> Key.SLASH;
            case GLFW_KEY_SEMICOLON -> Key.SEMICOLON;
            case GLFW_KEY_EQUAL -> Key.EQUALS;
            case GLFW_KEY_LEFT_BRACKET -> Key.LEFT_BRACKET;
            case GLFW_KEY_BACKSLASH -> Key.BACKSLASH;
            case GLFW_KEY_RIGHT_BRACKET -> Key.RIGHT_BRACKET;
            case GLFW_KEY_GRAVE_ACCENT -> Key.GRAVE;
            case GLFW_KEY_ESCAPE -> Key.ESCAPE;
            case GLFW_KEY_ENTER -> Key.ENTER;
            case GLFW_KEY_TAB -> Key.TAB;
            case GLFW_KEY_BACKSPACE -> Key.BACKSPACE;
            case GLFW_KEY_INSERT -> Key.INSERT;
            case GLFW_KEY_DELETE -> Key.DELETE;
            case GLFW_KEY_RIGHT -> Key.RIGHT;
            case GLFW_KEY_LEFT -> Key.LEFT;
            case GLFW_KEY_DOWN -> Key.DOWN;
            case GLFW_KEY_UP -> Key.UP;
            case GLFW_KEY_PAGE_UP -> Key.PAGE_UP;
            case GLFW_KEY_PAGE_DOWN -> Key.PAGE_DOWN;
            case GLFW_KEY_HOME -> Key.HOME;
            case GLFW_KEY_END -> Key.END;
            case GLFW_KEY_CAPS_LOCK -> Key.CAPS_LOCK;
            case GLFW_KEY_SCROLL_LOCK -> Key.SCROLL_LOCK;
            case GLFW_KEY_NUM_LOCK -> Key.NUM_LOCK;
            case GLFW_KEY_PRINT_SCREEN -> Key.PRINT_SCREEN;
            case GLFW_KEY_PAUSE -> Key.PAUSE;
            case GLFW_KEY_KP_DECIMAL -> Key.KP_DECIMAL;
            case GLFW_KEY_KP_DIVIDE -> Key.KP_DIVIDE;
            case GLFW_KEY_KP_MULTIPLY -> Key.KP_MULTIPLY;
            case GLFW_KEY_KP_SUBTRACT -> Key.KP_SUBTRACT;
            case GLFW_KEY_KP_ADD -> Key.KP_ADD;
            case GLFW_KEY_KP_ENTER -> Key.KP_ENTER;
            case GLFW_KEY_KP_EQUAL -> Key.KP_EQUALS;
            case GLFW_KEY_LEFT_SHIFT -> Key.LEFT_SHIFT;
            case GLFW_KEY_LEFT_CONTROL -> Key.LEFT_CONTROL;
            case GLFW_KEY_LEFT_ALT -> Key.LEFT_ALT;
            case GLFW_KEY_LEFT_SUPER -> Key.LEFT_SUPER;
            case GLFW_KEY_RIGHT_SHIFT -> Key.RIGHT_SHIFT;
            case GLFW_KEY_RIGHT_CONTROL -> Key.RIGHT_CONTROL;
            case GLFW_KEY_RIGHT_ALT -> Key.RIGHT_ALT;
            case GLFW_KEY_RIGHT_SUPER -> Key.RIGHT_SUPER;
            case GLFW_KEY_MENU -> Key.MENU;
            default -> Key.UNKNOWN;
        };
    }

    /**
     * Translates a key action.
     *
     * @param action {@code GLFW_PRESS}, {@code GLFW_REPEAT} or {@code GLFW_RELEASE}
     * @return the action; any other code counts as a press
     */
    public static KeyAction action(final int action) {
        return switch (action) {
            case GLFW_RELEASE -> KeyAction.RELEASE;
            case GLFW_REPEAT -> KeyAction.REPEAT;
            default -> KeyAction.PRESS;
        };
    }

    /**
     * Translates a mouse button. GLFW's buttons 4 and 5 are the back and forward side buttons.
     *
     * @param button a {@code GLFW_MOUSE_BUTTON_*} code
     * @return the button, or {@link MouseButton#UNKNOWN} for buttons past the fifth
     */
    public static MouseButton button(final int button) {
        return switch (button) {
            case GLFW_MOUSE_BUTTON_LEFT -> MouseButton.LEFT;
            case GLFW_MOUSE_BUTTON_RIGHT -> MouseButton.RIGHT;
            case GLFW_MOUSE_BUTTON_MIDDLE -> MouseButton.MIDDLE;
            case GLFW_MOUSE_BUTTON_4 -> MouseButton.BACK;
            case GLFW_MOUSE_BUTTON_5 -> MouseButton.FORWARD;
            default -> MouseButton.UNKNOWN;
        };
    }

    /**
     * Translates modifier bits. Caps Lock and Num Lock are not modifiers in Strata and are left out.
     *
     * @param mods {@code GLFW_MOD_*} bits
     * @return the modifiers
     */
    public static Modifiers modifiers(final int mods) {
        Modifiers modifiers = Modifiers.NONE;
        if ((mods & GLFW_MOD_SHIFT) != 0) {
            modifiers = modifiers.with(Modifier.SHIFT);
        }
        if ((mods & GLFW_MOD_CONTROL) != 0) {
            modifiers = modifiers.with(Modifier.CONTROL);
        }
        if ((mods & GLFW_MOD_ALT) != 0) {
            modifiers = modifiers.with(Modifier.ALT);
        }
        if ((mods & GLFW_MOD_SUPER) != 0) {
            modifiers = modifiers.with(Modifier.SUPER);
        }
        return modifiers;
    }

    /**
     * Returns whether a typed code point is text, not a control character, so it may go to
     * {@code InputSink.charTyped}.
     *
     * @param codePoint the code point GLFW's character callback gave
     * @return false for C0 and C1 control characters and for values that are not code points
     */
    public static boolean isText(final int codePoint) {
        return Character.isValidCodePoint(codePoint) && codePoint >= 0x20 && !(codePoint >= 0x7F && codePoint <= 0x9F);
    }
}

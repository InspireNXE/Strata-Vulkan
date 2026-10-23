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

import org.inspirenxe.strata.spi.Clipboard;

import java.util.Optional;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

/**
 * The system clipboard through GLFW, which holds text only. GLFW reads and writes it on the main thread, so text boxes
 * must copy and paste on the thread that runs GLFW's events, as they do when that thread renders the views.
 */
public final class GlfwClipboard implements Clipboard {
    private final long window;

    /**
     * Creates the clipboard.
     *
     * @param window the GLFW window the clipboard is read through, as GLFW asks for on some systems
     */
    public GlfwClipboard(final long window) {
        this.window = window;
    }

    /**
     * Reads the clipboard's text.
     *
     * @return the text, or empty when the clipboard is empty, holds no text, or cannot be read
     */
    @Override
    public Optional<String> get() {
        try {
            return Optional.ofNullable(glfwGetClipboardString(this.window));
        } catch (final RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Puts text on the clipboard. A failure is ignored.
     *
     * @param text the text
     */
    @Override
    public void set(final String text) {
        try {
            glfwSetClipboardString(this.window, text);
        } catch (final RuntimeException ignored) {
            // The clipboard keeps what it had
        }
    }
}

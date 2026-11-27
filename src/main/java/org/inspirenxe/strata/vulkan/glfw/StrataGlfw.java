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
import org.inspirenxe.strata.spi.ScreenStack;
import org.inspirenxe.strata.vulkan.StrataVulkan;
import org.inspirenxe.strata.vulkan.VulkanPlatform;
import org.inspirenxe.strata.vulkan.VulkanTextMetrics;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorEnterCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowCloseCallback;
import org.lwjgl.glfw.GLFWWindowContentScaleCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.GLFW_ARROW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CROSSHAIR_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_IBEAM_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_NOT_ALLOWED_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_POINTING_HAND_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_ALL_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_EW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_NESW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_NS_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_NWSE_CURSOR;
import static org.lwjgl.glfw.GLFW.glfwCreateStandardCursor;
import static org.lwjgl.glfw.GLFW.glfwDestroyCursor;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetKey;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursor;
import static org.lwjgl.glfw.GLFW.glfwSetCursorEnterCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetDropCallback;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowCloseCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowContentScaleCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;

/**
 * Connects a {@link StrataVulkan} view to a GLFW window: input, size, display scale, cursor, title and close button.
 *
 * <pre>{@code
 * VulkanPlatform platform = StrataGlfw.platform(window, VulkanTextMetrics.defaults()).build();
 * StrataVulkan strata = new StrataVulkan(platform, renderer, Themes.flat(), 1f);
 * StrataGlfw glfw = StrataGlfw.attach(window, strata, 2f);
 * glfw.manageWindow("My tool");
 * }</pre>
 *
 * <p>{@link #attach} installs GLFW callbacks for the keyboard, characters, pointer, buttons, wheel, dropped files,
 * framebuffer size, content scale and window focus, and keeps the callbacks the window had. Input Strata uses stops
 * there; input it does not use goes on to the earlier callback, so the application still hears clicks and keys outside
 * Strata's screens. Key and button releases always go on, used or not, so an application that tracks held keys sees a
 * key it saw pressed come up even when a screen opened in between. Pointer movement, the pointer leaving, size and
 * scale always go on too, and so does window focus, which also makes the screens active or inactive through
 * {@code activeChanged}, so focus leaves while the window is in the background. Install the application's own callbacks
 * before attaching, or they replace Strata's. An application that keeps its own callbacks uses {@link #create} instead
 * and forwards events itself through {@link #key}, {@link #charTyped}, {@link #cursorPos}, {@link #mouseButton},
 * {@link #scroll}, {@link #cursorEnter} and {@link #drop}, calls {@link #updateSize()} when the framebuffer or the
 * content scale changes, and calls {@code screens().activeChanged} when the window gains or loses focus; it should
 * handle releases itself whatever those methods return.</p>
 *
 * <p>The view covers the whole framebuffer. Its scale is the zoom times the window's content scale, so a zoom of 2 on
 * a display scaled to 150% draws three framebuffer pixels per UI unit, and pointer positions are converted from the
 * window's coordinates to the framebuffer's and then to UI units. GLFW sends key repeats, so build the platform with
 * {@link #platform}, which also gives it GLFW's clipboard.</p>
 *
 * <p>GLFW runs on the main thread, so use this class there, the thread that also renders the view. An application
 * that renders on another thread uses {@link StrataHost}, which makes no GLFW call. This class turns each event into
 * one for a {@link StrataHost} by reading the pointer position and the held modifiers from the window, and answers as
 * that host's {@link WindowHost}.</p>
 */
public final class StrataGlfw implements WindowHost, AutoCloseable {
    private final long window;
    private final StrataVulkan strata;
    private final StrataHost host;
    private final Map<CursorKind, Long> cursors;
    private final List<Runnable> restore;
    private String windowTitle;
    private boolean managed;

    private StrataGlfw(final long window, final StrataVulkan strata, final float zoom) {
        this.window = window;
        this.strata = Objects.requireNonNull(strata, "strata");
        this.cursors = new EnumMap<>(CursorKind.class);
        this.restore = new ArrayList<>();
        this.host = new StrataHost(strata, this, zoom);
    }

    /**
     * Starts a platform for a GLFW window: GLFW's clipboard, and key repeats from GLFW.
     *
     * @param window the window
     * @param textMetrics the fonts to measure and draw text with
     * @return the builder, to change more settings or build
     */
    public static VulkanPlatform.Builder platform(final long window, final VulkanTextMetrics textMetrics) {
        return VulkanPlatform.builder(textMetrics).clipboard(new GlfwClipboard(window)).sendsKeyRepeat(true);
    }

    /**
     * Connects a view to a window at a zoom of 1 and installs the callbacks.
     *
     * @param window the window
     * @param strata the view, which then covers the window's framebuffer
     * @return the connection; close it to remove the callbacks
     */
    public static StrataGlfw attach(final long window, final StrataVulkan strata) {
        return StrataGlfw.attach(window, strata, 1f);
    }

    /**
     * Connects a view to a window and installs the callbacks.
     *
     * @param window the window
     * @param strata the view, which then covers the window's framebuffer
     * @param zoom UI units per display unit: the view's scale is this times the window's content scale
     * @return the connection; close it to remove the callbacks
     * @throws IllegalArgumentException if the zoom is not finite and positive
     */
    public static StrataGlfw attach(final long window, final StrataVulkan strata, final float zoom) {
        final StrataGlfw glfw = new StrataGlfw(window, strata, zoom);
        glfw.install();
        return glfw;
    }

    /**
     * Connects a view to a window without installing callbacks, for an application that forwards GLFW's events
     * itself.
     *
     * @param window the window
     * @param strata the view, which then covers the window's framebuffer
     * @param zoom UI units per display unit: the view's scale is this times the window's content scale
     * @return the connection
     * @throws IllegalArgumentException if the zoom is not finite and positive
     */
    public static StrataGlfw create(final long window, final StrataVulkan strata, final float zoom) {
        return new StrataGlfw(window, strata, zoom);
    }

    /**
     * Lets the view run the window's title and close button: the title follows the top screen's, and the close button
     * asks the screens with {@link ScreenStack#requestClose()} and closes the window only when every screen agreed.
     * Takes effect for the close button only after {@link #attach}, which installs the close callback.
     *
     * @param title the title the window shows while the top screen has none or no screen is open
     */
    public void manageWindow(final String title) {
        this.windowTitle = Objects.requireNonNull(title, "title");
        this.managed = true;
        glfwSetWindowTitle(this.window, title);
    }

    /**
     * Returns the zoom.
     *
     * @return UI units per display unit
     */
    public float zoom() {
        return this.host.zoom();
    }

    /**
     * Changes the zoom, and with it the view's scale.
     *
     * @param zoom UI units per display unit, positive
     * @throws IllegalArgumentException if the zoom is not finite and positive
     */
    public void setZoom(final float zoom) {
        this.host.setZoom(zoom);
    }

    /**
     * Reads the window's framebuffer size and content scale again and gives them to the view. The installed callbacks
     * call it when either changes.
     */
    public void updateSize() {
        this.host.updateSize();
    }

    /**
     * Reads the window's size, framebuffer size and content scale from GLFW.
     *
     * @return the metrics
     */
    @Override
    public WindowMetrics metrics() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer windowWidth = stack.mallocInt(1);
            final IntBuffer windowHeight = stack.mallocInt(1);
            glfwGetWindowSize(this.window, windowWidth, windowHeight);
            final IntBuffer width = stack.mallocInt(1);
            final IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(this.window, width, height);
            final FloatBuffer scaleX = stack.mallocFloat(1);
            final FloatBuffer scaleY = stack.mallocFloat(1);
            glfwGetWindowContentScale(this.window, scaleX, scaleY);
            return new WindowMetrics(windowWidth.get(0), windowHeight.get(0), width.get(0), height.get(0), scaleX.get(0));
        }
    }

    // ------------------------------------------------------------------
    // Forwarding, for applications that keep GLFW's callbacks
    // ------------------------------------------------------------------

    /**
     * Sends a key event, as GLFW's key callback gives it.
     *
     * @param key the {@code GLFW_KEY_*} code
     * @param scancode the scancode, unused
     * @param action {@code GLFW_PRESS}, {@code GLFW_REPEAT} or {@code GLFW_RELEASE}
     * @param mods the {@code GLFW_MOD_*} bits
     * @return true if Strata used the key
     */
    public boolean key(final int key, final int scancode, final int action, final int mods) {
        return this.host.key(key, scancode, action, mods);
    }

    /**
     * Sends a typed character, as GLFW's character callback gives it. Control characters are dropped.
     *
     * @param codePoint the code point
     * @return true if Strata used the character
     */
    public boolean charTyped(final int codePoint) {
        return this.host.charTyped(codePoint, this.heldModifiers());
    }

    /**
     * Sends the pointer's position, as GLFW's cursor position callback gives it.
     *
     * @param x the position in the window's coordinates
     * @param y the position in the window's coordinates
     * @return true if the pointer is over Strata's screens or dragging in them
     */
    public boolean cursorPos(final double x, final double y) {
        return this.host.cursorPos(x, y, this.heldModifiers());
    }

    /**
     * Sends a mouse button's press or release, as GLFW's mouse button callback gives it, at the pointer's current
     * position.
     *
     * @param button the {@code GLFW_MOUSE_BUTTON_*} code
     * @param action {@code GLFW_PRESS} or {@code GLFW_RELEASE}
     * @param mods the {@code GLFW_MOD_*} bits
     * @return true if Strata used the button
     */
    public boolean mouseButton(final int button, final int action, final int mods) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final DoubleBuffer x = stack.mallocDouble(1);
            final DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(this.window, x, y);
            return this.host.mouseButton(button, action, mods, x.get(0), y.get(0));
        }
    }

    /**
     * Sends wheel or trackpad scrolling, as GLFW's scroll callback gives it, at the pointer's current position. Shift
     * with the vertical wheel scrolls horizontally. A whole number of notches counts as a wheel, anything else as a
     * precise device.
     *
     * @param xOffset the horizontal scroll, positive toward the left
     * @param yOffset the vertical scroll, positive away from the user
     * @return true if Strata used the scroll
     */
    public boolean scroll(final double xOffset, final double yOffset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final DoubleBuffer x = stack.mallocDouble(1);
            final DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(this.window, x, y);
            return this.host.scroll(xOffset, yOffset, x.get(0), y.get(0), this.heldModifiers());
        }
    }

    /**
     * Sends the pointer entering or leaving the window, as GLFW's cursor enter callback gives it.
     *
     * @param entered true when the pointer entered
     */
    public void cursorEnter(final boolean entered) {
        this.host.cursorEnter(entered);
    }

    /**
     * Sends files dropped on the window, as GLFW's drop callback gives them, at the pointer's current position.
     *
     * @param paths the files' paths
     * @return true if a component took the drop
     */
    public boolean drop(final List<Path> paths) {
        if (paths.isEmpty()) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final DoubleBuffer x = stack.mallocDouble(1);
            final DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(this.window, x, y);
            return this.host.drop(paths, x.get(0), y.get(0), this.heldModifiers());
        }
    }

    // ------------------------------------------------------------------
    // The window
    // ------------------------------------------------------------------

    @Override
    public boolean showsCursor(final CursorKind cursor) {
        return cursor == CursorKind.ARROW || this.cursor(cursor) != 0L;
    }

    @Override
    public void showCursor(final CursorKind cursor) {
        // The arrow is GLFW's default cursor
        glfwSetCursor(this.window, cursor == CursorKind.ARROW ? 0L : this.cursor(cursor));
    }

    @Override
    public void showTitle(final String title) {
        if (this.managed) {
            glfwSetWindowTitle(this.window, title.isEmpty() ? this.windowTitle : title);
        }
    }

    /**
     * Puts back the callbacks the window had before {@link #attach}, destroys the cursors this made, and disconnects
     * the view from the window. Calling it again does nothing.
     */
    @Override
    public void close() {
        for (int i = this.restore.size() - 1; i >= 0; i--) {
            this.restore.get(i).run();
        }
        this.restore.clear();
        glfwSetCursor(this.window, 0L);
        for (final long cursor : this.cursors.values()) {
            if (cursor != 0L) {
                glfwDestroyCursor(cursor);
            }
        }
        this.cursors.clear();
        this.host.close();
    }

    private void install() {
        final GLFWKeyCallback key = glfwSetKeyCallback(this.window, null);
        this.callback(GLFWKeyCallback.create((window, code, scancode, action, mods) -> {
            if ((!this.key(code, scancode, action, mods) || action == GLFW_RELEASE) && key != null) {
                key.invoke(window, code, scancode, action, mods);
            }
        }), callback -> glfwSetKeyCallback(this.window, callback), key);

        final GLFWCharCallback character = glfwSetCharCallback(this.window, null);
        this.callback(GLFWCharCallback.create((window, codePoint) -> {
            if (!this.charTyped(codePoint) && character != null) {
                character.invoke(window, codePoint);
            }
        }), callback -> glfwSetCharCallback(this.window, callback), character);

        final GLFWCursorPosCallback position = glfwSetCursorPosCallback(this.window, null);
        this.callback(GLFWCursorPosCallback.create((window, x, y) -> {
            this.cursorPos(x, y);
            if (position != null) {
                position.invoke(window, x, y);
            }
        }), callback -> glfwSetCursorPosCallback(this.window, callback), position);

        final GLFWMouseButtonCallback button = glfwSetMouseButtonCallback(this.window, null);
        this.callback(GLFWMouseButtonCallback.create((window, code, action, mods) -> {
            if ((!this.mouseButton(code, action, mods) || action == GLFW_RELEASE) && button != null) {
                button.invoke(window, code, action, mods);
            }
        }), callback -> glfwSetMouseButtonCallback(this.window, callback), button);

        final GLFWScrollCallback scroll = glfwSetScrollCallback(this.window, null);
        this.callback(GLFWScrollCallback.create((window, x, y) -> {
            if (!this.scroll(x, y) && scroll != null) {
                scroll.invoke(window, x, y);
            }
        }), callback -> glfwSetScrollCallback(this.window, callback), scroll);

        final GLFWCursorEnterCallback enter = glfwSetCursorEnterCallback(this.window, null);
        this.callback(GLFWCursorEnterCallback.create((window, entered) -> {
            this.cursorEnter(entered);
            if (enter != null) {
                enter.invoke(window, entered);
            }
        }), callback -> glfwSetCursorEnterCallback(this.window, callback), enter);

        final GLFWDropCallback drop = glfwSetDropCallback(this.window, null);
        this.callback(GLFWDropCallback.create((window, count, names) -> {
            final List<Path> paths = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                paths.add(Path.of(GLFWDropCallback.getName(names, i)));
            }
            if (!this.drop(paths) && drop != null) {
                drop.invoke(window, count, names);
            }
        }), callback -> glfwSetDropCallback(this.window, callback), drop);

        final GLFWFramebufferSizeCallback size = glfwSetFramebufferSizeCallback(this.window, null);
        this.callback(GLFWFramebufferSizeCallback.create((window, width, height) -> {
            this.updateSize();
            if (size != null) {
                size.invoke(window, width, height);
            }
        }), callback -> glfwSetFramebufferSizeCallback(this.window, callback), size);

        final GLFWWindowContentScaleCallback scale = glfwSetWindowContentScaleCallback(this.window, null);
        this.callback(GLFWWindowContentScaleCallback.create((window, x, y) -> {
            this.updateSize();
            if (scale != null) {
                scale.invoke(window, x, y);
            }
        }), callback -> glfwSetWindowContentScaleCallback(this.window, callback), scale);

        final GLFWWindowFocusCallback focus = glfwSetWindowFocusCallback(this.window, null);
        this.callback(GLFWWindowFocusCallback.create((window, focused) -> {
            this.host.focusChanged(focused);
            if (focus != null) {
                focus.invoke(window, focused);
            }
        }), callback -> glfwSetWindowFocusCallback(this.window, callback), focus);

        final GLFWWindowCloseCallback close = glfwSetWindowCloseCallback(this.window, null);
        this.callback(GLFWWindowCloseCallback.create(window -> {
            if (this.managed && !this.strata.screens().requestClose()) {
                glfwSetWindowShouldClose(window, false);
                return;
            }
            if (close != null) {
                close.invoke(window);
            }
        }), callback -> glfwSetWindowCloseCallback(this.window, callback), close);
    }

    /**
     * Installs one callback and remembers how to put the earlier one back and free this one.
     */
    private <C extends Callback> void callback(final C installed, final Consumer<C> set, final C previous) {
        set.accept(installed);
        this.restore.add(() -> {
            set.accept(previous);
            installed.free();
        });
    }

    private long cursor(final CursorKind cursor) {
        return this.cursors.computeIfAbsent(cursor, kind -> {
            final int shape = switch (kind) {
                case ARROW -> GLFW_ARROW_CURSOR;
                case TEXT -> GLFW_IBEAM_CURSOR;
                case HAND -> GLFW_POINTING_HAND_CURSOR;
                case CROSSHAIR -> GLFW_CROSSHAIR_CURSOR;
                case RESIZE_HORIZONTAL -> GLFW_RESIZE_EW_CURSOR;
                case RESIZE_VERTICAL -> GLFW_RESIZE_NS_CURSOR;
                case RESIZE_ALL -> GLFW_RESIZE_ALL_CURSOR;
                case RESIZE_DIAGONAL_DOWN -> GLFW_RESIZE_NWSE_CURSOR;
                case RESIZE_DIAGONAL_UP -> GLFW_RESIZE_NESW_CURSOR;
                case NOT_ALLOWED -> GLFW_NOT_ALLOWED_CURSOR;
                default -> 0;
            };
            // GLFW reports an error and returns null for a shape the system lacks; the kind's fallback is used
            return shape == 0 ? 0L : glfwCreateStandardCursor(shape);
        });
    }

    /**
     * Reads the modifiers held now, as {@code GLFW_MOD_*} bits, for events whose callback does not carry them.
     */
    private int heldModifiers() {
        int mods = 0;
        if (this.held(GLFW_KEY_LEFT_SHIFT) || this.held(GLFW_KEY_RIGHT_SHIFT)) {
            mods |= GLFW_MOD_SHIFT;
        }
        if (this.held(GLFW_KEY_LEFT_CONTROL) || this.held(GLFW_KEY_RIGHT_CONTROL)) {
            mods |= GLFW_MOD_CONTROL;
        }
        if (this.held(GLFW_KEY_LEFT_ALT) || this.held(GLFW_KEY_RIGHT_ALT)) {
            mods |= GLFW_MOD_ALT;
        }
        if (this.held(GLFW_KEY_LEFT_SUPER) || this.held(GLFW_KEY_RIGHT_SUPER)) {
            mods |= GLFW_MOD_SUPER;
        }
        return mods;
    }

    private boolean held(final int key) {
        return glfwGetKey(this.window, key) != GLFW_RELEASE;
    }
}

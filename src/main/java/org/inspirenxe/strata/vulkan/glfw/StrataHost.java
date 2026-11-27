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
import org.inspirenxe.strata.api.input.Modifiers;
import org.inspirenxe.strata.api.input.MouseButton;
import org.inspirenxe.strata.api.input.ScrollKind;
import org.inspirenxe.strata.api.transfer.Transfer;
import org.inspirenxe.strata.spi.Clipboard;
import org.inspirenxe.strata.vulkan.StrataVulkan;
import org.inspirenxe.strata.vulkan.VulkanPlatform;
import org.inspirenxe.strata.vulkan.VulkanTextMetrics;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * Connects a {@link StrataVulkan} view to a GLFW window that another thread runs. GLFW allows most of its calls only on
 * the main thread, so an application that renders on a different thread cannot use {@link StrataGlfw}, which calls GLFW
 * from the thread it is used on. This class makes no GLFW call. The application's window thread receives GLFW's events
 * and gives each one, with the state it carried, to the thread that renders the view, which passes it here. The window
 * itself is answered for through a {@link WindowHost}.
 *
 * <pre>{@code
 * VulkanPlatform platform = StrataHost.platform(clipboard, VulkanTextMetrics.defaults()).build();
 * StrataVulkan strata = new StrataVulkan(platform, renderer, Themes.flat(), 1f);
 * StrataHost host = new StrataHost(strata, windowHost, 2f);
 * // on the thread that renders, for each queued event, then once a frame:
 * host.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0, x, y);
 * host.updateSize();
 * }</pre>
 *
 * <p>Every event method takes what GLFW's callback gave plus what the window's thread read when the event happened and
 * the callback does not carry: the held modifiers and, for a button, scroll or drop, the pointer's position. A queued
 * event so reaches the view as it would have when it happened, and no method reads the window. They return whether
 * Strata used the event, as {@link StrataGlfw}'s do, so the application can pass on what Strata did not use. Releases
 * are for the application to handle whatever the methods return.</p>
 *
 * <p>The view covers the whole framebuffer. Its scale is the zoom times the window's content scale, so a zoom of 2 on
 * a display scaled to 150% draws three framebuffer pixels per UI unit, and pointer positions are converted from the
 * window's coordinates to the framebuffer's and then to UI units. Call {@link #updateSize()} when the window's size or
 * scale changes. Use all methods on one thread, the one that renders the view.</p>
 */
public final class StrataHost implements AutoCloseable {
    private final StrataVulkan strata;
    private final WindowHost host;
    private float zoom;
    private WindowMetrics metrics;

    /**
     * Connects a view to a window and sizes the view to the window's current metrics.
     *
     * @param strata the view, which then covers the window's framebuffer
     * @param host the window
     * @param zoom UI units per display unit: the view's scale is this times the window's content scale
     * @throws IllegalArgumentException if the zoom is not finite and positive
     */
    public StrataHost(final StrataVulkan strata, final WindowHost host, final float zoom) {
        StrataHost.checkZoom(zoom);
        this.strata = Objects.requireNonNull(strata, "strata");
        this.host = Objects.requireNonNull(host, "host");
        this.zoom = zoom;
        strata.setWindow(host);
        this.updateSize();
    }

    /**
     * Starts a platform for a window this class serves: the given clipboard, and key repeats, which GLFW sends.
     *
     * @param clipboard the clipboard text boxes copy and paste through; it is used on the thread that renders, so it
     *         must reach the window's thread itself when the system needs that
     * @param textMetrics the fonts to measure and draw text with
     * @return the builder, to change more settings or build
     */
    public static VulkanPlatform.Builder platform(final Clipboard clipboard, final VulkanTextMetrics textMetrics) {
        return VulkanPlatform.builder(textMetrics).clipboard(clipboard).sendsKeyRepeat(true);
    }

    /**
     * Returns the zoom.
     *
     * @return UI units per display unit
     */
    public float zoom() {
        return this.zoom;
    }

    /**
     * Changes the zoom, and with it the view's scale.
     *
     * @param zoom UI units per display unit, positive
     * @throws IllegalArgumentException if the zoom is not finite and positive
     */
    public void setZoom(final float zoom) {
        StrataHost.checkZoom(zoom);
        this.zoom = zoom;
        this.updateSize();
    }

    /**
     * Reads the window's metrics again and gives the view the framebuffer's size and the scale. Pointer events use the
     * window's size as of the last call.
     */
    public void updateSize() {
        this.metrics = Objects.requireNonNull(this.host.metrics(), "metrics");
        this.strata.setScale(this.zoom * this.metrics.scale());
        this.strata.setBounds(0, 0, Math.max(0, this.metrics.framebufferWidth()), Math.max(0, this.metrics.framebufferHeight()));
    }

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
        final Key strataKey = GlfwInput.key(key);
        return this.strata.screens().key(strataKey, GlfwInput.action(action), GlfwInput.modifiers(mods));
    }

    /**
     * Sends a typed character, as GLFW's character callback gives it. Control characters are dropped.
     *
     * @param codePoint the code point
     * @param mods the {@code GLFW_MOD_*} bits held when it was typed
     * @return true if Strata used the character
     */
    public boolean charTyped(final int codePoint, final int mods) {
        return GlfwInput.isText(codePoint) && this.strata.screens().charTyped(codePoint, GlfwInput.modifiers(mods));
    }

    /**
     * Sends the pointer's position, as GLFW's cursor position callback gives it.
     *
     * @param x the position in the window's coordinates
     * @param y the position in the window's coordinates
     * @param mods the {@code GLFW_MOD_*} bits held when it moved
     * @return true if the pointer is over Strata's screens or dragging in them
     */
    public boolean cursorPos(final double x, final double y, final int mods) {
        return this.strata.screens().pointerMoved(this.unitX(x), this.unitY(y), GlfwInput.modifiers(mods));
    }

    /**
     * Sends a mouse button's press or release, as GLFW's mouse button callback gives it.
     *
     * @param button the {@code GLFW_MOUSE_BUTTON_*} code
     * @param action {@code GLFW_PRESS} or {@code GLFW_RELEASE}
     * @param mods the {@code GLFW_MOD_*} bits
     * @param x the pointer's position in the window's coordinates when the button changed
     * @param y the pointer's position in the window's coordinates when the button changed
     * @return true if Strata used the button
     */
    public boolean mouseButton(final int button, final int action, final int mods, final double x, final double y) {
        final MouseButton strataButton = GlfwInput.button(button);
        return this.strata.screens().pointerButton(this.unitX(x), this.unitY(y), strataButton, action == GLFW_PRESS,
                GlfwInput.modifiers(mods));
    }

    /**
     * Sends wheel or trackpad scrolling, as GLFW's scroll callback gives it. Shift with the vertical wheel scrolls
     * horizontally. A whole number of notches counts as a wheel, anything else as a precise device.
     *
     * @param xOffset the horizontal scroll, positive toward the left
     * @param yOffset the vertical scroll, positive away from the user
     * @param x the pointer's position in the window's coordinates when it scrolled
     * @param y the pointer's position in the window's coordinates when it scrolled
     * @param mods the {@code GLFW_MOD_*} bits held when it scrolled
     * @return true if Strata used the scroll
     */
    public boolean scroll(final double xOffset, final double yOffset, final double x, final double y, final int mods) {
        final Modifiers modifiers = GlfwInput.modifiers(mods);
        final boolean whole = xOffset == Math.rint(xOffset) && yOffset == Math.rint(yOffset);
        final ScrollKind kind = whole ? ScrollKind.WHEEL : ScrollKind.PRECISE;
        float scrollX = (float) xOffset;
        float scrollY = (float) yOffset;
        if (modifiers.shift() && scrollX == 0f) {
            scrollX = scrollY;
            scrollY = 0f;
        }
        return this.strata.screens().scroll(this.unitX(x), this.unitY(y), scrollX, scrollY, kind, modifiers);
    }

    /**
     * Sends the pointer entering or leaving the window, as GLFW's cursor enter callback gives it.
     *
     * @param entered true when the pointer entered
     */
    public void cursorEnter(final boolean entered) {
        if (!entered) {
            this.strata.screens().pointerLeft();
        }
    }

    /**
     * Sends files dropped on the window, as GLFW's drop callback gives them.
     *
     * @param paths the files' paths
     * @param x the pointer's position in the window's coordinates when they were dropped
     * @param y the pointer's position in the window's coordinates when they were dropped
     * @param mods the {@code GLFW_MOD_*} bits held when they were dropped
     * @return true if a component took the drop
     */
    public boolean drop(final List<Path> paths, final double x, final double y, final int mods) {
        if (paths.isEmpty()) {
            return false;
        }
        return this.strata.screens().dropped(this.unitX(x), this.unitY(y), Transfer.files(paths), GlfwInput.modifiers(mods)).isPresent();
    }

    /**
     * Tells the screens that the window gained or lost focus, which makes them active or inactive.
     *
     * @param focused true when the window gained focus
     */
    public void focusChanged(final boolean focused) {
        this.strata.screens().activeChanged(focused);
    }

    /**
     * Disconnects the view from the window. Calling it again does nothing.
     */
    @Override
    public void close() {
        this.strata.setWindow(null);
    }

    /**
     * Converts a horizontal position in the window's coordinates to UI units in the view.
     */
    private float unitX(final double x) {
        return this.strata.unitX(x * this.metrics.framebufferRatioX());
    }

    private float unitY(final double y) {
        return this.strata.unitY(y * this.metrics.framebufferRatioY());
    }

    private static void checkZoom(final float zoom) {
        if (!(zoom > 0f) || Float.isInfinite(zoom)) {
            throw new IllegalArgumentException("Zoom must be finite and positive: " + zoom);
        }
    }
}

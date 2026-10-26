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

import org.inspirenxe.strata.api.screen.Screen;
import org.inspirenxe.strata.api.theme.Theme;
import org.inspirenxe.strata.api.theme.ThemeRef;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.inspirenxe.strata.core.StrataCore;
import org.inspirenxe.strata.spi.ScreenStack;

import java.util.function.Consumer;

/**
 * Renders one frame of a screen to pixels with Vulkan, on a device of the adapter's own with no window, as
 * {@code SwingSnapshot} does with Java2D. Tools and tests use it to see what a screen looks like on this adapter.
 *
 * <p>The first snapshot finds a device with graphics and dynamic rendering, a discrete one where there is one, and
 * keeps it, with a renderer, for the life of the process. A machine without a Vulkan driver cannot take snapshots;
 * {@link #isSupported()} says whether this one can. Mesa's software rasterizer counts.</p>
 *
 * <p>The screen runs on the calling thread, which becomes its stack's UI thread, so a snapshot can be taken from a
 * test or a command line tool's main thread. Snapshots are taken one at a time.</p>
 */
public final class VulkanSnapshot {
    private static VulkanRenderer renderer;

    private VulkanSnapshot() {
    }

    /**
     * Returns whether this machine can take snapshots, making the adapter's device if it was not made yet.
     *
     * @return true when a Vulkan device can draw offscreen
     */
    public static boolean isSupported() {
        try {
            HeadlessDevice.get();
            return true;
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    /**
     * Opens a screen on a fresh screen stack, draws one frame, and closes it again. Text uses
     * {@link VulkanTextMetrics#defaults()}, and sprites load from this class's class loader. Any pixel the screen
     * does not draw stays transparent. The screen's {@code closed} callback runs before this method returns.
     *
     * @param screen the screen to render
     * @param theme the theme to render it with
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param scale pixels per UI unit, which is also the screen's density; the screen gets {@code width / scale} by
     *     {@code height / scale} units
     * @return the frame, ARGB with alpha not premultiplied
     * @throws IllegalStateException if this machine cannot take snapshots, or has no system font
     * @throws org.inspirenxe.strata.api.theme.ThemeException if the theme lacks a style key the screen's tree
     *     requires
     */
    public static PixelBuffer render(final Screen screen, final Theme theme, final int width, final int height,
                                     final float scale) {
        return VulkanSnapshot.render(screen, theme, width, height, scale, screens -> { });
    }

    /**
     * Opens a screen on a fresh screen stack, feeds it input, draws one frame, and closes it again. Use it to
     * picture a screen in a state that takes input to reach, such as with a drop-down open or the pointer over a
     * tooltip. Tooltips show without the usual delay. Everything else is as in
     * {@link #render(Screen, Theme, int, int, float)}.
     *
     * @param screen the screen to render
     * @param theme the theme to render it with
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param scale pixels per UI unit
     * @param input sends input to the stack, in UI units, after the screen opens and before the frame is drawn
     * @return the frame, ARGB with alpha not premultiplied
     * @throws IllegalStateException if this machine cannot take snapshots, or has no system font
     * @throws org.inspirenxe.strata.api.theme.ThemeException if the theme lacks a style key the screen's tree
     *     requires
     */
    public static PixelBuffer render(final Screen screen, final Theme theme, final int width, final int height,
                                     final float scale, final Consumer<ScreenStack> input) {
        return VulkanSnapshot.render(new VulkanPlatform(VulkanTextMetrics.defaults()), screen, theme, width, height,
                scale, new VulkanSprites(VulkanSnapshot.class.getClassLoader()), input);
    }

    /**
     * Opens a screen on a fresh screen stack of a given platform, feeds it input, draws one frame with the given
     * sprites, and closes it again. Text is measured and drawn with the platform's text metrics. Everything else is as
     * in {@link #render(Screen, Theme, int, int, float, Consumer)}.
     *
     * @param platform the platform, for its fonts, theme source and the rest
     * @param screen the screen to render
     * @param theme the theme to render it with
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param scale pixels per UI unit
     * @param sprites where sprite images come from
     * @param input sends input to the stack, in UI units, after the screen opens and before the frame is drawn
     * @return the frame, ARGB with alpha not premultiplied
     * @throws IllegalStateException if this machine cannot take snapshots
     * @throws org.inspirenxe.strata.api.theme.ThemeException if the theme lacks a style key the screen's tree
     *     requires
     */
    public static PixelBuffer render(final VulkanPlatform platform, final Screen screen, final Theme theme,
                                     final int width, final int height, final float scale,
                                     final VulkanSprites sprites, final Consumer<ScreenStack> input) {
        final HeadlessDevice device = HeadlessDevice.get();
        return VulkanSnapshot.draw(device, platform, StrataCore.createScreenStack(platform, theme), screen, width,
                height, scale, sprites, input);
    }

    /**
     * Opens a screen on a fresh screen stack whose theme is resolved from the platform's theme source, feeds it
     * input, draws one frame, and closes it again, so a snapshot shows what theme files make of a theme. Everything
     * else is as in {@link #render(VulkanPlatform, Screen, Theme, int, int, float, VulkanSprites, Consumer)}.
     *
     * @param platform the platform, for its fonts, theme source and the rest
     * @param screen the screen to render
     * @param theme the theme to resolve
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param scale pixels per UI unit
     * @param sprites where sprite images come from
     * @param input sends input to the stack, in UI units, after the screen opens and before the frame is drawn
     * @return the frame, ARGB with alpha not premultiplied
     * @throws IllegalStateException if this machine cannot take snapshots
     * @throws org.inspirenxe.strata.api.theme.ThemeException if the theme cannot be resolved or lacks a style key
     *     the screen's tree requires
     */
    public static PixelBuffer render(final VulkanPlatform platform, final Screen screen, final ThemeRef theme,
                                     final int width, final int height, final float scale,
                                     final VulkanSprites sprites, final Consumer<ScreenStack> input) {
        final HeadlessDevice device = HeadlessDevice.get();
        return VulkanSnapshot.draw(device, platform, StrataCore.createScreenStack(platform, theme), screen, width,
                height, scale, sprites, input);
    }

    private static synchronized PixelBuffer draw(final HeadlessDevice device, final VulkanPlatform platform,
                                                 final ScreenStack screens, final Screen screen, final int width,
                                                 final int height, final float scale, final VulkanSprites sprites,
                                                 final Consumer<ScreenStack> input) {
        if (width <= 0 || height <= 0 || !(scale > 0f)) {
            throw new IllegalArgumentException("A snapshot needs a positive size and scale: " + width + " by " + height
                    + " at " + scale);
        }
        if (renderer == null) {
            renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily, OffscreenImage.TARGET, 1);
        }
        // One frame is drawn, so a tooltip the input reaches shows without waiting
        screens.setTooltipDelay(0L);
        screens.resize(width / scale, height / scale);
        screens.setPixelsPerUnit(scale);
        screens.setSpriteSizes(sprites::size);
        final OffscreenImage image = new OffscreenImage(device, renderer, width, height);
        final FrameStream frames = renderer.openStream();
        try {
            screens.push(screen);
            input.accept(screens);
            final VulkanCanvas canvas = frames.begin(0, 0, width, height, scale);
            canvas.configure(platform.textMetrics(), sprites, null);
            screens.render(canvas);
            image.render(frames::end);
            final PixelBuffer pixels = PixelBuffer.of(width, height);
            System.arraycopy(image.pixels(), 0, pixels.array(), 0, width * height);
            return pixels.changed();
        } finally {
            screens.clear();
            device.idle();
            frames.close();
            image.close();
        }
    }
}

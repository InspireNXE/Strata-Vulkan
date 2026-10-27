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

import org.inspirenxe.strata.api.component.CursorKind;
import org.inspirenxe.strata.api.text.Text;
import org.inspirenxe.strata.api.theme.Theme;
import org.inspirenxe.strata.core.StrataCore;
import org.inspirenxe.strata.spi.ScreenStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A stack of Strata screens drawn with Vulkan into a pass the application records. Make one per window or area that
 * shows screens, open screens on {@link #screens()} or mark it as the default so {@code Strata.screens()} opens them
 * here, send it input, and call {@link #render} inside the application's pass every frame.
 *
 * <pre>{@code
 * VulkanRenderer renderer = new VulkanRenderer(device, queue, queueFamily,
 *         VulkanTarget.dynamic(swapchainFormat), framesInFlight);
 * StrataVulkan strata = new StrataVulkan(new VulkanPlatform(VulkanTextMetrics.defaults()), renderer,
 *         Themes.flat(), 2f);
 * strata.setDefault();
 * strata.setBounds(0, 0, framebufferWidth, framebufferHeight);
 * Strata.screens().open(new SettingsScreen());
 *
 * // Every frame, between vkCmdBeginRendering and vkCmdEndRendering:
 * strata.render(commandBuffer);
 * }</pre>
 *
 * <p>How Vulkan maps onto Strata:</p>
 * <ul>
 *     <li>The view fills a rectangle of the framebuffer, its bounds, set with {@link #setBounds}. One UI unit is
 *     {@link #scale()} framebuffer pixels, which is also the screens' density, the device pixels per unit that
 *     components and sprites read. A window on a display scaled to 150% with a zoom of 2 has a scale of 3.</li>
 *     <li>Input goes to {@link #screens()}, which takes it in UI units: subtract the bounds' corner from a position in
 *     framebuffer pixels and divide by the scale, or use {@link #unitX} and {@link #unitY}. The GLFW bridge does all
 *     of this for a GLFW window.</li>
 *     <li>{@link #render} lays out and draws the screens, submits the uploads they need on the renderer's queue, and
 *     records the draws into the command buffer. Strata leaves the pipeline, viewport, scissor, vertex buffer,
 *     descriptor sets and push constants bound as it last set them, so set your own again after it.</li>
 *     <li>A game renders every frame anyway, and the view draws its screens at most {@code screens().frameRate()}
 *     times a second, repeating the last frame in between. An application that renders only when something changes renders when
 *     {@code screens().needsFrame()} is true, or when the platform's clock reaches {@link #nextFrameNanos()}, and can
 *     be told when the first becomes true with {@code screens().setFrameRequester}. Either way it renders no sooner
 *     than {@code screens().frameIntervalNanos()} after its last render began, so the screens are drawn at most
 *     {@code screens().frameRate()} times a second, 30 unless set.</li>
 *     <li>The repeating tasks screens ask for with {@code ScreenHandle.every} run from the platform's
 *     {@link VulkanScheduler}, unless the application gave the platform its own scheduler: {@link #render} and
 *     {@link #prepare} run the tasks that are due before they draw.</li>
 *     <li>An application that keeps its image between frames, such as a tool or an editor, can redraw only what
 *     changed: {@link #prepare()} draws the screens and compares the frame with the last one, and the
 *     {@link VulkanFrame} it returns says which rectangles changed and records only the draws there. A frame with
 *     nothing changed needs no recording and no present. Call {@link #invalidate()} when the kept image is lost, such
 *     as after the swapchain was made again. An application that clears its image every frame uses {@link #render},
 *     which records every draw.</li>
 * </ul>
 *
 * <p>Use it on the render thread only, the thread that renders it and sends it input.</p>
 */
public final class StrataVulkan implements AutoCloseable {
    private static StrataVulkan defaultView;

    private final VulkanPlatform platform;
    private final VulkanRenderer renderer;
    private final VulkanTextMetrics textMetrics;
    private final ScreenStack screens;
    private final FrameStream frames;
    private VulkanSprites sprites;
    private VulkanImage backdrop;
    // The version of the sprites the last frame drew, or -1 when it must not be drawn again
    private long drawnSprites = -1L;
    // The version of the sprites whose sizes the screens have, so a cleared set sizes images again
    private long sizedSprites;
    private VulkanWindow window;
    private Predicate<CursorKind> windowCursors;
    private Text shownTitleText;
    private String shownTitle;
    private CursorKind shownCursor;
    private float scale;
    private int x;
    private int y;
    private int width;
    private int height;
    private long prepared;
    private long drawnNanos;
    private boolean drawnOnce;
    private boolean closed;

    /**
     * Creates a view with an empty screen stack. Sprites load from this class's class loader with
     * {@link SpriteResolver#DEFAULT}.
     *
     * @param platform the platform; its text metrics also draw the text
     * @param renderer the renderer, for the target of the passes this view draws in
     * @param theme the theme for every screen
     * @param scale framebuffer pixels per UI unit, positive
     * @throws IllegalArgumentException if {@code scale} is not finite and positive
     */
    public StrataVulkan(final VulkanPlatform platform, final VulkanRenderer renderer, final Theme theme,
                        final float scale) {
        this(platform, renderer, theme, scale, new VulkanSprites(StrataVulkan.class.getClassLoader()));
    }

    /**
     * Creates a view with an empty screen stack.
     *
     * @param platform the platform; its text metrics also draw the text
     * @param renderer the renderer, for the target of the passes this view draws in
     * @param theme the theme for every screen
     * @param scale framebuffer pixels per UI unit, positive
     * @param sprites where sprite images come from
     * @throws IllegalArgumentException if {@code scale} is not finite and positive
     */
    public StrataVulkan(final VulkanPlatform platform, final VulkanRenderer renderer, final Theme theme,
                        final float scale, final VulkanSprites sprites) {
        StrataVulkan.checkScale(scale);
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.platform = platform;
        this.textMetrics = platform.textMetrics();
        this.sprites = Objects.requireNonNull(sprites, "sprites");
        this.screens = StrataCore.createScreenStack(platform, theme);
        this.frames = renderer.openStream();
        this.scale = scale;
        this.screens.setPixelsPerUnit(scale);
        this.giveSpriteSizes();
    }

    /**
     * Returns the screens this view shows, which is also its {@link org.inspirenxe.strata.api.screen.ScreenController}
     * and the {@link org.inspirenxe.strata.spi.InputSink} to send input to. Push or open a screen to show it; the top
     * screen gets the input.
     *
     * @return the screen stack
     */
    public ScreenStack screens() {
        return this.screens;
    }

    /**
     * Marks this view as the default, so that {@code Strata.screens()} returns its {@link #screens()} and screens
     * opened through it show here. A later call on another view moves the mark.
     */
    public void setDefault() {
        synchronized (StrataVulkan.class) {
            defaultView = this;
            StrataCore.setScreenController(this.screens);
        }
    }

    /**
     * Returns whether this view is the default, the one {@code Strata.screens()} opens screens on.
     *
     * @return true if {@link #setDefault()} marked this view last and it is still open
     */
    public boolean isDefault() {
        synchronized (StrataVulkan.class) {
            return defaultView == this;
        }
    }

    /**
     * Changes where sprite images come from, starting with the next frame. The screens stay open.
     *
     * @param sprites where sprite images come from
     */
    public void setSprites(final VulkanSprites sprites) {
        this.sprites = Objects.requireNonNull(sprites, "sprites");
        this.drawnSprites = -1L;
        this.giveSpriteSizes();
    }

    private void giveSpriteSizes() {
        final VulkanSprites given = this.sprites;
        this.sizedSprites = given.version();
        this.screens.setSpriteSizes(given::size);
    }

    /**
     * Gives the view the image that frosted glass blurs: what the application drew behind the screens, such as its
     * scene rendered to an image. Each {@code Paint.backdrop} and {@code Painter.backdrop} blurs the part of it
     * under its rectangle, then tints it, so panels look like glass over a scene that moves. The image covers the
     * whole view, stretched to it, and is sampled every frame with its own sampler, linear unless the application
     * chose another; frames where it changed are damage where a backdrop shows, as for any image of the
     * application's. Without an image, a backdrop draws its tint alone. Close the image only after giving the view
     * another or {@code null}.
     *
     * <p>The blur reads the image as its {@link ImageEncoding} says, then brings the result into the space the target
     * blends in, so the glass is as bright as the scene under it. A scene tone-mapped into a {@code _SRGB} image is
     * {@link ImageEncoding#LINEAR}, since the device decodes it as it samples; make it with
     * {@link VulkanRenderer#image(long, ImageEncoding, float, float)} or the form that also takes a sampler, or the
     * glass comes out too dark on an sRGB target. The forms without an encoding take {@link ImageEncoding#ENCODED},
     * which suits a {@code _UNORM} image holding sRGB bytes.</p>
     *
     * @param backdrop an image this view's renderer made with {@link VulkanRenderer#image}, or {@code null} for none
     */
    public void setBackdrop(final VulkanImage backdrop) {
        if (backdrop != this.backdrop) {
            this.backdrop = backdrop;
            // The last frame's draws sampled the image before, so it is not drawn again as it was
            this.drawnSprites = -1L;
        }
    }

    /**
     * Returns the image frosted glass blurs.
     *
     * @return the image, or empty when none is set
     */
    public Optional<VulkanImage> backdrop() {
        return Optional.ofNullable(this.backdrop);
    }

    /**
     * Gives the view the window it sits in, for its cursor and title.
     *
     * @param window the window, or {@code null} for none; the canvas then accepts the SPI's default cursors and
     *               shows none of them
     */
    public void setWindow(final VulkanWindow window) {
        this.window = window;
        this.windowCursors = window == null ? null : window::showsCursor;
        this.shownTitleText = null;
        this.shownTitle = null;
        this.shownCursor = null;
        // The cursors the window shows can change what the screens draw
        this.drawnSprites = -1L;
    }

    /**
     * Returns the number of framebuffer pixels per UI unit.
     *
     * @return the scale
     */
    public float scale() {
        return this.scale;
    }

    /**
     * Changes the number of framebuffer pixels per UI unit, such as when the user zooms or the window moves to a
     * display with another scale. The screens get the view's size in the new units and the new density, and lay out
     * again.
     *
     * @param scale framebuffer pixels per UI unit, positive
     * @throws IllegalArgumentException if {@code scale} is not finite and positive
     */
    public void setScale(final float scale) {
        StrataVulkan.checkScale(scale);
        if (scale != this.scale) {
            this.scale = scale;
            this.frames.invalidate();
            this.screens.setPixelsPerUnit(scale);
            this.resizeScreens();
        }
    }

    /**
     * Sets the rectangle of the framebuffer the view fills, such as the whole framebuffer after it was resized.
     *
     * @param x the left edge in framebuffer pixels
     * @param y the top edge in framebuffer pixels
     * @param width the width in framebuffer pixels
     * @param height the height in framebuffer pixels
     * @throws IllegalArgumentException if a size is negative
     */
    public void setBounds(final int x, final int y, final int width, final int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("A view's size must not be negative: " + width + " by " + height);
        }
        if (x != this.x || y != this.y || width != this.width || height != this.height) {
            this.frames.invalidate();
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.resizeScreens();
    }

    /**
     * Returns the left edge of the view's bounds.
     *
     * @return the left edge in framebuffer pixels
     */
    public int x() {
        return this.x;
    }

    /**
     * Returns the top edge of the view's bounds.
     *
     * @return the top edge in framebuffer pixels
     */
    public int y() {
        return this.y;
    }

    /**
     * Returns the width of the view's bounds.
     *
     * @return the width in framebuffer pixels
     */
    public int width() {
        return this.width;
    }

    /**
     * Returns the height of the view's bounds.
     *
     * @return the height in framebuffer pixels
     */
    public int height() {
        return this.height;
    }

    /**
     * Converts a horizontal position in framebuffer pixels to UI units in the view, for input.
     *
     * @param framebufferX the position in framebuffer pixels
     * @return the position in UI units from the view's left edge
     */
    public float unitX(final double framebufferX) {
        return (float) ((framebufferX - this.x) / this.scale);
    }

    /**
     * Converts a vertical position in framebuffer pixels to UI units in the view, for input.
     *
     * @param framebufferY the position in framebuffer pixels
     * @return the position in UI units from the view's top edge
     */
    public float unitY(final double framebufferY) {
        return (float) ((framebufferY - this.y) / this.scale);
    }

    /**
     * Draws a frame of the screens into a command buffer. Call it once per frame, on the render thread, while the
     * command buffer is recording inside a render pass or dynamic rendering of the renderer's
     * {@linkplain VulkanRenderer#target() target}, and submit the command buffer to the renderer's queue afterwards.
     * Before it records anything, it submits the textures and glyphs the frame needs to that queue.
     *
     * <p>Every draw is recorded, for an image cleared before the pass. The next {@link #prepare()} damages the whole
     * view. A view with an empty area lays out and runs its screens' timers but records nothing.</p>
     *
     * <p>When the screens would draw what they drew for the last frame, and nothing the view drew with changed, the
     * view records the last frame's draws again without drawing the screens, copying anything or submitting
     * uploads. The screens are also drawn at most {@code screens().frameRate()} times a second, 30 unless set: until
     * {@code screens().frameIntervalNanos()} has passed since the last frame they drew, the view records that frame
     * again, so something that changes or moves every frame is drawn at the frame rate, not at the application's.
     * New bounds, a new scale or new sprites are drawn at once.</p>
     *
     * @param commands the command buffer
     * @throws IllegalStateException if the view or its renderer is closed, or Vulkan fails
     */
    public void render(final VkCommandBuffer commands) {
        this.checkOpen();
        this.runDueTasks();
        this.prepared++;
        final long now = this.platform.clock().nanoTime();
        if (this.repeats() || this.paced(now)) {
            this.frames.repeat(commands);
            this.showCursorAndTitle(this.frames.canvas());
            return;
        }
        final VulkanCanvas canvas = this.begin();
        boolean drawn = false;
        try {
            this.screens.render(canvas);
            drawn = true;
        } finally {
            this.frames.end(commands, drawn);
        }
        this.drawnNanos = now;
        this.drawnOnce = true;
        this.drawnSprites = this.sprites.version();
        this.showCursorAndTitle(canvas);
    }

    /**
     * Draws a frame of the screens and compares it with the view's last frame, for an application that keeps its
     * image between frames. Call it on the render thread, in place of {@link #render}, before the pass begins: the
     * frame's damage decides the render area, or that there is nothing to record. When something changed, it submits
     * the textures and glyphs the frame needs to the renderer's queue; record the frame with
     * {@link VulkanFrame#record} into a pass over the kept image, loaded rather than cleared, and submit that to the
     * same queue. A frame where nothing changed takes nothing from the device and counts as no frame, so the
     * application may skip recording, submitting and presenting.
     *
     * <p>The first frame, the frame after the bounds or the scale change or after {@link #invalidate()}, and the
     * frame after a {@link #render} damage the whole view. When the screens would draw what they drew for the last
     * frame, and nothing the view drew with changed, the view does not draw them, and the frame's damage covers only
     * what shows an application's {@link VulkanImage}, unless the whole view is damaged.</p>
     *
     * @return the frame
     * @throws IllegalStateException if the view or its renderer is closed, or Vulkan fails
     */
    public VulkanFrame prepare() {
        this.checkOpen();
        this.runDueTasks();
        this.prepared++;
        final int rects;
        if (this.repeats()) {
            rects = this.frames.repeatDamage();
            this.showCursorAndTitle(this.frames.canvas());
        } else {
            final VulkanCanvas canvas = this.begin();
            try {
                this.screens.render(canvas);
                rects = this.frames.damage();
            } catch (final RuntimeException | Error e) {
                this.frames.abandon();
                this.frames.invalidate();
                throw e;
            }
            this.frames.kept();
            this.drawnSprites = this.sprites.version();
            this.showCursorAndTitle(canvas);
        }
        if (rects == 0) {
            this.frames.abandon();
            return new VulkanFrame(this, this.prepared, List.of());
        }
        this.frames.commit();
        final List<PixelRect> damage = new ArrayList<>(rects);
        for (int i = 0; i < rects; i++) {
            final int x0 = this.frames.damageRect(i, 0);
            final int y0 = this.frames.damageRect(i, 1);
            damage.add(new PixelRect(x0, y0, this.frames.damageRect(i, 2) - x0, this.frames.damageRect(i, 3) - y0));
        }
        return new VulkanFrame(this, this.prepared, damage);
    }

    /**
     * Returns when the view next needs a frame without input: the earlier of the screens'
     * {@code nextFrameNanos()} and the time the platform's {@link VulkanScheduler} next has a task due. An application
     * that renders on demand waits for it, by the platform's clock. A platform given another scheduler counts the
     * screens only.
     *
     * @return the time in nanoseconds, or {@link Long#MAX_VALUE} when nothing is timed
     */
    public long nextFrameNanos() {
        final long screens = this.screens.nextFrameNanos();
        return this.platform.scheduler() instanceof final VulkanScheduler scheduler
                ? Math.min(screens, scheduler.nextDueNanos())
                : screens;
    }

    /**
     * Returns whether the last frame can be drawn again in place of drawing the screens: they would draw the same,
     * and neither the view's bounds, scale, sprites or window nor anything the frame sampled changed since.
     */
    private boolean paced(final long now) {
        return this.drawnOnce && now - this.drawnNanos < this.screens.frameIntervalNanos()
                && this.drawnSprites == this.sprites.version()
                && this.frames.canRepeat(this.x, this.y, this.width, this.height, this.scale);
    }

    private boolean repeats() {
        return this.drawnSprites == this.sprites.version()
                && this.frames.canRepeat(this.x, this.y, this.width, this.height, this.scale)
                && !this.screens.needsRender(this.platform.clock().nanoTime());
    }

    private void runDueTasks() {
        if (this.platform.scheduler() instanceof final VulkanScheduler scheduler) {
            scheduler.runDue();
        }
        if (this.sizedSprites != this.sprites.version()) {
            this.giveSpriteSizes();
        }
    }

    /**
     * Makes the next {@link #prepare()} damage the whole view, because the application's kept image no longer holds
     * the last frame, such as after the swapchain or the image was made again.
     */
    public void invalidate() {
        this.frames.invalidate();
    }

    /**
     * Records a prepared frame, for {@link VulkanFrame#record}.
     */
    void record(final VulkanFrame frame, final long number, final VkCommandBuffer commands) {
        this.checkOpen();
        if (number != this.prepared) {
            throw new IllegalStateException("The view drew another frame after this one");
        }
        if (!frame.isEmpty()) {
            this.frames.record(commands, true);
        }
    }

    /**
     * Returns the view's stream of frames, which tests read.
     */
    FrameStream stream() {
        return this.frames;
    }

    private VulkanCanvas begin() {
        final VulkanCanvas canvas = this.frames.begin(this.x, this.y, this.width, this.height, this.scale);
        canvas.configure(this.textMetrics, this.sprites, this.windowCursors);
        canvas.setBackdrop(this.backdrop);
        return canvas;
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("The view is closed");
        }
    }

    private void showCursorAndTitle(final VulkanCanvas canvas) {
        final VulkanWindow window = this.window;
        if (window != null) {
            final CursorKind cursor = canvas.requestedCursor() == null ? CursorKind.ARROW : canvas.requestedCursor();
            if (cursor != this.shownCursor) {
                this.shownCursor = cursor;
                window.showCursor(cursor);
            }
            // Text is immutable, so the title the last frame showed needs no new string
            final Text text = this.screens.title();
            if (text != this.shownTitleText) {
                this.shownTitleText = text;
                final String title = text.plain();
                if (!title.equals(this.shownTitle)) {
                    this.shownTitle = title;
                    window.showTitle(title);
                }
            }
        }
    }

    /**
     * Closes every screen without asking them, and frees the view's buffers. Wait for the device to finish the frames
     * this view recorded first. If the view was the default, {@code Strata.screens()} throws afterwards. Calling it
     * again does nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.screens.clear();
        this.frames.close();
        synchronized (StrataVulkan.class) {
            if (defaultView == this) {
                defaultView = null;
                StrataCore.clearScreenController();
            }
        }
    }

    private void resizeScreens() {
        this.screens.resize(this.width / this.scale, this.height / this.scale);
    }

    private static void checkScale(final float scale) {
        if (!(scale > 0f) || Float.isInfinite(scale)) {
            throw new IllegalArgumentException("Scale must be finite and positive: " + scale);
        }
    }
}

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
import org.inspirenxe.strata.api.extension.Gradient;
import org.inspirenxe.strata.api.extension.Shape;
import org.inspirenxe.strata.api.extension.Stroke;
import org.inspirenxe.strata.api.geometry.Insets;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.text.TextSpan;
import org.inspirenxe.strata.api.text.TextStyle;
import org.inspirenxe.strata.api.theme.SpriteId;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.inspirenxe.strata.api.visual.Sampling;
import org.inspirenxe.strata.api.visual.Visual;
import org.inspirenxe.strata.spi.Canvas;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The canvas a {@link FrameStream} draws a frame with. It records triangles into a {@link DrawList} in device pixels;
 * nothing reaches the device until the frame ends.
 *
 * <ul>
 *     <li>Fills, gradients, sprites, nine-slices and pixel buffers snap their edges to the device pixel grid, as clips
 *     do, rounding half up, so pieces drawn side by side meet without seams or overlap.</li>
 *     <li>Rounded rectangles, outlines, ellipses and shadows are signed distance fields in the shader, anti-aliased
 *     over one device pixel. Lines, polylines, arcs and shapes are flattened, stroked and rasterized on the host into
 *     a coverage mask, filled by the nonzero rule.</li>
 *     <li>Round clips are anti-aliased too. Only the innermost round clip rounds its corners: inside another round
 *     clip, the outer one clips to its rectangle.</li>
 *     <li>Text is drawn from the renderer's glyph atlas at the size it lands on screen. A style whose size differs
 *     from its font's own is drawn as that font under a scale, so its glyphs are rasterized at the larger or smaller
 *     size and its shadow offset and lines grow with it. A shadow is the text again one unit of the font's size down
 *     and to the right, at a quarter of its brightness, as the Swing adapter draws it.</li>
 *     <li>Sprites and pixel buffers are sampled with nearest filtering, and with linear filtering inside
 *     {@link #pushSampling pushSampling(LINEAR)}; glyphs always with nearest. A missing sprite draws as magenta, and
 *     so does a visual that is neither one of {@link VulkanVisuals} nor an image this renderer made.</li>
 *     <li>Opacity multiplies into every draw's alpha. Layers need nothing: draws keep their order.</li>
 * </ul>
 */
final class VulkanCanvas implements Canvas {
    /** What a missing sprite or a foreign visual draws as. */
    static final int MISSING_COLOR = 0xFFFF00FF;
    private static final List<CursorKind> DEFAULT_CURSORS = List.of(CursorKind.ARROW, CursorKind.TEXT,
            CursorKind.HAND, CursorKind.CROSSHAIR, CursorKind.RESIZE_HORIZONTAL, CursorKind.RESIZE_VERTICAL,
            CursorKind.RESIZE_ALL, CursorKind.NOT_ALLOWED);

    private final VulkanRenderer renderer;
    private final DrawList draws;
    private final DevicePath path;
    private final Polygons polygons;
    private final Stroker stroker;
    private final Coverage coverage;
    private final int[] mask;
    private final float[] band;
    private final float[] bandOut;

    private VulkanTextMetrics metrics;
    private VulkanSprites sprites;
    private Predicate<CursorKind> cursors;
    private CursorKind requestedCursor;

    private int width;
    private int height;

    // Device pixels = origin + scale * units
    private float originX;
    private float originY;
    private float scale;
    private float[] transforms = new float[48];
    private int transformDepth;

    private int clipX0;
    private int clipY0;
    private int clipX1;
    private int clipY1;
    // The innermost round clip: center, half size and radius in device pixels; a negative radius for none
    private final float[] roundClip = new float[5];
    private int[] clips = new int[64];
    private float[] roundClips = new float[80];
    private int clipDepth;

    private float opacity;
    private float[] opacities = new float[16];
    private int opacityDepth;

    private boolean smooth;
    private boolean[] smoothings = new boolean[16];
    private int smoothingDepth;

    private boolean linear;
    private boolean[] samplings = new boolean[16];
    private int samplingDepth;

    // The pixel buffers the frame drew and the version of each, so drawing the frame again can tell they changed
    private PixelBuffer[] drawnBuffers = new PixelBuffer[4];
    private long[] drawnVersions = new long[4];
    private int drawnCount;

    VulkanCanvas(final VulkanRenderer renderer, final DrawList draws) {
        this.renderer = renderer;
        this.draws = draws;
        this.path = new DevicePath();
        this.polygons = new Polygons();
        this.stroker = new Stroker();
        this.coverage = new Coverage();
        this.mask = new int[5];
        this.band = new float[32];
        this.bandOut = new float[32];
    }

    /**
     * Sets what the canvas draws text and sprites with, and which cursors it can show.
     *
     * @param metrics the text metrics, or {@code null} to draw no text
     * @param sprites where sprites come from, or {@code null} to draw every sprite as missing
     * @param cursors the cursors the window shows, or {@code null} for the SPI's default eight
     */
    void configure(final VulkanTextMetrics metrics, final VulkanSprites sprites, final Predicate<CursorKind> cursors) {
        this.metrics = metrics;
        this.sprites = sprites;
        this.cursors = cursors;
    }

    /**
     * Resets every stack for a new frame.
     *
     * @param width the viewport's width in device pixels
     * @param height the viewport's height in device pixels
     * @param pixelsPerUnit device pixels per UI unit
     */
    void begin(final int width, final int height, final float pixelsPerUnit) {
        this.width = width;
        this.height = height;
        this.originX = 0f;
        this.originY = 0f;
        this.scale = pixelsPerUnit;
        this.transformDepth = 0;
        this.clipX0 = 0;
        this.clipY0 = 0;
        this.clipX1 = width;
        this.clipY1 = height;
        this.roundClip[4] = -1f;
        this.clipDepth = 0;
        this.opacity = 1f;
        this.opacityDepth = 0;
        this.smooth = true;
        this.smoothingDepth = 0;
        this.linear = false;
        this.samplingDepth = 0;
        this.requestedCursor = null;
        Arrays.fill(this.drawnBuffers, 0, this.drawnCount, null);
        this.drawnCount = 0;
        this.draws.scissor(0, 0, width, height);
        this.draws.noRoundClip();
    }

    /**
     * Ends the frame's drawing.
     */
    void end() {
        this.draws.noRoundClip();
    }

    /**
     * Returns whether a pixel buffer the frame drew changed since, so the frame no longer shows it as it is.
     *
     * @return true if one did
     */
    boolean pixelsChanged() {
        for (int i = 0; i < this.drawnCount; i++) {
            if (this.drawnBuffers[i].version() != this.drawnVersions[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the cursor asked for during the frame.
     *
     * @return the cursor, or {@code null} if none was asked for
     */
    CursorKind requestedCursor() {
        return this.requestedCursor;
    }

    // ------------------------------------------------------------------
    // Fills, sprites and text
    // ------------------------------------------------------------------

    @Override
    public void fill(final Rect rect, final int argb) {
        if ((argb >>> 24) == 0 || this.clipEmpty()) {
            return;
        }
        final int x0 = this.snapX(rect.x());
        final int y0 = this.snapY(rect.y());
        final int x1 = this.snapX(rect.x() + rect.width());
        final int y1 = this.snapY(rect.y() + rect.height());
        if (x1 <= x0 || y1 <= y0 || this.outside(x0, y0, x1, y1)) {
            return;
        }
        this.draws.texture(this.renderer.whiteSet());
        this.draws.quad(x0, y0, x1, y1, 0f, 0f, 1f, 1f, DrawList.rgba(argb, this.opacity), DrawList.SOLID, 0f, 0f,
                0f, 0f);
    }

    @Override
    public void fillGradient(final Rect rect, final int topArgb, final int bottomArgb) {
        if (((topArgb | bottomArgb) >>> 24) == 0 || this.clipEmpty()) {
            return;
        }
        final int x0 = this.snapX(rect.x());
        final int y0 = this.snapY(rect.y());
        final int x1 = this.snapX(rect.x() + rect.width());
        final int y1 = this.snapY(rect.y() + rect.height());
        if (x1 <= x0 || y1 <= y0 || this.outside(x0, y0, x1, y1)) {
            return;
        }
        final int top = DrawList.rgba(topArgb, this.opacity);
        final int bottom = DrawList.rgba(bottomArgb, this.opacity);
        this.draws.texture(this.renderer.whiteSet());
        this.draws.begin(6);
        this.draws.vertex(x0, y0, 0f, 0f, top, DrawList.SOLID, 0f, 0f, 0f, 0f);
        this.draws.vertex(x1, y0, 0f, 0f, top, DrawList.SOLID, 0f, 0f, 0f, 0f);
        this.draws.vertex(x1, y1, 0f, 0f, bottom, DrawList.SOLID, 0f, 0f, 0f, 0f);
        this.draws.vertex(x0, y0, 0f, 0f, top, DrawList.SOLID, 0f, 0f, 0f, 0f);
        this.draws.vertex(x1, y1, 0f, 0f, bottom, DrawList.SOLID, 0f, 0f, 0f, 0f);
        this.draws.vertex(x0, y1, 0f, 0f, bottom, DrawList.SOLID, 0f, 0f, 0f, 0f);
    }

    @Override
    public void sprite(final SpriteId sprite, final Rect rect, final int tint) {
        this.spriteRegion(sprite, rect, 0f, 0f, 1f, 1f, tint);
    }

    @Override
    public void spriteRegion(final SpriteId sprite, final Rect rect, final float u0, final float v0, final float u1,
                             final float v1, final int tint) {
        final Optional<VulkanSprites.SpriteImage> image = this.image(sprite);
        if (image.isEmpty()) {
            this.fill(rect, MISSING_COLOR);
            return;
        }
        this.texture(this.renderer.sprite(this.sprites, image.get()), rect.x(), rect.y(), rect.x() + rect.width(),
                rect.y() + rect.height(), u0, v0, u1, v1, tint);
    }

    @Override
    public void nineSlice(final SpriteId sprite, final Rect rect, final Insets border, final int tint) {
        final Optional<VulkanSprites.SpriteImage> found = this.image(sprite);
        if (found.isEmpty()) {
            this.fill(rect, MISSING_COLOR);
            return;
        }
        final VulkanSprites.SpriteImage image = found.get();
        final Texture texture = this.renderer.sprite(this.sprites, image);
        // The border counts the plain image's pixels; a denser image has more of them under each unit
        final float density = image.density();
        final int w = image.width();
        final int h = image.height();
        final int l = VulkanCanvas.clampBorder(border.left() * density, w);
        final int r = VulkanCanvas.clampBorder(border.right() * density, w - l);
        final int t = VulkanCanvas.clampBorder(border.top() * density, h);
        final int b = VulkanCanvas.clampBorder(border.bottom() * density, h - t);
        final float left = l / density;
        final float right = r / density;
        final float top = t / density;
        final float bottom = b / density;
        // Borders wider than the rectangle shrink in proportion, so nothing draws outside it
        final float horizontal = left + right > rect.width() ? rect.width() / (left + right) : 1f;
        final float vertical = top + bottom > rect.height() ? rect.height() / (top + bottom) : 1f;
        final float[] dx = {rect.x(), rect.x() + left * horizontal, rect.right() - right * horizontal, rect.right()};
        final float[] dy = {rect.y(), rect.y() + top * vertical, rect.bottom() - bottom * vertical, rect.bottom()};
        final int[] sx = {0, l, w - r, w};
        final int[] sy = {0, t, h - b, h};
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                if (dx[column + 1] > dx[column] && dy[row + 1] > dy[row] && sx[column + 1] > sx[column]
                        && sy[row + 1] > sy[row]) {
                    this.texture(texture, dx[column], dy[row], dx[column + 1], dy[row + 1],
                            sx[column] / (float) w, sy[row] / (float) h, sx[column + 1] / (float) w,
                            sy[row + 1] / (float) h, tint);
                }
            }
        }
    }

    @Override
    public void text(final TextSpan span, final float x, final float y) {
        final TextStyle style = span.style();
        if ((style.color() >>> 24) == 0 || span.text().isEmpty() || this.metrics == null || this.clipEmpty()) {
            return;
        }
        final VulkanFont font = this.metrics.font(style);
        if (font.isClosed()) {
            return;
        }
        final float factor = this.metrics.scale(style, font);
        if (factor == 1f) {
            this.drawText(font, span, x, y);
            return;
        }
        // Another size draws the font under a scale, so glyphs are rasterized at the size they land on screen
        this.pushTranslation(x, y);
        try {
            this.pushScale(factor);
            try {
                this.drawText(font, span, 0f, 0f);
            } finally {
                this.popTransform();
            }
        } finally {
            this.popTransform();
        }
    }

    /**
     * Draws a span with a font at the font's own size, with its shadow and lines.
     */
    private void drawText(final VulkanFont font, final TextSpan span, final float x, final float y) {
        final TextStyle style = span.style();
        // Drawn with the font the width was measured with, so spans side by side meet exactly
        final float ascent = Math.min(font.ascent(), font.lineHeight());
        final float baseline = y + ascent;
        if (style.shadow()) {
            font.draw(this, span, x + 1f, baseline + 1f, VulkanCanvas.shadowOf(style.color()));
        }
        font.draw(this, span, x, baseline, style.color());
        // The lines sit where the Swing adapter draws them, one unit of the font's size thick
        if (style.underline() || style.strikethrough()) {
            final float width = font.width(span);
            if (style.underline()) {
                this.fill(new Rect(x, baseline + 1f, width, 1f), style.color());
            }
            if (style.strikethrough()) {
                this.fill(new Rect(x, baseline - ascent * 0.35f, width, 1f), style.color());
            }
        }
    }

    // ------------------------------------------------------------------
    // Clips, transforms, opacity and smoothing
    // ------------------------------------------------------------------

    @Override
    public void pushClip(final Rect rect) {
        this.saveClip();
        this.intersectClip(rect);
    }

    @Override
    public void pushRoundClip(final Rect rect, final float radius) {
        this.saveClip();
        this.intersectClip(rect);
        final float x0 = this.originX + this.scale * rect.x();
        final float y0 = this.originY + this.scale * rect.y();
        final float halfWidth = this.scale * rect.width() / 2f;
        final float halfHeight = this.scale * rect.height() / 2f;
        final float r = Math.max(0f, Math.min(radius * this.scale, Math.min(halfWidth, halfHeight)));
        this.roundClip[0] = x0 + halfWidth;
        this.roundClip[1] = y0 + halfHeight;
        this.roundClip[2] = halfWidth;
        this.roundClip[3] = halfHeight;
        this.roundClip[4] = r;
        this.draws.roundClip(this.roundClip[0], this.roundClip[1], halfWidth, halfHeight, r);
    }

    @Override
    public void popClip() {
        if (this.clipDepth == 0) {
            return;
        }
        this.clipDepth--;
        final int at = this.clipDepth * 4;
        this.clipX0 = this.clips[at];
        this.clipY0 = this.clips[at + 1];
        this.clipX1 = this.clips[at + 2];
        this.clipY1 = this.clips[at + 3];
        System.arraycopy(this.roundClips, this.clipDepth * 5, this.roundClip, 0, 5);
        this.applyClip();
    }

    @Override
    public void pushTranslation(final float dx, final float dy) {
        this.saveTransform();
        this.originX += this.scale * dx;
        this.originY += this.scale * dy;
    }

    @Override
    public void pushScale(final float scale) {
        this.saveTransform();
        this.scale *= scale;
    }

    @Override
    public void popTransform() {
        if (this.transformDepth == 0) {
            return;
        }
        this.transformDepth--;
        final int at = this.transformDepth * 3;
        this.originX = this.transforms[at];
        this.originY = this.transforms[at + 1];
        this.scale = this.transforms[at + 2];
    }

    @Override
    public void pushOpacity(final float opacity) {
        if (this.opacityDepth == this.opacities.length) {
            this.opacities = Arrays.copyOf(this.opacities, this.opacityDepth * 2);
        }
        this.opacities[this.opacityDepth++] = this.opacity;
        this.opacity *= Math.max(0f, Math.min(1f, opacity));
    }

    @Override
    public void popOpacity() {
        if (this.opacityDepth > 0) {
            this.opacity = this.opacities[--this.opacityDepth];
        }
    }

    @Override
    public void pushSmoothing(final boolean smooth) {
        if (this.smoothingDepth == this.smoothings.length) {
            this.smoothings = Arrays.copyOf(this.smoothings, this.smoothingDepth * 2);
        }
        this.smoothings[this.smoothingDepth++] = this.smooth;
        this.smooth = smooth;
    }

    @Override
    public void popSmoothing() {
        if (this.smoothingDepth > 0) {
            this.smooth = this.smoothings[--this.smoothingDepth];
        }
    }

    @Override
    public void pushSampling(final Sampling sampling) {
        if (this.samplingDepth == this.samplings.length) {
            this.samplings = Arrays.copyOf(this.samplings, this.samplingDepth * 2);
        }
        this.samplings[this.samplingDepth++] = this.linear;
        this.linear = sampling == Sampling.LINEAR;
    }

    @Override
    public void popSampling() {
        if (this.samplingDepth > 0) {
            this.linear = this.samplings[--this.samplingDepth];
        }
    }

    // ------------------------------------------------------------------
    // Visuals, pixel buffers, layers and cursors
    // ------------------------------------------------------------------

    @Override
    public void visual(final Visual visual, final Rect rect, final int tint) {
        if (visual instanceof final VulkanImage image && image.belongsTo(this.renderer)) {
            // The application draws into its image whenever it likes
            this.texture(image.set(), DrawList.LIVE, rect.x(), rect.y(), rect.x() + rect.width(),
                    rect.y() + rect.height(), 0f, 0f, 1f, 1f, tint);
            return;
        }
        if (visual instanceof final VulkanVisuals.PixelVisual pixels) {
            this.pixels(pixels.pixels(), rect, tint);
            return;
        }
        this.fill(rect, MISSING_COLOR);
    }

    @Override
    public void pixels(final PixelBuffer buffer, final Rect rect, final int tint) {
        if (buffer.width() <= 0 || buffer.height() <= 0 || !(rect.width() > 0f) || !(rect.height() > 0f)
                || (tint >>> 24) == 0 || this.clipEmpty()) {
            return;
        }
        if (this.drawnCount == this.drawnBuffers.length) {
            this.drawnBuffers = Arrays.copyOf(this.drawnBuffers, this.drawnCount * 2);
            this.drawnVersions = Arrays.copyOf(this.drawnVersions, this.drawnCount * 2);
        }
        this.drawnBuffers[this.drawnCount] = buffer;
        this.drawnVersions[this.drawnCount++] = buffer.version();
        this.texture(this.renderer.pixels(buffer), rect.x(), rect.y(), rect.x() + rect.width(),
                rect.y() + rect.height(), 0f, 0f, 1f, 1f, tint);
    }

    @Override
    public void nextLayer() {
        // Draws keep their order, so a layer needs nothing
    }

    @Override
    public void requestCursor(final CursorKind cursor) {
        this.requestedCursor = cursor;
    }

    @Override
    public boolean showsCursor(final CursorKind cursor) {
        if (cursor == CursorKind.ARROW) {
            return true;
        }
        return this.cursors != null ? this.cursors.test(cursor) : DEFAULT_CURSORS.contains(cursor);
    }

    // ------------------------------------------------------------------
    // Shapes
    // ------------------------------------------------------------------

    @Override
    public void fillGradient(final Rect rect, final Gradient gradient) {
        if (this.clipEmpty() || !(rect.width() > 0f) || !(rect.height() > 0f)) {
            return;
        }
        final float x0 = this.snapX(rect.x());
        final float y0 = this.snapY(rect.y());
        final float x1 = this.snapX(rect.x() + rect.width());
        final float y1 = this.snapY(rect.y() + rect.height());
        if (x1 <= x0 || y1 <= y0 || this.outside((int) x0, (int) y0, (int) x1, (int) y1)) {
            return;
        }
        final float cos = (float) Math.cos(gradient.angleRadians());
        final float sin = (float) Math.sin(gradient.angleRadians());
        // Offsets run from the corner that comes first along the direction to the one that comes last
        final float start = Math.min(Math.min(x0 * cos + y0 * sin, x1 * cos + y0 * sin),
                Math.min(x0 * cos + y1 * sin, x1 * cos + y1 * sin));
        final float end = Math.max(Math.max(x0 * cos + y0 * sin, x1 * cos + y0 * sin),
                Math.max(x0 * cos + y1 * sin, x1 * cos + y1 * sin));
        final float length = end - start;
        this.draws.texture(this.renderer.whiteSet());
        final List<Gradient.Stop> stops = gradient.stops();
        // Each band between offsets is filled with colors blended across it; before the first stop and after the
        // last the color is flat
        float from = Float.NEGATIVE_INFINITY;
        for (int i = 0; i <= stops.size(); i++) {
            final float to = i < stops.size() ? stops.get(i).offset() : Float.POSITIVE_INFINITY;
            if (to > from) {
                this.gradientBand(gradient, x0, y0, x1, y1, cos, sin, start, length, from, to);
            }
            from = Math.max(from, to);
        }
    }

    @Override
    public void line(final float x1, final float y1, final float x2, final float y2, final Stroke stroke,
                     final int argb) {
        if (this.skipStroke(stroke, argb)) {
            return;
        }
        this.path.reset(this.originX, this.originY, this.scale);
        this.path.moveTo(x1, y1);
        this.path.lineTo(x2, y2);
        this.strokePath(stroke, argb);
    }

    @Override
    public void polyline(final float[] points, final int count, final Stroke stroke, final int argb) {
        if (this.skipStroke(stroke, argb) || count < 1) {
            return;
        }
        this.path.reset(this.originX, this.originY, this.scale);
        this.path.moveTo(points[0], points[1]);
        for (int i = 1; i < count; i++) {
            this.path.lineTo(points[i * 2], points[i * 2 + 1]);
        }
        this.strokePath(stroke, argb);
    }

    @Override
    public void fillRoundRect(final Rect rect, final float radius, final int argb) {
        this.distanceShape(rect, DrawList.ROUND_RECT, radius, 0f, argb, true);
    }

    @Override
    public void strokeRect(final Rect rect, final float radius, final Stroke stroke, final int argb) {
        if (stroke.width() > 0f) {
            this.distanceShape(rect, DrawList.ROUND_RECT_STROKE, radius, stroke.width(), argb, true);
        }
    }

    @Override
    public void fillEllipse(final Rect oval, final int argb) {
        this.distanceShape(oval, DrawList.ELLIPSE, 0f, 0f, argb, true);
    }

    @Override
    public void strokeEllipse(final Rect oval, final Stroke stroke, final int argb) {
        if (stroke.width() > 0f) {
            this.distanceShape(oval, DrawList.ELLIPSE_STROKE, 0f, stroke.width(), argb, true);
        }
    }

    @Override
    public void strokeArc(final Rect oval, final float startRadians, final float sweepRadians, final Stroke stroke,
                          final int argb) {
        if (this.skipStroke(stroke, argb)) {
            return;
        }
        final float half = stroke.width() * this.scale / 2f;
        final float cx = this.originX + this.scale * (oval.x() + oval.width() / 2f);
        final float cy = this.originY + this.scale * (oval.y() + oval.height() / 2f);
        // The stroke lies inside the oval, so its middle runs half a width in
        final float rx = Math.max(0f, this.scale * oval.width() / 2f - half);
        final float ry = Math.max(0f, this.scale * oval.height() / 2f - half);
        final int sides = Polygons.sides(Math.max(rx, ry), Math.abs(sweepRadians));
        this.path.reset(this.originX, this.originY, this.scale);
        for (int i = 0; i <= sides; i++) {
            final double angle = startRadians + (double) sweepRadians * i / sides;
            final float x = cx + (float) (Math.cos(angle) * rx);
            final float y = cy + (float) (Math.sin(angle) * ry);
            if (i == 0) {
                this.path.startDevice(x, y);
            } else {
                this.path.point(x, y, i == sides);
            }
        }
        this.strokePath(stroke, argb);
    }

    @Override
    public void fillShape(final Shape shape, final int argb) {
        if ((argb >>> 24) == 0 || this.clipEmpty() || shape.isEmpty()) {
            return;
        }
        this.path.reset(this.originX, this.originY, this.scale);
        shape.walk(this.path);
        this.polygons.reset();
        for (int subpath = 0; subpath < this.path.subpaths(); subpath++) {
            this.polygons.begin();
            for (int i = this.path.start(subpath); i < this.path.end(subpath); i++) {
                this.polygons.add(this.path.pointX(i), this.path.pointY(i));
            }
            this.polygons.end(false);
        }
        this.drawCoverage(argb);
    }

    @Override
    public void strokeShape(final Shape shape, final Stroke stroke, final int argb) {
        if (this.skipStroke(stroke, argb) || shape.isEmpty()) {
            return;
        }
        this.path.reset(this.originX, this.originY, this.scale);
        shape.walk(this.path);
        this.strokePath(stroke, argb);
    }

    @Override
    public void shadow(final Rect rect, final float radius, final float blur, final int argb) {
        if ((argb >>> 24) == 0 || this.clipEmpty() || !(blur > 0f)) {
            return;
        }
        final float b = blur * this.scale;
        final float x0 = this.originX + this.scale * rect.x();
        final float y0 = this.originY + this.scale * rect.y();
        final float x1 = x0 + this.scale * rect.width();
        final float y1 = y0 + this.scale * rect.height();
        if (!(x1 >= x0) || !(y1 >= y0)) {
            return;
        }
        // As the Swing adapter: the rectangle grown by half the blur, blurred so it fades out over the blur
        final float cx = (x0 + x1) / 2f;
        final float cy = (y0 + y1) / 2f;
        final float halfWidth = (x1 - x0) / 2f + b / 2f;
        final float halfHeight = (y1 - y0) / 2f + b / 2f;
        final float corner = Math.min(Math.max(0f, radius * this.scale) + b / 2f, Math.min(halfWidth, halfHeight));
        final float qx0 = Math.max(x0 - b, this.clipX0);
        final float qy0 = Math.max(y0 - b, this.clipY0);
        final float qx1 = Math.min(x1 + b, this.clipX1);
        final float qy1 = Math.min(y1 + b, this.clipY1);
        if (qx1 <= qx0 || qy1 <= qy0) {
            return;
        }
        this.draws.texture(this.renderer.whiteSet());
        this.draws.quad(qx0, qy0, qx1, qy1, qx0 - cx, qy0 - cy, qx1 - cx, qy1 - cy, DrawList.rgba(argb, this.opacity),
                DrawList.SHADOW, halfWidth, halfHeight, corner, b / 4f);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Optional<VulkanSprites.SpriteImage> image(final SpriteId sprite) {
        return this.sprites == null ? Optional.empty() : this.sprites.image(sprite, this.scale);
    }

    private void texture(final Texture texture, final float left, final float top, final float right,
                         final float bottom, final float u0, final float v0, final float u1, final float v1,
                         final int tint) {
        this.texture(texture.set(this.renderer, this.linear), texture.stamp(), left, top, right, bottom, u0, v0, u1,
                v1, tint);
    }

    /**
     * Draws part of a texture over a rectangle in UI units, its edges snapped to the pixel grid.
     */
    private void texture(final long set, final long stamp, final float left, final float top, final float right,
                         final float bottom, final float u0, final float v0, final float u1, final float v1,
                         final int tint) {
        if ((tint >>> 24) == 0 || this.clipEmpty()) {
            return;
        }
        final int x0 = this.snapX(left);
        final int y0 = this.snapY(top);
        final int x1 = this.snapX(right);
        final int y1 = this.snapY(bottom);
        if (x1 <= x0 || y1 <= y0 || this.outside(x0, y0, x1, y1)) {
            return;
        }
        this.draws.texture(set, stamp);
        this.draws.quad(x0, y0, x1, y1, u0, v0, u1, v1, DrawList.rgba(tint, this.opacity), DrawList.TEXTURE, 0f, 0f,
                0f, 0f);
    }

    // ------------------------------------------------------------------
    // What fonts draw through
    // ------------------------------------------------------------------

    /**
     * Returns how many device pixels one unit covers in the current transform.
     */
    float scale() {
        return this.scale;
    }

    /**
     * Converts a horizontal position in UI units to device pixels in the current transform.
     */
    float deviceX(final float x) {
        return this.originX + this.scale * x;
    }

    /**
     * Converts a vertical position in UI units to device pixels in the current transform.
     */
    float deviceY(final float y) {
        return this.originY + this.scale * y;
    }

    /**
     * Packs a color for vertices, with the current opacity.
     */
    int color(final int argb) {
        return DrawList.rgba(argb, this.opacity);
    }

    /**
     * Returns the renderer's glyph atlas.
     */
    GlyphAtlas atlas() {
        return this.renderer.glyphs();
    }

    /**
     * Draws a glyph with its pen on the baseline at a point in device pixels.
     *
     * @param entry the glyph in the atlas
     * @param penX the pen's horizontal position, in device pixels
     * @param baseline the baseline, in device pixels
     * @param color the color, packed by {@link #color}
     */
    void glyph(final GlyphAtlas.Entry entry, final int penX, final int baseline, final int color) {
        if (entry.width() <= 0) {
            return;
        }
        final int x = penX + entry.left();
        final int y = baseline - entry.top();
        if (this.outside(x, y, x + entry.width(), y + entry.height())) {
            return;
        }
        this.draws.texture(entry.texture().set);
        this.draws.quad(x, y, x + entry.width(), y + entry.height(), entry.u0(), entry.v0(), entry.u1(), entry.v1(),
                color, DrawList.GLYPH, 0f, 0f, 0f, 0f);
    }

    /**
     * Draws a shape the shader measures: a rectangle in UI units grown by a pixel for the anti-aliased edge, with
     * coordinates from its center.
     */
    private void distanceShape(final Rect rect, final int kind, final float radius, final float strokeWidth,
                               final int argb, final boolean smoothable) {
        if ((argb >>> 24) == 0 || this.clipEmpty() || !(rect.width() > 0f) || !(rect.height() > 0f)) {
            return;
        }
        final float x0 = this.originX + this.scale * rect.x();
        final float y0 = this.originY + this.scale * rect.y();
        final float halfWidth = this.scale * rect.width() / 2f;
        final float halfHeight = this.scale * rect.height() / 2f;
        final float cx = x0 + halfWidth;
        final float cy = y0 + halfHeight;
        final float qx0 = Math.max((float) Math.floor(x0 - 1f), this.clipX0);
        final float qy0 = Math.max((float) Math.floor(y0 - 1f), this.clipY0);
        final float qx1 = Math.min((float) Math.ceil(cx + halfWidth + 1f), this.clipX1);
        final float qy1 = Math.min((float) Math.ceil(cy + halfHeight + 1f), this.clipY1);
        if (qx1 <= qx0 || qy1 <= qy0) {
            return;
        }
        final float r = Math.max(0f, Math.min(radius * this.scale, Math.min(halfWidth, halfHeight)));
        final float w = strokeWidth * this.scale;
        final int mode = kind | (smoothable && !this.smooth ? DrawList.HARD : 0);
        final float p2 = kind == DrawList.ELLIPSE_STROKE ? w : r;
        this.draws.texture(this.renderer.whiteSet());
        this.draws.quad(qx0, qy0, qx1, qy1, qx0 - cx, qy0 - cy, qx1 - cx, qy1 - cy, DrawList.rgba(argb, this.opacity),
                mode, halfWidth, halfHeight, p2, w);
    }

    private boolean skipStroke(final Stroke stroke, final int argb) {
        return (argb >>> 24) == 0 || !(stroke.width() > 0f) || this.clipEmpty();
    }

    private void strokePath(final Stroke stroke, final int argb) {
        this.polygons.reset();
        this.stroker.stroke(this.path, stroke.width() * this.scale, stroke.cap(), stroke.join(), this.polygons);
        this.drawCoverage(argb);
    }

    /**
     * Rasterizes the polygons inside the clip and draws their coverage in a color.
     */
    private void drawCoverage(final int argb) {
        if (this.polygons.polygons() == 0
                || !this.coverage.rasterize(this.polygons, this.clipX0, this.clipY0, this.clipX1, this.clipY1,
                this.draws, this.mask)) {
            return;
        }
        final int x0 = this.mask[0];
        final int y0 = this.mask[1];
        final int x1 = x0 + this.mask[2];
        final int y1 = y0 + this.mask[3];
        this.draws.texture(this.renderer.whiteSet());
        this.draws.quad(x0, y0, x1, y1, 0f, 0f, 0f, 0f, DrawList.rgba(argb, this.opacity),
                DrawList.MASK | (this.smooth ? 0 : DrawList.HARD), x0, y0, this.mask[2], this.mask[4] / 4f);
    }

    /**
     * Fills the part of a rectangle whose gradient offset lies between two offsets.
     */
    private void gradientBand(final Gradient gradient, final float x0, final float y0, final float x1, final float y1,
                              final float cos, final float sin, final float start, final float length,
                              final float from, final float to) {
        final float[] polygon = this.band;
        polygon[0] = x0;
        polygon[1] = y0;
        polygon[2] = x1;
        polygon[3] = y0;
        polygon[4] = x1;
        polygon[5] = y1;
        polygon[6] = x0;
        polygon[7] = y1;
        int count = 4;
        if (length > 0f) {
            if (from != Float.NEGATIVE_INFINITY) {
                count = this.slab(count, cos, sin, start + from * length, true);
            }
            if (to != Float.POSITIVE_INFINITY) {
                count = this.slab(count, cos, sin, start + to * length, false);
            }
        } else if (from != Float.NEGATIVE_INFINITY) {
            // A rectangle with no length along the gradient takes the first stop's color
            return;
        }
        if (count < 3) {
            return;
        }
        this.draws.begin((count - 2) * 3);
        final int first = this.bandColor(gradient, polygon[0], polygon[1], cos, sin, start, length);
        for (int i = 1; i + 1 < count; i++) {
            final int a = this.bandColor(gradient, polygon[i * 2], polygon[i * 2 + 1], cos, sin, start, length);
            final int b = this.bandColor(gradient, polygon[i * 2 + 2], polygon[i * 2 + 3], cos, sin, start, length);
            this.draws.vertex(polygon[0], polygon[1], 0f, 0f, first, DrawList.SOLID, 0f, 0f, 0f, 0f);
            this.draws.vertex(polygon[i * 2], polygon[i * 2 + 1], 0f, 0f, a, DrawList.SOLID, 0f, 0f, 0f, 0f);
            this.draws.vertex(polygon[i * 2 + 2], polygon[i * 2 + 3], 0f, 0f, b, DrawList.SOLID, 0f, 0f, 0f, 0f);
        }
    }

    private int bandColor(final Gradient gradient, final float x, final float y, final float cos, final float sin,
                          final float start, final float length) {
        final float offset = length > 0f ? (x * cos + y * sin - start) / length : 0f;
        return DrawList.rgba(gradient.colorAt(offset), this.opacity);
    }

    /**
     * Keeps the part of {@link #band} on one side of a line across the gradient.
     *
     * @param at the line's position along the gradient's direction
     * @param after true to keep what lies at or after it, false for what lies at or before it
     */
    private int slab(final int count, final float cos, final float sin, final float at, final boolean after) {
        final float[] in = this.band;
        final float[] out = this.bandOut;
        int n = 0;
        for (int i = 0; i < count; i++) {
            final int j = (i + 1) % count;
            final float da = in[i * 2] * cos + in[i * 2 + 1] * sin - at;
            final float db = in[j * 2] * cos + in[j * 2 + 1] * sin - at;
            final boolean aIn = after ? da >= 0f : da <= 0f;
            final boolean bIn = after ? db >= 0f : db <= 0f;
            if (aIn) {
                out[n * 2] = in[i * 2];
                out[n * 2 + 1] = in[i * 2 + 1];
                n++;
            }
            if (aIn != bIn) {
                final float t = da / (da - db);
                out[n * 2] = in[i * 2] + (in[j * 2] - in[i * 2]) * t;
                out[n * 2 + 1] = in[i * 2 + 1] + (in[j * 2 + 1] - in[i * 2 + 1]) * t;
                n++;
            }
        }
        System.arraycopy(out, 0, in, 0, n * 2);
        return n;
    }

    private void saveClip() {
        if (this.clipDepth * 4 + 4 > this.clips.length) {
            this.clips = Arrays.copyOf(this.clips, this.clips.length * 2);
            this.roundClips = Arrays.copyOf(this.roundClips, this.roundClips.length * 2);
        }
        final int at = this.clipDepth * 4;
        this.clips[at] = this.clipX0;
        this.clips[at + 1] = this.clipY0;
        this.clips[at + 2] = this.clipX1;
        this.clips[at + 3] = this.clipY1;
        System.arraycopy(this.roundClip, 0, this.roundClips, this.clipDepth * 5, 5);
        this.clipDepth++;
    }

    private void intersectClip(final Rect rect) {
        this.clipX0 = Math.max(this.clipX0, this.snapX(rect.x()));
        this.clipY0 = Math.max(this.clipY0, this.snapY(rect.y()));
        this.clipX1 = Math.min(this.clipX1, this.snapX(rect.x() + rect.width()));
        this.clipY1 = Math.min(this.clipY1, this.snapY(rect.y() + rect.height()));
        this.applyClip();
    }

    private void applyClip() {
        this.draws.scissor(this.clipX0, this.clipY0, Math.max(0, this.clipX1 - this.clipX0),
                Math.max(0, this.clipY1 - this.clipY0));
        if (this.roundClip[4] >= 0f) {
            this.draws.roundClip(this.roundClip[0], this.roundClip[1], this.roundClip[2], this.roundClip[3],
                    this.roundClip[4]);
        } else {
            this.draws.noRoundClip();
        }
    }

    private void saveTransform() {
        if (this.transformDepth * 3 + 3 > this.transforms.length) {
            this.transforms = Arrays.copyOf(this.transforms, this.transforms.length * 2);
        }
        final int at = this.transformDepth * 3;
        this.transforms[at] = this.originX;
        this.transforms[at + 1] = this.originY;
        this.transforms[at + 2] = this.scale;
        this.transformDepth++;
    }

    private boolean clipEmpty() {
        return this.clipX1 <= this.clipX0 || this.clipY1 <= this.clipY0;
    }

    private boolean outside(final int x0, final int y0, final int x1, final int y1) {
        return x1 <= this.clipX0 || y1 <= this.clipY0 || x0 >= this.clipX1 || y0 >= this.clipY1;
    }

    /**
     * Rounds a horizontal coordinate in UI units to the nearest device pixel edge, clamped to a range wide enough for
     * any screen.
     */
    private int snapX(final float x) {
        return VulkanCanvas.snap(this.originX + this.scale * x);
    }

    private int snapY(final float y) {
        return VulkanCanvas.snap(this.originY + this.scale * y);
    }

    private static int snap(final float device) {
        if (Float.isNaN(device)) {
            return 0;
        }
        return (int) Math.floor(Math.max(-1e8f, Math.min(1e8f, device)) + 0.5f);
    }

    private static int clampBorder(final float border, final int limit) {
        return Math.max(0, Math.min(Math.round(border), limit));
    }

    /**
     * A quarter-brightness copy of a color, for text shadows.
     */
    private static int shadowOf(final int argb) {
        return (argb & 0xFF000000) | ((argb >> 2) & 0x003F3F3F);
    }
}

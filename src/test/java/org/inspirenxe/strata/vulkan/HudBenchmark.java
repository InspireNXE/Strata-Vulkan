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

import org.inspirenxe.strata.api.ComponentFactory;
import org.inspirenxe.strata.api.component.Component;
import org.inspirenxe.strata.api.control.display.Label;
import org.inspirenxe.strata.api.extension.ArrangeContext;
import org.inspirenxe.strata.api.extension.CustomContainer;
import org.inspirenxe.strata.api.extension.MeasureContext;
import org.inspirenxe.strata.api.geometry.Insets;
import org.inspirenxe.strata.api.geometry.Rect;
import org.inspirenxe.strata.api.geometry.Size;
import org.inspirenxe.strata.api.layout.GridLength;
import org.inspirenxe.strata.api.layout.GridPanel;
import org.inspirenxe.strata.api.layout.HorizontalAlignment;
import org.inspirenxe.strata.api.layout.VerticalAlignment;
import org.inspirenxe.strata.api.screen.Screen;
import org.inspirenxe.strata.api.screen.ScreenHandle;
import org.inspirenxe.strata.api.text.Text;
import org.inspirenxe.strata.api.text.TextStyle;
import org.inspirenxe.strata.api.theme.Paint;
import org.inspirenxe.strata.api.theme.Themes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures the host time {@link StrataVulkan#render} takes for a debug overlay like Wintergreen's: three grids of
 * about 45 labels, 35 of them set ten times a second, drawn at 120 frames a second by a simulated clock. Runs only
 * when the environment variable {@code STRATA_VULKAN_BENCH} is set, and prints what it measured.
 */
class HudBenchmark {
    private static final long FRAME_NANOS = 1_000_000_000L / 120;
    private static final int WARMUP_FRAMES = 3_600;
    private static final int MEASURED_FRAMES = 3_600;
    private static final Path UBUNTU = Path.of("..", "Wintergreen", "client", "src", "main", "resources", "core",
            "fonts", "ubuntu");

    @Test
    void debugOverlay() throws Exception {
        assumeTrue(System.getenv("STRATA_VULKAN_BENCH") != null, "STRATA_VULKAN_BENCH is not set");
        final TestDevice device = TestDevice.get();
        final long[] nanos = {1L};
        final VulkanTextMetrics metrics = HudBenchmark.metrics();
        final VulkanPlatform platform = VulkanPlatform.builder(metrics).clock(() -> nanos[0]).build();
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        try (VulkanRenderer renderer = new VulkanRenderer(device.logical, device.queue, device.queueFamily,
                OffscreenImage.TARGET, 2);
             StrataVulkan strata = new StrataVulkan(platform, renderer, Themes.flat(), 1f);
             OffscreenImage image = new OffscreenImage(device, renderer, 1280, 720)) {
            strata.setBounds(0, 0, 1280, 720);
            final Hud hud = new Hud();
            strata.screens().push(hud);
            final FrameStream frames = HudBenchmark.field(strata, "frames");
            final DrawList draws = HudBenchmark.field(frames, "draws");
            final long[] took = new long[1];
            final long[] allocated = new long[1];
            final long thread = Thread.currentThread().threadId();

            final long[] times = new long[MEASURED_FRAMES];
            final long[] bytes = new long[MEASURED_FRAMES];
            final boolean[] updated = new boolean[MEASURED_FRAMES];
            long vertices = 0L;
            int batches = 0;
            Layout.measures = 0;
            long committed = -1L;
            int uploads = 0;
            final Object[] slots = HudBenchmark.field(frames, "slots");
            final Field pending = FrameStream.Slot.class.getDeclaredField("pending");
            pending.setAccessible(true);
            for (int frame = -WARMUP_FRAMES; frame < MEASURED_FRAMES; frame++) {
                nanos[0] += FRAME_NANOS;
                final int updates = hud.updates;
                final long builtBefore = frames.committed();
                image.render(0xFF000000, commands -> {
                    final long before = threads.getThreadAllocatedBytes(thread);
                    final long start = System.nanoTime();
                    strata.render(commands);
                    took[0] = System.nanoTime() - start;
                    allocated[0] = threads.getThreadAllocatedBytes(thread) - before;
                });
                if (frame == 0) {
                    committed = frames.committed();
                }
                if (frame >= 0) {
                    times[frame] = took[0];
                    bytes[frame] = allocated[0];
                    updated[frame] = hud.updates != updates;
                    vertices = Math.max(vertices, draws.vertexCount());
                    batches = Math.max(batches, draws.batchCount());
                    // A frame that was built submitted uploads if its slot waits for them
                    final int slot = HudBenchmark.<Integer>field(frames, "slot");
                    if (frames.committed() != builtBefore && pending.getBoolean(slots[slot])) {
                        uploads++;
                    }
                }
            }
            System.out.println("Debug overlay: " + vertices / 6 + " quads, " + vertices * DrawList.STRIDE
                    + " vertex bytes and " + batches + " batches a frame at most; the overlay was measured "
                    + Layout.measures + " times and built " + (frames.committed() - committed) + " times in "
                    + MEASURED_FRAMES + " frames, submitting uploads " + uploads + " times");
            HudBenchmark.report("all frames", times, bytes, updated, null);
            HudBenchmark.report("frames after an update", times, bytes, updated, true);
            HudBenchmark.report("frames with no change", times, bytes, updated, false);
        }
    }

    private static void report(final String name, final long[] times, final long[] bytes, final boolean[] updated,
                               final Boolean which) {
        final List<Long> picked = new ArrayList<>();
        long allocated = 0L;
        for (int i = 0; i < times.length; i++) {
            if (which == null || updated[i] == which) {
                picked.add(times[i]);
                allocated += bytes[i];
            }
        }
        final long[] sorted = picked.stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) {
            return;
        }
        final double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
        System.out.printf(Locale.ROOT, "  %-24s %5d frames: mean %.4f ms, median %.4f, p99 %.4f, max %.4f;"
                        + " %.0f bytes allocated a frame%n", name, sorted.length, mean, sorted[sorted.length / 2] / 1e6,
                sorted[(int) (sorted.length * 0.99)] / 1e6, sorted[sorted.length - 1] / 1e6,
                allocated / (double) sorted.length);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    private static VulkanTextMetrics metrics() throws IOException {
        if (Files.isRegularFile(UBUNTU.resolve("Ubuntu-R.ttf"))) {
            return new VulkanTextMetrics(FreeTypeFont.builder(12f)
                    .regular(UBUNTU.resolve("Ubuntu-R.ttf"))
                    .bold(UBUNTU.resolve("Ubuntu-B.ttf"))
                    .build());
        }
        return TestFonts.metrics();
    }

    /**
     * The overlay: a game grid on the left, a title in the middle and a system grid on the right, with the labels
     * the timers set.
     */
    private static final class Hud implements Screen {
        private final Random random = new Random(7);
        private final List<Label> game = new ArrayList<>();
        private final List<Label> timings = new ArrayList<>();
        private final List<Label> memory = new ArrayList<>();
        private Label elapsed;
        private int seconds;
        int updates;

        @Override
        public Component<?> build(final ComponentFactory factory) {
            final List<Text> letters = new ArrayList<>();
            final String name = "Among The Stars";
            for (int i = 0; i < name.length(); i++) {
                final int argb = java.awt.Color.HSBtoRGB(0.30f + 0.06f * i / (name.length() - 1), 1f,
                        1f - 0.7f * i / (name.length() - 1));
                letters.add(Text.of(String.valueOf(name.charAt(i)),
                        TextStyle.DEFAULT.withColor(argb).withBold(true).withSize(15f)));
            }
            this.elapsed = factory.label("").setAlignment(HorizontalAlignment.CENTER, VerticalAlignment.TOP);
            final GridPanel title = Grid.panel(factory).columns(GridLength.auto())
                    .rows(GridLength.auto(), GridLength.auto())
                    .add(factory.label(Text.concat(letters))
                            .setAlignment(HorizontalAlignment.CENTER, VerticalAlignment.TOP), 0, 0)
                    .add(this.elapsed, 1, 0);

            final Grid game = new Grid(factory);
            game.header("Game");
            this.game.add(game.row("FPS:"));
            this.game.add(game.row("Viewport:"));
            game.gap();
            game.header("Player");
            for (final String key : List.of("Position:", "Facing:", "Movement:", "Sprinting:", "Block Below:")) {
                this.game.add(game.row(key));
            }
            game.gap();
            game.header("Camera");
            for (final String key : List.of("Mode:", "Position:", "Speed:", "Target:", "Tool:")) {
                this.game.add(game.row(key));
            }
            game.gap();
            game.header("Chunks");
            for (final String key : List.of("Client:", "Server:", "Loading:", "Holders:")) {
                this.game.add(game.row(key));
            }

            final Grid system = new Grid(factory);
            system.header("GPU");
            system.row("Vendor:", "NVIDIA Corporation");
            system.row("Model:", "NVIDIA GeForce RTX 4070 Ti SUPER");
            system.row("Driver:", "581.57.0.0");
            system.row("API:", "Vulkan 1.4.312");
            system.gap();
            system.header("Memory");
            this.memory.add(system.row("Used:"));
            this.memory.add(system.row("Allocated:"));
            system.row("Total:", "8.00GB");
            system.gap();
            system.header("Client (ms, peak)");
            for (final String key : List.of("Frame:", "Network:", "Tasks:", "Input:", "Gamepads:", "Update:", "Render:",
                    "  GPU wait:", "  Image wait:", "  Mesh schedule:", "  Uploads:", "  World:", "  Overlay:",
                    "  UI:", "  Submit:", "  Present:")) {
                this.timings.add(system.row(key));
            }
            system.gap();
            system.header("Server (ms, peak)");
            for (final String key : List.of("Tick rate:", "Tick:", "Network:", "Level:", "Movement:", "Physics:",
                    "Entities:", "Sync:", "Chunks:")) {
                this.timings.add(system.row(key));
            }
            system.gap();
            system.header("Meshing");
            for (final String key : List.of("Queue:", "Rate:", "Uploads:", "Memory:", "Drawn:")) {
                system.row(key, "0 waiting, 0 running, 0 uploading");
            }
            this.update();
            return new Layout(factory, 2.5f).setPadding(2.5f)
                    .add(game.panel())
                    .add(title)
                    .add(system.panel());
        }

        @Override
        public void opened(final ScreenHandle handle) {
            handle.every(100, this::update);
            handle.every(1000, () -> this.elapsed.setText(Text.of("0m " + this.seconds++ + "s")));
        }

        private void update() {
            this.updates++;
            final String[] game = {
                "120", "1280x720", "12x, 64y, -30z", "yaw 90, pitch -12", "forward 0, strafe 0", "false", "stone",
                "flying", "12.4x, 65.6y, -30.2z", "0 blocks/s", "stone at 12, 63, -30", "blocks, place stone",
                "441 loaded, 438 meshed", "441 in memory, 0 unloading", "0, 0 failed", "1",
            };
            for (int i = 0; i < this.game.size(); i++) {
                this.game.get(i).setText(Text.of(game[i]));
            }
            this.memory.get(0).setText(Text.of((300 + this.random.nextInt(200)) + "MB"));
            this.memory.get(1).setText(Text.of("1.02GB"));
            for (int i = 0; i < this.timings.size(); i++) {
                final String text = i == 0
                        ? String.format(Locale.ROOT, "%.2f of 8.33 (%.0f%%), %.2f", this.ms(), this.ms() * 12,
                                this.ms())
                        : String.format(Locale.ROOT, "%.2f, %.2f", this.ms(), this.ms());
                this.timings.get(i).setText(Text.of(text));
            }
        }

        private double ms() {
            return this.random.nextDouble() * 3;
        }
    }

    /**
     * A grid of keys and values, as Wintergreen's HUD builds them.
     */
    private static final class Grid {
        private final ComponentFactory factory;
        private final GridPanel panel;
        private int row;

        Grid(final ComponentFactory factory) {
            this.factory = factory;
            this.panel = Grid.panel(factory).columns(GridLength.auto(), GridLength.fixed(10), GridLength.weighted(1));
        }

        static GridPanel panel(final ComponentFactory factory) {
            return factory.gridPanel()
                    .setBackground(Paint.solid(0xA0000000))
                    .setPadding(5)
                    .setAlignment(HorizontalAlignment.LEFT, VerticalAlignment.TOP);
        }

        GridPanel panel() {
            return this.panel;
        }

        void header(final String text) {
            this.panel.addRow(GridLength.auto());
            this.panel.add(this.factory.label(Text.of(text).bold())
                    .setAlignment(HorizontalAlignment.CENTER, VerticalAlignment.TOP), this.row++, 0, 1, 3);
        }

        Label row(final String key) {
            this.panel.addRow(GridLength.auto());
            this.panel.add(this.factory.label(key).setAlignment(HorizontalAlignment.RIGHT, VerticalAlignment.TOP),
                    this.row, 0);
            final Label value = this.factory.label("");
            this.panel.add(value, this.row++, 2);
            return value;
        }

        void row(final String key, final String value) {
            this.row(key).setText(Text.of(value));
        }

        void gap() {
            this.panel.addRow(GridLength.fixed(5));
            this.row++;
        }
    }

    /**
     * Wintergreen's HUD layout: three children side by side when they fit, else wrapped.
     */
    private static final class Layout extends CustomContainer<Layout> {
        static int measures;
        private final float spacing;

        Layout(final ComponentFactory factory, final float spacing) {
            super(factory);
            this.spacing = spacing;
        }

        @Override
        protected Size measure(final MeasureContext context, final float availableWidth, final float availableHeight) {
            measures++;
            final Insets padding = this.padding();
            final float width = availableWidth - padding.horizontal();
            final List<Component<?>> children = this.children();
            final Size[] sizes = new Size[children.size()];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = context.measure(children.get(i), width, availableHeight - padding.vertical());
            }
            final float[] slots = this.layout(sizes, width);
            final float desired = Float.isFinite(availableWidth) ? availableWidth : slots[0] + padding.horizontal();
            return new Size(desired, slots[1] + padding.vertical());
        }

        @Override
        protected void arrange(final ArrangeContext context, final Rect bounds) {
            final Rect content = this.contentBox();
            final List<Component<?>> children = this.children();
            final Size[] sizes = new Size[children.size()];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = context.outerSize(children.get(i));
            }
            final float[] slots = this.layout(sizes, content.width());
            for (int i = 0; i < sizes.length; i++) {
                context.arrange(children.get(i), new Rect(content.x() + slots[2 + i * 2],
                        content.y() + slots[3 + i * 2], sizes[i].width(), sizes[i].height()));
            }
        }

        /**
         * Returns the width and height the children take, then each child's x and y.
         */
        private float[] layout(final Size[] sizes, final float width) {
            final float[] out = new float[2 + sizes.length * 2];
            if (sizes.length == 3) {
                final float left = sizes[0].width();
                final float center = sizes[1].width();
                final float right = sizes[2].width();
                if (2f * (Math.max(left, right) + this.spacing) + center <= width) {
                    out[2 + 2] = (width - center) / 2f;
                    out[2 + 4] = width - right;
                    out[0] = width;
                    out[1] = Math.max(sizes[0].height(), Math.max(sizes[1].height(), sizes[2].height()));
                    return out;
                }
            }
            float lineX = 0f;
            float lineY = 0f;
            float lineHeight = 0f;
            for (int i = 0; i < sizes.length; i++) {
                if (lineX > 0f && lineX + sizes[i].width() > width) {
                    lineY += lineHeight + this.spacing;
                    lineX = 0f;
                    lineHeight = 0f;
                }
                out[2 + i * 2] = lineX;
                out[3 + i * 2] = lineY;
                lineX += sizes[i].width() + this.spacing;
                lineHeight = Math.max(lineHeight, sizes[i].height());
                out[0] = Math.max(out[0], lineX - this.spacing);
            }
            out[1] = lineY + lineHeight;
            return out;
        }
    }
}

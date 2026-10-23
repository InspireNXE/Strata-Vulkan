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

import org.inspirenxe.strata.api.input.Modifier;
import org.inspirenxe.strata.spi.Capabilities;
import org.inspirenxe.strata.spi.Clipboard;
import org.inspirenxe.strata.spi.Clock;
import org.inspirenxe.strata.spi.Conventions;
import org.inspirenxe.strata.spi.Platform;
import org.inspirenxe.strata.spi.TextInputSink;
import org.inspirenxe.strata.spi.ThemeSource;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * The platform for applications that draw with Vulkan. Vulkan only draws, so everything else a platform answers comes
 * from the application, through the {@link Builder}: the clipboard, whether its input sends key repeats, and its
 * executor for the render thread. {@link org.inspirenxe.strata.vulkan.glfw.StrataGlfw#platform} fills these in for a
 * GLFW window.
 *
 * <p>The canvas draws every shape itself, so {@link Capabilities#drawsShapes()} is true. It does not slice sprites
 * natively, does not shape right-to-left text, and has a monospaced font when the text metrics have one.</p>
 *
 * <p>Without a builder setting it: the clipboard is {@link Clipboard#NONE}, the clock is {@link Clock#SYSTEM}, there is
 * no text input signal and no file dialogs, key repeats are made by the host, themes come from a
 * {@link VulkanThemeSource} on this class's class loader, and shortcuts use Command on macOS and Control elsewhere.
 * Without an executor, tasks handed to the UI thread run at the start of the next frame, and the UI thread is the
 * first one that renders.</p>
 */
public final class VulkanPlatform implements Platform {
    private final VulkanTextMetrics textMetrics;
    private final Clipboard clipboard;
    private final Clock clock;
    private final TextInputSink textInput;
    private final Capabilities capabilities;
    private final Conventions conventions;
    private final ThemeSource themes;
    private final Optional<Executor> uiExecutor;
    private final Optional<BooleanSupplier> uiThreadCheck;

    private VulkanPlatform(final Builder builder) {
        this.textMetrics = builder.textMetrics;
        this.clipboard = builder.clipboard;
        this.clock = builder.clock;
        this.textInput = builder.textInput;
        this.capabilities = new Capabilities(builder.sendsKeyRepeat, builder.textMetrics.hasMonospace(), false, true,
                false);
        this.conventions = builder.conventions;
        this.themes = builder.themes != null ? builder.themes
                : new VulkanThemeSource(VulkanPlatform.class.getClassLoader());
        this.uiExecutor = Optional.ofNullable(builder.uiExecutor);
        this.uiThreadCheck = Optional.ofNullable(builder.uiThreadCheck);
    }

    /**
     * Creates the platform with every default.
     *
     * @param textMetrics the fonts to measure and draw text with
     */
    public VulkanPlatform(final VulkanTextMetrics textMetrics) {
        this(VulkanPlatform.builder(textMetrics));
    }

    /**
     * Starts building a platform.
     *
     * @param textMetrics the fonts to measure and draw text with
     * @return the builder
     */
    public static Builder builder(final VulkanTextMetrics textMetrics) {
        return new Builder(textMetrics);
    }

    @Override
    public VulkanTextMetrics textMetrics() {
        return this.textMetrics;
    }

    @Override
    public Clipboard clipboard() {
        return this.clipboard;
    }

    @Override
    public Clock clock() {
        return this.clock;
    }

    @Override
    public TextInputSink textInput() {
        return this.textInput;
    }

    @Override
    public Capabilities capabilities() {
        return this.capabilities;
    }

    @Override
    public Conventions conventions() {
        return this.conventions;
    }

    @Override
    public ThemeSource themes() {
        return this.themes;
    }

    @Override
    public Optional<Executor> uiExecutor() {
        return this.uiExecutor;
    }

    @Override
    public Optional<BooleanSupplier> uiThreadCheck() {
        return this.uiThreadCheck;
    }

    /**
     * Builds a {@link VulkanPlatform}. Every setting has a default, given on {@link VulkanPlatform}.
     */
    public static final class Builder {
        private final VulkanTextMetrics textMetrics;
        private Clipboard clipboard = Clipboard.NONE;
        private Clock clock = Clock.SYSTEM;
        private TextInputSink textInput = TextInputSink.NONE;
        private boolean sendsKeyRepeat;
        private Conventions conventions = new Conventions(false, Builder.shortcutModifier(), true);
        private ThemeSource themes;
        private Executor uiExecutor;
        private BooleanSupplier uiThreadCheck;

        private Builder(final VulkanTextMetrics textMetrics) {
            this.textMetrics = Objects.requireNonNull(textMetrics, "textMetrics");
        }

        /**
         * Sets the clipboard text boxes copy to and paste from.
         *
         * @param clipboard the clipboard
         * @return this builder
         */
        public Builder clipboard(final Clipboard clipboard) {
            this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
            return this;
        }

        /**
         * Sets the clock for click counting, caret blinking and key repeats.
         *
         * @param clock a monotonic clock
         * @return this builder
         */
        public Builder clock(final Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets where hosts announce that a text field gained or lost focus, for a windowing library that opens an
         * input method or an on-screen keyboard.
         *
         * @param textInput the sink
         * @return this builder
         */
        public Builder textInput(final TextInputSink textInput) {
            this.textInput = Objects.requireNonNull(textInput, "textInput");
            return this;
        }

        /**
         * Says whether the application's input delivers key repeats itself, as
         * {@link Capabilities#sendsKeyRepeat()} describes. GLFW does.
         *
         * @param sendsKeyRepeat true when held keys arrive as repeats
         * @return this builder
         */
        public Builder sendsKeyRepeat(final boolean sendsKeyRepeat) {
            this.sendsKeyRepeat = sendsKeyRepeat;
            return this;
        }

        /**
         * Sets how the application's own controls behave, so Strata's match them.
         *
         * @param conventions the conventions
         * @return this builder
         */
        public Builder conventions(final Conventions conventions) {
            this.conventions = Objects.requireNonNull(conventions, "conventions");
            return this;
        }

        /**
         * Sets where themes named by id come from.
         *
         * @param themes the theme source
         * @return this builder
         */
        public Builder themes(final ThemeSource themes) {
            this.themes = Objects.requireNonNull(themes, "themes");
            return this;
        }

        /**
         * Sets the executor for the render thread, the thread that renders the views and sends them input, for work
         * handed to the UI thread while nothing renders. It must follow {@link Platform#uiExecutor()}.
         *
         * @param executor the executor
         * @return this builder
         */
        public Builder uiExecutor(final Executor executor) {
            this.uiExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Sets the test that answers whether the calling thread is the render thread. It must follow
         * {@link Platform#uiThreadCheck()}.
         *
         * @param check the test
         * @return this builder
         */
        public Builder uiThreadCheck(final BooleanSupplier check) {
            this.uiThreadCheck = Objects.requireNonNull(check, "check");
            return this;
        }

        /**
         * Builds the platform.
         *
         * @return the platform
         */
        public VulkanPlatform build() {
            return new VulkanPlatform(this);
        }

        private static Modifier shortcutModifier() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")
                    ? Modifier.SUPER
                    : Modifier.CONTROL;
        }
    }
}

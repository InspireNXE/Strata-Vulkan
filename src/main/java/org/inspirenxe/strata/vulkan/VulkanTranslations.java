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

import org.inspirenxe.strata.core.StrataCore;
import org.inspirenxe.strata.spi.Translator;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Sets where {@linkplain org.inspirenxe.strata.api.text.Text#translatable translated text} comes from in an
 * application that draws with Vulkan. There is one translator for the whole process; until one is set, Strata's own
 * keys read in English and other keys read as themselves. Open views lay out again on their next frame after a
 * change.
 *
 * <pre>{@code
 * VulkanTranslations.use(ResourceBundle.getBundle("shop.messages", Locale.GERMAN));
 * }</pre>
 */
public final class VulkanTranslations {

    private VulkanTranslations() {
    }

    /**
     * Translates with a resource bundle. Keys the bundle lacks fall back as if no translator were set. Call this
     * again with another bundle to change the language.
     *
     * <p>The bundle's {@linkplain ResourceBundle#getLocale() locale} becomes the translator's locale, which dates and
     * times follow: month and weekday names, the first day of the week, the order of a date's parts and the 12 or 24
     * hour clock. A base bundle, whose locale is {@link Locale#ROOT}, leaves the JVM's default locale for formatting
     * in place.</p>
     *
     * @param bundle the bundle, whose values are patterns with {@code %s}, {@code %1$s} and {@code %%}
     */
    public static void use(final ResourceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        final Locale locale = bundle.getLocale() == null || bundle.getLocale().equals(Locale.ROOT)
                ? Locale.getDefault(Locale.Category.FORMAT) : bundle.getLocale();
        VulkanTranslations.use(new Translator() {
            @Override
            public Optional<String> translate(final String key) {
                return bundle.containsKey(key) ? Optional.of(bundle.getString(key)) : Optional.empty();
            }

            @Override
            public long version() {
                return 0L;
            }

            @Override
            public Locale locale() {
                return locale;
            }
        });
    }

    /**
     * Translates with any translator, such as one that reads a different file format. The translator must follow
     * the {@link Translator} contract: it may be called from any thread, and its version must change whenever what
     * it returns may.
     *
     * @param translator the translator
     */
    public static void use(final Translator translator) {
        StrataCore.setTranslator(translator);
    }
}

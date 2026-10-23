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

import org.inspirenxe.strata.core.text.Translations;
import org.inspirenxe.strata.spi.Translator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VulkanTranslationsTest {

    @AfterEach
    void reset() {
        VulkanTranslations.use(Translator.NONE);
    }

    @Test
    void aBundleReportsItsLocale() {
        VulkanTranslations.use(ResourceBundle.getBundle(TestMessages.class.getName(), Locale.GERMANY));
        assertEquals(Locale.GERMAN, Translations.translator().locale());
        assertEquals(Optional.of("Laden"), Translations.translator().translate("shop.title"));
    }

    @Test
    void aBaseBundleKeepsTheDefaultFormatLocale() {
        VulkanTranslations.use(ResourceBundle.getBundle(TestMessages.class.getName(), Locale.ROOT,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_CLASS)));
        assertEquals(Locale.getDefault(Locale.Category.FORMAT), Translations.translator().locale());
        assertEquals(Optional.of("Shop"), Translations.translator().translate("shop.title"));
    }
}

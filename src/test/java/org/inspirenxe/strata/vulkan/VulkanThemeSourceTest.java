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

import org.inspirenxe.strata.api.control.button.Button;
import org.inspirenxe.strata.api.theme.Paint;
import org.inspirenxe.strata.api.theme.Theme;
import org.inspirenxe.strata.api.theme.ThemeException;
import org.inspirenxe.strata.api.theme.ThemeId;
import org.inspirenxe.strata.api.theme.ThemeRef;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.spi.ThemeDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkanThemeSourceTest {
    private static final ThemeId ID = ThemeId.of("mod", "shop");

    private static void write(final Path root, final String content) throws IOException {
        final Path file = root.resolve("assets/mod/themes/shop.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void earlierClassPathEntriesComeLast(@TempDir final Path first, @TempDir final Path second) throws IOException {
        write(first, "first");
        write(second, "second");
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
            final List<ThemeDocument> documents = new VulkanThemeSource(loader).documents(ID);
            assertEquals(List.of("second", "first"), documents.stream().map(ThemeDocument::content).toList());
        }
    }

    @Test
    void reloadReadsTheFilesAgain(@TempDir final Path root) throws IOException {
        write(root, "old");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            final VulkanThemeSource source = new VulkanThemeSource(loader);
            assertEquals("old", source.documents(ID).getFirst().content());
            write(root, "new");
            assertEquals("old", source.documents(ID).getFirst().content());
            final long version = source.version();
            source.reload();
            assertNotEquals(version, source.version());
            assertEquals("new", source.documents(ID).getFirst().content());
        }
    }

    @Test
    void registeredThemesAreBases(@TempDir final Path root) throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            final VulkanThemeSource source = new VulkanThemeSource(loader);
            assertTrue(source.base(ID).isEmpty());
            assertTrue(source.documents(ID).isEmpty());
            source.register(ID, Themes.flat());
            assertTrue(source.base(ID).isPresent());
        }
    }

    @Test
    void registeredFilesAreBaseFilesAndReplaceABaseTheme(@TempDir final Path root) throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            final VulkanThemeSource source = new VulkanThemeSource(loader);
            source.register(ID, Themes.flat());
            final long version = source.version();
            final ThemeDocument base = new ThemeDocument("shop.json",
                    "{ \"parent\": \"strata:flat\", \"paints\": { \"button.normal\": \"#000001\" } }", "shop");
            source.register(ID, List.of(base));
            assertNotEquals(version, source.version());
            assertTrue(source.base(ID).isEmpty());
            assertEquals(List.of(base), source.bases(ID));
            assertThrows(IllegalArgumentException.class, () -> source.register(ID, List.of()));

            write(root, "{ \"paints\": { \"button.hovered\": \"#000002\" } }");
            source.reload();
            final Theme theme = source.resolve(ThemeRef.of(ID));
            assertEquals(Paint.solid(0xFF000001), theme.paint(Button.STYLE_NORMAL).orElseThrow());
            assertEquals(Optional.of("shop"), theme.layer(Button.STYLE_NORMAL));
            assertEquals(Paint.solid(0xFF000002), theme.paint(Button.STYLE_HOVERED).orElseThrow());
            assertEquals(Optional.of(root.toUri().toURL().toString()), theme.layer(Button.STYLE_HOVERED));
            assertEquals(Themes.flat().paint(Button.STYLE_PRESSED), theme.paint(Button.STYLE_PRESSED));

            source.register(ID, Themes.flat());
            assertTrue(source.bases(ID).isEmpty());
        }
    }

    @Test
    void aBrokenBaseFileFailsToResolve(@TempDir final Path root) throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            final VulkanThemeSource source = new VulkanThemeSource(loader);
            source.register(ID, List.of(new ThemeDocument("broken.json", "{ nope", "shop")));
            final ThemeException e = assertThrows(ThemeException.class, () -> source.resolve(ThemeRef.of(ID)));
            assertTrue(e.getMessage().contains("broken.json"), e.getMessage());
        }
    }

    @Test
    void aJarsLayerIsItsFileName() throws IOException {
        final String name = "assets/mod/themes/shop.json";
        assertEquals("fancy.jar", VulkanThemeSource.layerOf(
                URI.create("jar:file:/C:/packs/fancy.jar!/" + name).toURL(), name));
        assertEquals("file:/C:/build/resources/main/", VulkanThemeSource.layerOf(
                URI.create("file:/C:/build/resources/main/" + name).toURL(), name));
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds the fonts {@link FreeTypeFont#system} and {@link FreeTypeFont#systemMonospace} use, by file name in the
 * operating system's font folders.
 */
final class SystemFonts {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static Map<String, Path> linuxFiles;

    /** Families as their regular, bold, italic and bold italic files, the first one found wins. */
    private static final List<List<String>> WINDOWS_SANS = List.of(
            List.of("segoeui.ttf", "segoeuib.ttf", "segoeuii.ttf", "segoeuiz.ttf"),
            List.of("arial.ttf", "arialbd.ttf", "ariali.ttf", "arialbi.ttf"));
    private static final List<List<String>> WINDOWS_MONO = List.of(
            List.of("consola.ttf", "consolab.ttf", "consolai.ttf", "consolaz.ttf"),
            List.of("cour.ttf", "courbd.ttf", "couri.ttf", "courbi.ttf"));
    private static final List<String> WINDOWS_FALLBACKS = List.of("seguisym.ttf", "msyh.ttc", "malgun.ttf",
            "seguiemj.ttf");

    private static final List<List<String>> MAC_SANS = List.of(
            List.of("/System/Library/Fonts/Supplemental/Arial.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
                    "/System/Library/Fonts/Supplemental/Arial Italic.ttf",
                    "/System/Library/Fonts/Supplemental/Arial Bold Italic.ttf"),
            List.of("/Library/Fonts/Arial.ttf", "/Library/Fonts/Arial Bold.ttf", "/Library/Fonts/Arial Italic.ttf",
                    "/Library/Fonts/Arial Bold Italic.ttf"));
    private static final List<String> MAC_MONO = List.of("/System/Library/Fonts/Menlo.ttc",
            "/System/Library/Fonts/Monaco.ttf");
    private static final List<String> MAC_FALLBACKS = List.of("/System/Library/Fonts/Apple Symbols.ttf",
            "/System/Library/Fonts/Hiragino Sans GB.ttc");

    private static final List<List<String>> LINUX_SANS = List.of(
            List.of("DejaVuSans.ttf", "DejaVuSans-Bold.ttf", "DejaVuSans-Oblique.ttf", "DejaVuSans-BoldOblique.ttf"),
            List.of("NotoSans-Regular.ttf", "NotoSans-Bold.ttf", "NotoSans-Italic.ttf", "NotoSans-BoldItalic.ttf"),
            List.of("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf",
                    "LiberationSans-BoldItalic.ttf"));
    private static final List<List<String>> LINUX_MONO = List.of(
            List.of("DejaVuSansMono.ttf", "DejaVuSansMono-Bold.ttf", "DejaVuSansMono-Oblique.ttf",
                    "DejaVuSansMono-BoldOblique.ttf"),
            List.of("NotoSansMono-Regular.ttf", "NotoSansMono-Bold.ttf", "", ""),
            List.of("LiberationMono-Regular.ttf", "LiberationMono-Bold.ttf", "LiberationMono-Italic.ttf",
                    "LiberationMono-BoldItalic.ttf"));
    private static final List<String> LINUX_FALLBACKS = List.of("NotoSansSymbols2-Regular.ttf",
            "NotoSansCJK-Regular.ttc", "DroidSansFallbackFull.ttf", "Symbola.ttf");

    private SystemFonts() {
    }

    static Optional<FreeTypeFont> sans(final float size) {
        if (OS.startsWith("windows")) {
            return SystemFonts.family(size, WINDOWS_SANS, SystemFonts::windows, WINDOWS_FALLBACKS);
        }
        if (OS.startsWith("mac")) {
            return SystemFonts.family(size, MAC_SANS, SystemFonts::mac, MAC_FALLBACKS);
        }
        return SystemFonts.family(size, LINUX_SANS, SystemFonts::linux, LINUX_FALLBACKS);
    }

    static Optional<FreeTypeFont> monospace(final float size) {
        if (OS.startsWith("windows")) {
            return SystemFonts.family(size, WINDOWS_MONO, SystemFonts::windows, List.of());
        }
        if (OS.startsWith("mac")) {
            for (final String name : MAC_MONO) {
                final Path file = Path.of(name);
                if (Files.isRegularFile(file)) {
                    try {
                        final FreeTypeFont.Builder builder = FreeTypeFont.builder(size);
                        return Optional.of(name.endsWith(".ttc") ? builder.collection(file).build()
                                : builder.regular(file).build());
                    } catch (final IOException | IllegalArgumentException ignored) {
                        // Try the next one
                    }
                }
            }
            return Optional.empty();
        }
        return SystemFonts.family(size, LINUX_MONO, SystemFonts::linux, List.of());
    }

    private static Optional<FreeTypeFont> family(final float size, final List<List<String>> families,
                                               final Resolver resolver, final List<String> fallbacks) {
        for (final List<String> family : families) {
            final Path regular = resolver.find(family.get(0));
            if (regular == null) {
                continue;
            }
            final FreeTypeFont.Builder builder = FreeTypeFont.builder(size).regular(regular);
            final Path bold = resolver.find(family.get(1));
            final Path italic = resolver.find(family.get(2));
            final Path boldItalic = resolver.find(family.get(3));
            if (bold != null) {
                builder.bold(bold);
            }
            if (italic != null) {
                builder.italic(italic);
            }
            if (boldItalic != null) {
                builder.boldItalic(boldItalic);
            }
            for (final String name : fallbacks) {
                final Path fallback = resolver.find(name);
                if (fallback != null) {
                    builder.fallback(fallback, 0);
                }
            }
            try {
                return Optional.of(builder.build());
            } catch (final IOException | IllegalArgumentException ignored) {
                // A font that cannot be read: try the next family
            }
        }
        return Optional.empty();
    }

    private static Path windows(final String name) {
        if (name.isEmpty()) {
            return null;
        }
        final String windows = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
        final Path system = Path.of(windows, "Fonts", name);
        if (Files.isRegularFile(system)) {
            return system;
        }
        final String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            final Path user = Path.of(local, "Microsoft", "Windows", "Fonts", name);
            if (Files.isRegularFile(user)) {
                return user;
            }
        }
        return null;
    }

    private static Path mac(final String name) {
        if (name.isEmpty()) {
            return null;
        }
        final Path file = Path.of(name);
        return Files.isRegularFile(file) ? file : null;
    }

    private static synchronized Path linux(final String name) {
        if (name.isEmpty()) {
            return null;
        }
        if (linuxFiles == null) {
            linuxFiles = new HashMap<>();
            final String home = System.getProperty("user.home", "");
            for (final String folder : List.of("/usr/share/fonts", "/usr/local/share/fonts",
                    home + "/.local/share/fonts", home + "/.fonts")) {
                final Path root = Path.of(folder);
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(root, 6)) {
                    files.filter(Files::isRegularFile)
                            .forEach(file -> linuxFiles.putIfAbsent(file.getFileName().toString(), file));
                } catch (final IOException | RuntimeException ignored) {
                    // A folder that cannot be listed adds nothing
                }
            }
        }
        return linuxFiles.get(name);
    }

    @FunctionalInterface
    private interface Resolver {
        Path find(String name);
    }
}

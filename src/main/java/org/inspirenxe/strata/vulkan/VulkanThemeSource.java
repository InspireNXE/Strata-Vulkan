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

import org.inspirenxe.strata.api.theme.Theme;
import org.inspirenxe.strata.api.theme.ThemeId;
import org.inspirenxe.strata.api.theme.ThemeRef;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.core.StrataCore;
import org.inspirenxe.strata.spi.ThemeDocument;
import org.inspirenxe.strata.spi.ThemeSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finds theme files on the class path at {@code assets/<namespace>/themes/<path>.json}, next to where
 * {@link SpriteResolver#DEFAULT} looks for sprites, or wherever a {@link ThemePathResolver} says. Every class path
 * entry that has the file contributes one document. An entry earlier on the class path has higher priority, as it
 * does for any other resource. Each document's layer is named after the entry it came from: a jar's file name, or
 * the URL of a directory.
 *
 * <p>A theme's base can be registered in two ways: a theme built in code with {@link #register(ThemeId, Theme)}, or
 * base files with {@link #register(ThemeId, List)}, for a theme written as JSON. Files are read once per id and
 * cached until {@link #reload()}, which also tells open hosts to resolve their themes again. A file that cannot be
 * read is left out.</p>
 *
 * <p>The platform's default theme, which screens opened without a theme use and {@code ThemeRef.platform()} names,
 * is {@link Themes#FLAT} until {@link #setDefaultTheme} changes it.</p>
 *
 * <p>Every method may be called from any thread.</p>
 */
public final class VulkanThemeSource implements ThemeSource {
    private final ClassLoader loader;
    private final ThemePathResolver resolver;
    private final Map<ThemeId, Theme> bases;
    private final Map<ThemeId, List<ThemeDocument>> baseFiles;
    private final Map<ThemeId, List<ThemeDocument>> cache;
    private final AtomicLong version;
    private volatile ThemeId defaultTheme = Themes.FLAT;

    /**
     * Creates a source.
     *
     * @param loader the class loader to find theme files with
     */
    public VulkanThemeSource(final ClassLoader loader) {
        this(loader, ThemePathResolver.DEFAULT);
    }

    /**
     * Creates a source that finds theme files where a resolver says.
     *
     * @param loader the class loader to find theme files with
     * @param resolver where each theme's files live on the class path
     */
    public VulkanThemeSource(final ClassLoader loader, final ThemePathResolver resolver) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.bases = new HashMap<>();
        this.baseFiles = new HashMap<>();
        this.cache = new HashMap<>();
        this.version = new AtomicLong();
    }

    /**
     * Registers a theme built in code under an id, replacing any base theme or base files registered under it
     * before, and reloads.
     *
     * @param id the id theme refs and theme files use
     * @param theme the theme
     */
    public synchronized void register(final ThemeId id, final Theme theme) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(theme, "theme");
        this.baseFiles.remove(id);
        this.bases.put(id, theme);
        this.reload();
    }

    /**
     * Registers base files under an id, replacing any base theme or base files registered under it before, and
     * reloads. Core starts the theme from the {@code parent} the last of them names, applies them in order, and then
     * applies the theme files the class path has for the id, whose {@code parent} it ignores. Each file's keys
     * report its {@link ThemeDocument#layer}. A base file that cannot be parsed makes the theme fail to resolve with
     * a {@link org.inspirenxe.strata.api.theme.ThemeException} naming the file's origin.
     *
     * <p>Keep the files somewhere the {@link ThemePathResolver} does not look, or they apply twice, once as base
     * files and once as theme files.</p>
     *
     * @param id the id theme refs and theme files use
     * @param documents the base files, lowest priority first; copied
     * @throws IllegalArgumentException if there are no documents
     */
    public synchronized void register(final ThemeId id, final List<ThemeDocument> documents) {
        Objects.requireNonNull(id, "id");
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("Theme " + id + " needs at least one base file");
        }
        this.bases.remove(id);
        this.baseFiles.put(id, List.copyOf(documents));
        this.reload();
    }

    /**
     * Sets the platform's default theme for every view whose platform reads this source, and reloads, so open
     * screens in the default theme change to it on their next layout or render.
     *
     * @param id the theme screens opened without a theme use
     * @throws IllegalArgumentException if the id is {@link Themes#PLATFORM}, which stands for the default
     */
    public void setDefaultTheme(final ThemeId id) {
        if (Objects.requireNonNull(id, "id").equals(Themes.PLATFORM)) {
            throw new IllegalArgumentException(Themes.PLATFORM + " stands for the default theme and cannot be it");
        }
        this.defaultTheme = id;
        this.reload();
    }

    @Override
    public ThemeId defaultTheme() {
        return this.defaultTheme;
    }

    /**
     * Drops the cached files and changes {@link #version()}, so open hosts read the files again on their next
     * layout or render. Call it after theme files change, for example while editing a theme.
     */
    public synchronized void reload() {
        this.cache.clear();
        this.version.incrementAndGet();
    }

    /**
     * Resolves a ref against this source once, with the themes and overrides added in code, as a host does when it
     * opens. Tools use it to get a fixed {@link Theme}, for example to adjust some of its paints with
     * {@link Theme#toBuilder()}.
     *
     * @param ref the ref
     * @return the theme
     * @throws org.inspirenxe.strata.api.theme.ThemeException if the ref cannot be resolved
     */
    public Theme resolve(final ThemeRef ref) {
        return StrataCore.resolveTheme(this, ref);
    }

    @Override
    public synchronized Optional<Theme> base(final ThemeId id) {
        return Optional.ofNullable(this.bases.get(id));
    }

    @Override
    public synchronized List<ThemeDocument> bases(final ThemeId id) {
        return this.baseFiles.getOrDefault(id, List.of());
    }

    @Override
    public synchronized List<ThemeDocument> documents(final ThemeId id) {
        return this.cache.computeIfAbsent(id, this::read);
    }

    @Override
    public long version() {
        return this.version.get();
    }

    private List<ThemeDocument> read(final ThemeId id) {
        final String name = this.resolver.resourcePath(id);
        final List<ThemeDocument> documents = new ArrayList<>();
        try {
            final Enumeration<URL> urls = this.loader.getResources(name);
            while (urls.hasMoreElements()) {
                final URL url = urls.nextElement();
                try (InputStream in = url.openStream()) {
                    documents.add(new ThemeDocument(url.toString(), new String(in.readAllBytes(),
                            StandardCharsets.UTF_8), VulkanThemeSource.layerOf(url, name)));
                } catch (final IOException ignored) {
                    // An unreadable file is left out, as the ThemeSource contract asks
                }
            }
        } catch (final IOException ignored) {
            // No files can be listed; resolve with none
        }
        // The class path lists the highest priority first; documents want it last
        Collections.reverse(documents);
        return List.copyOf(documents);
    }

    /**
     * Names the class path entry a resource was found in: a jar's file name, or the URL of a directory.
     *
     * @param url where the resource was found
     * @param name the resource's path
     * @return the layer name
     */
    static String layerOf(final URL url, final String name) {
        final String text = url.toString();
        final String root = text.endsWith(name) ? text.substring(0, text.length() - name.length()) : text;
        if (root.startsWith("jar:") && root.endsWith("!/")) {
            final String jar = root.substring("jar:".length(), root.length() - "!/".length());
            return jar.substring(jar.lastIndexOf('/') + 1);
        }
        return root;
    }
}

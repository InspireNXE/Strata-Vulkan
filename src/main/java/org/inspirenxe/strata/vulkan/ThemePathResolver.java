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

import org.inspirenxe.strata.api.theme.ThemeId;

/**
 * Turns a theme id into the class path resource that holds its theme files, for {@link VulkanThemeSource}.
 *
 * <p>Implementations must return a path without a leading slash, in the form {@link ClassLoader#getResources}
 * takes, and must return the same path every time for the same id.</p>
 */
@FunctionalInterface
public interface ThemePathResolver {
    /**
     * The default layout: {@code assets/<namespace>/themes/<path>.json}.
     */
    ThemePathResolver DEFAULT = theme -> "assets/" + theme.namespace() + "/themes/" + theme.path() + ".json";

    /**
     * Returns the resource path of a theme's files.
     *
     * @param theme the theme
     * @return the resource path, without a leading slash
     */
    String resourcePath(ThemeId theme);
}

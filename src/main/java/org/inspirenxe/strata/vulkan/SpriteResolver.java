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

import org.inspirenxe.strata.api.theme.SpriteId;

/**
 * Maps a sprite id to the class path resource that holds its image, for {@link SpriteSource#classPath}. Implementations
 * must return the same path every time for the same id. They are called on the UI thread.
 */
@FunctionalInterface
public interface SpriteResolver {
    /**
     * The default layout: {@code assets/<namespace>/sprites/<path>.png}, the same as the Swing adapter's.
     */
    SpriteResolver DEFAULT = sprite -> "assets/" + sprite.namespace() + "/sprites/" + sprite.path() + ".png";

    /**
     * Returns the resource path of a sprite's image.
     *
     * @param sprite the sprite
     * @return the resource path, without a leading slash
     */
    String resourcePath(SpriteId sprite);
}

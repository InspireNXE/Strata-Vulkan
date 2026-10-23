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

/**
 * The window a {@link StrataVulkan} view sits in, as far as Strata needs it: the mouse cursor and the title. Vulkan
 * has no windows, so the application or a bridge such as {@link org.inspirenxe.strata.vulkan.glfw.StrataGlfw}
 * implements it for its windowing library and gives it to the view with {@link StrataVulkan#setWindow}.
 *
 * <p>The view calls it on the render thread, at the end of every {@link StrataVulkan#render}.</p>
 */
public interface VulkanWindow {

    /**
     * Returns whether the window can show a cursor kind. The view asks during its frame, and asks for the kind's
     * {@linkplain CursorKind#fallback() fallback} when the answer is false. It must answer true for
     * {@link CursorKind#ARROW}, quickly and without side effects.
     *
     * @param cursor the kind
     * @return true if {@link #showCursor} shows it as itself
     */
    boolean showsCursor(CursorKind cursor);

    /**
     * Shows a cursor over the view, after every frame: the one the frame asked for, or {@link CursorKind#ARROW} when it
     * asked for none, such as while the pointer is outside every component. Only kinds {@link #showsCursor} accepted
     * arrive. The view calls it every frame, so an implementation should do nothing when the cursor is already shown.
     *
     * @param cursor the cursor
     */
    void showCursor(CursorKind cursor);

    /**
     * Shows the title of the view's top screen, translated, whenever it changes. The default does nothing.
     *
     * @param title the title, empty when the top screen has none or no screen is open
     */
    default void showTitle(final String title) {
    }
}

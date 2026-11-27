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
package org.inspirenxe.strata.vulkan.glfw;

import org.inspirenxe.strata.vulkan.VulkanWindow;

/**
 * The window a {@link StrataHost} sits in, for an application that runs GLFW on a different thread from the one that
 * renders. GLFW allows most calls only on the main thread, so the application answers for the window here and the
 * view never calls GLFW itself.
 *
 * <p>The view calls every method on the thread that renders. {@link #showsCursor}, {@link #showCursor} and
 * {@link #showTitle} come from {@link VulkanWindow}: an implementation hands the change to the window's thread and
 * returns at once, and answers {@link #showsCursor} from what it knows without asking GLFW. {@link #metrics()} must
 * answer from a snapshot the window's thread keeps, so it needs no call to GLFW either.</p>
 */
public interface WindowHost extends VulkanWindow {

    /**
     * Returns the window's size and the display's scale as the window's thread last saw them. The view reads it when it
     * is created, and again whenever {@link StrataHost#updateSize()} is called.
     *
     * @return the metrics, never null
     */
    WindowMetrics metrics();
}

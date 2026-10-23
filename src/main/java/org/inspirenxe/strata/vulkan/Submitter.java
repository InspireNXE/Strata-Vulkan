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

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.function.Consumer;

/**
 * A device with a queue that records, submits and waits, for drawing offscreen.
 */
interface Submitter {

    /**
     * Returns the device.
     */
    VkDevice device();

    /**
     * Records commands into a command buffer, submits them, and waits until the queue is idle.
     *
     * @param record what to record
     */
    void submit(Consumer<VkCommandBuffer> record);

    /**
     * Waits until the device is idle.
     */
    void idle();
}

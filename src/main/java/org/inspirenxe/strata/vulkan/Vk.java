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

import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;
import static org.lwjgl.vulkan.VK10.VK_ERROR_FEATURE_NOT_PRESENT;
import static org.lwjgl.vulkan.VK10.VK_ERROR_FORMAT_NOT_SUPPORTED;
import static org.lwjgl.vulkan.VK10.VK_ERROR_FRAGMENTED_POOL;
import static org.lwjgl.vulkan.VK10.VK_ERROR_INITIALIZATION_FAILED;
import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_HOST_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * Checks Vulkan results.
 */
final class Vk {

    private Vk() {
    }

    /**
     * Throws when a Vulkan call failed.
     *
     * @param result the call's result
     * @param action what the call did, for the message
     * @throws IllegalStateException if the result is an error
     */
    static void check(final int result, final String action) {
        if (result < VK_SUCCESS) {
            throw new IllegalStateException("Vulkan failed to " + action + ": " + Vk.name(result));
        }
    }

    /**
     * Names a result code.
     *
     * @param result the code
     * @return its name, or the number for codes this does not know
     */
    static String name(final int result) {
        return switch (result) {
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case VK_ERROR_FRAGMENTED_POOL -> "VK_ERROR_FRAGMENTED_POOL";
            case VK_ERROR_OUT_OF_POOL_MEMORY -> "VK_ERROR_OUT_OF_POOL_MEMORY";
            default -> "VkResult " + result;
        };
    }
}

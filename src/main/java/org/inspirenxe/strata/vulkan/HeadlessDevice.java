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

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES;

/**
 * A Vulkan device of the adapter's own, with no window, for {@link VulkanSnapshot}: the first device with graphics
 * and dynamic rendering, a discrete one where there is one. It is made once, the first time it is asked for, and kept
 * for the life of the process.
 */
final class HeadlessDevice implements Submitter {
    private static HeadlessDevice device;
    private static RuntimeException failure;

    final VkDevice logical;
    final VkQueue queue;
    final int queueFamily;
    private final VkCommandBuffer commands;

    private HeadlessDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Strata snapshots"))
                    .apiVersion(Math.max(Math.min(VK.getInstanceVersionSupported(), VK_API_VERSION_1_3),
                            VK_API_VERSION_1_2));
            final VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(app);
            final PointerBuffer handle = stack.mallocPointer(1);
            Vk.check(vkCreateInstance(info, null, handle), "create an instance");
            final VkInstance instance = new VkInstance(handle.get(0), info);

            final IntBuffer count = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, count, null);
            if (count.get(0) == 0) {
                throw new IllegalStateException("No Vulkan device is installed");
            }
            final PointerBuffer devices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, devices);
            VkPhysicalDevice chosen = null;
            int family = -1;
            boolean discrete = false;
            for (int i = 0; i < devices.capacity(); i++) {
                final VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                final int graphics = HeadlessDevice.graphicsFamily(stack, candidate);
                if (graphics < 0 || !HeadlessDevice.dynamicRendering(stack, candidate)) {
                    continue;
                }
                final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(candidate, properties);
                final boolean isDiscrete = properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
                if (chosen == null || isDiscrete && !discrete) {
                    chosen = candidate;
                    family = graphics;
                    discrete = isDiscrete;
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("No Vulkan device has graphics and dynamic rendering");
            }
            final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(chosen, properties);
            final int version = Math.min(properties.apiVersion(), app.apiVersion());
            final VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(family)
                    .pQueuePriorities(stack.floats(1f));
            final VkPhysicalDeviceDynamicRenderingFeatures features = VkPhysicalDeviceDynamicRenderingFeatures
                    .calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES)
                    .dynamicRendering(true);
            final VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pNext(features.address())
                    .pQueueCreateInfos(queues);
            if (version < VK_API_VERSION_1_3) {
                deviceInfo.ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)));
            }
            final PointerBuffer deviceHandle = stack.mallocPointer(1);
            Vk.check(vkCreateDevice(chosen, deviceInfo, null, deviceHandle), "create a device");
            this.logical = new VkDevice(deviceHandle.get(0), chosen, deviceInfo, version);
            this.queueFamily = family;
            final PointerBuffer queue = stack.mallocPointer(1);
            vkGetDeviceQueue(this.logical, family, 0, queue);
            this.queue = new VkQueue(queue.get(0), this.logical);

            final VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(family);
            final LongBuffer poolHandle = stack.mallocLong(1);
            Vk.check(vkCreateCommandPool(this.logical, pool, null, poolHandle), "create a command pool");
            final VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(poolHandle.get(0))
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            final PointerBuffer buffer = stack.mallocPointer(1);
            Vk.check(vkAllocateCommandBuffers(this.logical, allocate, buffer), "allocate a command buffer");
            this.commands = new VkCommandBuffer(buffer.get(0), this.logical);
        }
    }

    /**
     * Returns the device, making it the first time.
     *
     * @return the device
     * @throws IllegalStateException if the machine has no Vulkan driver or no device that can draw offscreen; later
     *     calls throw the same without trying again
     */
    static synchronized HeadlessDevice get() {
        if (device == null && failure == null) {
            try {
                device = new HeadlessDevice();
            } catch (final RuntimeException e) {
                failure = e;
            } catch (final LinkageError e) {
                failure = new IllegalStateException("Vulkan cannot be loaded: " + e.getMessage(), e);
            }
        }
        if (device == null) {
            throw new IllegalStateException("Vulkan cannot draw offscreen here: " + failure.getMessage(), failure);
        }
        return device;
    }

    @Override
    public VkDevice device() {
        return this.logical;
    }

    @Override
    public void submit(final Consumer<VkCommandBuffer> record) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkResetCommandBuffer(this.commands, 0);
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            Vk.check(vkBeginCommandBuffer(this.commands, begin), "begin recording");
            record.accept(this.commands);
            Vk.check(vkEndCommandBuffer(this.commands), "finish recording");
            final VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(this.commands));
            Vk.check(vkQueueSubmit(this.queue, submit, 0L), "submit");
            Vk.check(vkQueueWaitIdle(this.queue), "wait for the queue");
        }
    }

    @Override
    public void idle() {
        vkDeviceWaitIdle(this.logical);
    }

    private static int graphicsFamily(final MemoryStack stack, final VkPhysicalDevice device) {
        final IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        final VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean dynamicRendering(final MemoryStack stack, final VkPhysicalDevice device) {
        final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(device, properties);
        if (properties.apiVersion() >= VK_API_VERSION_1_3) {
            return true;
        }
        final IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
        final VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(device, (String) null, count, extensions);
        for (final VkExtensionProperties extension : extensions) {
            if (extension.extensionNameString().equals(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)) {
                return true;
            }
        }
        return false;
    }
}

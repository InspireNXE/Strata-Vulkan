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
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
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
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;
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
 * One Vulkan device for the whole test run, without a window: a graphics queue with dynamic rendering, and the
 * Khronos validation layer when it is installed, whose errors fail the test that caused them. A machine without a
 * Vulkan driver skips the tests that need a device, unless {@code strata.vulkan.requireDevice} is true.
 */
final class TestDevice implements Submitter {
    private static final String VALIDATION = "VK_LAYER_KHRONOS_validation";
    private static TestDevice device;
    private static String failure;

    final VkInstance instance;
    final VkPhysicalDevice physical;
    final VkDevice logical;
    final VkQueue queue;
    final int queueFamily;
    final String name;
    final boolean validated;
    private final long commandPool;
    private final VkCommandBuffer commands;
    private final List<String> errors;

    private TestDevice() {
        this.errors = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int version = Math.min(VK.getInstanceVersionSupported(), VK_API_VERSION_1_3);
            final VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Strata Vulkan tests"))
                    .apiVersion(Math.max(version, VK_API_VERSION_1_2));
            this.validated = TestDevice.hasLayer(stack, VALIDATION);
            final VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(app);
            if (this.validated) {
                info.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION)))
                        .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)));
            }
            final PointerBuffer handle = stack.mallocPointer(1);
            TestDevice.check(vkCreateInstance(info, null, handle), "create an instance");
            this.instance = new VkInstance(handle.get(0), info);
            if (this.validated) {
                this.messenger(stack);
            }

            final IntBuffer count = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(this.instance, count, null);
            if (count.get(0) == 0) {
                throw new IllegalStateException("No Vulkan device");
            }
            final PointerBuffer devices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(this.instance, count, devices);
            VkPhysicalDevice chosen = null;
            int family = -1;
            boolean discrete = false;
            for (int i = 0; i < devices.capacity(); i++) {
                final VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), this.instance);
                final int graphics = TestDevice.graphicsFamily(stack, candidate);
                if (graphics < 0 || !TestDevice.dynamicRendering(stack, candidate)) {
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
                throw new IllegalStateException("No Vulkan device with graphics and dynamic rendering");
            }
            this.physical = chosen;
            this.queueFamily = family;
            final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(chosen, properties);
            this.name = properties.deviceNameString();
            final int deviceVersion = Math.min(properties.apiVersion(), app.apiVersion());

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
            if (deviceVersion < VK_API_VERSION_1_3) {
                deviceInfo.ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)));
            }
            final PointerBuffer deviceHandle = stack.mallocPointer(1);
            TestDevice.check(vkCreateDevice(chosen, deviceInfo, null, deviceHandle), "create a device");
            this.logical = new VkDevice(deviceHandle.get(0), chosen, deviceInfo, deviceVersion);
            final PointerBuffer queue = stack.mallocPointer(1);
            vkGetDeviceQueue(this.logical, family, 0, queue);
            this.queue = new VkQueue(queue.get(0), this.logical);

            final VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(family);
            final LongBuffer poolHandle = stack.mallocLong(1);
            TestDevice.check(vkCreateCommandPool(this.logical, pool, null, poolHandle), "create a command pool");
            this.commandPool = poolHandle.get(0);
            final VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(this.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            final PointerBuffer buffer = stack.mallocPointer(1);
            TestDevice.check(vkAllocateCommandBuffers(this.logical, allocate, buffer), "allocate a command buffer");
            this.commands = new VkCommandBuffer(buffer.get(0), this.logical);
        }
    }

    /**
     * Returns the device, skipping the calling test when there is none.
     *
     * @return the device
     */
    static synchronized TestDevice get() {
        if (device == null && failure == null) {
            try {
                device = new TestDevice();
            } catch (final RuntimeException | LinkageError e) {
                failure = e.toString();
            }
        }
        if (device == null) {
            if (Boolean.getBoolean("strata.vulkan.requireDevice")) {
                fail("No Vulkan device for the tests: " + failure);
            }
            assumeTrue(false, "No Vulkan device: " + failure);
        }
        device.errors.clear();
        return device;
    }

    @Override
    public VkDevice device() {
        return this.logical;
    }

    /**
     * Records commands into the test command buffer, submits them and waits until the device is idle.
     *
     * @param record what to record
     */
    @Override
    public void submit(final Consumer<VkCommandBuffer> record) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkResetCommandBuffer(this.commands, 0);
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            TestDevice.check(vkBeginCommandBuffer(this.commands, begin), "begin a command buffer");
            record.accept(this.commands);
            TestDevice.check(vkEndCommandBuffer(this.commands), "end a command buffer");
            final VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(this.commands));
            TestDevice.check(vkQueueSubmit(this.queue, submit, 0L), "submit");
            TestDevice.check(vkQueueWaitIdle(this.queue), "wait for the queue");
        }
        this.checkValidation();
    }

    /**
     * Waits until the device is idle.
     */
    @Override
    public void idle() {
        vkDeviceWaitIdle(this.logical);
        this.checkValidation();
    }

    /**
     * Fails the test if the validation layer reported an error since the last check.
     */
    void checkValidation() {
        if (!this.errors.isEmpty()) {
            final List<String> reported = List.copyOf(this.errors);
            this.errors.clear();
            fail("Vulkan validation reported:\n" + String.join("\n", reported));
        }
    }

    private void messenger(final MemoryStack stack) {
        final VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback((severity, types, data, user) -> {
                    final String message = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
                    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                        this.errors.add(message);
                    } else {
                        System.err.println("Vulkan validation warning: " + message);
                    }
                    return VK_FALSE;
                });
        final LongBuffer messenger = stack.mallocLong(1);
        TestDevice.check(vkCreateDebugUtilsMessengerEXT(this.instance, info, null, messenger),
                "create a debug messenger");
    }

    private static boolean hasLayer(final MemoryStack stack, final String name) {
        final IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(count, null);
        final VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, layers);
        for (final VkLayerProperties layer : layers) {
            if (layer.layerNameString().equals(name)) {
                return true;
            }
        }
        return false;
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

    private static void check(final int result, final String action) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Vulkan failed to " + action + ": " + result);
        }
    }
}

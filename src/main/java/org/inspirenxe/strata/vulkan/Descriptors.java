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

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_ERROR_FRAGMENTED_POOL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkFreeDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * Allocates descriptor sets from pools it adds as they fill, and frees them one at a time.
 */
final class Descriptors {
    private static final int SETS_PER_POOL = 256;

    private final VkDevice device;
    private final List<Long> pools;
    private final Map<Long, Long> poolOfSet;

    Descriptors(final VkDevice device) {
        this.device = device;
        this.pools = new ArrayList<>();
        this.poolOfSet = new HashMap<>();
    }

    /**
     * Allocates a set.
     *
     * @param layout the set's layout
     * @return the set
     */
    long allocate(final long layout) {
        for (int i = this.pools.size() - 1; i >= 0; i--) {
            final long set = this.tryAllocate(this.pools.get(i), layout);
            if (set != 0L) {
                return set;
            }
        }
        final long pool = this.createPool();
        this.pools.add(pool);
        final long set = this.tryAllocate(pool, layout);
        if (set == 0L) {
            throw new IllegalStateException("Vulkan failed to allocate a descriptor set from a new pool");
        }
        return set;
    }

    /**
     * Allocates a set that samples an image.
     *
     * @param layout the image set layout
     * @param view the image view
     * @param sampler the sampler
     * @return the set
     */
    long image(final long layout, final long view, final long sampler) {
        final long set = this.allocate(layout);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(view)
                    .sampler(sampler)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            final VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(image);
            vkUpdateDescriptorSets(this.device, write, null);
        }
        return set;
    }

    /**
     * Points a storage buffer set at a buffer.
     *
     * @param set the set
     * @param buffer the buffer
     */
    void storage(final long set, final long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer)
                    .offset(0)
                    .range(VK_WHOLE_SIZE);
            final VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(info);
            vkUpdateDescriptorSets(this.device, write, null);
        }
    }

    /**
     * Frees a set. The device must be done with every command that uses it.
     *
     * @param set the set
     */
    void free(final long set) {
        final Long pool = this.poolOfSet.remove(set);
        if (pool != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkFreeDescriptorSets(this.device, pool, stack.longs(set));
            }
        }
    }

    /**
     * Destroys every pool, and with them every set.
     */
    void destroy() {
        for (final long pool : this.pools) {
            vkDestroyDescriptorPool(this.device, pool, null);
        }
        this.pools.clear();
        this.poolOfSet.clear();
    }

    private long tryAllocate(final long pool, final long layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout));
            final LongBuffer set = stack.mallocLong(1);
            final int result = vkAllocateDescriptorSets(this.device, info, set);
            if (result == VK_ERROR_OUT_OF_POOL_MEMORY || result == VK_ERROR_FRAGMENTED_POOL) {
                return 0L;
            }
            Vk.check(result, "allocate a descriptor set");
            this.poolOfSet.put(set.get(0), pool);
            return set.get(0);
        }
    }

    private long createPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_POOL);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(SETS_PER_POOL / 4);
            final VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(SETS_PER_POOL)
                    .pPoolSizes(sizes);
            final LongBuffer pool = stack.mallocLong(1);
            final int result = vkCreateDescriptorPool(this.device, info, null, pool);
            if (result != VK_SUCCESS) {
                Vk.check(result, "create a descriptor pool");
            }
            return pool.get(0);
        }
    }
}

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
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.util.vma.Vma.vmaInvalidateAllocation;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;

/**
 * A buffer the host writes or reads through a pointer that stays mapped for the buffer's life.
 */
final class GpuBuffer {
    final long buffer;
    final long allocation;
    final long address;
    final long size;

    private GpuBuffer(final long buffer, final long allocation, final long address, final long size) {
        this.buffer = buffer;
        this.allocation = allocation;
        this.address = address;
        this.size = size;
    }

    /**
     * Creates a mapped buffer.
     *
     * @param allocator the VMA allocator
     * @param size the size in bytes, greater than 0
     * @param usage the {@code VK_BUFFER_USAGE_*} bits
     * @param readBack true for a buffer the host reads, false for one it only writes
     * @return the buffer
     */
    static GpuBuffer create(final long allocator, final long size, final int usage, final boolean readBack) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            final VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO)
                    .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT | (readBack
                            ? VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                            : VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT));
            final LongBuffer buffer = stack.mallocLong(1);
            final PointerBuffer handle = stack.mallocPointer(1);
            final VmaAllocationInfo result = VmaAllocationInfo.calloc(stack);
            Vk.check(vmaCreateBuffer(allocator, info, allocation, buffer, handle, result), "create a buffer");
            return new GpuBuffer(buffer.get(0), handle.get(0), result.pMappedData(), size);
        }
    }

    /**
     * Makes what the host wrote visible to the device, for memory that is not coherent.
     *
     * @param allocator the VMA allocator
     * @param bytes how many bytes from the start were written
     */
    void flush(final long allocator, final long bytes) {
        if (bytes > 0) {
            vmaFlushAllocation(allocator, this.allocation, 0, bytes);
        }
    }

    /**
     * Makes what the device wrote visible to the host, for memory that is not coherent.
     *
     * @param allocator the VMA allocator
     */
    void invalidate(final long allocator) {
        vmaInvalidateAllocation(allocator, this.allocation, 0, this.size);
    }

    /**
     * Destroys the buffer and frees its memory.
     *
     * @param allocator the VMA allocator
     */
    void destroy(final long allocator) {
        vmaDestroyBuffer(allocator, this.buffer, this.allocation);
    }
}

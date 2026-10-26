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

import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/**
 * The device resources Strata draws with on one Vulkan device: its pipeline for a {@link VulkanTarget}, the glyph
 * atlas, sprite and pixel buffer textures, samplers and descriptor pools. One renderer serves any number of
 * {@link StrataVulkan} views that draw into passes of the same target.
 *
 * <h2>What the application provides</h2>
 * <ul>
 *     <li>A {@code VkDevice} created through LWJGL, so that its function pointers are loaded, with the features the
 *     target needs: dynamic rendering for a {@linkplain VulkanTarget#dynamic dynamic} target. Strata needs no other
 *     feature or extension.</li>
 *     <li>A queue that can do graphics, and its family's index. Strata submits its own uploads to it while
 *     {@link StrataVulkan#render} runs, so the application must not use the queue on another thread at that time,
 *     and must submit the command buffer Strata recorded into to the same queue afterwards. Submission order on the
 *     queue is what makes the uploads land before the draws that sample them.</li>
 *     <li>The number of frames it keeps in flight: when a view renders for the {@code n}th time, the device must have
 *     finished the command buffer of that view's {@code (n - framesInFlight)}th render. Waiting on each frame's fence
 *     before recording it again, as most renderers do, gives this.</li>
 * </ul>
 *
 * <p>Uploads use their own command buffers and fences, one per frame in flight, and never wait on the host for the
 * device except when a view renders before the device has finished that frame's earlier uploads. Resources that go
 * away, such as a pixel buffer's texture that is no longer drawn, are destroyed only once every view has rendered
 * {@code framesInFlight} more frames.</p>
 *
 * <p>Use the renderer on the application's render thread only, the thread that calls {@link StrataVulkan#render}.
 * Close every view first, wait for the device to finish their frames, then close the renderer.</p>
 */
public final class VulkanRenderer implements AutoCloseable {
    private static final String SHADERS = "org/inspirenxe/strata/vulkan/shaders/";

    private final VkDevice device;
    private final VkQueue queue;
    private final VulkanTarget target;
    private final int framesInFlight;
    private final long allocator;
    private final long commandPool;
    private final long imageLayout;
    private final long masksLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long nearestSampler;
    private final long linearSampler;
    private final Descriptors descriptors;
    private final GlyphAtlas glyphs;
    private final Texture white;
    private final List<Upload> uploads;
    private final List<FrameStream> streams;
    private final List<Retired> retired;
    private final Map<VulkanSprites, SpriteTextures> sprites;
    private final Map<PixelBuffer, PixelTexture> pixels;
    private long ticks;
    // Moves on whenever a resource is retired, so a stream knows its last frame may sample one
    private long retirements;
    private boolean closed;

    /**
     * Creates the renderer and its pipeline.
     *
     * @param device the device
     * @param queue a graphics queue of the device
     * @param queueFamilyIndex the queue's family
     * @param target what the views draw into
     * @param framesInFlight how many frames the application keeps in flight, at least 1
     * @throws IllegalArgumentException if {@code framesInFlight} is less than 1
     * @throws IllegalStateException if Vulkan fails to create a resource, or the shaders are missing from the jar
     */
    public VulkanRenderer(final VkDevice device, final VkQueue queue, final int queueFamilyIndex,
                          final VulkanTarget target, final int framesInFlight) {
        if (framesInFlight < 1) {
            throw new IllegalArgumentException("At least one frame must be in flight: " + framesInFlight);
        }
        this.device = Objects.requireNonNull(device, "device");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.target = Objects.requireNonNull(target, "target");
        this.framesInFlight = framesInFlight;
        this.uploads = new ArrayList<>();
        this.streams = new ArrayList<>();
        this.retired = new ArrayList<>();
        this.sprites = new IdentityHashMap<>();
        this.pixels = new IdentityHashMap<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack)
                    .set(device.getPhysicalDevice().getInstance(), device);
            final VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(device.getPhysicalDevice())
                    .device(device)
                    .instance(device.getPhysicalDevice().getInstance())
                    .pVulkanFunctions(functions)
                    .vulkanApiVersion(Math.min(device.getCapabilities().apiVersion,
                            device.getCapabilitiesInstance().apiVersion));
            final PointerBuffer handle = stack.mallocPointer(1);
            Vk.check(vmaCreateAllocator(info, handle), "create a memory allocator");
            this.allocator = handle.get(0);

            final VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamilyIndex);
            final LongBuffer pointer = stack.mallocLong(1);
            Vk.check(vkCreateCommandPool(device, pool, null, pointer), "create a command pool");
            this.commandPool = pointer.get(0);
        }
        this.imageLayout = this.setLayout(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        this.masksLayout = this.setLayout(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        this.pipelineLayout = this.createPipelineLayout();
        this.pipeline = this.createPipeline();
        this.nearestSampler = this.createSampler(VK_FILTER_NEAREST);
        this.linearSampler = this.createSampler(VK_FILTER_LINEAR);
        this.descriptors = new Descriptors(device);
        this.glyphs = new GlyphAtlas(this);
        this.white = Texture.create(this, 1, 1, VK_FORMAT_R8G8B8A8_UNORM, 4, this.nearestSampler);
        this.queueUpload(this.white, 0, 0, 1, 1, new byte[]{-1, -1, -1, -1}, 0, 1);
    }

    /**
     * Returns what this renderer's pipeline draws into.
     *
     * @return the target
     */
    public VulkanTarget target() {
        return this.target;
    }

    /**
     * Returns how many frames the application keeps in flight.
     *
     * @return the number given to the constructor
     */
    public int framesInFlight() {
        return this.framesInFlight;
    }

    /**
     * Makes a live visual of an image the application owns, which screens show with an {@code Image} control or a
     * painter's {@code visual} call. Strata samples it with linear filtering, clamped at the edges.
     *
     * @param imageView a view of the image; the image must be in {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}
     *                  whenever a frame that draws it executes
     * @param width the visual's natural width in UI units
     * @param height the visual's natural height in UI units
     * @return the visual; close it when no longer needed
     * @throws IllegalArgumentException if a size is negative or not finite
     */
    public VulkanImage image(final long imageView, final float width, final float height) {
        return this.image(imageView, this.linearSampler, width, height);
    }

    /**
     * Makes a live visual of an image the application owns, sampled with the application's sampler.
     *
     * @param imageView a view of the image; the image must be in {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}
     *                  whenever a frame that draws it executes
     * @param sampler the sampler, which must outlive the visual
     * @param width the visual's natural width in UI units
     * @param height the visual's natural height in UI units
     * @return the visual; close it when no longer needed
     * @throws IllegalArgumentException if a size is negative or not finite
     */
    public VulkanImage image(final long imageView, final long sampler, final float width, final float height) {
        this.checkOpen();
        return new VulkanImage(this, this.descriptors.image(this.imageLayout, imageView, sampler), width, height);
    }

    /**
     * Destroys every device resource. Close every {@link StrataVulkan} that uses the renderer, and wait for the device
     * to finish their frames, first. Calling it again does nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (final FrameStream stream : List.copyOf(this.streams)) {
            stream.close();
        }
        for (final Retired entry : this.retired) {
            entry.destroy().run();
        }
        this.retired.clear();
        for (final SpriteTextures textures : this.sprites.values()) {
            textures.destroy(this);
        }
        this.sprites.clear();
        for (final PixelTexture texture : this.pixels.values()) {
            texture.texture.destroy(this);
        }
        this.pixels.clear();
        this.glyphs.destroy();
        this.white.destroy(this);
        this.descriptors.destroy();
        vkDestroySampler(this.device, this.nearestSampler, null);
        vkDestroySampler(this.device, this.linearSampler, null);
        vkDestroyPipeline(this.device, this.pipeline, null);
        vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(this.device, this.imageLayout, null);
        vkDestroyDescriptorSetLayout(this.device, this.masksLayout, null);
        vkDestroyCommandPool(this.device, this.commandPool, null);
        vmaDestroyAllocator(this.allocator);
    }

    // ------------------------------------------------------------------
    // What the canvas and streams use
    // ------------------------------------------------------------------

    VkDevice device() {
        return this.device;
    }

    long allocator() {
        return this.allocator;
    }

    Descriptors descriptors() {
        return this.descriptors;
    }

    long imageLayout() {
        return this.imageLayout;
    }

    long masksLayout() {
        return this.masksLayout;
    }

    long pipeline() {
        return this.pipeline;
    }

    long pipelineLayout() {
        return this.pipelineLayout;
    }

    long nearestSampler() {
        return this.nearestSampler;
    }

    long linearSampler() {
        return this.linearSampler;
    }

    long commandPool() {
        return this.commandPool;
    }

    GlyphAtlas glyphs() {
        return this.glyphs;
    }

    /**
     * Returns the descriptor set of a white pixel, which draws without a texture sample.
     */
    long whiteSet() {
        return this.white.set;
    }

    boolean isClosed() {
        return this.closed;
    }

    /**
     * Opens a stream of frames for a view.
     */
    FrameStream openStream() {
        this.checkOpen();
        final FrameStream stream = new FrameStream(this);
        this.streams.add(stream);
        return stream;
    }

    /**
     * Forgets a closed stream, so retired resources no longer wait for it.
     */
    void streamClosed(final FrameStream stream) {
        this.streams.remove(stream);
    }

    /**
     * Destroys a resource once every open stream has rendered {@code framesInFlight} more frames, when the device can
     * no longer be using it.
     *
     * @param destroy what destroys it
     */
    void retire(final Runnable destroy) {
        this.retirements++;
        if (this.closed) {
            destroy.run();
            return;
        }
        final long[] at = new long[this.streams.size()];
        for (int i = 0; i < at.length; i++) {
            at[i] = this.streams.get(i).frames();
        }
        this.retired.add(new Retired(List.copyOf(this.streams), at, destroy));
    }

    /**
     * Returns how many resources were retired so far. A frame drawn before the count moved may sample a resource
     * that is about to be destroyed, so it must not be drawn again.
     */
    long retirements() {
        return this.retirements;
    }

    /**
     * Destroys the retired resources the device is done with, and the pixel buffer textures not drawn for a while.
     * Streams call it as each frame begins.
     */
    void beginFrame() {
        this.ticks++;
        final Iterator<Retired> entries = this.retired.iterator();
        while (entries.hasNext()) {
            final Retired entry = entries.next();
            boolean done = true;
            for (int i = 0; i < entry.streams().size() && done; i++) {
                final FrameStream stream = entry.streams().get(i);
                done = stream.isClosed() || stream.frames() >= entry.at()[i] + this.framesInFlight;
            }
            if (done) {
                entry.destroy().run();
                entries.remove();
            }
        }
        // A pixel buffer not drawn by any view for this many frames lets go of its texture
        final long stale = 8L * this.framesInFlight * Math.max(1, this.streams.size());
        final Iterator<PixelTexture> textures = this.pixels.values().iterator();
        while (textures.hasNext()) {
            final PixelTexture texture = textures.next();
            if (this.ticks - texture.drawn > stale) {
                texture.texture.retired = true;
                this.retire(() -> texture.texture.destroy(this));
                textures.remove();
            }
        }
    }

    /**
     * Returns the texture of a sprite image from a sprite set, making it the first time. When the set's
     * {@linkplain VulkanSprites#version() version} moved, the textures made from its earlier images are retired.
     */
    Texture sprite(final VulkanSprites sprites, final VulkanSprites.SpriteImage image) {
        SpriteTextures textures = this.sprites.get(sprites);
        if (textures == null || textures.version != sprites.version()) {
            if (textures != null) {
                final SpriteTextures stale = textures;
                stale.retireAll();
                this.retire(() -> stale.destroy(this));
            }
            textures = new SpriteTextures(sprites.version());
            this.sprites.put(sprites, textures);
        }
        Texture texture = textures.textures.get(image);
        if (texture == null) {
            texture = Texture.create(this, image.width(), image.height(), VK_FORMAT_R8G8B8A8_UNORM, 4,
                    this.nearestSampler);
            this.queueUpload(texture, 0, 0, image.width(), image.height(), image.rgba(), 0, image.width());
            textures.textures.put(image, texture);
        }
        return texture;
    }

    /**
     * Returns the texture of a pixel buffer with its current pixels, copying them again when its version moved.
     */
    Texture pixels(final PixelBuffer buffer) {
        PixelTexture texture = this.pixels.get(buffer);
        if (texture != null && (texture.texture.width != buffer.width()
                || texture.texture.height != buffer.height())) {
            final Texture stale = texture.texture;
            stale.retired = true;
            this.retire(() -> stale.destroy(this));
            texture = null;
        }
        if (texture == null) {
            texture = new PixelTexture(Texture.create(this, buffer.width(), buffer.height(), VK_FORMAT_B8G8R8A8_UNORM,
                    4, this.nearestSampler));
            texture.version = buffer.version() - 1;
            this.pixels.put(buffer, texture);
        }
        if (texture.version != buffer.version()) {
            texture.version = buffer.version();
            texture.texture.version++;
            this.queueUpload(texture.texture, 0, 0, buffer.width(), buffer.height(), buffer.array().clone(), 0,
                    buffer.width());
        }
        texture.drawn = this.ticks;
        return texture.texture;
    }

    /**
     * Queues pixels to copy into part of a texture when the frame ends. The source is read when the frame ends, so
     * pass a copy of pixels that may change before then.
     *
     * @param source a {@code byte[]} with one byte per channel, or an {@code int[]} of ARGB pixels
     * @param offset the first pixel's index in the source, in pixels
     * @param stride the source's row length, in pixels
     */
    void queueUpload(final Texture texture, final int x, final int y, final int width, final int height,
                     final Object source, final int offset, final int stride) {
        this.uploads.add(new Upload(texture, x, y, width, height, source, offset, stride));
    }

    /**
     * Records and submits every queued upload, the glyph atlas's included, with a stream slot's command buffer,
     * staging buffer and fence.
     */
    void flushUploads(final FrameStream.Slot slot) {
        this.glyphs.upload();
        this.uploads.removeIf(upload -> upload.texture().retired);
        // An upload a later one covers is dropped, such as a pixel buffer changed twice in a frame
        for (int i = this.uploads.size() - 1; i >= 0; i--) {
            final Upload earlier = this.uploads.get(i);
            for (int j = i + 1; j < this.uploads.size(); j++) {
                if (this.uploads.get(j).covers(earlier)) {
                    this.uploads.remove(i);
                    break;
                }
            }
        }
        if (this.uploads.isEmpty()) {
            return;
        }
        long total = 0;
        for (final Upload upload : this.uploads) {
            total = VulkanRenderer.align(total) + (long) upload.width() * upload.height() * upload.texture().bytesPerPixel;
        }
        final GpuBuffer staging = slot.staging(this, total);
        final ByteBuffer memory = MemoryUtil.memByteBuffer(staging.address, (int) staging.size);
        final long[] offsets = new long[this.uploads.size()];
        long at = 0;
        for (int i = 0; i < this.uploads.size(); i++) {
            final Upload upload = this.uploads.get(i);
            at = VulkanRenderer.align(at);
            offsets[i] = at;
            final int rowBytes = upload.width() * upload.texture().bytesPerPixel;
            for (int row = 0; row < upload.height(); row++) {
                final int position = (int) at + row * rowBytes;
                if (upload.source() instanceof final byte[] bytes) {
                    final int bpp = upload.texture().bytesPerPixel;
                    memory.put(position, bytes, (upload.offset() + row * upload.stride()) * bpp, rowBytes);
                } else if (upload.source() instanceof final int[] ints) {
                    memory.slice(position, rowBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                            .put(0, ints, upload.offset() + row * upload.stride(), upload.width());
                }
            }
            at += (long) rowBytes * upload.height();
        }
        staging.flush(this.allocator, at);

        final VkCommandBuffer commands = slot.commands();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            Vk.check(vkBeginCommandBuffer(commands, begin), "begin recording uploads");

            final Set<Texture> textures = new LinkedHashSet<>();
            for (final Upload upload : this.uploads) {
                textures.add(upload.texture());
            }
            final VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(textures.size(), stack);
            final VkImageMemoryBarrier.Buffer toShader = VkImageMemoryBarrier.calloc(textures.size(), stack);
            int i = 0;
            for (final Texture texture : textures) {
                VulkanRenderer.barrier(toTransfer.get(i), texture.image,
                        texture.ready ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
                VulkanRenderer.barrier(toShader.get(i), texture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT);
                i++;
            }
            // Earlier frames on this queue may still sample these images: wait for their fragment shaders
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    null, null, toTransfer);
            final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            final Set<Texture> written = new HashSet<>();
            final VkMemoryBarrier.Buffer between = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            for (int u = 0; u < this.uploads.size(); u++) {
                final Upload upload = this.uploads.get(u);
                // Copies to one image land in order only with a barrier between them
                if (!written.add(upload.texture())) {
                    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                            between, null, null);
                    written.clear();
                    written.add(upload.texture());
                }
                region.bufferOffset(offsets[u]).bufferRowLength(0).bufferImageHeight(0);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0)
                        .layerCount(1);
                region.imageOffset().set(upload.x(), upload.y(), 0);
                region.imageExtent().set(upload.width(), upload.height(), 1);
                vkCmdCopyBufferToImage(commands, staging.buffer, upload.texture().image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, toShader);
            Vk.check(vkEndCommandBuffer(commands), "finish recording uploads");

            final long fence = slot.fence();
            Vk.check(vkResetFences(this.device, fence), "reset a fence");
            final VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commands));
            Vk.check(vkQueueSubmit(this.queue, submit, fence), "submit uploads");
            slot.submitted();
            for (final Texture texture : textures) {
                texture.ready = true;
            }
        }
        this.uploads.clear();
    }

    /**
     * Creates a primary command buffer from the renderer's pool.
     */
    VkCommandBuffer createCommandBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(this.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            final PointerBuffer pointer = stack.mallocPointer(1);
            Vk.check(vkAllocateCommandBuffers(this.device, info, pointer), "allocate a command buffer");
            return new VkCommandBuffer(pointer.get(0), this.device);
        }
    }

    /**
     * Creates a fence that starts signaled.
     */
    long createFence() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkFenceCreateInfo info = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            final LongBuffer fence = stack.mallocLong(1);
            Vk.check(vkCreateFence(this.device, info, null, fence), "create a fence");
            return fence.get(0);
        }
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("The renderer is closed");
        }
    }

    private static long align(final long offset) {
        return (offset + 15L) & ~15L;
    }

    private static void barrier(final VkImageMemoryBarrier barrier, final long image, final int from, final int to,
                                final int sourceAccess, final int destinationAccess) {
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(from)
                .newLayout(to)
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
    }

    private long setLayout(final int type) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0)
                    .descriptorType(type)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            final VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);
            final LongBuffer layout = stack.mallocLong(1);
            Vk.check(vkCreateDescriptorSetLayout(this.device, info, null, layout), "create a descriptor set layout");
            return layout.get(0);
        }
    }

    private long createPipelineLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0)
                    .size(FrameStream.PUSH_CONSTANTS);
            final VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(this.imageLayout, this.masksLayout))
                    .pPushConstantRanges(push);
            final LongBuffer layout = stack.mallocLong(1);
            Vk.check(vkCreatePipelineLayout(this.device, info, null, layout), "create a pipeline layout");
            return layout.get(0);
        }
    }

    private long createSampler(final int filter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0f)
                    .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
            final LongBuffer sampler = stack.mallocLong(1);
            Vk.check(vkCreateSampler(this.device, info, null, sampler), "create a sampler");
            return sampler.get(0);
        }
    }

    private long createPipeline() {
        final long vertex = this.shader("strata.vert.spv");
        final long fragment = this.shader("strata.frag.spv");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final ByteBuffer main = stack.UTF8("main");
            final VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(main);
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(main);

            final VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(DrawList.STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            final VkVertexInputAttributeDescription.Buffer attributes =
                    VkVertexInputAttributeDescription.calloc(7, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
            attributes.get(2).location(2).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(16);
            attributes.get(3).location(3).binding(0).format(VK_FORMAT_R32_UINT).offset(20);
            attributes.get(4).location(4).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(24);
            attributes.get(5).location(5).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(40);
            attributes.get(6).location(6).binding(0).format(VK_FORMAT_R32_SFLOAT).offset(56);
            final VkPipelineVertexInputStateCreateInfo input = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(binding)
                    .pVertexAttributeDescriptions(attributes);
            final VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            final VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);
            final VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1f);
            final VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(this.target.samples());
            final VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(false)
                    .depthWriteEnable(false)
                    .depthCompareOp(VK_COMPARE_OP_ALWAYS)
                    .stencilTestEnable(false);
            final VkPipelineColorBlendAttachmentState.Buffer attachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack)
                            .blendEnable(true)
                            .srcColorBlendFactor(VK_BLEND_FACTOR_ONE)
                            .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                            .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .alphaBlendOp(VK_BLEND_OP_ADD)
                            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                                    | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            final VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(attachment);
            final VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            final VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(input)
                    .pInputAssemblyState(assembly)
                    .pViewportState(viewport)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depth)
                    .pColorBlendState(blend)
                    .pDynamicState(dynamic)
                    .layout(this.pipelineLayout)
                    .basePipelineIndex(-1);
            if (this.target.isDynamic()) {
                final VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                        .colorAttachmentCount(1)
                        .pColorAttachmentFormats(stack.ints(this.target.colorFormat()))
                        .depthAttachmentFormat(this.target.depthFormat())
                        .stencilAttachmentFormat(this.target.stencilFormat());
                info.pNext(rendering.address());
            } else {
                info.renderPass(this.target.renderPass()).subpass(this.target.subpass());
            }
            final LongBuffer pipeline = stack.mallocLong(1);
            Vk.check(vkCreateGraphicsPipelines(this.device, VK10.VK_NULL_HANDLE, info, null, pipeline),
                    "create Strata's pipeline");
            return pipeline.get(0);
        } finally {
            vkDestroyShaderModule(this.device, vertex, null);
            vkDestroyShaderModule(this.device, fragment, null);
        }
    }

    private long shader(final String name) {
        final byte[] code;
        try (InputStream in = VulkanRenderer.class.getClassLoader().getResourceAsStream(SHADERS + name)) {
            if (in == null) {
                throw new IllegalStateException("The shader " + SHADERS + name + " is missing from the class path");
            }
            code = in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read the shader " + name, e);
        }
        final ByteBuffer buffer = MemoryUtil.memAlloc(code.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            buffer.put(0, code);
            final VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(buffer);
            final LongBuffer module = stack.mallocLong(1);
            Vk.check(vkCreateShaderModule(this.device, info, null, module), "create a shader module");
            return module.get(0);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    /**
     * Pixels to copy into part of a texture when the frame ends.
     */
    private record Upload(Texture texture, int x, int y, int width, int height, Object source, int offset,
                          int stride) {
        /**
         * Returns whether this upload writes every pixel another writes, to the same texture.
         */
        boolean covers(final Upload other) {
            return this.texture == other.texture && this.x <= other.x && this.y <= other.y
                    && this.x + this.width >= other.x + other.width && this.y + this.height >= other.y + other.height;
        }
    }

    /**
     * A resource to destroy once the streams that existed when it was retired have moved on far enough.
     */
    private record Retired(List<FrameStream> streams, long[] at, Runnable destroy) {
    }

    /**
     * The textures made from one sprite set's images, while the set's version stays the same.
     */
    private static final class SpriteTextures {
        final long version;
        final Map<VulkanSprites.SpriteImage, Texture> textures = new IdentityHashMap<>();

        SpriteTextures(final long version) {
            this.version = version;
        }

        void retireAll() {
            for (final Texture texture : this.textures.values()) {
                texture.retired = true;
            }
        }

        void destroy(final VulkanRenderer renderer) {
            for (final Texture texture : this.textures.values()) {
                texture.destroy(renderer);
            }
            this.textures.clear();
        }
    }

    /**
     * A pixel buffer's texture, the version of the pixels it holds, and when it was last drawn.
     */
    private static final class PixelTexture {
        final Texture texture;
        long version;
        long drawn;

        PixelTexture(final Texture texture) {
            this.texture = texture;
        }
    }
}

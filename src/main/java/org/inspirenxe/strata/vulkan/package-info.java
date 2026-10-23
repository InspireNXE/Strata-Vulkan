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
/**
 * The Vulkan adapter: Strata screens drawn into a Vulkan render pass the application records, on any device the
 * application created through LWJGL.
 *
 * <p>An application makes one {@link org.inspirenxe.strata.vulkan.VulkanRenderer} for its device and the target its
 * passes draw into, a {@link org.inspirenxe.strata.vulkan.VulkanPlatform} with the fonts of a
 * {@link org.inspirenxe.strata.vulkan.VulkanTextMetrics}, and a {@link org.inspirenxe.strata.vulkan.StrataVulkan}
 * for each window or area that shows screens, whose {@code render} records a frame into the application's command
 * buffer. {@link org.inspirenxe.strata.vulkan.VulkanSnapshot} renders a screen to pixels on a device of its own.
 * {@link org.inspirenxe.strata.vulkan.glfw.StrataGlfw} connects a view to a GLFW window; with another windowing
 * library, send input to the view's screens and implement {@link org.inspirenxe.strata.vulkan.VulkanWindow}.</p>
 *
 * <p>Text is drawn with FreeType from any font file through {@link org.inspirenxe.strata.vulkan.FreeTypeFont}, or
 * from images through {@link org.inspirenxe.strata.vulkan.BitmapFont}. Sprites load through
 * {@link org.inspirenxe.strata.vulkan.VulkanSprites}: from the class path, at
 * {@code assets/<namespace>/sprites/<path>.png} unless a {@link org.inspirenxe.strata.vulkan.SpriteResolver} names
 * another place, or from anywhere a {@link org.inspirenxe.strata.vulkan.SpriteSource} reads, with images for denser
 * displays beside them as {@code <path>@2x.png} and {@code <path>@1.5x.png}.
 * {@link org.inspirenxe.strata.vulkan.VulkanThemeSource} finds theme files at
 * {@code assets/<namespace>/themes/<path>.json} unless a {@link org.inspirenxe.strata.vulkan.ThemePathResolver} names
 * another place. {@link org.inspirenxe.strata.vulkan.VulkanTranslations} sets where translated text comes from, and
 * {@link org.inspirenxe.strata.vulkan.VulkanRenderer#image} makes visuals of the application's own images.</p>
 *
 * <p>The classes here must be used on the application's render thread unless their documentation says
 * otherwise.</p>
 */
package org.inspirenxe.strata.vulkan;

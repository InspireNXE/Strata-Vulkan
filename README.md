<p align="center"><img src=".github/assets/logo.svg" width="128" alt="Strata Vulkan logo"></p>

# Strata Vulkan

The Vulkan adapter for [Strata](https://github.com/InspireNXE/Strata). Any Java application that renders with Vulkan through [LWJGL](https://www.lwjgl.org) can show Strata screens: the adapter records its draws into a render pass or dynamic rendering that the application has already begun, on the application's own device and queue. It needs no window of its own, and an optional bridge connects it to a GLFW window.

```java
VulkanRenderer renderer = new VulkanRenderer(device, graphicsQueue, queueFamily,
        VulkanTarget.dynamic(swapchainFormat), framesInFlight);
VulkanPlatform platform = StrataGlfw.platform(window, VulkanTextMetrics.defaults()).build();
StrataVulkan strata = new StrataVulkan(platform, renderer, Themes.flat(), 1f);
strata.setDefault();
StrataGlfw.attach(window, strata, 2f).manageWindow("Settings");
Strata.screens().open(new SettingsScreen());

// Every frame, inside the application's pass:
strata.render(commandBuffer);
```

`VulkanRenderer` holds what Strata needs on the device: one pipeline for the pass's attachments, a glyph atlas, and the textures of sprites and images. `VulkanTarget.dynamic` describes dynamic rendering by its formats, and `VulkanTarget.renderPass` a subpass of a render pass. `StrataVulkan` is a stack of screens that fills a rectangle of the framebuffer, and `render` lays them out, uploads what changed with its own command buffer on the application's queue, and records the draws. The number of frames in flight tells the renderer when the device is done with a frame's buffers, so it can reuse them without waiting on the device.

An application that keeps its image between frames, such as a tool or an editor, can call `prepare()` in place of `render`. The `VulkanFrame` it returns names the rectangles that changed since the last frame and records only the draws there, and a frame where nothing changed needs no recording and no present. An application that renders on demand draws at most `screens().frameRate()` frames a second, 30 unless set.

Shapes are drawn on the device as signed distance fields, and paths are rasterized on the host into a coverage mask, so rounded rectangles, outlines, ellipses, arcs, lines, shapes, gradients at any angle and soft shadows are all anti-aliased. Text is drawn with FreeType from any TrueType or OpenType file through `FreeTypeFont`, or from images through `BitmapFont`; `VulkanTextMetrics.defaults()` uses the operating system's own fonts. `VulkanSnapshot` renders a screen to pixels on a device of its own, with no window. Sprites load from `assets/<namespace>/sprites/<path>.png` on the class path, with denser images beside them as `<path>@2x.png`, unless a `SpriteResolver` or `SpriteSource` says otherwise.

Apps depend on `strata-vulkan`, which brings `strata-api`, `strata-spi` and LWJGL's Vulkan bindings, keeps `strata-core` off the compile classpath, and includes the natives of LWJGL, VMA, FreeType and stb for Windows, Linux and macOS on x64 and arm64. The GLFW bridge needs `lwjgl-glfw`, which the adapter does not bring. The adapter passes Strata's SPI contract tests on a real device.

## Building

Building needs JDK 25 and a Strata checkout beside this one at `../Strata`, for Strata's convention plugins in `build-logic`. The adapter builds against that checkout, or against the Strata version in `gradle.properties` from Maven Central when only `build-logic` is there. The shaders in `src/main/glsl` are compiled to SPIR-V during the build.

```bash
./gradlew build
```

The tests draw on a Vulkan device and skip when the machine has none; set `STRATA_VULKAN_REQUIRE_DEVICE=true` to fail them instead. When the Khronos validation layer is installed, the tests turn it on and fail on any error it reports. `build/snapshots` holds images of the screens the tests drew.

The [Vulkan platform page](https://inspirenxe.github.io/Strata-Docs/platforms/vulkan/) covers the rest.

## License

Strata Vulkan is released under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

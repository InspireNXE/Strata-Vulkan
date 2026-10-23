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
package org.inspirenxe.strata.vulkan.shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_0;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;

/**
 * Compiles every {@code .vert} and {@code .frag} file in a folder to SPIR-V for Vulkan 1.0, writing each as
 * {@code <file name>.spv} to the output folder. The build runs it before it packs the resources.
 */
public final class ShaderCompiler {

    private ShaderCompiler() {
    }

    /**
     * Compiles the shaders.
     *
     * @param args the source folder and the output folder
     * @throws IOException if a file cannot be read or written
     */
    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ShaderCompiler <source folder> <output folder>");
        }
        final Path sources = Path.of(args[0]);
        final Path output = Path.of(args[1]);
        Files.createDirectories(output);
        final List<Path> shaders;
        try (Stream<Path> files = Files.list(sources)) {
            shaders = files.filter(file -> file.toString().endsWith(".vert") || file.toString().endsWith(".frag"))
                    .sorted().toList();
        }
        final long compiler = shaderc_compiler_initialize();
        final long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            for (final Path shader : shaders) {
                final String name = shader.getFileName().toString();
                final int kind = name.endsWith(".vert") ? shaderc_vertex_shader : shaderc_fragment_shader;
                final String source = Files.readString(shader, StandardCharsets.UTF_8);
                final long result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
                try {
                    if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                        throw new IllegalStateException("Cannot compile " + name + ":\n"
                                + shaderc_result_get_error_message(result));
                    }
                    final ByteBuffer spirv = shaderc_result_get_bytes(result);
                    final byte[] bytes = new byte[spirv.remaining()];
                    spirv.get(bytes);
                    Files.write(output.resolve(name + ".spv"), bytes);
                } finally {
                    shaderc_result_release(result);
                }
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}

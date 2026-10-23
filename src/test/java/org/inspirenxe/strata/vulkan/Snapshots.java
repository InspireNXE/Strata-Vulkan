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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes what the tests drew to PNG files, for a person to look at.
 */
final class Snapshots {

    private Snapshots() {
    }

    /**
     * Writes an image's last frame to a PNG file.
     */
    static void save(final OffscreenImage image, final Path file, final int width, final int height) {
        final BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, width, height, image.pixels(), 0, width);
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(out, "png", file.toFile());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

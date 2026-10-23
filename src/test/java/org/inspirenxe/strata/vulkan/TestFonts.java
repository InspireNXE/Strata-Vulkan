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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The system fonts the tests measure and draw with, read once.
 */
final class TestFonts {
    private static VulkanTextMetrics metrics;
    private static boolean tried;

    private TestFonts() {
    }

    /**
     * Returns metrics for the system's fonts, skipping the calling test when the system has none of them.
     *
     * @return the metrics
     */
    static synchronized VulkanTextMetrics metrics() {
        if (!tried) {
            tried = true;
            try {
                metrics = VulkanTextMetrics.defaults();
            } catch (final IllegalStateException ignored) {
                // No system font: the tests that need one are skipped
            }
        }
        assumeTrue(metrics != null, "No system font found");
        return metrics;
    }
}

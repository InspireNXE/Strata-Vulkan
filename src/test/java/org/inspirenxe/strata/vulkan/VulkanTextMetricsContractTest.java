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

import org.inspirenxe.strata.api.text.TextMetrics;
import org.inspirenxe.strata.spi.testing.TextMetricsContract;

/**
 * Runs the SPI's text metrics contract on FreeType metrics of the system's fonts.
 */
class VulkanTextMetricsContractTest extends TextMetricsContract {
    @Override
    protected TextMetrics metrics() {
        return TestFonts.metrics();
    }
}

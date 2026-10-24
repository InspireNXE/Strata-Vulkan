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

import org.inspirenxe.strata.spi.Platform;
import org.inspirenxe.strata.spi.testing.PlatformContract;

/**
 * Runs the SPI's platform contract on a Vulkan platform with every default, which has no clipboard. Its scheduler
 * runs tasks as views render, so letting time pass runs what is due, as a render would.
 */
class VulkanPlatformContractTest extends PlatformContract {
    private VulkanPlatform last;

    @Override
    protected Platform platform() {
        this.last = new VulkanPlatform(TestFonts.metrics());
        return this.last;
    }

    @Override
    protected void passTime(final long millis) throws InterruptedException {
        Thread.sleep(millis);
        ((VulkanScheduler) this.last.scheduler()).runDue();
    }

    @Override
    protected boolean hasClipboard() {
        return false;
    }
}

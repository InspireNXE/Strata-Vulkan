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

import org.inspirenxe.strata.api.ComponentFactory;
import org.inspirenxe.strata.api.component.Component;
import org.inspirenxe.strata.api.control.button.Button;
import org.inspirenxe.strata.api.input.Modifiers;
import org.inspirenxe.strata.api.screen.Screen;
import org.inspirenxe.strata.api.theme.Themes;
import org.inspirenxe.strata.api.visual.PixelBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Takes snapshots on the adapter's own device, which tests skip where there is no Vulkan driver.
 */
class VulkanSnapshotTest {

    @Test
    void rendersAScreenAfterItsInputAndClosesIt() {
        assumeTrue(VulkanSnapshot.isSupported(), "no Vulkan device");
        final List<String> events = new ArrayList<>();
        final Button[] button = new Button[1];
        final PixelBuffer pixels = VulkanSnapshot.render(new VulkanPlatform(TestFonts.metrics()), new Screen() {
                    @Override
                    public Component<?> build(final ComponentFactory factory) {
                        button[0] = factory.button("Snapshot");
                        return factory.absolutePanel().add(button[0], 4, 4);
                    }

                    @Override
                    public void closed() {
                        events.add("closed");
                    }
                }, Themes.flat(), 200, 80, 2f, new VulkanSprites(VulkanSnapshotTest.class.getClassLoader()),
                screens -> screens.pointerMoved(10, 10, Modifiers.NONE));
        assertEquals(200, pixels.width());
        assertEquals(80, pixels.height());
        assertNotEquals(0, pixels.get(20, 20) >>> 24, "the button's face");
        assertEquals(0, pixels.get(190, 70), "nothing past the button stays transparent");
        assertEquals(List.of("closed"), events, "the screen closes before the snapshot returns");
    }
}

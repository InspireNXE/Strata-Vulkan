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

import org.inspirenxe.strata.api.event.Subscription;
import org.inspirenxe.strata.spi.Clock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The default scheduler runs tasks as the views render, by the platform's clock, and drops runs it missed.
 */
class VulkanSchedulerTest {
    private long nanos;
    private final VulkanScheduler scheduler = new VulkanScheduler(() -> this.nanos);

    private void at(final long millis) {
        this.nanos = millis * 1_000_000L;
        this.scheduler.runDue();
    }

    @Test
    void aTaskRunsAfterEachInterval() {
        final List<Long> runs = new ArrayList<>();
        this.scheduler.every(100L, () -> runs.add(this.nanos / 1_000_000L));
        assertEquals(100_000_000L, this.scheduler.nextDueNanos());
        this.at(99L);
        this.at(100L);
        this.at(150L);
        this.at(210L);
        assertEquals(List.of(100L, 210L), runs);
        assertEquals(300_000_000L, this.scheduler.nextDueNanos(), "the next run keeps the beat");
    }

    @Test
    void runsMissedWhileNothingRenderedAreDropped() {
        final List<Long> runs = new ArrayList<>();
        this.scheduler.every(100L, () -> runs.add(this.nanos / 1_000_000L));
        this.at(1_000L);
        this.at(1_050L);
        this.at(1_100L);
        assertEquals(List.of(1_000L, 1_100L), runs, "one run for the long gap, then one interval later");
    }

    @Test
    void aClosedTaskNeverRunsAgain() {
        final int[] runs = new int[1];
        final Subscription subscription = this.scheduler.every(10L, () -> runs[0]++);
        this.at(10L);
        subscription.close();
        subscription.close();
        this.at(100L);
        assertEquals(1, runs[0]);
        assertEquals(Long.MAX_VALUE, this.scheduler.nextDueNanos());
    }

    @Test
    void aTaskThatClosesAnotherStopsItInTheSameRound() {
        final int[] runs = new int[1];
        final Subscription[] second = new Subscription[1];
        this.scheduler.every(10L, () -> second[0].close());
        second[0] = this.scheduler.every(10L, () -> runs[0]++);
        this.at(10L);
        assertEquals(0, runs[0]);
    }

    @Test
    void anIntervalOfZeroIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> this.scheduler.every(0L, () -> { }));
    }

    @Test
    void thePlatformUsesItsClockUnlessGivenAScheduler() {
        final Clock clock = () -> this.nanos;
        final VulkanPlatform platform = VulkanPlatform.builder(TestFonts.metrics()).clock(clock).build();
        assertEquals(VulkanScheduler.class, platform.scheduler().getClass());
        final VulkanScheduler own = new VulkanScheduler(clock);
        assertEquals(own, VulkanPlatform.builder(TestFonts.metrics()).scheduler(own).build().scheduler());
    }
}

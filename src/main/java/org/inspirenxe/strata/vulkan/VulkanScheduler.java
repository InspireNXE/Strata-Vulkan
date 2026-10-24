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
import org.inspirenxe.strata.spi.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The scheduler a {@link VulkanPlatform} has unless its builder is given the application's own. Vulkan has no loop
 * of its own, so tasks run when the application renders: {@link StrataVulkan#render} and {@link StrataVulkan#prepare}
 * call {@link #runDue()} before they draw, and {@link StrataVulkan#nextFrameNanos()} counts the next task's time, so
 * an application that renders on demand wakes for it.
 *
 * <p>A task runs at most once per {@link #runDue()}, first about one interval after it was scheduled. When frames
 * come further apart than its interval, the runs it missed are dropped: its next run comes one interval after the one
 * that ran. Tasks run in the order they were scheduled, by the platform's clock, on the thread that renders.</p>
 *
 * <p>Use it on the render thread only.</p>
 */
public final class VulkanScheduler implements Scheduler {
    private final Clock clock;
    private final List<Task> tasks = new ArrayList<>();

    /**
     * Creates a scheduler that reads a clock.
     *
     * @param clock the platform's clock
     */
    public VulkanScheduler(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Subscription every(final long millis, final Runnable task) {
        Objects.requireNonNull(task, "task");
        if (millis <= 0L) {
            throw new IllegalArgumentException("A task's interval must be above 0 ms: " + millis);
        }
        final long interval = millis * 1_000_000L;
        final Task scheduled = new Task(interval, this.clock.nanoTime() + interval, task);
        this.tasks.add(scheduled);
        return scheduled;
    }

    /**
     * Runs every task whose time has come, once each.
     */
    public void runDue() {
        if (this.tasks.isEmpty()) {
            return;
        }
        final long now = this.clock.nanoTime();
        // A task may close itself or schedule another while it runs
        for (final Task task : List.copyOf(this.tasks)) {
            if (task.closed || task.dueNanos > now) {
                continue;
            }
            task.dueNanos += task.intervalNanos;
            if (task.dueNanos <= now) {
                task.dueNanos = now + task.intervalNanos;
            }
            task.task.run();
        }
    }

    /**
     * Returns when the next task is due, by the platform's clock.
     *
     * @return the time in nanoseconds, or {@link Long#MAX_VALUE} when no task is scheduled
     */
    public long nextDueNanos() {
        long next = Long.MAX_VALUE;
        for (final Task task : this.tasks) {
            next = Math.min(next, task.dueNanos);
        }
        return next;
    }

    private final class Task implements Subscription {
        private final long intervalNanos;
        private final Runnable task;
        private long dueNanos;
        private boolean closed;

        Task(final long intervalNanos, final long dueNanos, final Runnable task) {
            this.intervalNanos = intervalNanos;
            this.dueNanos = dueNanos;
            this.task = task;
        }

        @Override
        public void unsubscribe() {
            this.closed = true;
            VulkanScheduler.this.tasks.remove(this);
        }
    }
}

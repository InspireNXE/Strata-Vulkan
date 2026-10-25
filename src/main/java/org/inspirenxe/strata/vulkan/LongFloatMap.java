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

/**
 * A map from {@code long} keys to {@code float} values that boxes nothing, such as the kerning of glyph pairs. It
 * probes linearly in a table whose size is a power of two, kept at most half full.
 */
final class LongFloatMap {
    private long[] keys;
    private float[] values;
    private boolean[] used;
    private int size;

    LongFloatMap() {
        this.keys = new long[64];
        this.values = new float[64];
        this.used = new boolean[64];
    }

    /**
     * Returns the value under a key.
     *
     * @param key the key
     * @return the value, or NaN if none is under it
     */
    float get(final long key) {
        final int mask = this.keys.length - 1;
        for (int at = LongMap.slot(key, mask); ; at = (at + 1) & mask) {
            if (!this.used[at]) {
                return Float.NaN;
            }
            if (this.keys[at] == key) {
                return this.values[at];
            }
        }
    }

    /**
     * Puts a value under a key, replacing any value there.
     *
     * @param key the key
     * @param value the value
     */
    void put(final long key, final float value) {
        if ((this.size + 1) * 2 > this.keys.length) {
            this.grow();
        }
        final int mask = this.keys.length - 1;
        int at = LongMap.slot(key, mask);
        while (this.used[at] && this.keys[at] != key) {
            at = (at + 1) & mask;
        }
        if (!this.used[at]) {
            this.used[at] = true;
            this.size++;
        }
        this.keys[at] = key;
        this.values[at] = value;
    }

    private void grow() {
        final long[] keys = this.keys;
        final float[] values = this.values;
        final boolean[] used = this.used;
        this.keys = new long[keys.length * 2];
        this.values = new float[values.length * 2];
        this.used = new boolean[used.length * 2];
        final int mask = this.keys.length - 1;
        for (int i = 0; i < keys.length; i++) {
            if (used[i]) {
                int at = LongMap.slot(keys[i], mask);
                while (this.used[at]) {
                    at = (at + 1) & mask;
                }
                this.keys[at] = keys[i];
                this.values[at] = values[i];
                this.used[at] = true;
            }
        }
    }
}

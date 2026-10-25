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

import java.util.Arrays;

/**
 * A map from {@code long} keys to values that boxes nothing, so looking up a glyph each frame allocates nothing. It
 * probes linearly in a table whose size is a power of two, kept at most half full. Values may not be null.
 *
 * @param <V> the type of the values
 */
final class LongMap<V> {
    private long[] keys;
    private Object[] values;
    private int size;

    LongMap() {
        this.keys = new long[64];
        this.values = new Object[64];
    }

    /**
     * Returns the value under a key.
     *
     * @param key the key
     * @return the value, or {@code null} if none is under it
     */
    @SuppressWarnings("unchecked")
    V get(final long key) {
        final int mask = this.keys.length - 1;
        for (int at = LongMap.slot(key, mask); ; at = (at + 1) & mask) {
            final Object value = this.values[at];
            if (value == null || this.keys[at] == key) {
                return (V) value;
            }
        }
    }

    /**
     * Puts a value under a key, replacing any value there.
     *
     * @param key the key
     * @param value the value
     */
    void put(final long key, final V value) {
        if ((this.size + 1) * 2 > this.keys.length) {
            this.grow();
        }
        final int mask = this.keys.length - 1;
        int at = LongMap.slot(key, mask);
        while (this.values[at] != null && this.keys[at] != key) {
            at = (at + 1) & mask;
        }
        if (this.values[at] == null) {
            this.size++;
        }
        this.keys[at] = key;
        this.values[at] = value;
    }

    /**
     * Removes every value.
     */
    void clear() {
        Arrays.fill(this.values, null);
        this.size = 0;
    }

    private void grow() {
        final long[] keys = this.keys;
        final Object[] values = this.values;
        this.keys = new long[keys.length * 2];
        this.values = new Object[values.length * 2];
        final int mask = this.keys.length - 1;
        for (int i = 0; i < keys.length; i++) {
            if (values[i] != null) {
                int at = LongMap.slot(keys[i], mask);
                while (this.values[at] != null) {
                    at = (at + 1) & mask;
                }
                this.keys[at] = keys[i];
                this.values[at] = values[i];
            }
        }
    }

    /**
     * Spreads a key's bits over the table, since keys such as code points differ only in their low bits.
     */
    static int slot(final long key, final int mask) {
        final long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}

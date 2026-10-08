// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads parameters using the tail consumption and range reduction of Zstandard's fuzz_data_producer.c.
@NotNullByDefault
public final class ZstdFuzzDataProducer {
    /// The unchanged input containing both payload and parameters.
    private final byte @Unmodifiable [] data;

    /// The first available parameter byte.
    private int start;

    /// The exclusive end of available parameter bytes.
    private int end;

    /// Creates a producer initially spanning the complete input.
    public ZstdFuzzDataProducer(byte @Unmodifiable [] data) {
        this.data = data;
        end = data.length;
    }

    /// Reads an inclusive unsigned 32-bit range, returning its minimum after exhaustion.
    public long range(long minimum, long maximum) {
        assertTrue(0 <= minimum && minimum <= maximum && maximum <= 0xffffffffL);
        long span = maximum - minimum;
        long result = 0;
        for (long rolling = span; rolling != 0 && end > start; rolling >>>= 8) {
            result = (result << 8) | Byte.toUnsignedInt(data[--end]);
        }
        return minimum + result % (span + 1);
    }

    /// Returns the number of parameter bytes not yet consumed or reserved as payload.
    public int remainingBytes() {
        return end - start;
    }

    /// Reserves the front payload and restricts later reads to the selected remaining tail.
    public int reservePrefix() {
        int size = Math.toIntExact(range(0, remainingBytes()));
        int prefix = remainingBytes() - Math.min(size, remainingBytes());
        start += prefix;
        return prefix;
    }
}

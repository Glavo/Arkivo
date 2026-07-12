// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Computes the reflected CRC-64/XZ integrity value.
@NotNullByDefault
final class CRC64 {
    /// The reflected CRC-64/XZ polynomial.
    private static final long POLYNOMIAL = 0xc96c_5795_d787_0f42L;

    /// The byte-wise CRC transition table.
    private static final long @Unmodifiable [] TABLE = createTable();

    /// The current inverted CRC state.
    private long state = -1L;

    /// Updates the CRC with one byte range.
    void update(byte[] bytes, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            state = TABLE[(Byte.toUnsignedInt(bytes[index]) ^ (int) state) & 0xff] ^ state >>> 8;
        }
    }

    /// Returns the completed CRC without resetting this calculator.
    long value() {
        return ~state;
    }

    /// Resets this calculator to its initial state.
    void reset() {
        state = -1L;
    }

    /// Creates the reflected byte transition table.
    private static long[] createTable() {
        long[] table = new long[256];
        for (int value = 0; value < table.length; value++) {
            long remainder = value;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                remainder = (remainder & 1L) != 0L
                        ? remainder >>> 1 ^ POLYNOMIAL
                        : remainder >>> 1;
            }
            table[value] = remainder;
        }
        return table;
    }

}

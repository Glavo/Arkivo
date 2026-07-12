// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Supplies the reflected CRC-32 words used by legacy RAR key schedules.
@NotNullByDefault
final class RarLegacyCRC {
    /// The standard reflected CRC-32 lookup table.
    private static final int @Unmodifiable [] TABLE = createTable();

    /// Prevents construction of this utility class.
    private RarLegacyCRC() {
    }

    /// Returns the lookup word for one unsigned byte index.
    static int word(int index) {
        return TABLE[index & 0xff];
    }

    /// Updates one reflected CRC-32 accumulator with one byte.
    static int update(int crc, int value) {
        return TABLE[(crc ^ value) & 0xff] ^ crc >>> 8;
    }

    /// Builds the reflected CRC-32 table from its public polynomial.
    private static int[] createTable() {
        int[] table = new int[256];
        for (int index = 0; index < table.length; index++) {
            int value = index;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                value = (value & 1) == 0 ? value >>> 1 : value >>> 1 ^ 0xedb8_8320;
            }
            table[index] = value;
        }
        return table;
    }
}

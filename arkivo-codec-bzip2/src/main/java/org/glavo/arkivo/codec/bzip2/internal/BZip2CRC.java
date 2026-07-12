// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Computes the non-reflected CRC-32 variant used by BZip2 blocks.
@NotNullByDefault
final class BZip2CRC {
    /// The BZip2 CRC polynomial.
    private static final int POLYNOMIAL = 0x04c11db7;

    /// The byte-at-a-time CRC transition table.
    private static final int @Unmodifiable [] TABLE = createTable();

    /// Prevents utility-class construction.
    private BZip2CRC() {
    }

    /// Returns the initial block CRC state.
    static int initial() {
        return -1;
    }

    /// Updates a CRC state with one unsigned byte.
    static int update(int crc, int value) {
        return (crc << 8) ^ TABLE[((crc >>> 24) ^ value) & 0xff];
    }

    /// Returns the externally stored CRC for an accumulated state.
    static int finish(int crc) {
        return ~crc;
    }

    /// Adds a completed block CRC to the stream-level combined CRC.
    static int combine(int combinedCrc, int blockCrc) {
        return Integer.rotateLeft(combinedCrc, 1) ^ blockCrc;
    }

    /// Creates the byte-at-a-time transition table.
    private static int @Unmodifiable [] createTable() {
        int[] table = new int[256];
        for (int value = 0; value < table.length; value++) {
            int crc = value << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc << 1) ^ ((crc & 0x80000000) != 0 ? POLYNOMIAL : 0);
            }
            table[value] = crc;
        }
        return table;
    }
}

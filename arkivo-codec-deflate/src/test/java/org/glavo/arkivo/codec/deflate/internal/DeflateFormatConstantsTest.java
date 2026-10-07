// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the complete length and distance mappings used by both Deflate formats.
@NotNullByDefault
final class DeflateFormatConstantsTest {
    /// Verifies every RFC 1951 match length, including the preferred encoding of length 258.
    @Test
    void mapsStandardLengths() {
        int[] upperBounds = {
                3, 4, 5, 6, 7, 8, 9, 10,
                12, 14, 16, 18, 22, 26, 30, 34,
                42, 50, 58, 66, 82, 98, 114, 130,
                162, 194, 226, 257, 258
        };
        int lowerBound = 3;
        for (int index = 0; index < upperBounds.length; index++) {
            assertEquals(lowerBound, DeflateFormatConstants.LENGTH_BASES[index]);
            int width = upperBounds[index] - lowerBound + 1;
            int extraBits = Integer.SIZE - Integer.numberOfLeadingZeros(width - 1);
            assertEquals(extraBits, DeflateFormatConstants.LENGTH_EXTRA_BITS[index]);
            for (int length = lowerBound; length <= upperBounds[index]; length++) {
                assertEquals(257 + index, DeflateFormatConstants.LENGTH_SYMBOLS[length]);
            }
            lowerBound = upperBounds[index] + 1;
        }
    }

    /// Verifies all backward distances, including the two symbols reserved for Deflate64.
    @Test
    void mapsStandardAndExtendedDistances() {
        int[] bases = {
                1, 2, 3, 4, 5, 7, 9, 13,
                17, 25, 33, 49, 65, 97, 129, 193,
                257, 385, 513, 769, 1025, 1537, 2049, 3073,
                4097, 6145, 8193, 12289, 16385, 24577, 32769, 49153,
                65537
        };
        for (int symbol = 0; symbol < bases.length - 1; symbol++) {
            int base = bases[symbol];
            int width = bases[symbol + 1] - base;
            assertEquals(base, DeflateFormatConstants.distanceBase(symbol));
            assertEquals(Integer.numberOfTrailingZeros(width),
                    DeflateFormatConstants.distanceExtraBits(symbol));
            for (int offset = 0; offset < width; offset++) {
                int distance = base + offset;
                assertEquals(symbol, DeflateFormatConstants.distanceSymbol(distance));
                assertEquals(offset, DeflateFormatConstants.distanceExtraValue(distance, symbol));
            }
        }
    }
}

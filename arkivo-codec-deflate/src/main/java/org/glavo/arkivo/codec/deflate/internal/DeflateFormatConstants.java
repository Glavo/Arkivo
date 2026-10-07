// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
// Portions adapted from zlib 1.3.1 and zlib-ng; see NOTICE and LICENSES/Zlib.txt.
// This Java implementation differs from the original C sources.

package org.glavo.arkivo.codec.deflate.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Defines the length and distance alphabets shared by Deflate and Deflate64.
///
/// Length tables use RFC 1951 semantics. Deflate64 interprets symbol 285 separately as a
/// 16-bit length offset from three; its distance alphabet also includes symbols 30 and 31.
@NotNullByDefault
final class DeflateFormatConstants {
    /// The minimum Deflate match length.
    static final int MINIMUM_MATCH_LENGTH = 3;

    /// The end-of-block literal/length symbol.
    static final int END_OF_BLOCK_SYMBOL = 256;

    /// The first length symbol.
    static final int FIRST_LENGTH_SYMBOL = 257;

    /// The final length symbol.
    static final int LAST_LENGTH_SYMBOL = 285;

    /// The maximum literal/length and distance Huffman code length.
    static final int MAXIMUM_DATA_CODE_LENGTH = 15;

    /// The maximum code-length Huffman code length.
    static final int MAXIMUM_CODE_LENGTH = 7;

    /// RFC 1951 base lengths for symbols 257 through 285; Deflate64 overrides symbol 285.
    static final short @Unmodifiable [] LENGTH_BASES = {
            3, 4, 5, 6, 7, 8, 9, 10,
            11, 13, 15, 17,
            19, 23, 27, 31,
            35, 43, 51, 59,
            67, 83, 99, 115,
            131, 163, 195, 227,
            258
    };

    /// Extra-bit counts for groups of four length indices, packed into successive low-to-high nibbles.
    /// Groups 0 through 7 contain 0, 0, 1, 2, 3, 4, 5, and 0; the last group contains symbol 285.
    private static final int PACKED_LENGTH_EXTRA_BITS = 0x05432100;

    /// Encoding symbols for lengths 3 through 258, choosing symbol 285 for length 258.
    static final short @Unmodifiable [] LENGTH_SYMBOLS = lengthSymbols();

    /// The ordered code-length alphabet used by dynamic block headers.
    static final byte @Unmodifiable [] CODE_LENGTH_ORDER = {
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    /// Returns the RFC 1951 extra-bit count for a length-table index from 0 through 28.
    static int lengthExtraBits(int index) {
        return (PACKED_LENGTH_EXTRA_BITS >>> (index & ~3)) & 0xf;
    }

    /// Creates the symbol lookup for match lengths from three through 258.
    private static short[] lengthSymbols() {
        short[] result = new short[259];
        for (int length = MINIMUM_MATCH_LENGTH; length <= 258; length++) {
            result[length] = LAST_LENGTH_SYMBOL;
            for (int index = 0; index < LENGTH_BASES.length - 1; index++) {
                int maximum = index == LENGTH_BASES.length - 2
                        ? 257
                        : LENGTH_BASES[index] + (1 << lengthExtraBits(index)) - 1;
                if (length <= maximum) {
                    result[length] = (short) (FIRST_LENGTH_SYMBOL + index);
                    break;
                }
            }
        }
        return result;
    }

    /// Returns the symbol for a backward distance from 1 through 65536.
    static int distanceSymbol(int distance) {
        if (distance <= 4) return distance - 1;
        int logarithm = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(distance - 1);
        return (logarithm << 1) + ((distance - 1) >>> (logarithm - 1) & 1);
    }

    /// Returns the extra-bit count for a distance symbol from 0 through 31.
    static int distanceExtraBits(int symbol) {
        return symbol < 4 ? 0 : (symbol >>> 1) - 1;
    }

    /// Returns the base distance for a distance symbol from 0 through 31.
    static int distanceBase(int symbol) {
        int extraBits = distanceExtraBits(symbol);
        return symbol < 4 ? symbol + 1 : ((2 + (symbol & 1)) << extraBits) + 1;
    }

    /// Returns the offset of a distance from the base of its encoding symbol.
    static int distanceExtraValue(int distance, int symbol) {
        return distance - distanceBase(symbol);
    }

    /// Prevents instantiation.
    private DeflateFormatConstants() {
    }
}

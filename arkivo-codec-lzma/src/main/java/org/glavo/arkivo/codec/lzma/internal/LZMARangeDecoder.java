// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Decodes the adaptive binary range coding used by LZMA probability models.
@NotNullByDefault
final class LZMARangeDecoder {
    /// The total probability scale.
    static final int PROBABILITY_TOTAL = 1 << 11;

    /// The adaptive-probability movement shift.
    private static final int PROBABILITY_MOVE_BITS = 5;

    /// The compressed range-coded source.
    private final LZMAChannelInput input;

    /// The current unsigned 32-bit range.
    private long range = 0xffff_ffffL;

    /// The current unsigned 32-bit code value.
    private long code;

    /// Creates and initializes a range decoder from its five-byte prefix.
    LZMARangeDecoder(LZMAChannelInput input) throws IOException {
        this.input = Objects.requireNonNull(input, "input");
        if (readRequiredByte() != 0) {
            throw new IOException("Invalid LZMA range coder prefix");
        }
        for (int index = 0; index < 4; index++) {
            code = code << 8 | readRequiredByte();
        }
    }

    /// Initializes every supplied binary probability to one half.
    static void initializeProbabilities(short[] probabilities) {
        java.util.Arrays.fill(probabilities, (short) (PROBABILITY_TOTAL >>> 1));
    }

    /// Decodes one adaptive binary probability.
    int decodeBit(short[] probabilities, int index) throws IOException {
        int probability = Short.toUnsignedInt(probabilities[index]);
        long bound = (range >>> 11) * probability;
        int bit;
        if (code < bound) {
            range = bound;
            probability += (PROBABILITY_TOTAL - probability) >>> PROBABILITY_MOVE_BITS;
            bit = 0;
        } else {
            range -= bound;
            code -= bound;
            probability -= probability >>> PROBABILITY_MOVE_BITS;
            bit = 1;
        }
        probabilities[index] = (short) probability;
        normalize();
        return bit;
    }

    /// Decodes a most-significant-bit-first direct-bit value.
    int decodeDirectBits(int count) throws IOException {
        int result = 0;
        for (int index = 0; index < count; index++) {
            range >>>= 1;
            int bit;
            if (code >= range) {
                code -= range;
                bit = 1;
            } else {
                bit = 0;
            }
            result = result << 1 | bit;
            normalize();
        }
        return result;
    }

    /// Decodes a normal bit tree and returns its symbol.
    int decodeBitTree(short[] probabilities, int offset, int bitCount) throws IOException {
        int symbol = 1;
        for (int index = 0; index < bitCount; index++) {
            symbol = symbol << 1 | decodeBit(probabilities, offset + symbol);
        }
        return symbol - (1 << bitCount);
    }

    /// Decodes a least-significant-bit-first bit tree and returns its symbol.
    int decodeReverseBitTree(short[] probabilities, int offset, int bitCount) throws IOException {
        int symbol = 1;
        int result = 0;
        for (int index = 0; index < bitCount; index++) {
            int bit = decodeBit(probabilities, offset + symbol);
            symbol = symbol << 1 | bit;
            result |= bit << index;
        }
        return result;
    }

    /// Returns whether the final range code has been reduced to zero.
    boolean finished() {
        return code == 0L;
    }

    /// Reads another source byte whenever the range loses its high byte.
    private void normalize() throws IOException {
        if (range < 1L << 24) {
            range <<= 8;
            code = (code << 8 | readRequiredByte()) & 0xffff_ffffL;
        }
    }

    /// Reads one required compressed byte.
    private int readRequiredByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated LZMA range-coded stream");
        }
        return value;
    }
}

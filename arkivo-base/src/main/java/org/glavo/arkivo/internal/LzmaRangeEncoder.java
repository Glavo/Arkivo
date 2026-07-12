// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/// Encodes adaptive binary probabilities with the range coder used by LZMA.
@NotNullByDefault
final class LzmaRangeEncoder {
    /// The destination for range-coded bytes.
    private final OutputStream output;

    /// The low end of the active coding interval.
    private long low;

    /// The unsigned 32-bit width of the active coding interval.
    private long range = 0xffff_ffffL;

    /// The delayed high byte of `low`.
    private int cache;

    /// The number of delayed bytes represented by `cache`.
    private int cacheSize = 1;

    /// Creates a range encoder targeting the supplied stream.
    LzmaRangeEncoder(OutputStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /// Encodes one adaptive binary probability.
    void encodeBit(short[] probabilities, int index, int bit) throws IOException {
        int probability = Short.toUnsignedInt(probabilities[index]);
        long bound = (range >>> 11) * probability;
        if (bit == 0) {
            range = bound;
            probability += (LzmaRangeDecoder.PROBABILITY_TOTAL - probability) >>> 5;
        } else {
            low += bound;
            range -= bound;
            probability -= probability >>> 5;
        }
        probabilities[index] = (short) probability;
        normalize();
    }

    /// Encodes a most-significant-bit-first direct-bit value.
    void encodeDirectBits(int value, int count) throws IOException {
        for (int index = count - 1; index >= 0; index--) {
            range >>>= 1;
            if ((value >>> index & 1) != 0) {
                low += range;
            }
            normalize();
        }
    }

    /// Encodes a normal bit-tree symbol.
    void encodeBitTree(short[] probabilities, int offset, int bitCount, int value) throws IOException {
        int symbol = 1;
        for (int index = bitCount - 1; index >= 0; index--) {
            int bit = value >>> index & 1;
            encodeBit(probabilities, offset + symbol, bit);
            symbol = symbol << 1 | bit;
        }
    }

    /// Encodes a least-significant-bit-first bit-tree symbol.
    void encodeReverseBitTree(short[] probabilities, int offset, int bitCount, int value) throws IOException {
        int symbol = 1;
        for (int index = 0; index < bitCount; index++) {
            int bit = value >>> index & 1;
            encodeBit(probabilities, offset + symbol, bit);
            symbol = symbol << 1 | bit;
        }
    }

    /// Flushes the final coding interval.
    void finish() throws IOException {
        for (int index = 0; index < 5; index++) {
            shiftLow();
        }
    }

    /// Normalizes the coding interval whenever its high byte is empty.
    private void normalize() throws IOException {
        if (range < 1L << 24) {
            range <<= 8;
            shiftLow();
        }
    }

    /// Emits stable high bytes while carrying into delayed `0xff` bytes.
    private void shiftLow() throws IOException {
        int high = (int) (low >>> 32);
        if (high != 0 || low < 0xff00_0000L) {
            int value = cache;
            do {
                output.write(value + high);
                value = 0xff;
            } while (--cacheSize != 0);
            cache = (int) (low >>> 24) & 0xff;
        }
        cacheSize++;
        low = (low & 0x00ff_ffffL) << 8;
    }
}

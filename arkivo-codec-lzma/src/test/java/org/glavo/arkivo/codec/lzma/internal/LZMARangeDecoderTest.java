// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Compares unsigned range arithmetic and probability updates with an independent long-valued decoder.
@NotNullByDefault
final class LZMARangeDecoderTest {
    /// Verifies both intervals, direct bits, normalization, and adaptive probability state.
    @Test
    void matchesUnsignedReferenceAcrossBitSequences() throws IOException {
        for (int seed = 0; seed < 4; seed++) {
            byte[] bytes = new byte[1 << 20];
            Random random = new Random(seed);
            random.nextBytes(bytes);
            bytes[0] = 0;
            ArrayInput input = new ArrayInput(bytes);
            LZMARangeDecoder actual = new LZMARangeDecoder(input);
            Reference expected = new Reference(bytes);
            short[] probabilities = new short[64];
            Arrays.fill(probabilities, (short) 1024);
            for (int operation = 0; operation < 50_000; operation++) {
                if ((operation & 7) == 0) {
                    int count = 1 + random.nextInt(16);
                    assertEquals(expected.direct(count), actual.decodeDirectBits(count));
                } else {
                    int index = random.nextInt(probabilities.length);
                    assertEquals(expected.bit(index), actual.decodeBit(probabilities, index));
                }
                assertEquals(expected.position, input.position);
                if ((operation & 255) == 0) assertArrayEquals(expected.probabilities, probabilities);
            }
            assertArrayEquals(expected.probabilities, probabilities);
        }
    }

    /// Supplies a deterministic byte sequence without speculative parsing.
    @NotNullByDefault
    private static final class ArrayInput implements LZMAInput {
        /// The encoded byte sequence.
        private final byte @Unmodifiable [] bytes;
        /// The next unread byte index.
        private int position;
        /// Creates one independent input cursor.
        private ArrayInput(byte[] bytes) { this.bytes = bytes; }
        /// Reads the next unsigned byte or physical end of input.
        @Override
        public int read() { return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]); }
    }

    /// Uses explicit unsigned longs and ordinary branches as the arithmetic reference.
    @NotNullByDefault
    private static final class Reference {
        /// The deterministic source bytes.
        private final byte @Unmodifiable [] bytes;
        /// The next unread byte index after the five-byte prefix.
        private int position = 5;
        /// The unsigned interval width.
        private long range = 0xffff_ffffL;
        /// The unsigned code point.
        private long code;
        /// Independently adapted probability models.
        private final short[] probabilities = new short[64];
        /// Reads the range prefix and initializes the probability models.
        private Reference(byte[] bytes) {
            this.bytes = bytes;
            for (int i = 1; i < 5; i++) code = code << 8 | Byte.toUnsignedInt(bytes[i]);
            Arrays.fill(probabilities, (short) 1024);
        }
        /// Decodes one modeled bit with unsigned long arithmetic.
        private int bit(int index) {
            int probability = probabilities[index];
            long bound = (range >>> 11) * probability;
            int bit;
            if (code < bound) {
                range = bound;
                probability += (2048 - probability) >>> 5;
                bit = 0;
            } else {
                range -= bound;
                code -= bound;
                probability -= probability >>> 5;
                bit = 1;
            }
            probabilities[index] = (short) probability;
            normalize();
            return bit;
        }
        /// Decodes one direct-bit integer without probability models.
        private int direct(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                range >>>= 1;
                int bit = code >= range ? 1 : 0;
                if (bit != 0) code -= range;
                result = result << 1 | bit;
                normalize();
            }
            return result;
        }
        /// Restores the interval's high byte and advances the independent input cursor.
        private void normalize() {
            if (range < 1L << 24) {
                range <<= 8;
                code = (code << 8 | Byte.toUnsignedInt(bytes[position++])) & 0xffff_ffffL;
            }
        }
    }
}

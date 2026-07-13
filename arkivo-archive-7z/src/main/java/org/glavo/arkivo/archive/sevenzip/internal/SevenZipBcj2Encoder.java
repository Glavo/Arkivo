// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;
import java.util.Objects;

/// Splits an x86 instruction stream into the four inputs consumed by the 7z BCJ2 decoder.
///
/// The encoder keeps only four bytes of lookahead. Source and destination ownership remains with the caller.
@NotNullByDefault
final class SevenZipBcj2Encoder {
    /// The default relative-address conversion limit used by current 7-Zip releases.
    private static final int RELATIVE_LIMIT = 0x0f00_0000;

    /// The number of bytes following an x86 branch marker.
    private static final int ADDRESS_SIZE = Integer.BYTES;

    /// Creates no instances.
    private SevenZipBcj2Encoder() {
    }

    /// Splits exactly `sourceSize` bytes into MAIN, CALL, JUMP, and range streams.
    static void encode(
            InputStream source,
            long sourceSize,
            OutputStream main,
            OutputStream call,
            OutputStream jump,
            OutputStream rangeOutput
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(jump, "jump");
        Objects.requireNonNull(rangeOutput, "rangeOutput");
        if (sourceSize < 0L) {
            throw new IllegalArgumentException("sourceSize must be non-negative");
        }

        PushbackInputStream input = new PushbackInputStream(source, ADDRESS_SIZE);
        RangeEncoder range = new RangeEncoder(rangeOutput);
        byte[] address = new byte[ADDRESS_SIZE];
        int previous = 0;
        long position = 0L;
        while (position < sourceSize) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("Unexpected end of BCJ2 source stream");
            }
            main.write(current);

            int context = (previous << 24) | current;
            boolean marker = (current & 0xfe) == 0xe8
                    || (previous == 0x0f && (current & 0xf0) == 0x80);
            if (!marker) {
                previous = current;
                position++;
                continue;
            }

            int probabilityIndex = current == 0xe8 ? previous + 2 : current == 0xe9 ? 1 : 0;
            int addressLength = readLookahead(input, address, sourceSize - position - 1L);
            int relative = addressLength == ADDRESS_SIZE ? readLittleEndianInt(address) : 0;
            long instructionPointer = position + 1L;
            long target = instructionPointer + ADDRESS_SIZE + relative;
            int overlapBoundary = ((context + 0x20) >>> 5) & 1;
            long limitedRelative = Integer.toUnsignedLong(relative + RELATIVE_LIMIT) >>> 1;
            boolean convert = addressLength == ADDRESS_SIZE
                    && instructionPointer > overlapBoundary
                    && target >= 0L
                    && target < sourceSize
                    && limitedRelative < RELATIVE_LIMIT;
            range.encode(probabilityIndex, convert ? 1 : 0);
            if (!convert) {
                input.unread(address, 0, addressLength);
                previous = current;
                position++;
                continue;
            }

            writeBigEndianInt(current == 0xe8 ? call : jump, (int) target);
            previous = Byte.toUnsignedInt(address[ADDRESS_SIZE - 1]);
            position += 1L + ADDRESS_SIZE;
        }
        if (input.read() >= 0) {
            throw new IOException("BCJ2 source stream exceeds its declared size");
        }
        range.finish();
    }

    /// Reads up to four lookahead bytes without crossing the declared source boundary.
    private static int readLookahead(InputStream input, byte[] address, long remaining) throws IOException {
        int required = (int) Math.min(ADDRESS_SIZE, remaining);
        int count = 0;
        while (count < required) {
            int read = input.read(address, count, required - count);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                address[count++] = (byte) value;
            } else {
                count += read;
            }
        }
        return count;
    }

    /// Reads one little-endian signed 32-bit relative address.
    private static int readLittleEndianInt(byte[] bytes) {
        return Byte.toUnsignedInt(bytes[0])
                | (Byte.toUnsignedInt(bytes[1]) << 8)
                | (Byte.toUnsignedInt(bytes[2]) << 16)
                | (bytes[3] << 24);
    }

    /// Writes one unsigned 32-bit absolute address in BCJ2 big-endian order.
    private static void writeBigEndianInt(OutputStream output, int value) throws IOException {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    /// Encodes BCJ2 branch-selection decisions with adaptive binary probabilities.
    @NotNullByDefault
    private static final class RangeEncoder {
        /// The range encoder normalization threshold.
        private static final long TOP_VALUE = 1L << 24;

        /// The total binary probability scale.
        private static final int PROBABILITY_TOTAL = 1 << 11;

        /// The probability adaptation shift.
        private static final int PROBABILITY_MOVE_BITS = 5;

        /// The destination range stream.
        private final OutputStream output;

        /// The adaptive BCJ2 probability models.
        private final int[] probabilities = new int[258];

        /// The pending low value including carry bits.
        private long low;

        /// The current unsigned 32-bit range.
        private long range = 0xffff_ffffL;

        /// The delayed output byte.
        private int cache;

        /// The number of delayed bytes sharing the pending carry.
        private long cacheSize = 1L;

        /// Whether the terminal range state has been emitted.
        private boolean finished;

        /// Creates a range encoder with neutral probabilities.
        private RangeEncoder(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
            Arrays.fill(probabilities, PROBABILITY_TOTAL >>> 1);
        }

        /// Encodes one branch-selection bit with the requested probability model.
        private void encode(int probabilityIndex, int bit) throws IOException {
            if (finished) {
                throw new IllegalStateException("BCJ2 range encoder is finished");
            }
            int probability = probabilities[probabilityIndex];
            long bound = (range >>> 11) * probability;
            if (bit == 0) {
                range = bound;
                probabilities[probabilityIndex] =
                        probability + ((PROBABILITY_TOTAL - probability) >>> PROBABILITY_MOVE_BITS);
            } else {
                low += bound;
                range -= bound;
                probabilities[probabilityIndex] = probability - (probability >>> PROBABILITY_MOVE_BITS);
            }
            while (range < TOP_VALUE) {
                range <<= 8;
                shiftLow();
            }
        }

        /// Emits the five-byte terminal range state.
        private void finish() throws IOException {
            if (finished) {
                return;
            }
            for (int index = 0; index < 5; index++) {
                shiftLow();
            }
            finished = true;
        }

        /// Emits stabilized high bytes while propagating a carry through delayed bytes.
        private void shiftLow() throws IOException {
            long low32 = low & 0xffff_ffffL;
            long high = low >>> 32;
            if (low32 < 0xff00_0000L || high != 0L) {
                int value = cache;
                do {
                    output.write((value + (int) high) & 0xff);
                    value = 0xff;
                } while (--cacheSize != 0L);
                cache = (int) (low32 >>> 24);
            }
            cacheSize++;
            low = (low32 & 0x00ff_ffffL) << 8;
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies compatibility with BZip2 streams that declare excess Huffman selectors.
@NotNullByDefault
public final class BZip2SelectorOverflowTest {
    /// The BZip2 compressed-block marker.
    private static final long BLOCK_MAGIC = 0x314159265359L;

    /// The BZip2 end-of-stream marker.
    private static final long END_MAGIC = 0x177245385090L;

    /// The largest selector count representable by the 15-bit field.
    private static final int MAXIMUM_ENCODED_SELECTOR_COUNT = (1 << 15) - 1;

    /// Decodes a generated stream after extending its selector sequence to the field maximum.
    @Test
    public void decodesExcessSelectorsAfterConsumingTheirBits() throws IOException {
        byte @Unmodifiable [] expected =
                "legacy lbzip2 selector overflow".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] expanded = expandSelectorSequence(compress(expected), false);

        assertArrayEquals(expected, decompress(expanded));
    }

    /// Rejects an out-of-range selector value even when it appears in the discarded suffix.
    @Test
    public void rejectsInvalidDiscardedSelectorValue() throws IOException {
        byte @Unmodifiable [] compressed =
                compress("invalid discarded selector".getBytes(StandardCharsets.UTF_8));
        byte @Unmodifiable [] malformed = expandSelectorSequence(compressed, true);

        assertThrows(IOException.class, () -> decompress(malformed));
    }

    /// Compresses bytes with the smallest block-size level to maximize the discarded selector suffix.
    private static byte @Unmodifiable [] compress(byte @Unmodifiable [] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        new BZip2Codec()
                .withCompressionLevel(1L)
                .compress(
                        Channels.newChannel(new ByteArrayInputStream(input)),
                        Channels.newChannel(compressed)
                );
        return compressed.toByteArray();
    }

    /// Decompresses one complete BZip2 byte sequence.
    private static byte @Unmodifiable [] decompress(byte @Unmodifiable [] compressed) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        new BZip2Codec().decompress(
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(decoded)
        );
        return decoded.toByteArray();
    }

    /// Rewrites the first block to declare 32767 selectors and appends their unary encodings.
    private static byte @Unmodifiable [] expandSelectorSequence(
            byte @Unmodifiable [] compressed,
            boolean invalidateLastSelector
    ) {
        int position = 0;
        requireBits(compressed, position, 32);
        if (readBits(compressed, position, 8) != 'B'
                || readBits(compressed, position + 8, 8) != 'Z'
                || readBits(compressed, position + 16, 8) != 'h') {
            throw new IllegalArgumentException("Expected a BZip2 stream header");
        }
        position += 32;

        if (readBitsLong(compressed, position, 48) != BLOCK_MAGIC) {
            throw new IllegalArgumentException("Expected a BZip2 compressed block");
        }
        position += 48 + 32 + 1 + 24;

        int inUseGroups = readBits(compressed, position, 16);
        position += 16;
        for (int group = 0; group < 16; group++) {
            if ((inUseGroups & (1 << (15 - group))) != 0) {
                position += 16;
            }
        }

        int groupCount = readBits(compressed, position, 3);
        position += 3;
        if (groupCount < 2 || groupCount > 6) {
            throw new IllegalArgumentException("Invalid generated BZip2 Huffman group count");
        }

        int selectorCountPosition = position;
        int originalSelectorCount = readBits(compressed, position, 15);
        position += 15;
        if (originalSelectorCount < 1 || originalSelectorCount >= MAXIMUM_ENCODED_SELECTOR_COUNT) {
            throw new IllegalArgumentException("Invalid generated BZip2 selector count");
        }

        int selectorDataStart = position;
        for (int index = 0; index < originalSelectorCount; index++) {
            while (readBit(compressed, position)) {
                position++;
            }
            position++;
        }
        int selectorDataEnd = position;
        int meaningfulBitLength = findMeaningfulBitLength(compressed);
        if (selectorDataEnd >= meaningfulBitLength) {
            throw new IllegalArgumentException("Invalid generated BZip2 selector layout");
        }

        BitOutput output = new BitOutput();
        copyBits(compressed, 0, selectorCountPosition, output);
        output.writeBits(MAXIMUM_ENCODED_SELECTOR_COUNT, 15);
        copyBits(compressed, selectorDataStart, selectorDataEnd, output);
        for (int index = originalSelectorCount; index < MAXIMUM_ENCODED_SELECTOR_COUNT; index++) {
            int moveToFrontValue =
                    invalidateLastSelector && index == MAXIMUM_ENCODED_SELECTOR_COUNT - 1
                            ? groupCount
                            : 0;
            for (int unaryBit = 0; unaryBit < moveToFrontValue; unaryBit++) {
                output.writeBit(true);
            }
            output.writeBit(false);
        }
        copyBits(compressed, selectorDataEnd, meaningfulBitLength, output);
        return output.toByteArray();
    }

    /// Finds the meaningful bit length by locating the final stream marker before byte padding.
    private static int findMeaningfulBitLength(byte @Unmodifiable [] bytes) {
        int totalBits = Math.multiplyExact(bytes.length, Byte.SIZE);
        for (int padding = 0; padding < Byte.SIZE; padding++) {
            int meaningfulBits = totalBits - padding;
            if (meaningfulBits >= 80
                    && readBitsLong(bytes, meaningfulBits - 80, 48) == END_MAGIC
                    && areZeroBits(bytes, meaningfulBits, totalBits)) {
                return meaningfulBits;
            }
        }
        throw new IllegalArgumentException("BZip2 end marker was not found");
    }

    /// Returns whether every bit in the half-open range is zero.
    private static boolean areZeroBits(byte @Unmodifiable [] bytes, int start, int end) {
        for (int position = start; position < end; position++) {
            if (readBit(bytes, position)) {
                return false;
            }
        }
        return true;
    }

    /// Copies a half-open bit range to the destination.
    private static void copyBits(byte @Unmodifiable [] source, int start, int end, BitOutput target) {
        for (int position = start; position < end; position++) {
            target.writeBit(readBit(source, position));
        }
    }

    /// Reads an unsigned integer containing at most 31 bits.
    private static int readBits(byte @Unmodifiable [] bytes, int position, int count) {
        return Math.toIntExact(readBitsLong(bytes, position, count));
    }

    /// Reads an unsigned integer containing at most 63 bits.
    private static long readBitsLong(byte @Unmodifiable [] bytes, int position, int count) {
        if (count < 0 || count > Long.SIZE - 1) {
            throw new IllegalArgumentException("Invalid bit count: " + count);
        }
        requireBits(bytes, position, count);
        long value = 0L;
        for (int offset = 0; offset < count; offset++) {
            value = value << 1 | (readBit(bytes, position + offset) ? 1L : 0L);
        }
        return value;
    }

    /// Reads one most-significant-bit-first bit.
    private static boolean readBit(byte @Unmodifiable [] bytes, int position) {
        requireBits(bytes, position, 1);
        int value = Byte.toUnsignedInt(bytes[position / Byte.SIZE]);
        return (value & (1 << (Byte.SIZE - 1 - position % Byte.SIZE))) != 0;
    }

    /// Verifies a requested bit range lies inside the source bytes.
    private static void requireBits(byte @Unmodifiable [] bytes, int position, int count) {
        if (position < 0
                || count < 0
                || (long) position + count > (long) bytes.length * Byte.SIZE) {
            throw new IllegalArgumentException("Bit range exceeds the generated BZip2 stream");
        }
    }

    /// Collects a most-significant-bit-first sequence into a padded byte array.
    @NotNullByDefault
    private static final class BitOutput {
        /// The completed bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// The incomplete byte accumulator.
        private int currentByte;

        /// The number of bits currently held in the accumulator.
        private int currentBitCount;

        /// Writes one bit.
        private void writeBit(boolean value) {
            currentByte = currentByte << 1 | (value ? 1 : 0);
            currentBitCount++;
            if (currentBitCount == Byte.SIZE) {
                bytes.write(currentByte);
                currentByte = 0;
                currentBitCount = 0;
            }
        }

        /// Writes the requested low-order bits from most significant to least significant.
        private void writeBits(long value, int count) {
            if (count < 0 || count > Long.SIZE - 1) {
                throw new IllegalArgumentException("Invalid bit count: " + count);
            }
            for (int shift = count - 1; shift >= 0; shift--) {
                writeBit(((value >>> shift) & 1L) != 0L);
            }
        }

        /// Returns the sequence padded with zero bits to its next byte boundary.
        private byte @Unmodifiable [] toByteArray() {
            if (currentBitCount != 0) {
                bytes.write(currentByte << (Byte.SIZE - currentBitCount));
                currentByte = 0;
                currentBitCount = 0;
            }
            return bytes.toByteArray();
        }
    }
}

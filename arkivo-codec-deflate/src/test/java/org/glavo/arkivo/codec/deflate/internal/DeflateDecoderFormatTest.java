// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RFC 1951 profile boundaries in the shared pure Java decoder.
@NotNullByDefault
final class DeflateDecoderFormatTest {
    /// Verifies that symbol 285 means exactly 258 bytes without Deflate64 extra bits.
    @Test
    void decodesStandardLengthSymbol285() throws IOException {
        BitWriter writer = new BitWriter();
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength('a');
        writer.writeFixedLiteralLength('b');
        writer.writeFixedLiteralLength('c');
        writer.writeFixedLiteralLength(285);
        writer.writeFixedDistance(2);
        writer.writeFixedLiteralLength(256);

        byte[] expected = new byte[261];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) ('a' + index % 3);
        }

        assertArrayEquals(expected, decode(writer.toByteArray()));
    }

    /// Verifies the largest RFC 1951 backward distance across a preceding stored block.
    @Test
    void decodesMaximumStandardDistance() throws IOException {
        byte[] history = new byte[1 << 15];
        for (int index = 0; index < history.length; index++) {
            history[index] = (byte) (index * 31 + index / 251);
        }
        BitWriter writer = new BitWriter();
        writer.writeStoredBlock(false, history);
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength(257);
        writer.writeFixedDistance(29);
        writer.writeBits(0x1fff, 13);
        writer.writeFixedLiteralLength(256);

        byte[] expected = Arrays.copyOf(history, history.length + 3);
        System.arraycopy(history, 0, expected, history.length, 3);

        assertArrayEquals(expected, decode(writer.toByteArray()));
    }

    /// Verifies that Deflate64-only distance symbols remain reserved in RFC 1951 streams.
    @Test
    void rejectsDeflate64DistanceSymbols() {
        BitWriter writer = new BitWriter();
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength('a');
        writer.writeFixedLiteralLength(257);
        writer.writeFixedDistance(30);

        IOException exception = assertThrows(IOException.class, () -> decode(writer.toByteArray()));

        assertTrue(exception.getMessage().contains("distance symbol 30"));
    }

    /// Decodes a complete raw stream from fresh one-byte source buffers.
    private static byte[] decode(byte[] compressed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int offset = 0;
        try (CompressionDecoder decoder = new DeflateDecoder(null)) {
            while (true) {
                int offered = Math.min(1, compressed.length - offset);
                ByteBuffer source = ByteBuffer.wrap(compressed, offset, offered).slice();
                ByteBuffer target = ByteBuffer.allocateDirect(7);
                CodecOutcome outcome = decoder.decode(source, target, offset + offered == compressed.length);
                offset += source.position();
                target.flip();
                while (target.hasRemaining()) {
                    output.write(target.get());
                }
                if (outcome == CodecOutcome.FINISHED) {
                    return output.toByteArray();
                }
                if (outcome != CodecOutcome.NEEDS_INPUT && outcome != CodecOutcome.NEEDS_OUTPUT) {
                    throw new IOException("Unexpected raw Deflate decoder outcome: " + outcome);
                }
            }
        }
    }

    /// Writes the RFC 1951 fields needed by profile-boundary fixtures.
    @NotNullByDefault
    private static final class BitWriter {
        /// The completed output bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// The partially filled output byte.
        private int currentByte;

        /// The number of occupied bits in the partial byte.
        private int bitCount;

        /// Writes a least-significant-bit-first integer field.
        private void writeBits(int value, int count) {
            for (int index = 0; index < count; index++) {
                currentByte |= (value >>> index & 1) << bitCount;
                bitCount++;
                if (bitCount == 8) {
                    flushByte();
                }
            }
        }

        /// Writes a canonical Huffman code from its most significant bit.
        private void writeCode(int code, int length) {
            for (int bit = length - 1; bit >= 0; bit--) {
                writeBits(code >>> bit & 1, 1);
            }
        }

        /// Writes one complete stored block.
        private void writeStoredBlock(boolean finalBlock, byte[] content) {
            if (content.length > 0xffff) {
                throw new IllegalArgumentException("stored block content is too large");
            }
            writeBits(finalBlock ? 1 : 0, 1);
            writeBits(0, 2);
            alignToByte();
            writeLittleEndianShort(content.length);
            writeLittleEndianShort(~content.length);
            output.writeBytes(content);
        }

        /// Writes a final fixed-Huffman block header.
        private void writeFixedBlockHeader() {
            writeBits(1, 1);
            writeBits(1, 2);
        }

        /// Writes one fixed literal/length-tree symbol.
        private void writeFixedLiteralLength(int symbol) {
            if (symbol < 0 || symbol > 287) {
                throw new IllegalArgumentException("fixed literal/length symbol is out of range");
            }
            if (symbol <= 143) {
                writeCode(0x30 + symbol, 8);
            } else if (symbol <= 255) {
                writeCode(0x190 + symbol - 144, 9);
            } else if (symbol <= 279) {
                writeCode(symbol - 256, 7);
            } else {
                writeCode(0xc0 + symbol - 280, 8);
            }
        }

        /// Writes one fixed distance-tree symbol.
        private void writeFixedDistance(int symbol) {
            if (symbol < 0 || symbol > 31) {
                throw new IllegalArgumentException("fixed distance symbol is out of range");
            }
            writeCode(symbol, 5);
        }

        /// Pads the current byte with zero bits.
        private void alignToByte() {
            if (bitCount != 0) {
                flushByte();
            }
        }

        /// Writes a little-endian unsigned short.
        private void writeLittleEndianShort(int value) {
            if (bitCount != 0) {
                throw new IllegalStateException("short output must be byte-aligned");
            }
            output.write(value);
            output.write(value >>> 8);
        }

        /// Returns the completed stream bytes.
        private byte[] toByteArray() {
            alignToByte();
            return output.toByteArray();
        }

        /// Flushes the partial byte.
        private void flushByte() {
            output.write(currentByte);
            currentByte = 0;
            bitCount = 0;
        }
    }
}

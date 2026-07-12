// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests native raw Deflate64 block and history decoding.
@NotNullByDefault
public final class Deflate64InputStreamTest {
    /// Verifies stored blocks and zero-length reads.
    @Test
    public void decodesStoredBlock() throws IOException {
        byte[] expected = "stored Deflate64 content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        BitWriter writer = new BitWriter();
        writer.writeStoredBlock(true, expected);

        try (Deflate64InputStream input = input(writer)) {
            assertEquals(0, input.read(new byte[4], 0, 0));
            assertArrayEquals(expected, input.readAllBytes());
            assertEquals(-1, input.read());
        }
    }

    /// Verifies the sixteen-bit length extension assigned to fixed-tree symbol 285.
    @Test
    public void decodesExtendedLengthSymbol() throws IOException {
        BitWriter writer = new BitWriter();
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteral('a');
        writer.writeFixedLiteral('b');
        writer.writeFixedLiteral('c');
        writer.writeFixedLiteralLength(285);
        writer.writeBits(297, 16);
        writer.writeFixedDistance(2);
        writer.writeFixedLiteralLength(256);

        byte[] expected = new byte[303];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) ('a' + index % 3);
        }
        try (Deflate64InputStream input = input(writer)) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }

    /// Verifies distance symbol 31 and history retained across stored and fixed blocks.
    @Test
    public void decodesMaximumDistance() throws IOException {
        byte[] history = new byte[1 << 16];
        for (int index = 0; index < history.length; index++) {
            history[index] = (byte) (index * 31 + index / 251);
        }

        BitWriter writer = new BitWriter();
        writer.writeStoredBlock(false, Arrays.copyOfRange(history, 0, 0xffff));
        writer.writeStoredBlock(false, Arrays.copyOfRange(history, 0xffff, history.length));
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength(257);
        writer.writeFixedDistance(31);
        writer.writeBits(0x3fff, 14);
        writer.writeFixedLiteralLength(256);

        byte[] expected = Arrays.copyOf(history, history.length + 3);
        System.arraycopy(history, 0, expected, history.length, 3);
        try (Deflate64InputStream input = input(writer)) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }

    /// Verifies dynamic Huffman headers and long zero repeats in the code-length alphabet.
    @Test
    public void decodesDynamicHuffmanBlock() throws IOException {
        BitWriter writer = dynamicLiteralAWriter();
        try (Deflate64InputStream input = input(writer)) {
            assertArrayEquals(new byte[]{'A', 'A', 'A', 'A'}, input.readAllBytes());
        }
    }

    /// Verifies the shared Huffman grammar against an independently generated raw Deflate dynamic block.
    @Test
    public void decodesStandardDynamicHuffmanGrammar() throws IOException {
        byte[] expected = new byte[8192];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) ((index * 73 ^ index >>> 3) & 15);
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setStrategy(Deflater.HUFFMAN_ONLY);
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed, deflater)) {
            output.write(expected);
        } finally {
            deflater.end();
        }

        try (Deflate64InputStream input =
                     new Deflate64InputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }

    /// Verifies malformed block headers, stored lengths, symbols, distances, and dynamic trees.
    @Test
    public void rejectsMalformedStreams() throws IOException {
        BitWriter reserved = new BitWriter();
        reserved.writeBits(1, 1);
        reserved.writeBits(3, 2);
        try (Deflate64InputStream input = input(reserved)) {
            assertThrows(IOException.class, input::read);
        }

        BitWriter stored = new BitWriter();
        stored.writeBits(1, 1);
        stored.writeBits(0, 2);
        stored.alignToByte();
        stored.writeLittleEndianShort(1);
        stored.writeLittleEndianShort(0);
        try (Deflate64InputStream input = input(stored)) {
            assertThrows(IOException.class, input::read);
        }

        BitWriter distance = new BitWriter();
        distance.writeFixedBlockHeader();
        distance.writeFixedLiteralLength(257);
        distance.writeFixedDistance(0);
        distance.writeFixedLiteralLength(256);
        try (Deflate64InputStream input = input(distance)) {
            IOException distanceException = assertThrows(IOException.class, input::read);
            assertTrue(distanceException.getMessage().contains("exceeds available history"));
        }

        BitWriter symbol = new BitWriter();
        symbol.writeFixedBlockHeader();
        symbol.writeFixedLiteralLength(286);
        try (Deflate64InputStream input = input(symbol)) {
            IOException symbolException = assertThrows(IOException.class, input::read);
            assertTrue(symbolException.getMessage().contains("literal/length symbol"));
        }

        BitWriter dynamic = new BitWriter();
        dynamic.writeBits(1, 1);
        dynamic.writeBits(2, 2);
        dynamic.writeBits(0, 5);
        dynamic.writeBits(0, 5);
        dynamic.writeBits(0, 4);
        dynamic.writeBits(1, 3);
        dynamic.writeBits(1, 3);
        dynamic.writeBits(1, 3);
        dynamic.writeBits(0, 3);
        try (Deflate64InputStream input = input(dynamic)) {
            IOException treeException = assertThrows(IOException.class, input::read);
            assertTrue(treeException.getMessage().contains("oversubscribed"));
        }
    }

    /// Verifies truncated fields and missing end-of-block symbols.
    @Test
    public void rejectsTruncatedStreams() throws IOException {
        try (Deflate64InputStream input = new Deflate64InputStream(new ByteArrayInputStream(new byte[0]))) {
            assertThrows(IOException.class, input::read);
        }

        BitWriter fixed = new BitWriter();
        fixed.writeFixedBlockHeader();
        fixed.writeFixedLiteral('x');
        try (Deflate64InputStream input = input(fixed)) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    /// Verifies source ownership and closed-stream behavior.
    @Test
    public void closesSource() throws IOException {
        TrackingInputStream source = new TrackingInputStream(new byte[]{1, 0, 0, (byte) 0xff, (byte) 0xff});
        Deflate64InputStream input = new Deflate64InputStream(source);
        input.close();
        input.close();

        assertEquals(1, source.closeCount());
        assertThrows(ClosedChannelException.class, input::read);
    }

    /// Creates a dynamic block containing four `A` literals.
    private static BitWriter dynamicLiteralAWriter() {
        BitWriter writer = new BitWriter();
        writer.writeBits(1, 1);
        writer.writeBits(2, 2);
        writer.writeBits(0, 5);
        writer.writeBits(0, 5);
        writer.writeBits(14, 4);

        int[] codeLengthLengths = new int[18];
        codeLengthLengths[2] = 2;
        codeLengthLengths[3] = 1;
        codeLengthLengths[17] = 2;
        for (int length : codeLengthLengths) {
            writer.writeBits(length, 3);
        }

        writer.writeCode(3, 2);
        writer.writeBits(54, 7);
        writer.writeCode(2, 2);
        writer.writeCode(3, 2);
        writer.writeBits(127, 7);
        writer.writeCode(3, 2);
        writer.writeBits(41, 7);
        writer.writeCode(2, 2);
        writer.writeCode(2, 2);

        for (int index = 0; index < 4; index++) {
            writer.writeCode(0, 1);
        }
        writer.writeCode(1, 1);
        return writer;
    }

    /// Opens a decoder for one generated bitstream.
    private static Deflate64InputStream input(BitWriter writer) {
        return new Deflate64InputStream(new ByteArrayInputStream(writer.toByteArray()));
    }

    /// Writes Deflate64 fields in their format-specific bit order.
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

        /// Writes a fixed-Huffman block header.
        private void writeFixedBlockHeader() {
            writeBits(1, 1);
            writeBits(1, 2);
        }

        /// Writes one fixed-tree literal.
        private void writeFixedLiteral(int symbol) {
            writeFixedLiteralLength(symbol);
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

    /// Tracks source closure.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// The number of close calls.
        private int closeCount;

        /// Creates a tracking source.
        private TrackingInputStream(byte[] content) {
            super(content);
        }

        /// Records source closure.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }
}

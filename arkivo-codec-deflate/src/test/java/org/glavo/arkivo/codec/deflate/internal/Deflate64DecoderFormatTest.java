// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Deflate64-specific fields and malformed-stream handling in the buffer decoder.
@NotNullByDefault
final class Deflate64DecoderFormatTest {
    /// The largest extended match resumes across output calls after fast literal decoding without consuming a trailer.
    @Test
    void extendedMatchesCrossOutputCallsAndPreserveLookahead() throws IOException {
        BitWriter writer = new BitWriter();
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength('a');
        writer.writeFixedLiteralLength('b');
        writer.writeFixedLiteralLength('c');
        writer.writeFixedLiteralLength(285);
        writer.writeBits(0xffff, 16);
        writer.writeFixedDistance(2);
        writer.writeFixedLiteralLength(256);
        byte[] compressed = writer.toByteArray();
        byte[] framed = Arrays.copyOf(compressed, compressed.length + 8);
        Arrays.fill(framed, compressed.length, framed.length, (byte) 0x7f);
        byte[] expected = new byte[65_541];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) ('a' + i % 3);
        for (boolean direct : new boolean[]{false, true}) {
            try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE64, null)) {
                ByteBuffer input = direct ? ByteBuffer.allocateDirect(framed.length) : ByteBuffer.allocate(framed.length);
                input.put(framed).flip();
                input = input.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer output = direct ? ByteBuffer.allocateDirect(8192) : ByteBuffer.allocate(8192);
                output.order(ByteOrder.LITTLE_ENDIAN);
                byte[] scratch = new byte[output.capacity()];
                var decoded = new ByteArrayOutputStream();
                CodecOutcome outcome;
                do {
                    output.clear();
                    outcome = decoder.finish(input, output);
                    int produced = output.position();
                    output.get(0, scratch, 0, produced);
                    decoded.write(scratch, 0, produced);
                    assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
                } while (outcome != CodecOutcome.FINISHED);
                assertArrayEquals(expected, decoded.toByteArray());
                assertEquals(compressed.length, input.position());
                assertEquals(8, input.remaining());
                assertEquals(ByteOrder.LITTLE_ENDIAN, input.order());
                assertEquals(ByteOrder.LITTLE_ENDIAN, output.order());
            }
        }
    }

    /// Verifies the sixteen-bit length extension assigned to fixed-tree symbol 285.
    @Test
    void decodesExtendedLengthSymbol() throws IOException {
        BitWriter writer = new BitWriter();
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength('a');
        writer.writeFixedLiteralLength('b');
        writer.writeFixedLiteralLength('c');
        writer.writeFixedLiteralLength(285);
        writer.writeBits(297, 16);
        writer.writeFixedDistance(2);
        writer.writeFixedLiteralLength(256);

        byte[] expected = new byte[303];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) ('a' + index % 3);
        }

        assertArrayEquals(expected, decode(writer.toByteArray()));
    }

    /// Verifies distance symbol 31 and history retained across stored and fixed blocks.
    @Test
    void decodesMaximumDistance() throws IOException {
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
        assertArrayEquals(expected, decode(writer.toByteArray()));
    }

    /// Maximum-length matches wrap the history window at both ordinary and extended distance boundaries.
    ///
    /// @param distance the backward match distance
    /// @param symbol the fixed distance code
    /// @param extraValue the distance extension value
    /// @param extraBits the width of the distance extension
    @ParameterizedTest
    @CsvSource({"1, 0, 0, 0", "32768, 29, 8191, 13", "32769, 30, 0, 14",
            "65535, 31, 16382, 14", "65536, 31, 16383, 14"})
    void maximumLengthMatchesCrossDistanceBoundaries(
            int distance, int symbol, int extraValue, int extraBits
    ) throws IOException {
        byte[] expected = new byte[distance + 65_538];
        for (int index = 0; index < distance; index++) {
            expected[index] = (byte) (index * 31 + index / 251);
        }
        for (int index = distance; index < expected.length; index++) {
            expected[index] = expected[index - distance];
        }
        BitWriter writer = new BitWriter();
        for (int offset = 0; offset < distance; offset += 0xffff) {
            writer.writeStoredBlock(false, Arrays.copyOfRange(expected, offset, Math.min(distance, offset + 0xffff)));
        }
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength(285);
        writer.writeBits(0xffff, 16);
        writer.writeFixedDistance(symbol);
        writer.writeBits(extraValue, extraBits);
        writer.writeFixedLiteralLength(256);
        byte[] compressed = writer.toByteArray();
        try (var reference = new Deflate64CompressorInputStream(new ByteArrayInputStream(compressed))) {
            assertArrayEquals(expected, reference.readAllBytes());
        }

        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE64, null)) {
            for (boolean direct : new boolean[]{false, true}) {
                decoder.reset();
                assertFragmentedMatch(decoder, compressed, expected, direct);
                decoder.reset();
                ByteBuffer emptyFrame = ByteBuffer.wrap(new byte[]{3, 0});
                ByteBuffer output = ByteBuffer.allocate(1);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(emptyFrame, output));
                assertEquals(0, output.position());
                assertEquals(2, emptyFrame.position());
            }
        }
    }

    /// Reset clears both an incomplete extended length and a match suspended by a full output buffer.
    ///
    /// @param truncated whether to fail inside the length extension instead of suspending its output
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetsIncompleteExtendedMatch(boolean truncated) throws IOException {
        BitWriter writer = new BitWriter();
        writer.writeFixedBlockHeader();
        writer.writeFixedLiteralLength('x');
        writer.writeFixedLiteralLength(285);
        writer.writeBits(0xffff, 16);
        writer.writeFixedDistance(0);
        writer.writeFixedLiteralLength(256);
        byte[] compressed = writer.toByteArray();
        byte[] expected = new byte[65_539];
        Arrays.fill(expected, (byte) 'x');
        try (var decoder = new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE64, null)) {
            ByteBuffer output = ByteBuffer.allocate(2);
            if (truncated) {
                // Three header bits and two eight-bit codes leave only five length-extension bits.
                ByteBuffer input = ByteBuffer.wrap(compressed, 0, 3);
                assertThrows(IOException.class, () -> decoder.finish(input, output));
                assertEquals(1, output.position());
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(ByteBuffer.wrap(compressed), output));
                assertArrayEquals(new byte[]{'x', 'x'}, output.array());
            }
            decoder.reset();
            ByteBuffer emptyFrame = ByteBuffer.wrap(new byte[]{3, 0});
            output.clear();
            assertEquals(CodecOutcome.FINISHED, decoder.finish(emptyFrame, output));
            assertEquals(0, output.position());
            decoder.reset();
            assertFragmentedMatch(decoder, compressed, expected, true);
        }
    }

    /// Checks a long match through fresh input views, empty targets, and an untouched frame suffix.
    private static void assertFragmentedMatch(
            DeflateDecoderEngine decoder, byte[] compressed, byte[] expected, boolean direct
    ) throws IOException {
        byte[] framed = Arrays.copyOf(compressed, compressed.length + 8);
        Arrays.fill(framed, compressed.length, framed.length, (byte) 0xa5);
        int chunkSize = direct ? 7 : framed.length;
        int outputSize = direct ? 257 : 65_536;
        ByteBuffer inputStorage = direct ? ByteBuffer.allocateDirect(chunkSize + 3)
                : ByteBuffer.allocate(chunkSize + 3);
        ByteBuffer output = direct ? ByteBuffer.allocateDirect(outputSize + 5)
                : ByteBuffer.allocate(outputSize + 5);
        byte[] scratch = new byte[outputSize];
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        for (int call = 0; call < 2 * (framed.length + expected.length); call++) {
            int offered = Math.min(chunkSize, framed.length - offset);
            inputStorage.clear().position(3);
            inputStorage.put(framed, offset, offered).flip().position(3);
            ByteBuffer input = inputStorage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            input.mark();
            output.clear().position(5).limit(call % 17 == 0 ? 5 : output.capacity()).mark();
            int outputLimit = output.limit();
            CodecOutcome outcome = decoder.decode(input, output);
            offset += input.position() - 3;
            int produced = output.position() - 5;
            output.get(5, scratch, 0, produced);
            decoded.write(scratch, 0, produced);
            assertTrue(decoded.size() <= expected.length);
            assertEquals(offered + 3, input.limit());
            assertEquals(outputLimit, output.limit());
            input.reset();
            output.reset();
            if (outcome == CodecOutcome.FINISHED) {
                assertEquals(compressed.length, offset);
                assertArrayEquals(expected, decoded.toByteArray());
                return;
            }
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertTrue(offset < compressed.length);
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            }
        }
        throw new AssertionError("Deflate64 decoding did not finish");
    }

    /// Verifies the common dynamic Huffman grammar against a raw JDK Deflater stream.
    @Test
    void decodesStandardDynamicHuffmanGrammar() throws IOException {
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

        assertArrayEquals(expected, decode(compressed.toByteArray()));
    }

    /// Verifies reserved blocks, invalid stored lengths, unavailable distances, and invalid symbols.
    @Test
    void rejectsMalformedStreams() {
        BitWriter reserved = new BitWriter();
        reserved.writeBits(1, 1);
        reserved.writeBits(3, 2);
        assertThrows(IOException.class, () -> decode(reserved.toByteArray()));

        byte[] invalidStoredLength = {1, 1, 0, 0, 0};
        assertThrows(IOException.class, () -> decode(invalidStoredLength));

        BitWriter distance = new BitWriter();
        distance.writeFixedBlockHeader();
        distance.writeFixedLiteralLength(257);
        distance.writeFixedDistance(0);
        distance.writeFixedLiteralLength(256);
        IOException distanceException = assertThrows(IOException.class, () -> decode(distance.toByteArray()));
        assertTrue(distanceException.getMessage().contains("exceeds available history"));

        BitWriter symbol = new BitWriter();
        symbol.writeFixedBlockHeader();
        symbol.writeFixedLiteralLength(286);
        IOException symbolException = assertThrows(IOException.class, () -> decode(symbol.toByteArray()));
        assertTrue(symbolException.getMessage().contains("literal/length symbol"));
    }

    /// Verifies empty input and a fixed block missing its end-of-block symbol.
    @Test
    void rejectsTruncatedStreams() {
        assertThrows(IOException.class, () -> decode(new byte[0]));

        BitWriter fixed = new BitWriter();
        fixed.writeFixedBlockHeader();
        fixed.writeFixedLiteralLength('x');
        assertThrows(IOException.class, () -> decode(fixed.toByteArray()));
    }

    /// Decodes a complete stream from fresh one-byte source buffers.
    private static byte[] decode(byte[] compressed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int offset = 0;
        try (DeflateDecoderEngine decoder =
                     new DeflateDecoderEngine(DeflateDecoderEngine.Format.DEFLATE64, null)) {
            while (true) {
                int offered = Math.min(1, compressed.length - offset);
                ByteBuffer source = ByteBuffer.wrap(compressed, offset, offered).slice();
                ByteBuffer target = ByteBuffer.allocateDirect(8192);
                CodecOutcome outcome = offset + offered == compressed.length
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                offset += source.position();
                target.flip();
                while (target.hasRemaining()) {
                    output.write(target.get());
                }
                if (outcome == CodecOutcome.FINISHED) {
                    return output.toByteArray();
                }
                if (outcome != CodecOutcome.NEEDS_INPUT && outcome != CodecOutcome.NEEDS_OUTPUT) {
                    throw new IOException("Unexpected Deflate64 decoder outcome: " + outcome);
                }
            }
        }
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate64.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the transport-independent Deflate64 buffer engines and their caller-buffer ownership contract.
@NotNullByDefault
final class Deflate64BufferEngineTest {
    /// Verifies a multi-block stream while replacing both direct buffers after every operation.
    @Test
    void roundTripsAcrossFreshDirectBuffers() throws IOException {
        byte[] input = new byte[180_000];
        new Random(0xdef1_ace6_4L).nextBytes(input);
        byte[] phrase = " fresh Deflate64 buffer engine ".getBytes(StandardCharsets.UTF_8);
        for (int offset = 4096; offset + phrase.length <= input.length; offset += 97) {
            System.arraycopy(phrase, 0, input, offset, phrase.length);
        }

        byte[] compressed = encode(input, 37, 1);
        DecodeResult decoded = decode(compressed, 1, 1, true);

        assertArrayEquals(input, decoded.content());
        assertEquals(compressed.length, decoded.consumedInput());
    }

    /// Verifies a dynamic Huffman header whose transactional parse spans one-byte source buffers.
    @Test
    void decodesDynamicHeaderAcrossOneByteSources() throws IOException {
        byte[] compressed = dynamicLiteralAWriter().toByteArray();

        DecodeResult decoded = decode(compressed, 1, 1, true);

        assertArrayEquals(new byte[]{'A', 'A', 'A', 'A'}, decoded.content());
        assertEquals(compressed.length, decoded.consumedInput());
    }

    /// Verifies that reaching the final block leaves bytes belonging to a following container field untouched.
    @Test
    void preservesTrailingInput() throws IOException {
        byte[] input = "payload before a container trailer".repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encode(input, 11, 3);
        byte[] trailer = {0x12, 0x34, 0x56, 0x78};
        byte[] combined = Arrays.copyOf(compressed, compressed.length + trailer.length);
        System.arraycopy(trailer, 0, combined, compressed.length, trailer.length);

        DecodeResult decoded = decode(combined, combined.length, 2, false);

        assertArrayEquals(input, decoded.content());
        assertEquals(compressed.length, decoded.consumedInput());
    }

    /// Verifies that flush ends a nonfinal block and finish emits the final stream boundary through tiny targets.
    @Test
    void flushesAndFinishesThroughFreshTargets() throws IOException {
        byte[] first = "first flushed segment ".repeat(200).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second final segment ".repeat(200).getBytes(StandardCharsets.UTF_8);
        byte[] expected = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        try (Deflate64Encoder encoder = new Deflate64Encoder(6)) {
            encodeSegment(encoder, first, 19, 2, compressed);
            int beforeFlush = compressed.size();
            flush(encoder, 1, compressed);
            assertTrue(compressed.size() > beforeFlush);
            DecodeResult flushed = decode(compressed.toByteArray(), 1, 1, false);
            assertArrayEquals(first, flushed.content());
            assertEquals(compressed.size(), flushed.consumedInput());
            encodeSegment(encoder, second, 23, 2, compressed);
            finish(encoder, 1, compressed);
        }

        assertArrayEquals(expected, decode(compressed.toByteArray(), 1, 1, true).content());
    }

    /// Verifies that reset abandons staged compressed bytes and restores deterministic initial state.
    @Test
    void resetDiscardsPendingOutput() throws IOException {
        byte[] abandoned = new byte[1 << 16];
        new Random(1L).nextBytes(abandoned);
        byte[] retained = "stream encoded after reset ".repeat(300).getBytes(StandardCharsets.UTF_8);
        byte[] expected = encode(retained, 29, 4);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();

        try (Deflate64Encoder encoder = new Deflate64Encoder(6)) {
            ByteBuffer source = directBuffer(abandoned, 0, abandoned.length);
            ByteBuffer target = ByteBuffer.allocateDirect(1);
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, target));
            assertEquals(abandoned.length, source.position());

            encoder.reset();
            encodeSegment(encoder, retained, 29, 4, actual);
            finish(encoder, 1, actual);
        }

        assertArrayEquals(expected, actual.toByteArray());
    }

    /// Verifies malformed headers and truncated streams through the buffer decoder's end-of-input contract.
    @Test
    void rejectsMalformedAndTruncatedStreams() {
        BitWriter reserved = new BitWriter();
        reserved.writeBits(1, 1);
        reserved.writeBits(3, 2);
        byte[] reservedBlock = reserved.toByteArray();
        assertThrows(IOException.class, () -> decode(reservedBlock, 1, 1, true));

        BitWriter truncated = new BitWriter();
        truncated.writeFixedBlockHeader();
        truncated.writeFixedLiteral('x');
        byte[] truncatedBlock = truncated.toByteArray();
        assertThrows(IOException.class, () -> decode(truncatedBlock, 1, 1, true));

        assertThrows(IOException.class, () -> decode(new byte[0], 1, 1, true));
    }

    /// Encodes one complete stream using fresh direct source and target buffers for every call.
    private static byte[] encode(byte[] input, int sourceChunkSize, int targetChunkSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Deflate64Encoder encoder = new Deflate64Encoder(6)) {
            encodeSegment(encoder, input, sourceChunkSize, targetChunkSize, output);
            finish(encoder, targetChunkSize, output);
        }
        return output.toByteArray();
    }

    /// Feeds one input segment through newly allocated caller buffers.
    private static void encodeSegment(
            Deflate64Encoder encoder,
            byte[] input,
            int sourceChunkSize,
            int targetChunkSize,
            ByteArrayOutputStream output
    ) throws IOException {
        int offset = 0;
        while (offset < input.length) {
            int offered = Math.min(sourceChunkSize, input.length - offset);
            ByteBuffer source = directBuffer(input, offset, offered);
            ByteBuffer target = ByteBuffer.allocateDirect(targetChunkSize);
            CodecOutcome outcome = encoder.encode(source, target);
            offset += source.position();
            drain(target, output);

            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertEquals(offered, source.position());
            }
        }
    }

    /// Completes a flush using a new target buffer for every operation.
    private static void flush(Deflate64Encoder encoder, int targetChunkSize, ByteArrayOutputStream output) {
        while (true) {
            ByteBuffer target = ByteBuffer.allocateDirect(targetChunkSize);
            CodecOutcome outcome = encoder.flush(target);
            drain(target, output);
            if (outcome == CodecOutcome.FLUSHED) {
                return;
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
        }
    }

    /// Completes stream finalization using a new target buffer for every operation.
    private static void finish(Deflate64Encoder encoder, int targetChunkSize, ByteArrayOutputStream output) {
        while (true) {
            ByteBuffer target = ByteBuffer.allocateDirect(targetChunkSize);
            CodecOutcome outcome = encoder.finish(target);
            drain(target, output);
            if (outcome == CodecOutcome.FINISHED) {
                return;
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
        }
    }

    /// Decodes one stream while replacing both direct buffers after every operation.
    private static DecodeResult decode(
            byte[] input,
            int sourceChunkSize,
            int targetChunkSize,
            boolean endOfInput
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int offset = 0;
        try (Deflate64Decoder decoder = new Deflate64Decoder()) {
            while (true) {
                int offered = Math.min(sourceChunkSize, input.length - offset);
                ByteBuffer source = directBuffer(input, offset, offered);
                ByteBuffer target = ByteBuffer.allocateDirect(targetChunkSize);
                boolean finalSource = endOfInput && offset + offered == input.length;
                CodecOutcome outcome = decoder.decode(source, target, finalSource);
                offset += source.position();
                drain(target, output);

                if (outcome == CodecOutcome.FINISHED) {
                    return new DecodeResult(output.toByteArray(), offset);
                }
                if (outcome == CodecOutcome.NEEDS_INPUT) {
                    assertEquals(offered, source.position());
                    if (!endOfInput && offset == input.length) {
                        return new DecodeResult(output.toByteArray(), offset);
                    }
                } else {
                    assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                }
            }
        }
    }

    /// Creates a newly allocated direct source containing one input slice.
    private static ByteBuffer directBuffer(byte[] input, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(length);
        buffer.put(input, offset, length);
        return buffer.flip();
    }

    /// Copies produced direct-buffer bytes into the accumulated test output.
    private static void drain(ByteBuffer source, ByteArrayOutputStream output) {
        source.flip();
        while (source.hasRemaining()) {
            output.write(source.get());
        }
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

    /// Contains decoded bytes and the number of compressed source bytes consumed.
    ///
    /// @param content decoded bytes
    /// @param consumedInput consumed compressed bytes
    @NotNullByDefault
    private record DecodeResult(byte[] content, int consumedInput) {
    }

    /// Writes the Deflate64 fields needed by buffer-engine fixtures.
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

        /// Pads the current byte with zero bits.
        private void alignToByte() {
            if (bitCount != 0) {
                flushByte();
            }
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

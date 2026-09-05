// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the transport-independent LZMA-alone header and raw payload composition.
@NotNullByDefault
public final class LZMAStandaloneBufferEngineTest {
    /// Shared LZMA-alone codec under test.
    private static final LZMACodec CODEC = new LZMACodec();

    /// Verifies fresh one-byte input buffers, tiny direct targets, and exact EOS positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] content = testData();
        byte[] encoded = encode(content, CompressionCodec.UNKNOWN_SIZE, 3, 1);
        byte[] tail = {41, 43, 47};
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + tail.length);
        System.arraycopy(tail, 0, withTail, encoded.length, tail.length);

        DecodeResult result = decode(withTail, 1, 2, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
        assertArrayEquals(tail, Arrays.copyOfRange(withTail, result.consumedInput(), withTail.length));
    }

    /// Verifies a pledged-size header drives an exact-size payload without an EOS marker.
    @Test
    public void pledgedSizeCreatesExactBoundary() throws IOException {
        byte[] content = Arrays.copyOf(testData(), 91_003);
        byte[] encoded = encode(content, content.length, 17, 3);
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + 2);
        withTail[encoded.length] = 0x55;
        withTail[encoded.length + 1] = 0x66;

        DecodeResult result = decode(withTail, 5, 7, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
        assertEquals(content.length, readLittleEndianLong(encoded, 5));
    }

    /// Verifies incremental LZMA-alone header validation.
    @Test
    public void rejectsInvalidHeader() throws IOException {
        byte[] invalid = new byte[13];
        invalid[0] = (byte) 0xff;
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            for (int offset = 0; offset < invalid.length - 1; offset++) {
                assertEquals(
                        CodecOutcome.NEEDS_INPUT,
                        decoder.decode(
                                ByteBuffer.wrap(invalid, offset, 1).slice(),
                                ByteBuffer.allocateDirect(1)
                        )
                );
            }
            assertThrows(
                    IOException.class,
                    () -> decoder.finish(
                            ByteBuffer.wrap(invalid, invalid.length - 1, 1).slice(),
                            ByteBuffer.allocateDirect(1)
                    )
            );
        }
    }

    /// Verifies every incomplete standalone header and an unsupported dictionary size fail with stable diagnostics.
    @Test
    public void rejectsTruncatedAndOversizedHeaders() throws IOException {
        byte[] encoded = encode(new byte[0], CompressionCodec.UNKNOWN_SIZE, 1, 1);
        for (int length = 0; length < 13; length++) {
            byte[] prefix = Arrays.copyOf(encoded, length);
            try (CompressionDecoder decoder = CODEC.newDecoder()) {
                IOException failure = assertThrows(
                        IOException.class,
                        () -> decoder.finish(ByteBuffer.wrap(prefix), ByteBuffer.allocate(1)),
                        () -> "header prefix length " + prefix.length
                );
                assertEquals("Truncated LZMA-alone header", failure.getMessage());
            }
        }

        byte[] oversizedDictionary = Arrays.copyOf(encoded, 13);
        ByteBuffer.wrap(oversizedDictionary)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1, LZMAProperties.MAXIMUM_DICTIONARY_SIZE + 1);
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            IOException failure = assertThrows(
                    IOException.class,
                    () -> decoder.finish(ByteBuffer.wrap(oversizedDictionary), ByteBuffer.allocate(1))
            );
            assertEquals(
                    "Unsupported LZMA dictionary size: " + (LZMAProperties.MAXIMUM_DICTIONARY_SIZE + 1L),
                    failure.getMessage()
            );
        }
    }

    /// Verifies decoder reset, zero-capacity backpressure, stable completion, null checks, and permanent closure.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void decoderLifecycleAndReset() throws IOException {
        byte[] content = Arrays.copyOf(testData(), 8_193);
        byte[] encoded = encode(content, CompressionCodec.UNKNOWN_SIZE, 11, 3);
        CompressionDecoder decoder = CODEC.newDecoder();

        ByteBuffer blockedSource = ByteBuffer.wrap(encoded);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(blockedSource, ByteBuffer.allocate(0)));
        assertEquals(0, blockedSource.position());
        assertThrows(NullPointerException.class, () -> decoder.decode(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.decode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.finish(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.finish(ByteBuffer.allocate(0), null));

        ByteBuffer partialHeader = ByteBuffer.wrap(encoded, 0, 7).slice();
        assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(partialHeader, ByteBuffer.allocate(1)));
        assertFalse(partialHeader.hasRemaining());
        decoder.reset();

        assertArrayEquals(content, decode(decoder, encoded, 5));
        assertEquals(CodecOutcome.FINISHED, decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
        decoder.reset();
        assertArrayEquals(content, decode(decoder, encoded, 7));

        decoder.close();
        decoder.close();
        IllegalStateException resetFailure = assertThrows(IllegalStateException.class, decoder::reset);
        assertEquals("LZMA decoder is closed", resetFailure.getMessage());
        IllegalStateException decodeFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertEquals("LZMA decoder is closed", decodeFailure.getMessage());
        IllegalStateException finishFailure = assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertEquals("LZMA decoder is closed", finishFailure.getMessage());
    }

    /// Verifies bytes produced before a truncated EOS marker remain reflected in the caller's target position.
    @Test
    public void truncatedPayloadPreservesDecodedProgress() throws IOException {
        byte[] content = Arrays.copyOf(testData(), 8_193);
        byte[] encoded = encode(content, CompressionCodec.UNKNOWN_SIZE, 13, 5);
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        ByteBuffer source = ByteBuffer.wrap(truncated);
        ByteBuffer target = ByteBuffer.allocate(content.length + 1);

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            assertThrows(IOException.class, () -> decoder.finish(source, target));
        }

        assertFalse(source.hasRemaining());
        assertEquals(content.length, target.position());
        target.flip();
        byte[] actualPrefix = new byte[target.remaining()];
        target.get(actualPrefix);
        assertArrayEquals(Arrays.copyOf(content, actualPrefix.length), actualPrefix);
    }

    /// Encodes one LZMA-alone stream through fresh bounded buffers.
    private static byte[] encode(
            byte[] content,
            long pledgedSourceSize,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = CODEC.newEncoder(
                EncodingOptions.ofSourceSize(pledgedSourceSize)
        )) {
            int offset = 0;
            while (offset < content.length) {
                int length = Math.min(sourceFragmentSize, content.length - offset);
                ByteBuffer source = ByteBuffer.wrap(content, offset, length).slice();
                CodecOutcome outcome;
                do {
                    ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                    outcome = encoder.encode(source, target);
                    drain(target, encoded);
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
                offset += length;
            }
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                outcome = encoder.finish(target);
                drain(target, encoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
        }
        return encoded.toByteArray();
    }

    /// Decodes with a fresh source and target buffer for every operation.
    private static DecodeResult decode(
            byte[] encoded,
            int sourceFragmentSize,
            int targetSize,
            boolean endAtArrayBoundary
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(sourceFragmentSize, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                boolean endOfInput = endAtArrayBoundary && offset + length == encoded.length;
                outcome = endOfInput
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                offset += source.position();
                drain(target, decoded);
            }
        }
        return new DecodeResult(decoded.toByteArray(), offset);
    }

    /// Decodes one complete stream through an existing decoder and bounded direct targets.
    private static byte[] decode(CompressionDecoder decoder, byte[] encoded, int targetSize) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = decoder.finish(source, target);
            drain(target, decoded);
            assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertFalse(source.hasRemaining());
        return decoded.toByteArray();
    }

    /// Copies produced bytes into the supplied byte stream.
    private static void drain(ByteBuffer buffer, ByteArrayOutputStream output) {
        buffer.flip();
        byte @Unmodifiable [] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
    }

    /// Reads one little-endian 64-bit value from the LZMA-alone header.
    private static long readLittleEndianLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(bytes[offset + index]) << (index * 8);
        }
        return value;
    }

    /// Creates deterministic data spanning multiple match-finder processing blocks.
    private static byte @Unmodifiable [] testData() {
        byte[] data = new byte[170_333];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 29) ^ (index >>> 5) ^ (index % 239));
        }
        System.arraycopy(data, 2_048, data, data.length - 60_000, 60_000);
        return data;
    }

    /// Holds decoded bytes and the exact compressed boundary.
    ///
    /// @param content       decoded bytes
    /// @param consumedInput compressed bytes consumed through the stream boundary
    @NotNullByDefault
    private record DecodeResult(byte @Unmodifiable [] content, int consumedInput) {
    }
}

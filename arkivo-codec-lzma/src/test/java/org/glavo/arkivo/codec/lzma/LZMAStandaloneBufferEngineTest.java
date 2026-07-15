// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        byte[] encoded = encode(content, CodecOptions.EMPTY, 3, 1);
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
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) content.length)
                .build();
        byte[] encoded = encode(content, options, 17, 3);
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + 2);
        withTail[encoded.length] = 0x55;
        withTail[encoded.length + 1] = 0x66;

        DecodeResult result = decode(withTail, 5, 7, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
        assertEquals(content.length, readLittleEndianLong(encoded, 5));
    }

    /// Verifies incremental header validation and advertised native buffer capabilities.
    @Test
    public void rejectsInvalidHeaderAndAdvertisesCapabilities() throws IOException {
        byte[] invalid = new byte[13];
        invalid[0] = (byte) 0xff;
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            for (int offset = 0; offset < invalid.length - 1; offset++) {
                assertEquals(
                        CodecOutcome.NEEDS_INPUT,
                        decoder.decode(
                                ByteBuffer.wrap(invalid, offset, 1).slice(),
                                ByteBuffer.allocateDirect(1),
                                false
                        )
                );
            }
            assertThrows(
                    IOException.class,
                    () -> decoder.decode(
                            ByteBuffer.wrap(invalid, invalid.length - 1, 1).slice(),
                            ByteBuffer.allocateDirect(1),
                            true
                    )
            );
        }
        assertTrue(CODEC.capabilities().supports(CompressionFeature.BUFFER_COMPRESSION));
        assertTrue(CODEC.capabilities().supports(CompressionFeature.BUFFER_DECOMPRESSION));
    }

    /// Encodes one LZMA-alone stream through fresh bounded buffers.
    private static byte[] encode(
            byte[] content,
            CodecOptions options,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = CODEC.newEncoder(options)) {
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
                outcome = decoder.decode(source, target, endOfInput);
                offset += source.position();
                drain(target, decoded);
            }
        }
        return new DecodeResult(decoded.toByteArray(), offset);
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

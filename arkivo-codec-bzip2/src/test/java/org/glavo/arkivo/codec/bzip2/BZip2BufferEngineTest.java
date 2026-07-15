// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecompressionLimitException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the transport-independent BZip2 encoder and decoder contracts.
@NotNullByDefault
public final class BZip2BufferEngineTest {
    /// Shared BZip2 codec under test.
    private static final BZip2Codec CODEC = new BZip2Codec();

    /// Verifies fresh fragmented caller buffers and exact trailing-input positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] content = patternedData(24_013);
        byte[] encoded = encode(content, CodecOptions.EMPTY, 3, 1);
        byte[] tail = {11, 22, 33, 44};
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.length + tail.length);
        source.put(encoded).put(tail).flip();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(2);
                outcome = decoder.decode(source, target, false);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
        }

        assertArrayEquals(content, decoded.toByteArray());
        assertEquals(encoded.length, source.position());
        byte[] remaining = new byte[source.remaining()];
        source.get(remaining);
        assertArrayEquals(tail, remaining);
    }

    /// Verifies a level-one stream spanning several BZip2 blocks under compressed-input fragmentation.
    @Test
    public void decoderReplaysIncompleteBlocksAcrossFreshInputs() throws IOException {
        byte[] content = patternedData(230_017);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.COMPRESSION_LEVEL, 1L)
                .build();
        byte[] encoded = encode(content, options, 257, 31);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            int offset = 0;
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(4096, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                ByteBuffer target = ByteBuffer.allocateDirect(17);
                boolean endOfInput = offset + length == encoded.length;
                outcome = decoder.decode(source, target, endOfInput);
                offset += source.position();
                drain(target, decoded);
                assertTrue(
                        outcome == CodecOutcome.NEEDS_INPUT
                                || outcome == CodecOutcome.NEEDS_OUTPUT
                                || outcome == CodecOutcome.FINISHED
                );
            }
            assertEquals(encoded.length, offset);
        }

        assertArrayEquals(content, decoded.toByteArray());
    }

    /// Verifies flush emits a complete frame while preserving a continuing encoding session.
    @Test
    public void flushProducesDecodableFrameBoundary() throws IOException {
        byte[] first = patternedData(17_003);
        byte[] second = patternedData(9_001);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder encoder = CODEC.newEncoder()) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 5);
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                outcome = encoder.flush(target);
                drain(target, encoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FLUSHED, outcome);

            byte[] firstFrame = encoded.toByteArray();
            ByteBuffer firstSource = ByteBuffer.wrap(firstFrame);
            ByteBuffer firstTarget = ByteBuffer.allocate(first.length + 1);
            try (CompressionDecoder decoder = CODEC.newDecoder()) {
                assertEquals(CodecOutcome.FINISHED, decoder.decode(firstSource, firstTarget, true));
            }
            firstTarget.flip();
            byte[] restoredFirst = new byte[firstTarget.remaining()];
            firstTarget.get(restoredFirst);
            assertArrayEquals(first, restoredFirst);

            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 5);
            finish(encoder, encoded, 1);
        }

        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        ByteBuffer restored = CODEC.decompress(ByteBuffer.wrap(encoded.toByteArray()), expected.length);
        byte[] actual = new byte[restored.remaining()];
        restored.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Verifies reset, output limiting, closure, and advertised buffer capabilities.
    @Test
    public void lifecycleLimitsAndCapabilities() throws IOException {
        byte[] content = patternedData(12_345);
        CompressionEncoder encoder = CODEC.newEncoder();
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(content), first, 7);
        finish(encoder, first, 2);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(1)));

        encoder.reset();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(content), second, 7);
        finish(encoder, second, 2);
        assertArrayEquals(first.toByteArray(), second.toByteArray());
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);

        CodecOptions limited = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, (long) content.length - 1L)
                .build();
        assertThrows(
                DecompressionLimitException.class,
                () -> CODEC.decompress(ByteBuffer.wrap(first.toByteArray()), content.length, limited)
        );
        assertTrue(CODEC.capabilities().supports(CompressionFeature.BUFFER_COMPRESSION));
        assertTrue(CODEC.capabilities().supports(CompressionFeature.BUFFER_DECOMPRESSION));
    }

    /// Encodes source fragments into one BZip2 frame.
    private static byte[] encode(
            byte[] content,
            CodecOptions options,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = CODEC.newEncoder(options)) {
            for (int offset = 0; offset < content.length; offset += sourceFragmentSize) {
                int length = Math.min(sourceFragmentSize, content.length - offset);
                encodeSource(
                        encoder,
                        ByteBuffer.wrap(content, offset, length).slice(),
                        encoded,
                        targetSize
                );
            }
            finish(encoder, encoded, targetSize);
        }
        return encoded.toByteArray();
    }

    /// Drives one source buffer until the encoder requests more input.
    private static void encodeSource(
            CompressionEncoder encoder,
            ByteBuffer source,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.encode(source, target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
        assertFalse(source.hasRemaining());
    }

    /// Drains encoder finalization with bounded target buffers.
    private static void finish(
            CompressionEncoder encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.finish(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
    }

    /// Copies produced buffer bytes into the supplied byte stream.
    private static void drain(ByteBuffer buffer, ByteArrayOutputStream output) {
        buffer.flip();
        byte @Unmodifiable [] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic weakly compressible bytes.
    private static byte @Unmodifiable [] patternedData(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 31) ^ (index >>> 3) ^ (index % 251));
        }
        return data;
    }
}

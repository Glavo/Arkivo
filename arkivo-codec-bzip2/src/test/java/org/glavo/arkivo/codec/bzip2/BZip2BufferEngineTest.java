// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.CompressionEncoder;
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
        byte[] encoded = encode(content, CODEC, 3, 1);
        byte[] tail = {11, 22, 33, 44};
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.length + tail.length);
        source.put(encoded).put(tail).flip();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(2);
                outcome = decoder.decode(source, target);
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
        BZip2Codec codec = CODEC.withCompressionLevel(1L);
        byte[] encoded = encode(content, codec, 257, 31);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            int offset = 0;
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(4096, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                ByteBuffer target = ByteBuffer.allocateDirect(17);
                boolean endOfInput = offset + length == encoded.length;
                outcome = endOfInput
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
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

    /// Verifies frame completion emits a decodable boundary while preserving the encoding session.
    @Test
    public void finishFrameProducesDecodableBoundary() throws IOException {
        byte[] first = patternedData(17_003);
        byte[] second = patternedData(9_001);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Framed encoder = CODEC.newEncoder()) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 5);
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                outcome = encoder.finishFrame(target);
                drain(target, encoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.BOUNDARY_REACHED, outcome);

            byte[] firstFrame = encoded.toByteArray();
            ByteBuffer firstSource = ByteBuffer.wrap(firstFrame);
            ByteBuffer firstTarget = ByteBuffer.allocate(first.length + 1);
            try (CompressionDecoder decoder = CODEC.newDecoder()) {
                assertEquals(CodecOutcome.FINISHED, decoder.finish(firstSource, firstTarget));
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

    /// Verifies reset, output limiting, and closure.
    @Test
    public void lifecycleAndLimits() throws IOException {
        byte[] content = patternedData(12_345);
        CompressionEncoder.Framed encoder = CODEC.newEncoder();
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

        DecompressionLimits limits =
                DecompressionLimits.ofMaximumOutputSize(content.length - 1L);
        assertThrows(
                DecompressionLimitException.class,
                () -> CODEC.decompress(ByteBuffer.wrap(first.toByteArray()), limits)
        );
    }

    /// Encodes source fragments into one BZip2 frame.
    private static byte[] encode(
            byte[] content,
            BZip2Codec codec,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder.Framed encoder = codec.newEncoder()) {
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
            CompressionEncoder.Framed encoder,
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
            CompressionEncoder.Framed encoder,
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the transport-independent raw Deflate encoder and decoder contracts.
@NotNullByDefault
public final class DeflateBufferEngineTest {
    /// Shared raw Deflate codec under test.
    private static final DeflateCodec CODEC = new DeflateCodec();

    /// Verifies fragmented caller-owned buffers and exact stream-boundary source positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, 7, 1, false);
        byte[] tail = {11, 22, 33, 44};
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.length + tail.length);
        source.put(encoded).put(tail).flip();

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                outcome = decoder.decode(source, target);
                drain(target, decoded);
                assertTrue(outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED);
            } while (outcome != CodecOutcome.FINISHED);
        }

        assertArrayEquals(input, decoded.toByteArray());
        assertEquals(encoded.length, source.position());
        byte[] remaining = new byte[source.remaining()];
        source.get(remaining);
        assertArrayEquals(tail, remaining);
    }

    /// Verifies that each operation releases its caller-owned source and target buffers.
    @Test
    public void acceptsFreshBuffersForEveryOperation() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, 1, 2, false);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            int offset = 0;
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(3, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                boolean endOfInput = offset + length == encoded.length;
                do {
                    ByteBuffer target = ByteBuffer.allocate(2);
                    outcome = endOfInput
                            ? decoder.finish(source, target)
                            : decoder.decode(source, target);
                    drain(target, decoded);
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                offset += source.position();
                assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.FINISHED);
            }
            assertEquals(encoded.length, offset);
        }

        assertArrayEquals(input, decoded.toByteArray());
    }

    /// Verifies flush completion with tiny targets and JDK partial-stream interoperability.
    @Test
    public void flushProducesDecodableBoundary() throws IOException, DataFormatException {
        byte[] first = "first flushed payload".repeat(32).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second finished payload".repeat(32).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Flushable encoder = CODEC.newEncoder()) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 3);
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(1);
                outcome = encoder.flush(target);
                drain(target, encoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FLUSHED, outcome);

            Inflater inflater = new Inflater(true);
            try {
                inflater.setInput(encoded.toByteArray());
                byte[] partial = new byte[first.length];
                int produced = inflater.inflate(partial);
                assertEquals(first.length, produced);
                assertArrayEquals(first, partial);
            } finally {
                inflater.end();
            }

            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 3);
            finish(encoder, encoded, 1);
        }

        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertArrayEquals(expected, decode(encoded.toByteArray(), 2, DecompressionLimits.UNLIMITED));
    }

    /// Verifies finalization drains a completed block before reusing engine-owned output storage.
    @Test
    public void finishDrainsPendingBlockBeforeReusingOutput() throws IOException {
        byte[] input = new byte[64 * 1024];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) ((index * 37) ^ (index >>> 7));
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Flushable encoder = CODEC.newEncoder()) {
            ByteBuffer source = ByteBuffer.wrap(input);
            ByteBuffer firstTarget = ByteBuffer.allocate(1);
            CodecOutcome outcome = encoder.encode(source, firstTarget);
            drain(firstTarget, encoded);
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            assertEquals(false, source.hasRemaining());

            do {
                ByteBuffer target = ByteBuffer.allocate(7);
                outcome = encoder.finish(target);
                drain(target, encoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
        }

        assertArrayEquals(input, decode(encoded.toByteArray(), 13, DecompressionLimits.UNLIMITED));
    }

    /// Verifies reset, idempotent completed-stream observation, and close-abort behavior.
    @Test
    public void lifecycleStateIsExplicit() throws IOException {
        byte[] input = testData();
        CompressionEncoder.Flushable encoder = CODEC.newEncoder();
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), first, 4);
        finish(encoder, first, 1);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(1)));

        encoder.reset();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), second, 4);
        finish(encoder, second, 1);
        assertArrayEquals(first.toByteArray(), second.toByteArray());
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> encoder.encode(ByteBuffer.wrap(input), ByteBuffer.allocate(8))
        );
    }

    /// Verifies that the output limiter accepts the exact bound and rejects one additional byte.
    @Test
    public void maximumOutputSizeAppliesToBufferDecoder() throws IOException {
        byte[] input = testData();
        byte[] encoded = encodeInFragments(input, 13, 5, false);
        DecompressionLimits exactLimits =
                DecompressionLimits.ofMaximumOutputSize(input.length);
        DecompressionLimits shortLimits =
                DecompressionLimits.ofMaximumOutputSize(input.length - 1L);

        assertArrayEquals(input, decode(encoded, 1, exactLimits));
        assertThrows(DecompressionLimitException.class, () -> decode(encoded, 1, shortLimits));
    }

    /// Encodes source fragments into one raw Deflate stream.
    private static byte[] encodeInFragments(
            byte[] input,
            int sourceFragmentSize,
            int targetSize,
            boolean flushEachFragment
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder.Flushable encoder = CODEC.newEncoder()) {
            for (int offset = 0; offset < input.length; offset += sourceFragmentSize) {
                int end = Math.min(input.length, offset + sourceFragmentSize);
                encodeSource(encoder, ByteBuffer.wrap(Arrays.copyOfRange(input, offset, end)), encoded, targetSize);
                if (flushEachFragment) {
                    CodecOutcome outcome;
                    do {
                        ByteBuffer target = ByteBuffer.allocate(targetSize);
                        outcome = encoder.flush(target);
                        drain(target, encoded);
                    } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                }
            }
            finish(encoder, encoded, targetSize);
        }
        return encoded.toByteArray();
    }

    /// Drives one source buffer until the encoder requests more input.
    private static void encodeSource(
            CompressionEncoder.Flushable encoder,
            ByteBuffer source,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.encode(source, target);
            drain(target, encoded);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
        assertEquals(false, source.hasRemaining());
    }

    /// Drains encoder finalization with bounded target buffers.
    private static void finish(
            CompressionEncoder.Flushable encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.finish(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
    }

    /// Decodes one complete stream with bounded target buffers.
    private static byte[] decode(
            byte[] encoded,
            int targetSize,
            DecompressionLimits limits
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ByteBuffer source = ByteBuffer.wrap(encoded);
        try (CompressionDecoder decoder = CODEC.newDecoder(limits)) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(targetSize);
                outcome = decoder.finish(source, target);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
            assertEquals(false, source.hasRemaining());
        }
        return decoded.toByteArray();
    }

    /// Copies produced buffer bytes into the supplied byte stream.
    private static void drain(ByteBuffer buffer, ByteArrayOutputStream output) {
        buffer.flip();
        byte @Unmodifiable [] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic, weakly compressible test content.
    private static byte @Unmodifiable [] testData() {
        byte[] data = new byte[32 * 1024 + 37];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 31) ^ (index >>> 3));
        }
        return data;
    }
}

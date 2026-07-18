// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the transport-independent XZ encoder and decoder contracts.
@NotNullByDefault
public final class XZBufferEngineTest {
    /// Shared XZ codec under test.
    private static final XZCodec CODEC = new XZCodec();

    /// Shared codec exercising multi-Block layout and a stateful filter chain.
    private static final XZCodec FILTERED_CODEC = XZCodec.builder()
            .blockSize(4096L)
            .checkType(XZCheckType.SHA256)
            .filterChain(new XZFilterChain(List.of(
                    new XZDeltaFilter(7L),
                    new XZBCJFilter(XZBCJFilter.Architecture.X86, 32L)
            )))
            .build();

    /// Verifies one-byte fresh source buffers, tiny direct targets, and exact trailing-input positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] content = patternedData(30_017);
        byte[] encoded = encode(content, FILTERED_CODEC, 3, 1);
        byte[] tail = {11, 22, 33, 44};
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + tail.length);
        System.arraycopy(tail, 0, withTail, encoded.length, tail.length);

        DecodeResult result = decodeFreshBuffers(withTail, CODEC, 1, 2, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
        assertArrayEquals(tail, Arrays.copyOfRange(withTail, result.consumedInput(), withTail.length));
    }

    /// Verifies nonterminal Stream completion, legal padding consumption, reset, and a following Stream boundary.
    @Test
    public void finishFrameAndPaddedConcatenation() throws IOException {
        byte[] first = patternedData(17_003);
        byte[] second = patternedData(9_001);
        ByteArrayOutputStream firstEncoded = new ByteArrayOutputStream();
        ByteArrayOutputStream secondEncoded = new ByteArrayOutputStream();

        try (CompressionEncoder generic = FILTERED_CODEC.newEncoder()) {
            CompressionEncoder.Framed encoder = assertInstanceOf(CompressionEncoder.Framed.class, generic);
            encodeSource(encoder, ByteBuffer.wrap(first), firstEncoded, 3);
            finishFrame(encoder, firstEncoded, 1);
            encodeSource(encoder, ByteBuffer.wrap(second), secondEncoded, 3);
            finish(encoder, secondEncoded, 1);
        }

        byte[] firstStream = firstEncoded.toByteArray();
        byte[] secondStream = secondEncoded.toByteArray();
        byte[] combined = new byte[firstStream.length + 4 + secondStream.length];
        System.arraycopy(firstStream, 0, combined, 0, firstStream.length);
        System.arraycopy(secondStream, 0, combined, firstStream.length + 4, secondStream.length);

        ByteBuffer source = ByteBuffer.wrap(combined);
        ByteBuffer firstTarget = ByteBuffer.allocateDirect(first.length + 1);
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            assertEquals(CodecOutcome.FINISHED, decoder.decode(source, firstTarget));
            assertEquals(firstStream.length, source.position());
            decoder.reset();

            ByteBuffer secondTarget = ByteBuffer.allocateDirect(second.length + 1);
            assertEquals(CodecOutcome.FINISHED, decoder.finish(source, secondTarget));
            firstTarget.flip();
            secondTarget.flip();
            assertArrayEquals(first, readBytes(firstTarget));
            assertArrayEquals(second, readBytes(secondTarget));
        }
        assertEquals(combined.length, source.position());

        byte[] unpadded = concatenate(firstStream, secondStream);
        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(unpadded)
        )) {
            assertArrayEquals(concatenate(first, second), input.readAllBytes());
        }
    }

    /// Verifies flush exposes all accepted bytes without ending the current XZ Block or Stream.
    @Test
    public void flushProducesIncrementallyDecodableBoundary() throws IOException {
        byte[] first = patternedData(13_337);
        byte[] second = patternedData(8_123);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (CompressionEncoder.FlushableFramed encoder = FILTERED_CODEC.newEncoder()) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 3);
            CodecOutcome flushOutcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                flushOutcome = encoder.flush(target);
                drain(target, encoded);
            } while (flushOutcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FLUSHED, flushOutcome);

            ByteBuffer partialSource = ByteBuffer.wrap(encoded.toByteArray());
            ByteBuffer partialTarget = ByteBuffer.allocateDirect(first.length + 1);
            try (CompressionDecoder decoder = CODEC.newDecoder()) {
                assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(partialSource, partialTarget));
            }
            partialTarget.flip();
            assertArrayEquals(first, readBytes(partialTarget));

            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 3);
            finish(encoder, encoded, 1);
        }

        ByteBuffer restored = CODEC.decompress(
                ByteBuffer.wrap(encoded.toByteArray()),
                first.length + second.length
        );
        assertArrayEquals(concatenate(first, second), readBytes(restored));
    }

    /// Verifies reset, output limits, closure, independent interoperability, and framed engine behavior.
    @Test
    public void lifecycleLimitsInteroperabilityAndCapabilities() throws IOException {
        byte[] content = patternedData(12_345);
        CompressionEncoder encoder = FILTERED_CODEC.newEncoder();
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

        try (org.tukaani.xz.XZInputStream input = new org.tukaani.xz.XZInputStream(
                new ByteArrayInputStream(first.toByteArray())
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }

        DecodingOptions limited =
                DecodingOptions.ofMaximumOutputSize((long) content.length - 1L);
        assertThrows(
                DecompressionLimitException.class,
                () -> CODEC.decompress(ByteBuffer.wrap(first.toByteArray()), limited)
        );
        assertInstanceOf(CompressionEncoder.FlushableFramed.class, CODEC.newEncoder()).close();
    }

    /// Encodes source fragments into one XZ Stream.
    private static byte[] encode(
            byte[] content,
            XZCodec codec,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = codec.newEncoder()) {
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

    /// Decodes with fresh source and target buffers for every operation.
    private static DecodeResult decodeFreshBuffers(
            byte[] encoded,
            XZCodec codec,
            int sourceFragmentSize,
            int targetSize,
            boolean endAtArrayBoundary
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        try (CompressionDecoder decoder = codec.newDecoder()) {
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
                assertTrue(
                        outcome == CodecOutcome.NEEDS_INPUT
                                || outcome == CodecOutcome.NEEDS_OUTPUT
                                || outcome == CodecOutcome.FINISHED
                );
            }
        }
        return new DecodeResult(decoded.toByteArray(), offset);
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

    /// Drains one nonterminal XZ Stream finalization.
    private static void finishFrame(
            CompressionEncoder.Framed encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            outcome = encoder.finishFrame(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.BOUNDARY_REACHED, outcome);
    }

    /// Drains terminal XZ Stream finalization.
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
        output.writeBytes(readBytes(buffer));
    }

    /// Returns every remaining byte from one buffer.
    private static byte @Unmodifiable [] readBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /// Concatenates two byte arrays.
    private static byte @Unmodifiable [] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Creates deterministic content spanning multiple LZMA2 chunks and XZ Blocks.
    private static byte @Unmodifiable [] patternedData(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 31) ^ (index >>> 4) ^ (index % 251));
        }
        return data;
    }

    /// Holds decoded content and the exact compressed-byte boundary.
    ///
    /// @param content decoded bytes
    /// @param consumedInput number of source bytes consumed through Stream Padding
    @NotNullByDefault
    private record DecodeResult(byte @Unmodifiable [] content, int consumedInput) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.LZMA2Options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the transport-independent raw LZMA2 encoder and decoder contracts.
@NotNullByDefault
public final class LZMA2BufferEngineTest {
    /// Shared externally declared dictionary size.
    private static final int DICTIONARY_SIZE = 1 << 20;

    /// Shared raw LZMA2 codec under test.
    private static final LZMA2Codec CODEC =
            new LZMA2Codec().withDictionarySize(DICTIONARY_SIZE);

    /// Verifies fresh fragmented caller buffers and exact stream-boundary positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] content = testData();
        byte[] encoded = encode(content, 3, 1);
        byte[] tail = {11, 22, 33, 44};
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + tail.length);
        System.arraycopy(tail, 0, withTail, encoded.length, tail.length);

        DecodeResult result = decodeFreshBuffers(withTail, 1, 2, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
        assertArrayEquals(tail, Arrays.copyOfRange(withTail, result.consumedInput(), withTail.length));
    }

    /// Verifies independent XZ for Java streams with cross-chunk state under one-byte input fragmentation.
    @Test
    public void decoderReadsIndependentChunkSequence() throws IOException {
        byte[] content = testData();
        byte[] encoded = encodeIndependently(content);

        DecodeResult result = decodeFreshBuffers(encoded, 1, 3, true);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
    }

    /// Verifies tiny-target encoder output through XZ for Java's independent decoder.
    @Test
    public void encoderWritesIndependentStream() throws IOException {
        byte[] content = testData();
        byte[] encoded = encode(content, 5, 1);

        try (LZMA2InputStream input = new LZMA2InputStream(
                new ByteArrayInputStream(encoded),
                DICTIONARY_SIZE
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies lifecycle reset and output limiting.
    @Test
    public void lifecycleAndLimits() throws IOException {
        byte[] content = Arrays.copyOf(testData(), 12_345);
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

        assertThrows(
                DecompressionLimitException.class,
                () -> CODEC.withMaximumOutputSize(content.length - 1L)
                        .decompress(ByteBuffer.wrap(first.toByteArray()))
        );
    }

    /// Encodes source fragments into one raw LZMA2 stream.
    private static byte[] encode(byte[] content, int sourceFragmentSize, int targetSize) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = CODEC.newEncoder()) {
            int offset = 0;
            while (offset < content.length) {
                int length = Math.min(sourceFragmentSize, content.length - offset);
                ByteBuffer source = ByteBuffer.wrap(content, offset, length).slice();
                ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                CodecOutcome outcome = encoder.encode(source, target);
                offset += source.position();
                drain(target, encoded);
                assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            }
            finish(encoder, encoded, targetSize);
        }
        return encoded.toByteArray();
    }

    /// Encodes an independent raw LZMA2 stream with XZ for Java.
    private static byte @Unmodifiable [] encodeIndependently(byte[] content) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options(6);
        options.setDictSize(DICTIONARY_SIZE);
        try (OutputStream output = options.getOutputStream(
                new FinishableWrapperOutputStream(encoded),
                ArrayCache.getDummyCache()
        )) {
            output.write(content);
        }
        return encoded.toByteArray();
    }

    /// Decodes with a fresh source and target buffer for every operation.
    private static DecodeResult decodeFreshBuffers(
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

    /// Creates deterministic content spanning multiple LZMA2 chunks.
    private static byte @Unmodifiable [] testData() {
        byte[] data = new byte[180_777];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 31) ^ (index >>> 4) ^ (index % 251));
        }
        return data;
    }

    /// Holds decoded content and the exact compressed-byte boundary.
    ///
    /// @param content decoded bytes
    /// @param consumedInput number of source bytes consumed through the stream end marker
    @NotNullByDefault
    private record DecodeResult(byte @Unmodifiable [] content, int consumedInput) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the transport-independent headerless LZMA encoder and decoder contracts.
@NotNullByDefault
public final class RawLZMABufferEngineTest {
    /// Shared dictionary size carried externally by raw streams.
    private static final int DICTIONARY_SIZE = 1 << 16;

    /// Shared raw LZMA codec under test.
    private static final RawLZMACodec CODEC =
            new RawLZMACodec().withDictionarySize(DICTIONARY_SIZE);

    /// Verifies one-byte fresh input fragments, tiny direct targets, and exact EOS positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] content = testData();
        byte[] encoded = encode(content, CODEC, CompressionCodec.UNKNOWN_SIZE, 3, 1);
        byte[] tail = {19, 23, 29, 31};
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + tail.length);
        System.arraycopy(tail, 0, withTail, encoded.length, tail.length);

        DecodeResult result = decodeFreshBuffers(withTail, CODEC, 1, 2, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
        assertArrayEquals(tail, Arrays.copyOfRange(withTail, result.consumedInput(), withTail.length));
    }

    /// Verifies exact-size streams without an EOS marker leave following container bytes untouched.
    @Test
    public void sizedStreamWithoutEndMarkerPreservesBoundary() throws IOException {
        byte[] content = Arrays.copyOf(testData(), 93_117);
        RawLZMACodec encodingCodec = CODEC.withEndMarker(false);
        RawLZMACodec decodingCodec = CODEC.withDecodedSize(content.length);
        byte[] encoded = encode(content, encodingCodec, content.length, 11, 3);
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + 5);
        Arrays.fill(withTail, encoded.length, withTail.length, (byte) 0x5a);

        DecodeResult result = decodeFreshBuffers(withTail, decodingCodec, 7, 5, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
    }

    /// Verifies the incremental decoder reads an independent EOS-terminated raw stream.
    @Test
    public void decoderReadsIndependentStream() throws IOException {
        byte[] content = testData();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        LZMA2Options independentOptions = new LZMA2Options(5);
        independentOptions.setDictSize(DICTIONARY_SIZE);
        try (LZMAOutputStream output = new LZMAOutputStream(encoded, independentOptions, true)) {
            output.write(content);
        }

        DecodeResult result = decodeFreshBuffers(encoded.toByteArray(), CODEC, 1, 7, true);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.size(), result.consumedInput());
    }

    /// Verifies tiny-target encoder output through XZ for Java's independent decoder.
    @Test
    public void encoderWritesIndependentStream() throws IOException {
        byte[] content = testData();
        byte[] encoded = encode(content, CODEC, CompressionCodec.UNKNOWN_SIZE, 5, 1);

        try (LZMAInputStream input = new LZMAInputStream(
                new ByteArrayInputStream(encoded),
                -1L,
                (byte) 0x5d,
                DICTIONARY_SIZE
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies reset, close, and pledged-size enforcement.
    @Test
    public void lifecycleAndPledgedSize() throws IOException {
        byte[] content = Arrays.copyOf(testData(), 12_345);
        CompressionEncoder encoder = CODEC.newEncoder();
        byte[] first = encodeWithEncoder(encoder, content, 13, 2);
        encoder.reset();
        byte[] second = encodeWithEncoder(encoder, content, 13, 2);
        assertArrayEquals(first, second);
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);

        CompressionEncoder incomplete = CODEC.newEncoder(content.length + 1L);
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        encodeSource(incomplete, ByteBuffer.wrap(content), ignored, 7);
        assertThrows(IOException.class, () -> incomplete.finish(ByteBuffer.allocate(32)));
        incomplete.close();

    }

    /// Encodes one raw stream with fresh bounded source and target buffers.
    private static byte[] encode(
            byte[] content,
            RawLZMACodec codec,
            long pledgedSourceSize,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        try (CompressionEncoder encoder = codec.newEncoder(pledgedSourceSize)) {
            return encodeWithEncoder(encoder, content, sourceFragmentSize, targetSize);
        }
    }

    /// Encodes one complete stream through an existing resettable encoder.
    private static byte[] encodeWithEncoder(
            CompressionEncoder encoder,
            byte[] content,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < content.length) {
            int length = Math.min(sourceFragmentSize, content.length - offset);
            ByteBuffer source = ByteBuffer.wrap(content, offset, length).slice();
            encodeSource(encoder, source, encoded, targetSize);
            offset += length;
        }
        finish(encoder, encoded, targetSize);
        return encoded.toByteArray();
    }

    /// Decodes with fresh source and target buffers for every operation.
    private static DecodeResult decodeFreshBuffers(
            byte[] encoded,
            RawLZMACodec codec,
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
                outcome = decoder.decode(source, target, endOfInput);
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

    /// Drains terminal range-coded bytes through bounded target buffers.
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

    /// Copies produced bytes into the supplied byte stream.
    private static void drain(ByteBuffer buffer, ByteArrayOutputStream output) {
        buffer.flip();
        byte @Unmodifiable [] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic literals, repetitions, and long-distance matches.
    private static byte @Unmodifiable [] testData() {
        byte[] data = new byte[180_777];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 37) ^ (index >>> 3) ^ (index % 241));
        }
        System.arraycopy(data, 4_096, data, data.length - 65_536, 65_536);
        return data;
    }

    /// Holds decoded bytes and the exact compressed boundary.
    ///
    /// @param content       decoded bytes
    /// @param consumedInput compressed bytes consumed through the raw stream boundary
    @NotNullByDefault
    private record DecodeResult(byte @Unmodifiable [] content, int consumedInput) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests transport-independent raw PPMd7 encoder and decoder behavior.
@NotNullByDefault
public final class PPMdBufferEngineTest {
    /// Maximum context order used by the fragmentation tests.
    private static final int MAXIMUM_ORDER = 6;

    /// Model memory used by the fragmentation tests.
    private static final long MEMORY_SIZE = 1L << 20;

    /// Shared codec under test.
    private static final PPMdCodec CODEC = new PPMdCodec()
            .withMaximumOrder(MAXIMUM_ORDER)
            .withMemorySize(MEMORY_SIZE);

    /// Verifies one-byte fresh sources, one-byte direct targets, and preservation of trailing container bytes.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] content = patternedData(65_537);
        byte[] encoded = encodeFragmented(content, 1, 1);
        byte[] tail = {11, 22, 33, 44};
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + tail.length);
        System.arraycopy(tail, 0, withTail, encoded.length, tail.length);

        DecodeResult result = decodeFragmented(withTail, content.length, 1, 1, false);

        assertArrayEquals(content, result.content());
        assertTrue(result.consumedInput() <= encoded.length);
        assertArrayEquals(tail, Arrays.copyOfRange(withTail, encoded.length, withTail.length));
    }

    /// Verifies fragmented decoding of an independently produced 7-Zip PPMd7 range stream.
    @Test
    public void decodesOfficialSevenZipVectorAcrossEveryRangeBoundary() throws IOException {
        byte[] expected = "Arkivo PPMd7 interoperability vector".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = HexFormat.of().parseHex(
                "004132f3e8a4c33d237af0157d27f1c7a7de5502f672000f78d5223a5a2c352cb8e543b300"
        );

        DecodeResult result = decodeFragmented(encoded, expected.length, 1, 1, true);

        assertArrayEquals(expected, result.content());
        assertTrue(result.consumedInput() <= encoded.length);
    }

    /// Verifies empty-stream prefix handling, reset determinism, and closure checks.
    @Test
    public void lifecycleAndEmptyStream() throws IOException {
        byte[] empty = encodeFragmented(new byte[0], 1, 1);
        assertEquals(5, empty.length);
        assertArrayEquals(new byte[0], decodeFragmented(empty, 0, 1, 1, true).content());

        byte[] content = patternedData(8_193);
        CompressionEncoder encoder = CODEC.newEncoder();
        byte[] first = encodeWithResettableEncoder(encoder, content);
        encoder.reset();
        byte[] second = encodeWithResettableEncoder(encoder, content);
        assertArrayEquals(first, second);
        assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(1)));
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);

        assertArrayEquals(content, decodeFragmented(first, content.length, 1, 2, true).content());
    }

    /// Verifies terminal input exhaustion is distinguished from temporary source fragmentation.
    @Test
    public void rejectsTruncatedPrefixAndPayload() throws IOException {
        byte[] content = patternedData(4_097);
        byte[] encoded = encodeFragmented(content, 3, 2);

        for (int length = 0; length < 5; length++) {
            ByteBuffer source = ByteBuffer.wrap(Arrays.copyOf(encoded, length));
            try (CompressionDecoder decoder = CODEC.withDecodedSize(content.length).newDecoder()) {
                assertThrows(
                        EOFException.class,
                        () -> decoder.decode(source, ByteBuffer.allocate(1), true),
                        "prefix length " + length
                );
            }
        }

        int truncatedLength = Math.max(5, encoded.length / 2);
        ByteBuffer source = ByteBuffer.wrap(Arrays.copyOf(encoded, truncatedLength));
        ByteBuffer target = ByteBuffer.allocate(content.length);
        try (CompressionDecoder decoder = CODEC.withDecodedSize(content.length).newDecoder()) {
            assertThrows(EOFException.class, () -> decoder.decode(source, target, true));
        }
    }

    /// Encodes one source through fresh fragments and fresh caller-owned targets.
    private static byte[] encodeFragmented(
            byte[] content,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = CODEC.newEncoder()) {
            for (int offset = 0; offset < content.length; offset += sourceFragmentSize) {
                int length = Math.min(sourceFragmentSize, content.length - offset);
                ByteBuffer source = ByteBuffer.allocateDirect(length);
                source.put(content, offset, length).flip();
                encodeSource(encoder, source, encoded, targetSize);
            }
            finish(encoder, encoded, targetSize);
        }
        return encoded.toByteArray();
    }

    /// Encodes one complete stream with an existing resettable encoder.
    private static byte[] encodeWithResettableEncoder(
            CompressionEncoder encoder,
            byte[] content
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int split = content.length / 2;
        encodeSource(encoder, ByteBuffer.wrap(content, 0, split).slice(), encoded, 3);
        encodeSource(
                encoder,
                ByteBuffer.wrap(content, split, content.length - split).slice(),
                encoded,
                3
        );
        finish(encoder, encoded, 2);
        return encoded.toByteArray();
    }

    /// Drives one source until every byte has been accepted by the encoder.
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

    /// Drains terminal arithmetic bytes until stream finalization completes.
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

    /// Decodes through fresh source and target buffers for every operation.
    private static DecodeResult decodeFragmented(
            byte[] encoded,
            int decodedSize,
            int sourceFragmentSize,
            int targetSize,
            boolean endAtArrayBoundary
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        try (CompressionDecoder decoder = CODEC.withDecodedSize(decodedSize).newDecoder()) {
            while (true) {
                int length = Math.min(sourceFragmentSize, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                boolean endOfInput = endAtArrayBoundary && offset + length == encoded.length;
                int sourceStart = source.position();
                int targetStart = target.position();
                CodecOutcome outcome = decoder.decode(source, target, endOfInput);
                offset += source.position();
                drain(target, decoded);
                if (outcome == CodecOutcome.FINISHED) {
                    return new DecodeResult(decoded.toByteArray(), offset);
                }
                assertTrue(
                        outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT,
                        outcome.toString()
                );
                assertTrue(
                        source.position() != sourceStart || target.position() != targetStart,
                        "PPMd decoder made no progress"
                );
            }
        }
    }

    /// Copies produced target bytes into the test accumulator.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Returns deterministic content that exercises literals, repetitions, and changing suffix contexts.
    private static byte @Unmodifiable [] patternedData(int length) {
        byte[] content = new byte[length];
        Random random = new Random(0x50504d6442756666L);
        for (int index = 0; index < content.length; index++) {
            int region = index & 0x3ff;
            content[index] = region < 768
                    ? (byte) (region * 31 + index / 1024)
                    : (byte) random.nextInt();
        }
        return content;
    }

    /// Captures decoded bytes and the exact caller-source position where the declared size completed.
    ///
    /// @param content decoded content
    /// @param consumedInput number of compressed bytes consumed
    private record DecodeResult(byte @Unmodifiable [] content, int consumedInput) {
    }
}

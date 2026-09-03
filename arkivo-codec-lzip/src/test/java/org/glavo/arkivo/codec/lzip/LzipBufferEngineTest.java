// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lzip.internal.LzipSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the transport-independent lzip encoder and decoder contracts.
@NotNullByDefault
public final class LzipBufferEngineTest {
    /// Shared codec with a small exactly representable dictionary for fast tests.
    private static final LzipCodec CODEC = new LzipCodec(64 * 1024);

    /// Verifies fresh direct source buffers, tiny targets, and exact trailing-input positioning.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] content = patternedData(24_013);
        byte[] encoded = encode(content, CODEC, EncodingOptions.DEFAULT, 3, 1);
        byte[] tail = patternedData(37);
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + tail.length);
        System.arraycopy(tail, 0, withTail, encoded.length, tail.length);

        DecodeResult result = decodeFreshBuffers(withTail, CODEC, 7, 2, false);

        assertArrayEquals(content, result.content());
        assertEquals(encoded.length, result.consumedInput());
        assertArrayEquals(tail, Arrays.copyOfRange(withTail, result.consumedInput(), withTail.length));
    }

    /// Verifies explicit frame options, tiny finalization targets, and independent member boundaries.
    @Test
    public void frameOptionsAndBoundaries() throws IOException {
        byte[] first = patternedData(13_337);
        byte[] second = patternedData(8_123);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int firstMemberSize;

        try (CompressionEncoder.Framed encoder = CODEC.newEncoder(
                EncodingOptions.ofSourceSize(first.length)
        )) {
            assertThrows(IllegalStateException.class, encoder::startFrame);
            encodeSource(encoder, directBuffer(first), encoded, 3);
            finishFrame(encoder, encoded, 1);
            firstMemberSize = encoded.size();

            encoder.startFrame(EncodingOptions.ofSourceSize(second.length));
            encodeSource(encoder, directBuffer(second), encoded, 5);
            finish(encoder, encoded, 1);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
            assertThrows(
                    IllegalStateException.class,
                    () -> encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
        }

        byte[] stream = encoded.toByteArray();
        DecodeResult firstResult = decodeFreshBuffers(
                Arrays.copyOf(stream, firstMemberSize),
                CODEC,
                11,
                3,
                true
        );
        DecodeResult secondResult = decodeFreshBuffers(
                Arrays.copyOfRange(stream, firstMemberSize, stream.length),
                CODEC,
                13,
                5,
                true
        );
        assertArrayEquals(first, firstResult.content());
        assertArrayEquals(second, secondResult.content());
        assertEquals(firstMemberSize, firstResult.consumedInput());
        assertEquals(stream.length - firstMemberSize, secondResult.consumedInput());
    }

    /// Verifies pledged-size failures preserve buffer progress and reset can discard failed state.
    @Test
    public void pledgedSourceSizeFailuresAndRecovery() throws IOException {
        CompressionEncoder.Framed oversized = CODEC.newEncoder(EncodingOptions.ofSourceSize(3L));
        ByteBuffer oversizedSource = ByteBuffer.wrap(new byte[4]);
        ByteBuffer oversizedTarget = ByteBuffer.allocate(32);
        assertThrows(IOException.class, () -> oversized.encode(oversizedSource, oversizedTarget));
        assertEquals(0, oversizedSource.position());
        assertEquals(LzipSupport.HEADER_SIZE, oversizedTarget.position());
        oversized.close();

        byte[] exactContent = {1, 2, 3};
        try (CompressionEncoder.Framed undersized = CODEC.newEncoder(EncodingOptions.ofSourceSize(3L))) {
            ByteArrayOutputStream discarded = new ByteArrayOutputStream();
            encodeSource(undersized, directBuffer(new byte[]{1, 2}), discarded, 7);
            assertThrows(IOException.class, () -> undersized.finish(ByteBuffer.allocate(64)));

            undersized.reset();
            ByteArrayOutputStream recovered = new ByteArrayOutputStream();
            encodeSource(undersized, directBuffer(exactContent), recovered, 7);
            finish(undersized, recovered, 2);
            ByteBuffer decoded = CODEC.withMaximumOutputSize(exactContent.length)
                    .decompress(ByteBuffer.wrap(recovered.toByteArray()));
            byte[] restored = new byte[decoded.remaining()];
            decoded.get(restored);
            assertArrayEquals(exactContent, restored);
        }
    }

    /// Verifies physical end-of-input distinguishes truncated headers, payloads, and trailers.
    @Test
    public void truncationAcrossMemberPhases() throws IOException {
        byte[] content = patternedData(4_097);
        byte[] encoded = encode(content, CODEC, EncodingOptions.DEFAULT, 31, 7);
        int trailerOffset = encoded.length - LzipSupport.TRAILER_SIZE;

        int[] strictEofOffsets = {0, 1, LzipSupport.HEADER_SIZE - 1, LzipSupport.HEADER_SIZE, trailerOffset};
        for (int offset : strictEofOffsets) {
            IOException exception = decodeFailure(Arrays.copyOf(encoded, offset), content.length + 1);
            assertInstanceOf(EOFException.class, exception, "cut at byte " + offset);
        }

        int payloadCut = Math.max(LzipSupport.HEADER_SIZE, trailerOffset - 1);
        decodeFailure(Arrays.copyOf(encoded, payloadCut), content.length + 1);
        assertInstanceOf(
                EOFException.class,
                decodeFailure(Arrays.copyOf(encoded, encoded.length - 1), content.length + 1)
        );
    }

    /// Verifies all defined dictionary header codes round-trip and all other exponent codes are rejected.
    @Test
    public void dictionaryHeaderCodeDomain() {
        for (int code = 0; code <= 0xff; code++) {
            int current = code;
            int logarithm = current & 0x1f;
            if (logarithm >= 12 && logarithm <= 29) {
                int dictionarySize = LzipSupport.decodeDictionarySize(current);
                assertEquals(current, LzipSupport.encodeDictionarySize(dictionarySize));
            } else {
                assertThrows(IllegalArgumentException.class, () -> LzipSupport.decodeDictionarySize(current));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> LzipSupport.decodeDictionarySize(-1));
        assertThrows(IllegalArgumentException.class, () -> LzipSupport.decodeDictionarySize(0x100));
    }

    /// Verifies zero-capacity targets and closed-engine operations obey lifecycle contracts.
    @Test
    public void emptyTargetsAndClosure() throws IOException {
        byte[] content = patternedData(257);
        byte[] encoded = encode(content, CODEC, EncodingOptions.DEFAULT, 19, 7);

        CompressionEncoder.Framed encoder = CODEC.newEncoder();
        ByteBuffer source = directBuffer(content);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.encode(source, ByteBuffer.allocate(0)));
        assertEquals(0, source.position());
        encoder.close();
        encoder.close();
        assertThrows(IllegalStateException.class, encoder::reset);
        assertThrows(IllegalStateException.class, encoder::startFrame);
        assertThrows(
                IllegalStateException.class,
                () -> encoder.finishFrame(ByteBuffer.allocate(1))
        );

        CompressionDecoder.Framed decoder = CODEC.newDecoder();
        ByteBuffer compressed = directBuffer(encoded);
        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(compressed, ByteBuffer.allocate(0)));
        assertEquals(LzipSupport.HEADER_SIZE, compressed.position());
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
    }

    /// Verifies a completed boundary can stay empty, start implicitly, and reset after terminal completion.
    @Test
    public void implicitMembersAndTerminalReset() throws IOException {
        byte[] first = patternedData(1_337);
        byte[] second = patternedData(2_049);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ByteArrayOutputStream resetEncoded = new ByteArrayOutputStream();

        try (CompressionEncoder.Framed encoder = CODEC.newEncoder()) {
            ByteBuffer initialHeader = ByteBuffer.allocate(LzipSupport.HEADER_SIZE);
            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.allocate(0), initialHeader)
            );
            drain(initialHeader, encoded);
            encodeSource(encoder, directBuffer(first), encoded, 11);
            finishFrame(encoder, encoded, 3);
            assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(0)));

            ByteBuffer emptySource = ByteBuffer.allocate(0);
            ByteBuffer emptyTarget = ByteBuffer.allocate(8);
            assertEquals(CodecOutcome.NEEDS_INPUT, encoder.encode(emptySource, emptyTarget));
            assertEquals(0, emptyTarget.position());

            encodeSource(encoder, directBuffer(second), encoded, 13);
            finish(encoder, encoded, 5);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));

            encoder.reset();
            encodeSource(encoder, directBuffer(first), resetEncoded, 7);
            finish(encoder, resetEncoded, 2);
        }

        ByteBuffer decoded = CODEC.withMaximumOutputSize(first.length + second.length)
                .decompress(ByteBuffer.wrap(encoded.toByteArray()));
        byte[] combined = new byte[decoded.remaining()];
        decoded.get(combined);
        byte[] expected = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertArrayEquals(expected, combined);

        ByteBuffer resetDecoded = CODEC.withMaximumOutputSize(first.length)
                .decompress(ByteBuffer.wrap(resetEncoded.toByteArray()));
        byte[] resetContent = new byte[resetDecoded.remaining()];
        resetDecoded.get(resetContent);
        assertArrayEquals(first, resetContent);
    }

    /// Verifies callers cannot switch terminal and nonterminal finalization after either operation has begun.
    @Test
    public void finalizationModeCannotChangeMidFlight() throws IOException {
        try (CompressionEncoder.Framed encoder = CODEC.newEncoder()) {
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finishFrame(ByteBuffer.allocate(0)));
            assertThrows(IllegalStateException.class, () -> encoder.finish(ByteBuffer.allocate(1)));

            encoder.reset();
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(ByteBuffer.allocate(0)));
            assertThrows(IllegalStateException.class, () -> encoder.finishFrame(ByteBuffer.allocate(1)));
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder.Framed encoder = CODEC.newEncoder()) {
            encodeSource(encoder, directBuffer(new byte[]{1, 2, 3}), encoded, 7);
            finishFrame(encoder, encoded, 3);
            ByteBuffer target = ByteBuffer.allocate(1);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(target));
            assertEquals(0, target.position());
        }
    }

    /// Verifies a completed decoder is stable and can be reset for another independently validated member.
    @Test
    public void completedDecoderIsStableAndReusable() throws IOException {
        byte[] content = patternedData(4_097);
        byte[] encoded = encode(content, CODEC, EncodingOptions.DEFAULT, 29, 11);

        try (CompressionDecoder.Framed decoder = CODEC.newDecoder()) {
            assertArrayEquals(content, decodeComplete(decoder, encoded));

            ByteBuffer followingSource = ByteBuffer.wrap(new byte[]{1, 2, 3});
            ByteBuffer followingTarget = ByteBuffer.allocate(3);
            assertEquals(CodecOutcome.FINISHED, decoder.decode(followingSource, followingTarget));
            assertEquals(0, followingSource.position());
            assertEquals(0, followingTarget.position());

            decoder.reset();
            assertArrayEquals(content, decodeComplete(decoder, encoded));
        }
    }

    /// Encodes source fragments into one complete lzip member.
    private static byte @Unmodifiable [] encode(
            byte @Unmodifiable [] content,
            LzipCodec codec,
            EncodingOptions options,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder.Framed encoder = codec.newEncoder(options)) {
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

    /// Decodes with fresh direct source and target buffers for every operation.
    private static DecodeResult decodeFreshBuffers(
            byte @Unmodifiable [] encoded,
            LzipCodec codec,
            int sourceFragmentSize,
            int targetSize,
            boolean endAtArrayBoundary
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        try (CompressionDecoder.Framed decoder = codec.newDecoder()) {
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(sourceFragmentSize, encoded.length - offset);
                ByteBuffer source = ByteBuffer.allocateDirect(length);
                source.put(encoded, offset, length).flip();
                ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                boolean endOfInput = endAtArrayBoundary && offset + length == encoded.length;
                outcome = endOfInput
                        ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                int consumed = source.position();
                int produced = drain(target, decoded);
                offset += consumed;
                assertTrue(consumed > 0 || produced > 0 || outcome == CodecOutcome.FINISHED);
                assertTrue(
                        outcome == CodecOutcome.NEEDS_INPUT
                                || outcome == CodecOutcome.NEEDS_OUTPUT
                                || outcome == CodecOutcome.FINISHED
                );
                if (outcome == CodecOutcome.NEEDS_INPUT) {
                    assertFalse(source.hasRemaining());
                } else if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                    assertEquals(targetSize, produced);
                }
            }
        }
        return new DecodeResult(decoded.toByteArray(), offset);
    }

    /// Decodes one complete member through a supplied reusable decoder.
    private static byte @Unmodifiable [] decodeComplete(
            CompressionDecoder decoder,
            byte @Unmodifiable [] encoded
    ) throws IOException {
        ByteBuffer source = directBuffer(encoded);
        ByteBuffer target = ByteBuffer.allocateDirect(4_099);
        CodecOutcome outcome = decoder.finish(source, target);
        assertEquals(CodecOutcome.FINISHED, outcome);
        assertFalse(source.hasRemaining());
        target.flip();
        byte[] decoded = new byte[target.remaining()];
        target.get(decoded);
        return decoded;
    }

    /// Drives one source buffer until the encoder requests more input.
    private static void encodeSource(
            CompressionEncoder encoder,
            ByteBuffer source,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        while (true) {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            int sourcePosition = source.position();
            CodecOutcome outcome = encoder.encode(source, target);
            int produced = drain(target, encoded);
            assertTrue(source.position() > sourcePosition || produced > 0);
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertFalse(source.hasRemaining());
                return;
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            assertEquals(targetSize, produced);
        }
    }

    /// Drains one nonterminal member finalization through bounded target buffers.
    private static void finishFrame(
            CompressionEncoder.Framed encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        while (true) {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            CodecOutcome outcome = encoder.finishFrame(target);
            int produced = drain(target, encoded);
            if (outcome == CodecOutcome.BOUNDARY_REACHED) {
                return;
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            assertEquals(targetSize, produced);
        }
    }

    /// Drains terminal member finalization through bounded target buffers.
    private static void finish(
            CompressionEncoder encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        while (true) {
            ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
            CodecOutcome outcome = encoder.finish(target);
            int produced = drain(target, encoded);
            if (outcome == CodecOutcome.FINISHED) {
                return;
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            assertEquals(targetSize, produced);
        }
    }

    /// Returns the failure raised while physically finishing one truncated member.
    private static IOException decodeFailure(
            byte @Unmodifiable [] encoded,
            int targetSize
    ) throws IOException {
        try (CompressionDecoder.Framed decoder = CODEC.newDecoder()) {
            ByteBuffer source = directBuffer(encoded);
            return assertThrows(IOException.class, () -> {
                while (true) {
                    ByteBuffer target = ByteBuffer.allocateDirect(targetSize);
                    CodecOutcome outcome = decoder.finish(source, target);
                    assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                }
            });
        }
    }

    /// Copies a target buffer's produced bytes into an owned byte stream.
    private static int drain(ByteBuffer target, ByteArrayOutputStream output) {
        int produced = target.position();
        target.flip();
        while (target.hasRemaining()) {
            output.write(target.get());
        }
        return produced;
    }

    /// Creates a direct buffer containing exactly the supplied bytes.
    private static ByteBuffer directBuffer(byte @Unmodifiable [] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        return buffer.put(bytes).flip();
    }

    /// Creates deterministic mixed repetitive and varying content.
    private static byte @Unmodifiable [] patternedData(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < data.length; index++) {
            data[index] = index % 41 < 31
                    ? (byte) ('a' + index % 9)
                    : (byte) ((index * 31) ^ (index >>> 3) ^ (index % 251));
        }
        return data;
    }

    /// Holds decoded bytes and the exact compressed-input boundary.
    ///
    /// @param content decoded bytes
    /// @param consumedInput number of compressed bytes consumed
    @NotNullByDefault
    private record DecodeResult(byte @Unmodifiable [] content, int consumedInput) {
    }
}

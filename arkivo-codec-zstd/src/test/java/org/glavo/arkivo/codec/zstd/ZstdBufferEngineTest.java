// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecodeDirective;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.FramedCompressionEncoder;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests transport-independent Zstandard encoder and decoder behavior.
@NotNullByDefault
public final class ZstdBufferEngineTest {
    /// Shared Zstandard codec under test.
    private static final ZstdCodec CODEC = new ZstdCodec();

    /// Verifies fragmented direct buffers and exact trailing-input preservation.
    @Test
    public void fragmentedBuffersAndTrailingInput() throws IOException {
        byte[] input = testData(180_003);
        byte[] encoded = encode(input, CodecOptions.EMPTY, 113, 1, false);
        byte[] tail = {9, 8, 7, 6};
        ByteBuffer source = ByteBuffer.allocateDirect(encoded.length + tail.length);
        source.put(encoded).put(tail).flip();
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                outcome = decoder.decode(source, target, false);
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

    /// Verifies fresh source buffers, flush boundaries, and checksum validation.
    @Test
    public void fragmentedInputAndFlush() throws IOException {
        byte[] input = testData(96_127);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .build();
        byte[] encoded = encode(input, options, 5, 2, true);
        assertArrayEquals(input, decodeFragments(encoded, options, 3, 2));
    }

    /// Verifies pledged size, magicless framing, and worker configuration on the buffer API.
    @Test
    public void exposesAdvancedEncoderOptions() throws IOException {
        byte[] input = testData(700_123);
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) input.length)
                .set(StandardCodecOptions.WORKER_COUNT, new WorkerCount(2))
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .set(ZstdCodec.FRAME_FORMAT, ZstdFrameFormat.MAGICLESS)
                .build();
        byte[] encoded = encode(input, options, 8191, 17, false);
        CodecOptions decoderOptions = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .set(ZstdCodec.FRAME_FORMAT, ZstdFrameFormat.MAGICLESS)
                .build();
        assertArrayEquals(input, decode(encoded, decoderOptions, 19));

        CodecOptions wrongPledge = CodecOptions.builder()
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) input.length + 1L)
                .build();
        CompressionEncoder encoder = CODEC.newEncoder(wrongPledge);
        try {
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            encodeSource(encoder, ByteBuffer.wrap(input), ignored, 64);
            assertThrows(IOException.class, () -> finish(encoder, ignored, 64));
        } finally {
            encoder.close();
        }
    }

    /// Verifies late dictionary binding through a frame dictionary identifier.
    @Test
    public void requestsAndAcceptsDictionary() throws IOException {
        byte[] dictionaryBytes = testData(4096);
        CompressionDictionary dictionary = CompressionDictionary.of(dictionaryBytes, 77L);
        byte[] input = Arrays.copyOfRange(dictionaryBytes, 1024, 3072);
        CodecOptions encoderOptions = CodecOptions.builder()
                .set(StandardCodecOptions.DICTIONARY, dictionary)
                .set(StandardCodecOptions.PLEDGED_SOURCE_SIZE, (long) input.length)
                .build();
        byte[] encoded = encode(input, encoderOptions, 31, 3, false);
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();

        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(7);
                outcome = decoder.decode(source, target, true);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.NEEDS_DICTIONARY, outcome);
            assertEquals(77L, decoder.requiredDictionaryId());
            decoder.provideDictionary(dictionary);
            do {
                ByteBuffer target = ByteBuffer.allocate(7);
                outcome = decoder.decode(source, target, true);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
        }

        assertArrayEquals(input, decoded.toByteArray());
    }

    /// Verifies skippable-frame boundaries and output-limit enforcement.
    @Test
    public void skippableFrameAndOutputLimit() throws IOException {
        byte[] payload = testData(257);
        byte[] tail = {1, 2, 3};
        ByteBuffer skippable = ByteBuffer.allocate(8 + payload.length + tail.length);
        skippable.putInt(Integer.reverseBytes(0x184d2a50));
        skippable.putInt(Integer.reverseBytes(payload.length));
        skippable.put(payload).put(tail).flip();
        try (CompressionDecoder decoder = CODEC.newDecoder()) {
            assertEquals(
                    CodecOutcome.FINISHED,
                    decoder.decode(skippable, ByteBuffer.allocate(1), false)
            );
        }
        assertEquals(8 + payload.length, skippable.position());

        byte[] input = testData(4097);
        byte[] encoded = encode(input, CodecOptions.EMPTY, 101, 5, false);
        CodecOptions exact = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, (long) input.length)
                .build();
        CodecOptions shortLimit = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, (long) input.length - 1L)
                .build();
        assertArrayEquals(input, decode(encoded, exact, 1));
        assertThrows(DecompressionLimitException.class, () -> decode(encoded, shortLimit, 1));
    }

    /// Verifies truncated input, checksum corruption, and maximum-window enforcement.
    @Test
    public void rejectsMalformedAndOversizedFrames() throws IOException {
        byte[] input = testData(24_019);
        CodecOptions checksumOptions = CodecOptions.builder()
                .set(StandardCodecOptions.CHECKSUM, ChecksumMode.ENABLED)
                .build();
        byte[] encoded = encode(input, checksumOptions, 257, 11, false);

        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(IOException.class, () -> decode(truncated, checksumOptions, 17));
        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decode(corrupt, checksumOptions, 17));

        CodecOptions smallWindow = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_WINDOW_SIZE, 1024L)
                .build();
        assertThrows(IOException.class, () -> decode(encoded, smallWindow, 17));
    }

    /// Verifies Channel adaptation exposes one exact frame boundary before physical EOF.
    @Test
    public void channelAdapterEndsAtPhysicalInput() throws IOException {
        byte[] input = testData(20_007);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CODEC.compress(
                Channels.newChannel(new java.io.ByteArrayInputStream(input)),
                Channels.newChannel(encoded)
        );
        byte[] frame = encoded.toByteArray();
        assertEquals(frame.length, CODEC.frameCompressedSize(ByteBuffer.wrap(frame)));

        try (DecompressingReadableByteChannel decoder = CODEC.openDecoder(
                Channels.newChannel(new java.io.ByteArrayInputStream(frame))
        )) {
            ByteBuffer target = ByteBuffer.allocate(input.length);
            CodecResult boundary = decoder.decode(target, DecodeDirective.STOP_AT_FRAME);
            assertEquals(CodecStatus.FRAME_FINISHED, boundary.status());
            assertEquals(frame.length, decoder.inputBytes());
            assertArrayEquals(input, Arrays.copyOf(target.array(), target.position()));
            assertEquals(
                    CodecStatus.END_OF_INPUT,
                    decoder.decode(ByteBuffer.allocate(1), DecodeDirective.CONTINUE).status()
            );
        }
    }

    /// Verifies reset and advertised transport-independent capabilities.
    @Test
    public void resetAndCapabilities() throws IOException {
        assertTrue(CODEC.capabilities().supports(CompressionFeature.BUFFER_COMPRESSION));
        assertTrue(CODEC.capabilities().supports(CompressionFeature.BUFFER_DECOMPRESSION));
        byte[] input = testData(20_003);
        CompressionEncoder encoder = CODEC.newEncoder();
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), first, 13);
        finish(encoder, first, 7);
        encoder.reset();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        encodeSource(encoder, ByteBuffer.wrap(input), second, 13);
        finish(encoder, second, 7);
        encoder.close();
        assertArrayEquals(first.toByteArray(), second.toByteArray());
        assertThrows(IllegalStateException.class, encoder::reset);
    }

    /// Verifies explicit frame boundaries preserve the encoder and terminal finish emits no empty frame.
    @Test
    public void framedEncoderStartsFollowingFramesLazily() throws IOException {
        byte[] first = testData(10_003);
        byte[] second = testData(17_009);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        try (FramedCompressionEncoder encoder = CODEC.newEncoder()) {
            encodeSource(encoder, ByteBuffer.wrap(first), encoded, 11);
            finishFrame(encoder, encoded, 3);
            int firstEnd = encoded.size();

            encodeSource(encoder, ByteBuffer.wrap(second), encoded, 13);
            finishFrame(encoder, encoded, 5);
            int secondEnd = encoded.size();

            assertEquals(
                    CodecOutcome.NEEDS_INPUT,
                    encoder.encode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
            );
            finish(encoder, encoded, 7);
            assertEquals(secondEnd, encoded.size());

            byte[] frames = encoded.toByteArray();
            assertEquals(firstEnd, CODEC.frameCompressedSize(ByteBuffer.wrap(frames)));
            assertEquals(
                    secondEnd - firstEnd,
                    CODEC.frameCompressedSize(ByteBuffer.wrap(frames, firstEnd, secondEnd - firstEnd).slice())
            );
            assertArrayEquals(first, decode(Arrays.copyOfRange(frames, 0, firstEnd), CodecOptions.EMPTY, 2));
            assertArrayEquals(second, decode(Arrays.copyOfRange(frames, firstEnd, secondEnd), CodecOptions.EMPTY, 2));
        }
    }

    /// Encodes input fragments into one frame.
    private static byte[] encode(
            byte[] input,
            CodecOptions options,
            int sourceFragmentSize,
            int targetSize,
            boolean flushEachFragment
    ) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (CompressionEncoder encoder = CODEC.newEncoder(options)) {
            for (int offset = 0; offset < input.length; offset += sourceFragmentSize) {
                int end = Math.min(input.length, offset + sourceFragmentSize);
                encodeSource(encoder, ByteBuffer.wrap(Arrays.copyOfRange(input, offset, end)), encoded, targetSize);
                if (flushEachFragment) {
                    flush(encoder, encoded, targetSize);
                }
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
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.encode(source, target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
        assertEquals(false, source.hasRemaining());
    }

    /// Drains one encoder flush operation.
    private static void flush(CompressionEncoder encoder, ByteArrayOutputStream encoded, int targetSize)
            throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.flush(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FLUSHED, outcome);
    }

    /// Drains one non-terminal Zstandard frame-finalization operation.
    private static void finishFrame(
            FramedCompressionEncoder encoder,
            ByteArrayOutputStream encoded,
            int targetSize
    ) throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.finishFrame(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.BOUNDARY_REACHED, outcome);
    }

    /// Drains one terminal encoder-finalization operation.
    private static void finish(CompressionEncoder encoder, ByteArrayOutputStream encoded, int targetSize)
            throws IOException {
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(targetSize);
            outcome = encoder.finish(target);
            drain(target, encoded);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
    }

    /// Decodes one frame from one complete source buffer.
    private static byte[] decode(byte[] encoded, CodecOptions options, int targetSize) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        ByteBuffer source = ByteBuffer.wrap(encoded);
        try (CompressionDecoder decoder = CODEC.newDecoder(options)) {
            CodecOutcome outcome;
            do {
                ByteBuffer target = ByteBuffer.allocate(targetSize);
                outcome = decoder.decode(source, target, true);
                drain(target, decoded);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
        }
        return decoded.toByteArray();
    }

    /// Decodes one frame while replacing the source buffer after each input request.
    private static byte[] decodeFragments(
            byte[] encoded,
            CodecOptions options,
            int sourceFragmentSize,
            int targetSize
    ) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (CompressionDecoder decoder = CODEC.newDecoder(options)) {
            int offset = 0;
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            while (outcome != CodecOutcome.FINISHED) {
                int length = Math.min(sourceFragmentSize, encoded.length - offset);
                ByteBuffer source = ByteBuffer.wrap(encoded, offset, length).slice();
                boolean endOfInput = offset + length == encoded.length;
                do {
                    ByteBuffer target = ByteBuffer.allocate(targetSize);
                    outcome = decoder.decode(source, target, endOfInput);
                    drain(target, decoded);
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                offset += source.position();
                assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.FINISHED);
            }
            assertEquals(encoded.length, offset);
        }
        return decoded.toByteArray();
    }

    /// Copies produced target bytes into a byte stream.
    private static void drain(ByteBuffer buffer, ByteArrayOutputStream output) {
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic test content of the requested size.
    private static byte[] testData(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((index * 67) ^ (index >>> 5));
        }
        return data;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
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

/// Adapts streaming progress and state regressions from Zstandard 1.5.7's zstreamtest.c.
@NotNullByDefault
@Timeout(30)
final class ZstdOfficialStreamingTest {
    /// Requires the official raw-block case to produce output before the whole block has arrived.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rawBlockCanBeStreamed(int shape) throws IOException {
        byte[] plain = new byte[10000];
        new Random(2335).nextBytes(plain);
        byte[] encoded = Zstd.compress(plain, -plain.length);
        int header = ZstdCodec.DEFAULT.frameInfo(ByteBuffer.wrap(encoded)).headerSize();
        assertEquals(0, (encoded[header] >>> 1) & 3, "The reference must emit a raw block");
        ByteBuffer source = input(encoded, shape);
        source.limit(0);
        ByteBuffer target = ByteBuffer.allocate(plain.length + 1);
        target.limit(0);
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            while (source.limit() < encoded.length) {
                source.limit(Math.min(source.limit() + 100, encoded.length));
                while (source.hasRemaining()) {
                    int before = target.position();
                    if (!target.hasRemaining()) {
                        target.limit(Math.min(target.limit() + 10, plain.length + 1));
                    }
                    decoder.decode(source, target);
                    assertTrue(target.position() > before, "Raw payload must stream before block completion");
                }
            }
            target.limit(target.capacity());
            assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
            assertEquals(plain.length, target.position());
            assertArrayEquals(plain, Arrays.copyOf(target.array(), target.position()));
        }
    }

    /// Checks that incremental raw delivery preserves the history referenced by the following compressed block.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rawHistoryFeedsFollowingCompressedBlock(int shape) throws IOException {
        byte[] plain = new byte[2 * 131072];
        new Random(1710).nextBytes(plain);
        System.arraycopy(plain, 0, plain, 131072, 131072);
        byte[] frame = Zstd.compress(plain, 3);
        int header = ZstdCodec.DEFAULT.frameInfo(ByteBuffer.wrap(frame)).headerSize();
        assertEquals(0, (frame[header] >>> 1) & 3);
        assertEquals(2, (frame[header + 3 + 131072] >>> 1) & 3);
        ByteBuffer source = input(frame, shape);
        source.limit(0);
        ByteBuffer output = ByteBuffer.allocate(plain.length + 1);
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
            int attempts = 0;
            while (outcome != CodecOutcome.FINISHED) {
                assertTrue(++attempts < 1000);
                source.limit(Math.min(frame.length, source.limit() + 1019));
                outcome = decoder.decode(source, output);
            }
            assertEquals(frame.length, source.position());
            assertArrayEquals(plain, Arrays.copyOf(output.array(), output.position()));
        }
    }

    /// Replays the exact upstream window-overwrite and short sequence-section frames through fragmented buffers.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void historicalSequenceRegressions(int shape) throws IOException {
        byte[] windowFrame = HexFormat.of().parseHex(
                "28b52ffd04004c00001061610100002a800544000008620100002a20045d00000003400000646027b0e00c6762cee0");
        byte[] windowPlain = new byte[1063];
        Arrays.fill(windowPlain, 0, 517, (byte) 'a');
        Arrays.fill(windowPlain, 517, 1060, (byte) 'b');
        Arrays.fill(windowPlain, 1060, 1063, (byte) 'a');
        assertArrayEquals(windowPlain, Zstd.decompress(windowFrame, windowPlain.length));
        checkFragments(windowFrame, windowPlain, shape);

        byte[] smallFrame = HexFormat.of().parseHex(
                "28b52ffd243c350100f08508c2c470cfd7c0967e4c6ba98bbcc5b6d97f4cf105a654efac6994891c03440a0700b40480400aa4");
        byte[] smallPlain = HexFormat.of().parseHex(
                "8508c2c470cfd7c0967e8508c2c470cfd7c0967e4c6ba98bbcc5b6d97f4c4c6ba98bbcc5b6d97f4cf105a654efac6994891cf105a654efac6994891c");
        assertArrayEquals(smallPlain, Zstd.decompress(smallFrame, smallPlain.length));
        checkFragments(smallFrame, smallPlain, shape);
    }

    /// Keeps source-size promises effective even when the frame omits its content-size field.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pledgedSizeAndReset(boolean contentSize) throws IOException {
        byte[] plain = new byte[4096];
        new Random(19).nextBytes(plain);
        ZstdCodec codec = ZstdCodec.builder().compressionLevel(1).contentSize(contentSize).build();
        for (int difference : new int[]{-1, 0, 1}) {
            try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(plain.length + difference))) {
                if (difference == 0) {
                    byte[] frame = encode(encoder, plain, true);
                    assertArrayEquals(plain, Zstd.decompress(frame, plain.length));
                    var info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
                    assertEquals(contentSize ? plain.length : -1, info.contentSize());
                } else {
                    assertThrows(IOException.class, () -> encode(encoder, plain, true));
                }
                encoder.reset();
                byte[] matching = Arrays.copyOf(plain, plain.length + difference);
                assertArrayEquals(matching, Zstd.decompress(encode(encoder, matching, true), matching.length));
            }
        }
    }

    /// Reuses an encoder across the upstream 513/1025-byte table-size transition and explicit frame options.
    @Test
    void contextReuseAcrossSourceSizes() throws IOException {
        try (var encoder = ZstdCodec.DEFAULT.withCompressionLevel(19).newEncoder(EncodingOptions.ofSourceSize(513))) {
            for (int size : new int[]{513, 1025}) {
                byte[] plain = new byte[size];
                new Random(size).nextBytes(plain);
                if (size == 1025) encoder.startFrame(EncodingOptions.ofSourceSize(size));
                ByteBuffer output = ByteBuffer.allocate(size + 1024);
                ByteBuffer source = ByteBuffer.wrap(plain);
                assertEquals(CodecOutcome.NEEDS_INPUT, encoder.encode(source, output));
                assertFalse(source.hasRemaining());
                assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(output));
                byte[] frame = Arrays.copyOf(output.array(), output.position());
                assertArrayEquals(plain, Zstd.decompress(frame, size));
                assertEquals(size, ((ZstdStandardFrameInfo) ZstdCodec.DEFAULT.frameInfo(ByteBuffer.wrap(frame))).contentSize());
            }
            assertEquals(CodecOutcome.FINISHED, encoder.finish(ByteBuffer.allocate(0)));
        }
    }

    /// Adapts the upstream NULL-buffer scenarios to valid zero-length Java buffers and recoverable backpressure.
    @ParameterizedTest
    @ValueSource(strings = {"", "aa"})
    void emptyOutputCanBeResumed(String text) throws IOException {
        byte[] plain = text.getBytes(StandardCharsets.US_ASCII);
        try (var encoder = ZstdCodec.DEFAULT.newEncoder()) {
            ByteBuffer source = ByteBuffer.wrap(plain);
            ByteBuffer empty = ByteBuffer.allocate(0);
            encoder.encode(source, empty);
            assertFalse(source.hasRemaining());
            assertEquals(CodecOutcome.NEEDS_OUTPUT, encoder.finish(empty));
            ByteBuffer encoded = ByteBuffer.allocate(128);
            assertEquals(CodecOutcome.FINISHED, encoder.finish(encoded));
            encoded.flip();
            try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
                for (int attempt = 0; attempt < 100; attempt++) {
                    assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.decode(encoded, empty));
                }
                ByteBuffer output = ByteBuffer.allocate(plain.length + 1);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(encoded, output));
                assertFalse(encoded.hasRemaining());
                assertArrayEquals(plain, Arrays.copyOf(output.array(), output.position()));
            }
        }
    }

    /// Checks exact consumption, one-byte fragmentation, reset reuse, and checksum rejection for a reference frame.
    private static void checkFragments(byte[] frame, byte[] plain, int shape) throws IOException {
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            for (int chunk : new int[]{1, 7, frame.length}) {
                decoder.reset();
                ByteBuffer source = input(Arrays.copyOf(frame, frame.length + 3), shape);
                source.limit(0);
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
                int attempts = 0;
                while (outcome != CodecOutcome.FINISHED) {
                    assertTrue(++attempts < 10000, "Decoder did not finish");
                    if (!source.hasRemaining()) source.limit(Math.min(source.capacity(), source.limit() + chunk));
                    ByteBuffer target = ByteBuffer.allocate(11);
                    outcome = decoder.decode(source, target);
                    result.write(target.array(), 0, target.position());
                }
                assertEquals(frame.length, source.position());
                assertArrayEquals(plain, result.toByteArray());
            }
            decoder.reset();
            byte[] damaged = frame.clone();
            damaged[damaged.length - 1] ^= 1;
            assertThrows(IOException.class, () -> decoder.finish(input(damaged, shape), ByteBuffer.allocate(plain.length + 1)));
        }
    }

    /// Encodes with tiny output buffers, optionally flushing after each input part without ending the frame.
    private static byte[] encode(CompressionEncoder.FlushableFramed encoder, byte[] plain, boolean flush)
            throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int offset = 0; offset < plain.length; offset += 513) {
            ByteBuffer source = ByteBuffer.wrap(plain, offset, Math.min(513, plain.length - offset));
            while (source.hasRemaining()) {
                ByteBuffer target = ByteBuffer.allocate(37);
                encoder.encode(source, target);
                result.write(target.array(), 0, target.position());
            }
            if (flush) {
                CodecOutcome outcome;
                do {
                    ByteBuffer target = ByteBuffer.allocate(37);
                    outcome = encoder.flush(target);
                    result.write(target.array(), 0, target.position());
                } while (outcome != CodecOutcome.FLUSHED);
            }
        }
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocate(37);
            outcome = encoder.finish(target);
            result.write(target.array(), 0, target.position());
        } while (outcome != CodecOutcome.FINISHED);
        return result.toByteArray();
    }

    /// Copies compressed input into a heap, direct, or read-only buffer.
    private static ByteBuffer input(byte[] bytes, int shape) {
        ByteBuffer buffer = shape == 1 ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.allocate(bytes.length);
        buffer.put(bytes).flip();
        return shape == 2 ? buffer.asReadOnlyBuffer() : buffer;
    }
}

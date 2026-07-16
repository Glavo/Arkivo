// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel.Directive;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Deflate64 encoding, interoperability, and incremental channel behavior.
@NotNullByDefault
final class Deflate64EncoderTest {
    /// The codec under test.
    private static final Deflate64Codec CODEC = new Deflate64Codec();

    /// Verifies ordinary patterned input with both Arkivo and an independent decoder.
    @Test
    void roundTripsWithIndependentDecoder() throws IOException {
        byte[] pattern = "Deflate64 channel codec interoperability\n".getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[200_000];
        for (int offset = 0; offset < input.length; offset += pattern.length) {
            System.arraycopy(pattern, 0, input, offset, Math.min(pattern.length, input.length - offset));
        }

        byte[] compressed = encode(input, 6L);

        assertTrue(compressed.length < input.length);
        assertArrayEquals(input, decodeWithArkivo(compressed));
        assertArrayEquals(input, decodeWithCommonsCompress(compressed));
    }

    /// Verifies the Deflate64 length extension beyond the Deflate limit of 258 bytes.
    @Test
    void encodesExtendedMatchLengths() throws IOException {
        byte[] input = new byte[60_000];
        Arrays.fill(input, (byte) 'A');

        byte[] compressed = encode(input, 9L);

        assertTrue(compressed.length < 100);
        assertArrayEquals(input, decodeWithArkivo(compressed));
        assertArrayEquals(input, decodeWithCommonsCompress(compressed));
    }

    /// Verifies the Deflate64-only 64 KiB backward distance across block history.
    @Test
    void encodesFullWindowDistance() throws IOException {
        byte[] history = new byte[65_536];
        new Random(0xdef1_ace6_4L).nextBytes(history);
        removeRepeatedInitialTriple(history);
        byte[] input = Arrays.copyOf(history, history.length + 4_096);
        System.arraycopy(history, 0, input, history.length, 4_096);

        byte[] compressed = encode(input, 9L);

        assertTrue(compressed.length < input.length);
        assertArrayEquals(input, decodeWithArkivo(compressed));
        assertArrayEquals(input, decodeWithCommonsCompress(compressed));
    }

    /// Verifies flush boundaries, counters, lifecycle state, and retained target ownership.
    @Test
    void flushesIncrementallyAndRetainsTarget() throws IOException {
        byte[] first = "first flushed segment ".repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second final segment ".repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[first.length + second.length];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(bytes);
        CompressingWritableByteChannel encoder = CODEC.openEncoder(target);

        CodecResult flushed = encoder.encode(ByteBuffer.wrap(first), Directive.FLUSH);
        int flushedSize = bytes.size();
        CodecResult finished = encoder.encode(ByteBuffer.wrap(second), Directive.END_FRAME);

        assertEquals(new CodecResult(first.length, flushedSize, CodecResult.Status.FLUSHED), flushed);
        assertEquals(second.length, finished.inputBytes());
        assertEquals(CodecResult.Status.FRAME_FINISHED, finished.status());
        assertEquals(expected.length, encoder.inputBytes());
        assertEquals(bytes.size(), encoder.outputBytes());
        assertTrue(flushedSize > 0);
        assertFalse(encoder.isOpen());
        assertTrue(target.isOpen());
        assertArrayEquals(expected, decodeWithCommonsCompress(bytes.toByteArray()));
    }

    /// Verifies stored-block level zero and match-search level nine produce valid distinct output.
    @Test
    void honorsCompressionLevel() throws IOException {
        byte[] input = "compression-level-search-depth".repeat(2_000).getBytes(StandardCharsets.UTF_8);

        byte[] levelZero = encode(input, 0L);
        byte[] levelNine = encode(input, 9L);

        assertTrue(levelNine.length < levelZero.length);
        assertArrayEquals(input, decodeWithCommonsCompress(levelZero));
        assertArrayEquals(input, decodeWithCommonsCompress(levelNine));
    }

    /// Verifies an empty raw stream is represented by a valid final block.
    @Test
    void encodesEmptyInput() throws IOException {
        byte[] compressed = encode(new byte[0], 6L);

        assertTrue(compressed.length > 0);
        assertArrayEquals(new byte[0], decodeWithArkivo(compressed));
        assertArrayEquals(new byte[0], decodeWithCommonsCompress(compressed));
    }

    /// Encodes one byte array at a selected compression level.
    private static byte[] encode(byte[] input, long level) throws IOException {
        Deflate64Codec codec = CODEC.withCompressionLevel(level);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CompressingWritableByteChannel encoder = codec.openEncoder(
                Channels.newChannel(bytes),
                ChannelOwnership.RETAIN
        )) {
            encoder.write(ByteBuffer.wrap(input));
        }
        return bytes.toByteArray();
    }

    /// Decodes raw Deflate64 with Arkivo's channel decoder.
    private static byte[] decodeWithArkivo(byte[] compressed) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DecompressingReadableByteChannel decoder = CODEC.openDecoder(
                Channels.newChannel(new ByteArrayInputStream(compressed))
        )) {
            ByteBuffer output = ByteBuffer.allocate(8_192);
            while (true) {
                int read = decoder.read(output);
                output.flip();
                byte[] chunk = new byte[output.remaining()];
                output.get(chunk);
                bytes.write(chunk);
                output.clear();
                if (read < 0) {
                    return bytes.toByteArray();
                }
            }
        }
    }

    /// Decodes raw Deflate64 with Apache Commons Compress for interoperability validation.
    private static byte[] decodeWithCommonsCompress(byte[] compressed) throws IOException {
        try (Deflate64CompressorInputStream input = new Deflate64CompressorInputStream(
                new ByteArrayInputStream(compressed)
        )) {
            return input.readAllBytes();
        }
    }

    /// Ensures the first three bytes do not recur within the retained history.
    private static void removeRepeatedInitialTriple(byte[] history) {
        for (int index = 1; index + 2 < history.length; index++) {
            if (history[index] == history[0]
                    && history[index + 1] == history[1]
                    && history[index + 2] == history[2]) {
                history[index + 2] ^= 1;
            }
        }
    }
}

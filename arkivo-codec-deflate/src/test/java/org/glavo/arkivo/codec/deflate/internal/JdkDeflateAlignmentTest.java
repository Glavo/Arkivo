// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.deflate.DeflateStrategy;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.deflate.ZlibDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/// Compares encoded bytes with a fixed JDK Deflater input and output schedule.
@NotNullByDefault
public final class JdkDeflateAlignmentTest {
    /// The input and output capacity of the reference schedule.
    private static final int REFERENCE_BUFFER_SIZE = 65536;

    /// Pins reference bytes from BellSoft OpenJDK 25+37-LTS with its bundled zlib 1.3.1.
    @Test
    void matchesPinnedReferenceFingerprints() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] input = new byte[70013];
        new Random(1951).nextBytes(input);
        for (int level : new int[]{0, 1, 6, 9}) {
            for (DeflateStrategy strategy : DeflateStrategy.values()) {
                for (boolean raw : new boolean[]{false, true}) {
                    digest.update((byte) level);
                    digest.update((byte) strategy.ordinal());
                    digest.update((byte) (raw ? 1 : 0));
                    digest.update(verify(input, level, strategy, raw, null,
                            new int[]{17, 65537}, 8192, 257, false));
                }
            }
        }
        assertEquals("d87bf18963e4215358e49608ec83d7250fd6e71ce81f52bbaafc6e9be66f7abd",
                HexFormat.of().formatHex(digest.digest()));
    }

    /// Covers every supported level and strategy with short, repeated, and random input.
    @Test
    void matchesJdkAcrossConfigurations() throws Exception {
        byte[] random = new byte[70013];
        new Random(42).nextBytes(random);
        byte[] tied = new byte[4096];
        for (int i = 0; i < tied.length; i++) tied[i] = (byte) (i & 15);
        byte[][] inputs = {new byte[0], {1, 2, 3},
                "Deflate alignment and repeated matches ".repeat(2000).getBytes(StandardCharsets.UTF_8), random, tied};
        for (byte[] input : inputs) {
            for (int level = 0; level <= 9; level++) {
                for (DeflateStrategy strategy : DeflateStrategy.values()) {
                    verify(input, level, strategy, true, null, new int[0], 8192, 257, false);
                    verify(input, level, strategy, false, null, new int[0], 257, 8192, true);
                }
            }
        }
    }

    /// Checks every explicit boundary and returns the independently encoded JDK reference bytes.
    public static byte[] verify(byte[] input, int level, DeflateStrategy strategy, boolean raw,
                              byte @Nullable [] dictionary, int[] boundaries,
                              int inputChunk, int outputChunk, boolean direct) throws Exception {
        Deflater reference = new Deflater(level, raw);
        try (CompressionEncoder.Flushable encoder = newEncoder(level, strategy, raw, dictionary)) {
            reference.setStrategy(switch (strategy) {
                case DEFAULT -> Deflater.DEFAULT_STRATEGY;
                case FILTERED -> Deflater.FILTERED;
                case HUFFMAN_ONLY -> Deflater.HUFFMAN_ONLY;
            });
            if (dictionary != null) reference.setDictionary(dictionary);
            byte[] referenceOutput = new byte[REFERENCE_BUFFER_SIZE];
            // JDK applies a changed strategy lazily; that call does not perform the requested flush.
            if (strategy != DeflateStrategy.DEFAULT) {
                assertEquals(0, reference.deflate(referenceOutput, 0, referenceOutput.length, Deflater.NO_FLUSH));
            }
            var expected = new ByteArrayOutputStream();
            var actual = new ByteArrayOutputStream();
            ByteBuffer target = direct ? ByteBuffer.allocateDirect(outputChunk) : ByteBuffer.allocate(outputChunk);
            ByteBuffer inputBuffer = direct ? ByteBuffer.allocateDirect(input.length + 3)
                    : ByteBuffer.allocate(input.length + 3);
            inputBuffer.position(3).put(input).flip().position(3);
            ByteBuffer source = inputBuffer.asReadOnlyBuffer();
            int start = 0;
            for (int index = 0; index <= boundaries.length; index++) {
                int end = index < boundaries.length ? boundaries[index] : input.length;
                for (int position = start; position < end;) {
                    int count = Math.min(REFERENCE_BUFFER_SIZE, end - position);
                    reference.setInput(input, position, count);
                    int written;
                    do {
                        written = reference.deflate(referenceOutput, 0, referenceOutput.length, Deflater.NO_FLUSH);
                        expected.write(referenceOutput, 0, written);
                    } while (!reference.needsInput() || written == referenceOutput.length);
                    position += count;
                }
                for (int position = start; position < end;) {
                    int count = Math.min(inputChunk, end - position);
                    source.limit(position + count + 3);
                    CodecOutcome outcome;
                    do {
                        target.clear();
                        outcome = encoder.encode(source, target);
                        drain(target, actual);
                    } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                    assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
                    assertEquals(source.limit(), source.position());
                    position += count;
                }
                if (index < boundaries.length) {
                    int written;
                    do {
                        written = reference.deflate(referenceOutput, 0, referenceOutput.length, Deflater.SYNC_FLUSH);
                        expected.write(referenceOutput, 0, written);
                    } while (written == referenceOutput.length);
                    CodecOutcome outcome;
                    do {
                        target.clear();
                        outcome = encoder.flush(target);
                        drain(target, actual);
                    } while (outcome == CodecOutcome.NEEDS_OUTPUT);
                    assertEquals(CodecOutcome.FLUSHED, outcome);
                    compare(expected, actual, level, strategy, raw, end);
                }
                start = end;
            }
            reference.finish();
            while (!reference.finished()) {
                int written = reference.deflate(referenceOutput);
                expected.write(referenceOutput, 0, written);
            }
            CodecOutcome outcome;
            do {
                target.clear();
                outcome = encoder.finish(target);
                drain(target, actual);
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertEquals(CodecOutcome.FINISHED, outcome);
            compare(expected, actual, level, strategy, raw, input.length);
            return expected.toByteArray();
        } finally {
            reference.end();
        }
    }

    /// Creates the selected wrapper without configuring an absent dictionary.
    private static CompressionEncoder.Flushable newEncoder(int level, DeflateStrategy strategy,
                                                           boolean raw, byte @Nullable [] dictionary) throws IOException {
        if (raw) {
            var codec = new DeflateCodec().withCompressionLevel(level).withStrategy(strategy);
            if (dictionary != null) codec = codec.withDictionary(RawCompressionDictionary.of(dictionary));
            return codec.newEncoder();
        }
        var codec = new ZlibCodec().withCompressionLevel(level).withStrategy(strategy);
        if (dictionary != null) codec = codec.withDictionary(ZlibDictionary.of(dictionary));
        return codec.newEncoder();
    }

    /// Copies completed target bytes without requiring a backing array.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        while (target.hasRemaining()) output.write(target.get());
    }

    /// Reports the first byte difference together with the encoding configuration.
    private static void compare(ByteArrayOutputStream expected, ByteArrayOutputStream actual,
                                int level, DeflateStrategy strategy, boolean raw, int boundary) {
        byte[] left = expected.toByteArray();
        byte[] right = actual.toByteArray();
        int mismatch = Arrays.mismatch(left, right);
        if (mismatch >= 0) fail("Deflate mismatch: level=" + level + ", strategy=" + strategy
                + ", raw=" + raw + ", boundary=" + boundary + ", offset=" + mismatch
                + ", expectedLength=" + left.length + ", actualLength=" + right.length
                + ", expectedByte=" + (mismatch < left.length ? Byte.toUnsignedInt(left[mismatch]) : -1)
                + ", actualByte=" + (mismatch < right.length ? Byte.toUnsignedInt(right[mismatch]) : -1));
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.DeflateStrategy;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Checks exact output across window, symbol-block, dictionary, and synchronization boundaries.
@NotNullByDefault
final class JdkDeflateAlignmentMatrixTest {
    /// Crosses stored-block carry rollover with bounded test memory rather than a large retained fixture.
    @Test
    void matchesStoredCarryRollover() throws Exception {
        verifyStoredCarry(7200, 0, true);
        verifyStoredCarry(6553, 65535, false);
    }

    /// Checks complete and partial canonical chunks near the stored-window rollover point.
    private static void verifyStoredCarry(int completeChunks, int tailSize, boolean raw) throws Exception {
        byte[] input = new byte[65536];
        new Random(31).nextBytes(input);
        byte[] referenceOutput = new byte[65536];
        ByteBuffer source = ByteBuffer.wrap(input).asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocate(65536);
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        MessageDigest actual = MessageDigest.getInstance("SHA-256");
        Deflater reference = new Deflater(0, raw);
        try (CompressionEncoder.Flushable encoder = raw
                ? new DeflateEncoderEngine(0, null, DeflateStrategy.DEFAULT)
                : new ZlibEncoder(0, null, DeflateStrategy.DEFAULT)) {
            for (int chunk = 0; chunk < completeChunks + (tailSize > 0 ? 1 : 0); chunk++) {
                int length = chunk < completeChunks ? input.length : tailSize;
                reference.setInput(input, 0, length);
                int written;
                do {
                    written = reference.deflate(referenceOutput);
                    expected.update(referenceOutput, 0, written);
                } while (!reference.needsInput() || written == referenceOutput.length);
                source.clear().limit(length);
                CodecOutcome outcome;
                do {
                    target.clear();
                    outcome = encoder.encode(source, target);
                    actual.update(target.array(), 0, target.position());
                } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            }
            reference.finish();
            while (!reference.finished()) {
                int written = reference.deflate(referenceOutput);
                expected.update(referenceOutput, 0, written);
            }
            CodecOutcome outcome;
            do {
                target.clear();
                outcome = encoder.finish(target);
                actual.update(target.array(), 0, target.position());
            } while (outcome == CodecOutcome.NEEDS_OUTPUT);
            assertArrayEquals(expected.digest(), actual.digest());
        } finally {
            reference.end();
        }
    }

    /// Covers short lookahead, symbol capacity, and both sides of sliding-window thresholds.
    @Test
    void matchesBoundaryLengths() throws Exception {
        for (int size : new int[]{0, 1, 2, 3, 261, 262, 263, 16382, 16383, 16384,
                32505, 32506, 32768, 65274, 65535, 65536, 65537, 131073}) {
            byte[] input = new byte[size];
            new Random(0x5a4c4942L + size).nextBytes(input);
            for (int profile = 0; profile < 2; profile++) {
                if (profile != 0) for (int i = 0; i < size; i++) input[i] &= 7;
                for (int level = 0; level <= 9; level++) {
                    for (DeflateStrategy strategy : DeflateStrategy.values()) {
                        JdkDeflateAlignmentTest.verify(input, level, strategy, true, null,
                                new int[0], 257, 7, profile != 0);
                        JdkDeflateAlignmentTest.verify(input, level, strategy, false, null,
                                new int[0], 8192, 257, profile == 0);
                    }
                }
            }
        }
    }

    /// Checks that frequent flushes do not let literal-only window indices grow without bounds.
    @Test
    void matchesFrequentFlushes() throws Exception {
        byte[] input = new byte[150013];
        new Random(77).nextBytes(input);
        int[] boundaries = new int[150];
        for (int i = 0; i < boundaries.length; i++) boundaries[i] = 997 * (i + 1);
        for (DeflateStrategy strategy : DeflateStrategy.values()) {
            JdkDeflateAlignmentTest.verify(input, 6, strategy, true, null, boundaries, 257, 7, true);
        }
    }

    /// Covers dictionaries of every relevant size and repeated empty synchronization requests.
    @Test
    void matchesDictionariesAndFlushes() throws Exception {
        for (int size : new int[]{0, 1, 2, 3, 257, 32506, 32768, 32769, 70013}) {
            byte[] dictionary = new byte[size];
            new Random(size).nextBytes(dictionary);
            byte[] input = new byte[150013];
            new Random(42).nextBytes(input);
            if (size != 0) for (int i = 0; i < input.length; i++) input[i] = dictionary[i % size];
            for (int level = 0; level <= 9; level++) {
                for (DeflateStrategy strategy : DeflateStrategy.values()) {
                    for (boolean raw : new boolean[]{false, true}) {
                        JdkDeflateAlignmentTest.verify(input, level, strategy, raw, dictionary,
                                new int[]{0, 0, 1, 1, 17, 32769, 65536, 65536, 131072}, 8192, 257, raw);
                    }
                }
            }
        }
    }

    /// Verifies long streams and transport fragmentation independently of the reference schedule.
    @Test
    void matchesLongStreamsAndFragmentation() throws Exception {
        byte[] input = new byte[4 * 1024 * 1024];
        new Random(1234).nextBytes(input);
        for (int profile = 0; profile < 3; profile++) {
            if (profile == 1) for (int i = 0; i < input.length; i++) input[i] &= 31;
            if (profile == 2) for (int i = 32768; i < input.length; i++) input[i] = input[i % 32768];
            for (int level : new int[]{0, 1, 3, 4, 6, 9}) {
                for (DeflateStrategy strategy : DeflateStrategy.values()) {
                    JdkDeflateAlignmentTest.verify(input, level, strategy, true, null, new int[0], 8192, 65536, false);
                }
            }
        }
        input = new byte[70013];
        new Random(7).nextBytes(input);
        for (int chunk : new int[]{1, 7, 257, 8192, 65536, input.length}) {
            for (int level : new int[]{0, 1, 6, 9}) {
                JdkDeflateAlignmentTest.verify(input, level, DeflateStrategy.DEFAULT, false, null,
                        new int[]{17, 65537}, chunk, 1, true);
            }
        }
    }
}

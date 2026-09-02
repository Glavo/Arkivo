// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.xxhash;

import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies Arkivo's XXH32 and XXH64 implementations against lz4-java's independent pure Java implementation.
@NotNullByDefault
public final class XXHashCompatibilityTest {
    /// The independent one-shot and streaming oracle factory.
    private static final XXHashFactory ORACLE = XXHashFactory.safeInstance();

    /// Input lengths around every lane and stripe transition used by either algorithm.
    private static final int @Unmodifiable [] BOUNDARY_LENGTHS = {
            0, 1, 3, 4, 7, 8, 15, 16, 17, 23, 31, 32, 33, 47, 63, 64, 65,
            127, 128, 129, 255, 256, 257, 4095, 4096, 65_535, 1 << 20
    };

    /// Verifies seeded array ranges across lane, stripe, and large-input boundaries.
    @Test
    public void seededArrayRangesMatchIndependentImplementation() {
        byte @Unmodifiable [] bytes = randomBytes((1 << 20) + 32, 0x7837_0789_134L);
        int @Unmodifiable [] seeds32 = {0, 1, -1, 0x9747_b28c};
        long @Unmodifiable [] seeds64 = {0L, 1L, -1L, 0x0123_4567_89ab_cdefL};

        for (int seed : seeds32) {
            XXHash32 algorithm = new XXHash32(seed);
            for (int length : BOUNDARY_LENGTHS) {
                int offset = length & 15;
                long expected = Integer.toUnsignedLong(ORACLE.hash32().hash(bytes, offset, length, seed));
                assertEquals(
                        expected,
                        algorithm.computeLong(bytes, offset, length),
                        "XXH32 seed " + seed + ", length " + length
                );
            }
        }

        for (long seed : seeds64) {
            XXHash64 algorithm = new XXHash64(seed);
            for (int length : BOUNDARY_LENGTHS) {
                int offset = length & 15;
                long expected = ORACLE.hash64().hash(bytes, offset, length, seed);
                assertEquals(
                        expected,
                        algorithm.computeLong(bytes, offset, length),
                        "XXH64 seed " + seed + ", length " + length
                );
            }
        }
    }

    /// Verifies fragmented array, direct-buffer, and read-only-buffer updates across partial stripes.
    @Test
    public void fragmentedUpdatesMatchIndependentStreamingImplementation() {
        byte @Unmodifiable [] bytes = randomBytes(262_177, 0x5378_91abL);
        int @Unmodifiable [] seeds32 = {0, 0x1357_9bdf, 0x8000_0000};
        long @Unmodifiable [] seeds64 = {0L, 0x1357_9bdf_2468_ace0L, Long.MIN_VALUE};

        for (int seed : seeds32) {
            ChecksumAccumulator.Width32 actual = new XXHash32(seed).newAccumulator();
            try (StreamingXXHash32 expected = ORACLE.newStreamingHash32(seed)) {
                updateFragmented(actual, expected, bytes);
                assertEquals(
                        Integer.toUnsignedLong(expected.getValue()),
                        actual.finishLong(),
                        "XXH32 seed " + seed
                );
            }
        }

        for (long seed : seeds64) {
            ChecksumAccumulator.Width64 actual = new XXHash64(seed).newAccumulator();
            try (StreamingXXHash64 expected = ORACLE.newStreamingHash64(seed)) {
                updateFragmented(actual, expected, bytes);
                assertEquals(expected.getValue(), actual.finishLong(), "XXH64 seed " + seed);
            }
        }
    }

    /// Feeds equivalent irregular fragments into an Arkivo and lz4-java XXH32 accumulator.
    private static void updateFragmented(
            ChecksumAccumulator.Width32 actual,
            StreamingXXHash32 expected,
            byte[] bytes
    ) {
        int position = 0;
        int fragmentIndex = 0;
        while (position < bytes.length) {
            int length = fragmentLength(position, fragmentIndex++, bytes.length);
            if ((fragmentIndex & 1) == 0) {
                actual.update(bytes, position, length);
            } else {
                actual.update(bufferFragment(bytes, position, length, fragmentIndex));
            }
            expected.update(bytes, position, length);
            position += length;
        }
    }

    /// Feeds equivalent irregular fragments into an Arkivo and lz4-java XXH64 accumulator.
    private static void updateFragmented(
            ChecksumAccumulator.Width64 actual,
            StreamingXXHash64 expected,
            byte[] bytes
    ) {
        int position = 0;
        int fragmentIndex = 0;
        while (position < bytes.length) {
            int length = fragmentLength(position, fragmentIndex++, bytes.length);
            if ((fragmentIndex & 1) == 0) {
                actual.update(bytes, position, length);
            } else {
                actual.update(bufferFragment(bytes, position, length, fragmentIndex));
            }
            expected.update(bytes, position, length);
            position += length;
        }
    }

    /// Creates an alternating direct or read-only heap buffer with nonzero surrounding offsets.
    private static ByteBuffer bufferFragment(byte[] bytes, int offset, int length, int fragmentIndex) {
        ByteBuffer buffer;
        if ((fragmentIndex & 3) == 1) {
            buffer = ByteBuffer.allocateDirect(length + 5);
        } else {
            buffer = ByteBuffer.allocate(length + 5);
        }
        buffer.position(3).put(bytes, offset, length).limit(length + 3).position(3);
        return (fragmentIndex & 3) == 3 ? buffer.asReadOnlyBuffer() : buffer;
    }

    /// Selects a deterministic positive fragment length that repeatedly crosses stripe boundaries.
    private static int fragmentLength(int position, int fragmentIndex, int totalLength) {
        int requested = 1 + Math.floorMod(position * 31 + fragmentIndex * 17, 8192);
        return Math.min(requested, totalLength - position);
    }

    /// Returns deterministic pseudo-random bytes.
    private static byte @Unmodifiable [] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}

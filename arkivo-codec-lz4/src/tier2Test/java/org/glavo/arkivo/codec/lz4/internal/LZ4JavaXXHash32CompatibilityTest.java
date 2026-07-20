// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.xxhash.XXHash32;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies Arkivo's seeded and streaming xxHash-32 against lz4-java 1.8.0's pure Java implementation.
@NotNullByDefault
public final class LZ4JavaXXHash32CompatibilityTest {
    /// The pure Java one-shot and streaming oracle factory.
    private static final XXHashFactory ORACLE = XXHashFactory.safeInstance();

    /// Verifies one-shot-equivalent range hashes across stripe and tail boundaries.
    @Test
    public void seededRangesMatchLZ4Java() {
        byte @Unmodifiable [] bytes = randomBytes((1 << 20) + 32, 0x7837_0789_134L);
        int @Unmodifiable [] seeds = {0, 1, -1, 0x9747_b28c};
        int @Unmodifiable [] lengths = {
                0, 1, 3, 4, 7, 15, 16, 17, 31, 32, 255, 4096, 65_535, 1 << 20
        };

        for (int seed : seeds) {
            for (int length : lengths) {
                int offset = length == bytes.length ? 0 : length & 15;
                ChecksumAccumulator.Width32 actual = new XXHash32(seed).newAccumulator();
                actual.update(bytes, offset, length);
                long expected = Integer.toUnsignedLong(
                        ORACLE.hash32().hash(bytes, offset, length, seed)
                );
                assertEquals(expected, actual.finishLong(), "seed " + seed + ", length " + length);
            }
        }
    }

    /// Verifies fragmented updates and resets against the streaming oracle.
    @Test
    public void fragmentedUpdatesAndResetsMatchLZ4Java() {
        byte @Unmodifiable [] bytes = randomBytes(262_177, 0x5378L);
        int @Unmodifiable [] seeds = {0, 0x1357_9bdf, 0x8000_0000};

        for (int seed : seeds) {
            ChecksumAccumulator.Width32 actual = new XXHash32(seed).newAccumulator();
            try (StreamingXXHash32 expected = ORACLE.newStreamingHash32(seed)) {
                for (int pass = 0; pass < 3; pass++) {
                    actual.reset();
                    expected.reset();
                    int position = 0;
                    while (position < bytes.length) {
                        int length = Math.min(
                                bytes.length - position,
                                1 + Math.floorMod(position * 31 + pass * 17, 8192)
                        );
                        actual.update(bytes, position, length);
                        expected.update(bytes, position, length);
                        position += length;
                    }
                    assertEquals(
                            Integer.toUnsignedLong(expected.getValue()),
                            actual.finishLong(),
                            "seed " + seed + ", pass " + pass
                    );
                }
            }
        }
    }

    /// Returns deterministic pseudo-random bytes.
    private static byte @Unmodifiable [] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}

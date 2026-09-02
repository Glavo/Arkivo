// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.xxhash;

import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies XXHash length accumulation with a logical input larger than eight GiB.
@NotNullByDefault
public final class XXHashLargeInputTest {
    /// Reusable source chunk size that bounds physical test memory.
    private static final int CHUNK_SIZE = 16 * 1024 * 1024;

    /// Logical input length required to cross both four-GiB and eight-GiB boundaries.
    private static final long TARGET_SIZE = (1L << 33) + CHUNK_SIZE;

    /// Verifies both widths against lz4-java's pure Java streaming implementations.
    @Test
    public void hashesMoreThanEightGiBWithoutLengthTruncation() {
        byte[] chunk = new byte[CHUNK_SIZE];
        new Random(0x4c5a_3401L).nextBytes(chunk);
        int seed32 = 0x9747_b28c;
        long seed64 = 0x0123_4567_89ab_cdefL;
        ChecksumAccumulator.Width32 actual32 = new XXHash32(seed32).newAccumulator();
        ChecksumAccumulator.Width64 actual64 = new XXHash64(seed64).newAccumulator();

        XXHashFactory oracle = XXHashFactory.safeInstance();
        try (StreamingXXHash32 expected32 = oracle.newStreamingHash32(seed32);
             StreamingXXHash64 expected64 = oracle.newStreamingHash64(seed64)) {
            long totalSize = 0L;
            while (totalSize < TARGET_SIZE) {
                actual32.update(chunk);
                actual64.update(chunk);
                expected32.update(chunk, 0, chunk.length);
                expected64.update(chunk, 0, chunk.length);
                totalSize += chunk.length;
            }
            assertEquals(
                    Integer.toUnsignedLong(expected32.getValue()),
                    actual32.finishLong(),
                    "XXH32 logical input size " + totalSize
            );
            assertEquals(
                    expected64.getValue(),
                    actual64.finishLong(),
                    "XXH64 logical input size " + totalSize
            );
        }
    }
}

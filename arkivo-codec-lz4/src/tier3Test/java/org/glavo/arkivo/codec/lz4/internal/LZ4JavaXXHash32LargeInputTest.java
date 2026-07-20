// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.XXHash32;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies xxHash-32 length rollover with a logical input larger than eight GiB.
@NotNullByDefault
public final class LZ4JavaXXHash32LargeInputTest {
    /// Reusable source chunk size that bounds physical test memory.
    private static final int CHUNK_SIZE = 16 * 1024 * 1024;

    /// Logical input length required to cross both four-GiB and eight-GiB boundaries.
    private static final long TARGET_SIZE = (1L << 33) + CHUNK_SIZE;

    /// Verifies a large-input result against lz4-java's pure Java streaming implementation.
    @Test
    public void hashesMoreThanEightGiBWithoutLengthTruncation() {
        byte[] chunk = new byte[CHUNK_SIZE];
        new Random(0x4c5a_3401L).nextBytes(chunk);
        int seed = 0x9747_b28c;
        ChecksumAccumulator.Width32 actual = new XXHash32(seed).newAccumulator();

        try (StreamingXXHash32 expected = XXHashFactory.safeInstance().newStreamingHash32(seed)) {
            long totalSize = 0L;
            while (totalSize < TARGET_SIZE) {
                actual.update(chunk);
                expected.update(chunk, 0, chunk.length);
                totalSize += chunk.length;
            }
            assertEquals(
                    Integer.toUnsignedLong(expected.getValue()),
                    actual.finishLong(),
                    "logical input size " + totalSize
            );
        }
    }
}

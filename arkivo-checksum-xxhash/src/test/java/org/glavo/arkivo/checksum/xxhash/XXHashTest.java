// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.xxhash;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumAlgorithm;
import org.glavo.arkivo.checksum.ChecksumValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies XXHash vectors, configuration, and accumulator lifecycle.
@NotNullByDefault
public final class XXHashTest {
    /// Verifies conventional zero-seeded XXH32 and XXH64 vectors.
    @Test
    public void defaultAlgorithmsMatchKnownVectors() {
        assertEquals(0x02cc_5d05L, XXHash32.DEFAULT.computeLong(new byte[0]));
        assertEquals(
                0x32d1_53ffL,
                XXHash32.DEFAULT.computeLong("abc".getBytes(StandardCharsets.US_ASCII))
        );
        assertEquals(0xef46_db37_51d8_e999L, XXHash64.DEFAULT.computeLong(new byte[0]));
        assertEquals(
                0x44bc_2cf5_ad77_0999L,
                XXHash64.DEFAULT.computeLong("abc".getBytes(StandardCharsets.US_ASCII))
        );
    }

    /// Verifies fragmented seeded updates and reset reuse.
    @Test
    public void seededConfigurationsCreateReusableIndependentState() {
        byte[] input = "0123456789abcdef".repeat(8).getBytes(StandardCharsets.US_ASCII);
        XXHash32 algorithm = new XXHash32(0x9747_b28c);
        ChecksumAccumulator.Width32 accumulator = algorithm.newAccumulator();
        for (int offset = 0; offset < input.length; offset += 7) {
            accumulator.update(input, offset, Math.min(7, input.length - offset));
        }
        assertEquals(algorithm.computeLong(input), accumulator.finishLong());

        accumulator.reset();
        ByteBuffer direct = ByteBuffer.allocateDirect(input.length + 4);
        direct.position(2).put(input).limit(input.length + 2).position(2);
        ByteBuffer readOnly = direct.asReadOnlyBuffer();
        accumulator.update(readOnly);
        assertEquals(readOnly.limit(), readOnly.position());
        assertEquals(algorithm.computeLong(input), accumulator.finishLong());

        assertSame(algorithm, algorithm.withSeed(algorithm.seed()));
        assertEquals(algorithm, new XXHash32(algorithm.seed()));
    }

    /// Verifies terminal finish behavior and explicit reset for both widths.
    @Test
    public void accumulatorsFollowTheChecksumLifecycle() {
        byte[] input = "xxhash lifecycle".getBytes(StandardCharsets.UTF_8);
        @Unmodifiable List<ChecksumAlgorithm> algorithms = List.of(XXHash32.DEFAULT, XXHash64.DEFAULT);

        for (ChecksumAlgorithm algorithm : algorithms) {
            ChecksumAccumulator accumulator = algorithm.newAccumulator();
            accumulator.update(input);
            ChecksumValue expected = accumulator.finish();
            assertEquals(expected, accumulator.finish(), algorithm.name());
            assertThrows(IllegalStateException.class, () -> accumulator.update((byte) 0), algorithm.name());

            accumulator.reset();
            accumulator.update(input);
            assertEquals(expected, accumulator.finish(), algorithm.name());
        }
    }
}

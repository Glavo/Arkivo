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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /// Verifies seeded algorithms expose complete immutable value semantics and diagnostic metadata.
    @Test
    public void seededAlgorithmsHaveValueSemantics() {
        XXHash32 hash32 = new XXHash32(-1);
        assertEquals(-1, hash32.seed());
        assertEquals("XXH32", hash32.name());
        assertEquals(Integer.SIZE, hash32.bitSize());
        assertEquals(Integer.BYTES, hash32.checksumSize());
        assertEquals("XXH32[seed=0xffffffff]", hash32.toString());
        assertTrue(hash32.equals(hash32));
        assertSame(hash32, hash32.withSeed(-1));
        assertEquals(hash32, new XXHash32(-1));
        assertEquals(hash32.hashCode(), new XXHash32(-1).hashCode());
        assertNotEquals(hash32, hash32.withSeed(0));
        assertNotEquals(hash32, XXHash64.DEFAULT);
        assertNotEquals(hash32, null);

        XXHash64 hash64 = new XXHash64(-1L);
        assertEquals(-1L, hash64.seed());
        assertEquals("XXH64", hash64.name());
        assertEquals(Long.SIZE, hash64.bitSize());
        assertEquals(Long.BYTES, hash64.checksumSize());
        assertEquals("XXH64[seed=0xffffffffffffffff]", hash64.toString());
        assertTrue(hash64.equals(hash64));
        assertSame(hash64, hash64.withSeed(-1L));
        assertEquals(hash64, new XXHash64(-1L));
        assertEquals(hash64.hashCode(), new XXHash64(-1L).hashCode());
        assertNotEquals(hash64, hash64.withSeed(0L));
        assertNotEquals(hash64, hash32);
        assertNotEquals(hash64, null);
    }

    /// Verifies single-byte updates and one-shot buffer methods cross every stripe boundary without retaining buffers.
    @Test
    public void byteUpdatesAndBufferComputationsCrossStripes() {
        byte[] input = new byte[97];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) (index * 37 + 11);
        }

        @Unmodifiable List<ChecksumAlgorithm.UpTo64Bits> algorithms = List.of(
                new XXHash32(0x1357_9bdf),
                new XXHash64(0x1357_9bdf_2468_ace0L)
        );
        for (ChecksumAlgorithm.UpTo64Bits algorithm : algorithms) {
            long expected = algorithm.computeLong(input);
            ChecksumAccumulator.UpTo64Bits accumulator = algorithm.newAccumulator();
            for (byte value : input) {
                accumulator.update(value);
            }
            assertEquals(expected, accumulator.finishLong(), algorithm.name());

            ByteBuffer direct = ByteBuffer.allocateDirect(input.length + 6);
            direct.position(3).put(input).limit(input.length + 3).position(3);
            ByteBuffer readOnly = direct.asReadOnlyBuffer();
            assertEquals(expected, algorithm.computeLong(readOnly), algorithm.name());
            assertEquals(readOnly.limit(), readOnly.position(), algorithm.name());

            ByteBuffer empty = ByteBuffer.allocate(4).position(2).limit(2);
            assertEquals(algorithm.computeLong(new byte[0]), algorithm.computeLong(empty), algorithm.name());
            assertEquals(empty.limit(), empty.position(), algorithm.name());
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.xxhash;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies XXHash static computations, seeded factories, and accumulator lifecycle.
@NotNullByDefault
public final class XXHashTest {
    /// Checks conventional zero-seeded XXH32 and XXH64 vectors.
    @Test
    public void staticComputationsMatchKnownVectors() {
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x02cc_5d05, XXHash32.computeInt(new byte[0]));
        assertEquals(0x32d1_53ff, XXHash32.computeInt(abc));
        assertEquals(0xef46_db37_51d8_e999L, XXHash64.computeLong(new byte[0]));
        assertEquals(0x44bc_2cf5_ad77_0999L, XXHash64.computeLong(abc));
        byte[] padded = "xabcx".getBytes(StandardCharsets.US_ASCII);
        assertEquals(XXHash32.computeInt(abc), XXHash32.computeInt(padded, 1, 3));
        assertEquals(XXHash64.computeLong(abc), XXHash64.computeLong(padded, 1, 3));
        assertEquals(XXHash32.computeInt(abc), XXHash32.computeInt(ByteBuffer.wrap(abc)));
        assertEquals(XXHash64.computeLong(abc), XXHash64.computeLong(ByteBuffer.wrap(abc)));
        var first = XXHash32.newAccumulator();
        first.update(abc);
        assertEquals(XXHash32.computeInt(abc), first.finishInt());
        var second = XXHash64.newAccumulator();
        second.update(abc);
        assertEquals(XXHash64.computeLong(abc), second.finishLong());
    }

    /// Factories create independent state, and resets restore the original seed after any completed or partial input.
    @Test
    public void seededFactoriesCreateIndependentReusableState() {
        @Unmodifiable List<Supplier<ChecksumAccumulator.UpTo64Bits>> factories = List.of(
                () -> XXHash32.newAccumulator(0x9747_b28c),
                () -> XXHash64.newAccumulator(0x1357_9bdf_2468_ace0L));
        byte[] input = "0123456789abcdef".repeat(8).getBytes(StandardCharsets.US_ASCII);
        for (var factory : factories) {
            var first = factory.get();
            var second = factory.get();
            assertNotSame(first, second);
            assertEquals(first.bitSize() / 8, first.checksumSize());
            second.update(input);
            long expected = second.finishLong();
            for (int offset = 0; offset < input.length; offset += 7) {
                first.update(input, offset, Math.min(7, input.length - offset));
            }
            assertEquals(expected, first.finishLong());
            ChecksumValue snapshot = first.finish();
            assertEquals(snapshot, first.finish());
            assertThrows(IllegalStateException.class, () -> first.update((byte) 0));
            assertThrows(IllegalStateException.class, () -> first.update(new byte[0]));
            first.reset();
            first.update((byte) 99);
            first.reset();
            for (byte value : input) {
                first.update(value);
            }
            assertEquals(snapshot, first.finish());
            assertEquals(expected, second.finishLong());
        }
    }

    /// Seeded buffer computations consume only the selected range and agree with static array computations.
    @Test
    public void seededBuffersCrossStripeBoundaries() {
        byte[] input = new byte[97];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) (index * 37 + 11);
        }
        for (int seed32 : new int[]{0, -1, Integer.MIN_VALUE, 0x1357_9bdf}) {
            long seed64 = (long) seed32 << 32 | Integer.toUnsignedLong(seed32);
            int expected32 = XXHash32.computeInt(input, seed32);
            long expected64 = XXHash64.computeLong(input, seed64);
            ByteBuffer parent = ByteBuffer.allocateDirect(input.length + 6);
            parent.position(3).put(input).limit(input.length + 3).position(3);
            ByteBuffer source = parent.asReadOnlyBuffer();
            assertEquals(expected32, XXHash32.computeInt(source, seed32));
            assertEquals(source.limit(), source.position());
            source.position(3);
            assertEquals(expected64, XXHash64.computeLong(source, seed64));
            assertEquals(source.limit(), source.position());
            assertEquals(3, parent.position());
            var accumulator32 = XXHash32.newAccumulator(seed32);
            var accumulator64 = XXHash64.newAccumulator(seed64);
            accumulator32.update(parent.duplicate());
            accumulator64.update(parent.duplicate());
            parent.clear();
            while (parent.hasRemaining()) {
                parent.put((byte) 0);
            }
            assertEquals(expected32, accumulator32.finishInt());
            assertEquals(Integer.toUnsignedLong(expected32), accumulator32.finishLong());
            assertEquals(expected64, accumulator64.finishLong());
            assertEquals("XXH32", accumulator32.name());
            assertEquals("XXH64", accumulator64.name());
            ByteBuffer empty = ByteBuffer.allocate(4).position(2).limit(2);
            assertEquals(XXHash32.computeInt(new byte[0], seed32), XXHash32.computeInt(empty, seed32));
            assertEquals(XXHash64.computeLong(new byte[0], seed64), XXHash64.computeLong(empty, seed64));
            assertEquals(2, empty.position());
        }
    }

    /// Invalid static inputs and rejected updates do not corrupt an existing seeded computation.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void validatesInputsWithoutDiscardingProgress() {
        assertThrows(NullPointerException.class, () -> XXHash32.computeInt((byte[]) null));
        assertThrows(NullPointerException.class, () -> XXHash64.computeLong((ByteBuffer) null));
        byte[] input = {1, 2, 3};
        assertThrows(IndexOutOfBoundsException.class, () -> XXHash32.computeInt(input, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> XXHash64.computeLong(input, 1, Integer.MAX_VALUE));
        var first = XXHash32.newAccumulator(-1);
        var second = XXHash64.newAccumulator(-1);
        first.update((byte) 1);
        second.update((byte) 1);
        assertThrows(IndexOutOfBoundsException.class, () -> first.update(input, 2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> second.update(input, -1, 1));
        first.update(input, 1, 2);
        second.update(input, 1, 2);
        assertEquals(XXHash32.computeInt(input, -1), first.finishInt());
        assertEquals(XXHash64.computeLong(input, -1), second.finishLong());
    }
}

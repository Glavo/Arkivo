// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumValue;
import org.glavo.arkivo.checksum.xxhash.XXHash32;
import org.glavo.arkivo.checksum.xxhash.XXHash64;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the common accumulator lifecycle and buffer contracts.
@NotNullByDefault
public final class ChecksumLifecycleTest {
    /// Returns the built-in factories sharing the accumulator lifecycle.
    private static Stream<Supplier<ChecksumAccumulator>> factories() {
        return Stream.of(XXHash32::newAccumulator, () -> XXHash32.newAccumulator(-1),
                XXHash64::newAccumulator, () -> XXHash64.newAccumulator(-1L));
    }

    /// Verifies finish idempotence, terminal updates, and explicit reset for every tested built-in factory.
    @ParameterizedTest
    @MethodSource("factories")
    public void finishIsIdempotentAndResetRestoresActiveState(Supplier<ChecksumAccumulator> factory) {
        byte[] input = "checksum lifecycle".getBytes(StandardCharsets.UTF_8);
        ChecksumAccumulator accumulator = factory.get();
        accumulator.update(input);
        ChecksumValue expected = accumulator.finish();
        assertEquals(expected, accumulator.finish(), factory.get().name());
        assertThrows(IllegalStateException.class, () -> accumulator.update((byte) 0), factory.get().name());
        assertThrows(IllegalStateException.class, () -> accumulator.update(input), factory.get().name());
        assertThrows(
                IllegalStateException.class,
                () -> accumulator.update(ByteBuffer.wrap(input)),
                factory.get().name()
        );

        accumulator.reset();
        accumulator.update(input);
        assertEquals(expected, accumulator.finish(), factory.get().name());

        ByteBuffer direct = ByteBuffer.allocateDirect(input.length);
        direct.put(input).flip();
        ByteBuffer readOnly = direct.asReadOnlyBuffer();
        accumulator.reset();
        accumulator.update(readOnly);
        assertEquals(readOnly.limit(), readOnly.position(), factory.get().name());
        assertEquals(expected, accumulator.finish(), factory.get().name());
    }

    /// Verifies a rejected finish target leaves the accumulator active.
    @ParameterizedTest
    @MethodSource("factories")
    public void finishTargetIsValidatedBeforeTerminalTransition(Supplier<ChecksumAccumulator> factory) {
        ChecksumAccumulator accumulator = factory.get();
        accumulator.update((byte) 1);
        ByteBuffer undersized = ByteBuffer.allocate(factory.get().checksumSize() + 3);
        undersized.position(4);
        assertThrows(BufferOverflowException.class, () -> accumulator.finish(undersized));
        assertEquals(4, undersized.position());
        ByteBuffer readOnly = ByteBuffer.allocate(factory.get().checksumSize()).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> accumulator.finish(readOnly));
        assertEquals(0, readOnly.position());
        accumulator.update((byte) 2);
        ChecksumValue expected = compute(factory, new byte[]{1, 2});
        ByteBuffer target = ByteBuffer.allocateDirect(factory.get().checksumSize() + 6)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < target.capacity(); index++) {
            target.put(index, (byte) 0x5a);
        }
        target.position(3).limit(3 + factory.get().checksumSize());
        accumulator.finish(target);
        assertEquals(target.limit(), target.position());
        assertEquals(3 + factory.get().checksumSize(), target.limit());
        assertEquals(expected, ChecksumValue.ofBytes(target.duplicate().position(3)));
        ByteBuffer storage = target.duplicate().clear();
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, storage.get(index));
            assertEquals((byte) 0x5a, storage.get(3 + factory.get().checksumSize() + index));
        }
        target.position(3);
        accumulator.finish(target);
        assertEquals(expected, accumulator.finish());
        assertThrows(IllegalStateException.class, () -> accumulator.update((byte) 3));
    }

    /// Verifies empty updates still obey the finished state and do not consume rejected input.
    @ParameterizedTest
    @MethodSource("factories")
    public void emptyUpdatesObeyLifecycle(Supplier<ChecksumAccumulator> factory) {
        ChecksumAccumulator accumulator = factory.get();
        byte[] input = {1, 2, 3};
        ByteBuffer empty = ByteBuffer.allocateDirect(7).position(7).asReadOnlyBuffer();
        accumulator.update(input, input.length, 0);
        accumulator.update(empty);
        ChecksumValue expected = compute(factory, new byte[0]);
        assertEquals(expected, accumulator.finish());
        assertThrows(IllegalStateException.class, () -> accumulator.update(new byte[0]));
        assertThrows(IllegalStateException.class, () -> accumulator.update(input, input.length, 0));
        assertThrows(IllegalStateException.class, () -> accumulator.update(empty));
        ByteBuffer nonempty = ByteBuffer.wrap(input).position(1).mark();
        assertThrows(IllegalStateException.class, () -> accumulator.update(nonempty));
        assertEquals(1, nonempty.position());
        assertEquals(3, nonempty.limit());
        assertEquals(1, nonempty.reset().position());
        assertEquals(7, empty.position());
        assertEquals(expected, accumulator.finish());
    }

    /// Verifies invalid array ranges leave existing progress usable, including overflowing ranges.
    @ParameterizedTest
    @MethodSource("factories")
    public void invalidUpdatesPreserveAccumulatedBytes(Supplier<ChecksumAccumulator> factory) {
        ChecksumAccumulator accumulator = factory.get();
        byte[] input = {1, 2, 3};
        accumulator.update(input[0]);
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, 2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, Integer.MAX_VALUE, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, 1, Integer.MAX_VALUE));
        accumulator.update(input, 1, 2);
        assertEquals(compute(factory, input), accumulator.finish());
    }

    /// Verifies reset discards partial progress without changing completed values or other accumulators.
    @ParameterizedTest
    @MethodSource("factories")
    public void resetAndIndependentAccumulatorsDoNotAlterSnapshots(Supplier<ChecksumAccumulator> factory) {
        byte[] firstInput = {1, 2, 3};
        byte[] secondInput = {4, 5};
        ChecksumValue firstExpected = compute(factory, firstInput);
        ChecksumValue secondExpected = compute(factory, secondInput);
        ChecksumAccumulator first = factory.get();
        ChecksumAccumulator second = factory.get();
        first.update(firstInput);
        second.update(secondInput[0]);
        ChecksumValue snapshot = first.finish();
        ByteBuffer view = snapshot.toByteBuffer();
        first.reset();
        first.update((byte) 99);
        first.reset();
        first.reset();
        first.update(secondInput);
        second.update(secondInput[1]);
        assertEquals(secondExpected, first.finish());
        assertEquals(secondExpected, second.finish());
        assertEquals(firstExpected, snapshot);
        assertEquals(firstExpected, ChecksumValue.ofBytes(view));
    }

    /// Verifies mixed update forms and sliced-buffer boundaries produce the same checksum as contiguous input.
    @ParameterizedTest
    @MethodSource("factories")
    public void fragmentedBuffersPreserveRangesAndPositions(Supplier<ChecksumAccumulator> factory) {
        int @Unmodifiable [] lengths = {0, 1, 55, 56, 63, 64, 65, 127, 128, 129, 5551, 5552, 5553, 8193};
        byte[] input = new byte[8193];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) (index * 37 + (index >>> 3));
        }
        for (int length : lengths) {
            ChecksumAccumulator accumulator = factory.get();
            int offset = 0;
            int fragment = 0;
            while (offset < length) {
                int count = Math.min(length - offset, 1 + (fragment * 31) % 131);
                if ((fragment & 3) == 0) {
                    for (int index = offset; index < offset + count; index++) {
                        accumulator.update(input[index]);
                    }
                } else if ((fragment & 3) == 1) {
                    accumulator.update(input, offset, count);
                } else {
                    ByteBuffer parent = (fragment & 3) == 2
                            ? ByteBuffer.allocateDirect(count + 13) : ByteBuffer.allocate(count + 13);
                    parent.position(7).put(input, offset, count).limit(7 + count).position(3);
                    ByteBuffer slice = parent.slice().position(4).asReadOnlyBuffer();
                    slice.order(ByteOrder.LITTLE_ENDIAN).mark();
                    accumulator.update(slice);
                    assertEquals(4 + count, slice.position());
                    assertEquals(4 + count, slice.limit());
                    assertEquals(4, slice.reset().position());
                    assertEquals(3, parent.position());
                    assertEquals(7 + count, parent.limit());
                    // Reusing caller storage must not change bytes already included in the checksum.
                    parent.clear();
                    while (parent.hasRemaining()) {
                        parent.put((byte) 0);
                    }
                }
                offset += count;
                fragment++;
            }
            assertEquals(compute(factory, Arrays.copyOf(input, length)), accumulator.finish(), "length " + length);
        }
    }

    /// Computes an expected value using an independent accumulator.
    private static ChecksumValue compute(Supplier<ChecksumAccumulator> factory, byte[] source) {
        var accumulator = factory.get();
        accumulator.update(source);
        return accumulator.finish();
    }

    /// Verifies raw signed and unsigned primitive views of the same completed computation.
    @Test
    public void width32ExposesRawIntAndUnsignedLongForms() {
        var accumulator = XXHash32.newAccumulator();
        accumulator.update("123456789".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0x937b_ad67, accumulator.finishInt());
        assertEquals(0x937b_ad67L, accumulator.finishLong());
        assertEquals(ChecksumValue.ofInt(0x937b_ad67), accumulator.finish());
    }
}

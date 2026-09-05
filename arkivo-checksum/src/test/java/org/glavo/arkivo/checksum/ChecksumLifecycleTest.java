// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the common accumulator lifecycle and buffer contracts.
@NotNullByDefault
public final class ChecksumLifecycleTest {
    /// Returns the built-in algorithms sharing the accumulator lifecycle.
    private static Stream<ChecksumAlgorithm> algorithms() {
        return Stream.of(Checksums.ADLER32, Checksums.CRC32, Checksums.CRC32C, Checksums.SHA256);
    }

    /// Verifies finish idempotence, terminal updates, and explicit reset for every core built-in algorithm.
    @ParameterizedTest
    @MethodSource("algorithms")
    public void finishIsIdempotentAndResetRestoresActiveState(ChecksumAlgorithm algorithm) {
        byte[] input = "checksum lifecycle".getBytes(StandardCharsets.UTF_8);
        ChecksumAccumulator accumulator = algorithm.newAccumulator();
        accumulator.update(input);
        ChecksumValue expected = accumulator.finish();
        assertEquals(expected, accumulator.finish(), algorithm.name());
        assertThrows(IllegalStateException.class, () -> accumulator.update((byte) 0), algorithm.name());
        assertThrows(IllegalStateException.class, () -> accumulator.update(input), algorithm.name());
        assertThrows(
                IllegalStateException.class,
                () -> accumulator.update(ByteBuffer.wrap(input)),
                algorithm.name()
        );

        accumulator.reset();
        accumulator.update(input);
        assertEquals(expected, accumulator.finish(), algorithm.name());

        ByteBuffer direct = ByteBuffer.allocateDirect(input.length);
        direct.put(input).flip();
        ByteBuffer readOnly = direct.asReadOnlyBuffer();
        accumulator.reset();
        accumulator.update(readOnly);
        assertEquals(readOnly.limit(), readOnly.position(), algorithm.name());
        assertEquals(expected, accumulator.finish(), algorithm.name());
    }

    /// Verifies one-shot source consumption and target prevalidation.
    @ParameterizedTest
    @MethodSource("algorithms")
    public void bufferComputationConsumesSourceOnlyAfterTargetValidation(ChecksumAlgorithm algorithm) {
        byte[] input = "buffer contract".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer source = ByteBuffer.wrap(input).asReadOnlyBuffer();
        source.position(2);
        int originalPosition = source.position();

        ByteBuffer undersized = ByteBuffer.allocate(algorithm.checksumSize() - 1);
        assertThrows(BufferOverflowException.class, () -> algorithm.compute(source, undersized));
        assertEquals(originalPosition, source.position());
        assertEquals(0, undersized.position());

        ByteBuffer readOnlyTarget = ByteBuffer.allocate(algorithm.checksumSize()).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> algorithm.compute(source, readOnlyTarget));
        assertEquals(originalPosition, source.position());
        assertEquals(0, readOnlyTarget.position());

        ByteBuffer target = ByteBuffer.allocate(algorithm.checksumSize() + 6);
        target.position(3);
        algorithm.compute(source, target);
        assertEquals(source.limit(), source.position());
        assertEquals(3 + algorithm.checksumSize(), target.position());
        assertEquals(
                algorithm.compute(ByteBuffer.wrap(input, originalPosition, input.length - originalPosition)),
                ChecksumValue.ofBytes(target.flip().position(3))
        );
    }

    /// Verifies a rejected finish target leaves the accumulator active.
    @ParameterizedTest
    @MethodSource("algorithms")
    public void finishTargetIsValidatedBeforeTerminalTransition(ChecksumAlgorithm algorithm) {
        ChecksumAccumulator accumulator = algorithm.newAccumulator();
        accumulator.update((byte) 1);
        ByteBuffer undersized = ByteBuffer.allocate(algorithm.checksumSize() + 3);
        undersized.position(4);
        assertThrows(BufferOverflowException.class, () -> accumulator.finish(undersized));
        assertEquals(4, undersized.position());
        ByteBuffer readOnly = ByteBuffer.allocate(algorithm.checksumSize()).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> accumulator.finish(readOnly));
        assertEquals(0, readOnly.position());
        accumulator.update((byte) 2);
        ChecksumValue expected = algorithm.compute(new byte[]{1, 2});
        ByteBuffer target = ByteBuffer.allocateDirect(algorithm.checksumSize() + 6)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < target.capacity(); index++) {
            target.put(index, (byte) 0x5a);
        }
        target.position(3).limit(3 + algorithm.checksumSize());
        accumulator.finish(target);
        assertEquals(target.limit(), target.position());
        assertEquals(3 + algorithm.checksumSize(), target.limit());
        assertEquals(expected, ChecksumValue.ofBytes(target.duplicate().position(3)));
        ByteBuffer storage = target.duplicate().clear();
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, storage.get(index));
            assertEquals((byte) 0x5a, storage.get(3 + algorithm.checksumSize() + index));
        }
        target.position(3);
        accumulator.finish(target);
        assertEquals(expected, accumulator.finish());
        assertThrows(IllegalStateException.class, () -> accumulator.update((byte) 3));
    }

    /// Verifies empty updates still obey the finished state and do not consume rejected input.
    @ParameterizedTest
    @MethodSource("algorithms")
    public void emptyUpdatesObeyLifecycle(ChecksumAlgorithm algorithm) {
        ChecksumAccumulator accumulator = algorithm.newAccumulator();
        byte[] input = {1, 2, 3};
        ByteBuffer empty = ByteBuffer.allocateDirect(7).position(7).asReadOnlyBuffer();
        accumulator.update(input, input.length, 0);
        accumulator.update(empty);
        ChecksumValue expected = algorithm.compute(new byte[0]);
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
    @MethodSource("algorithms")
    public void invalidUpdatesPreserveAccumulatedBytes(ChecksumAlgorithm algorithm) {
        ChecksumAccumulator accumulator = algorithm.newAccumulator();
        byte[] input = {1, 2, 3};
        accumulator.update(input[0]);
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, 2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, Integer.MAX_VALUE, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(input, 1, Integer.MAX_VALUE));
        accumulator.update(input, 1, 2);
        assertEquals(algorithm.compute(input), accumulator.finish());
    }

    /// Verifies reset discards partial progress without changing completed values or other accumulators.
    @ParameterizedTest
    @MethodSource("algorithms")
    public void resetAndIndependentAccumulatorsDoNotAlterSnapshots(ChecksumAlgorithm algorithm) {
        byte[] firstInput = {1, 2, 3};
        byte[] secondInput = {4, 5};
        ChecksumValue firstExpected = algorithm.compute(firstInput);
        ChecksumValue secondExpected = algorithm.compute(secondInput);
        ChecksumAccumulator first = algorithm.newAccumulator();
        ChecksumAccumulator second = algorithm.newAccumulator();
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
    @MethodSource("algorithms")
    public void fragmentedBuffersPreserveRangesAndPositions(ChecksumAlgorithm algorithm) {
        int @Unmodifiable [] lengths = {0, 1, 55, 56, 63, 64, 65, 127, 128, 129, 5551, 5552, 5553, 8193};
        byte[] input = new byte[8193];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) (index * 37 + (index >>> 3));
        }
        for (int length : lengths) {
            ChecksumAccumulator accumulator = algorithm.newAccumulator();
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
            assertEquals(algorithm.compute(Arrays.copyOf(input, length)), accumulator.finish(), "length " + length);
        }
    }

    /// Verifies 32-bit primitive methods preserve raw and unsigned forms.
    @Test
    public void width32ExposesRawIntAndUnsignedLongForms() {
        int raw = Checksums.CRC32.computeInt("123456789".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0xcbf4_3926, raw);
        assertEquals(0xcbf4_3926L, Checksums.CRC32.computeLong("123456789".getBytes(StandardCharsets.US_ASCII)));

        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        assertEquals(raw, Checksums.CRC32.computeInt(padded, 2, 9));
        assertEquals(Integer.toUnsignedLong(raw), Checksums.CRC32.computeLong(padded, 2, 9));
        assertEquals(ChecksumValue.ofInt(raw), Checksums.CRC32.compute(padded, 2, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> Checksums.CRC32.computeInt(padded, -1, 1));

        ByteBuffer direct = ByteBuffer.allocateDirect(padded.length + 4);
        direct.position(2).put(padded).limit(13).position(4);
        ByteBuffer readOnly = direct.asReadOnlyBuffer();
        assertEquals(raw, Checksums.CRC32.computeInt(readOnly));
        assertEquals(readOnly.limit(), readOnly.position());

        direct.position(4);
        assertEquals(Integer.toUnsignedLong(raw), Checksums.CRC32.computeLong(direct));
        assertEquals(direct.limit(), direct.position());
    }
}

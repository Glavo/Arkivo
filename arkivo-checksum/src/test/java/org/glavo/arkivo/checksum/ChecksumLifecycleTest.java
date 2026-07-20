// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the common accumulator lifecycle and buffer contracts.
@NotNullByDefault
public final class ChecksumLifecycleTest {
    /// Verifies finish idempotence, terminal updates, and explicit reset for every core built-in algorithm.
    @Test
    public void finishIsIdempotentAndResetRestoresActiveState() {
        byte[] input = "checksum lifecycle".getBytes(StandardCharsets.UTF_8);
        List<ChecksumAlgorithm> algorithms = List.of(
                Checksums.ADLER32,
                Checksums.CRC32,
                Checksums.CRC32C,
                Checksums.SHA256
        );

        for (ChecksumAlgorithm algorithm : algorithms) {
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
    }

    /// Verifies one-shot source consumption and target prevalidation.
    @Test
    public void bufferComputationConsumesSourceOnlyAfterTargetValidation() {
        byte[] input = "buffer contract".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer source = ByteBuffer.wrap(input).asReadOnlyBuffer();
        source.position(2);
        int originalPosition = source.position();

        ByteBuffer undersized = ByteBuffer.allocate(Checksums.CRC32.checksumSize() - 1);
        assertThrows(BufferOverflowException.class, () -> Checksums.CRC32.compute(source, undersized));
        assertEquals(originalPosition, source.position());
        assertEquals(0, undersized.position());

        ByteBuffer readOnlyTarget = ByteBuffer.allocate(Integer.BYTES).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> Checksums.CRC32.compute(source, readOnlyTarget));
        assertEquals(originalPosition, source.position());
        assertEquals(0, readOnlyTarget.position());

        ByteBuffer target = ByteBuffer.allocate(10);
        target.position(3);
        Checksums.CRC32.compute(source, target);
        assertEquals(source.limit(), source.position());
        assertEquals(3 + Integer.BYTES, target.position());
        assertEquals(
                Checksums.CRC32.compute(ByteBuffer.wrap(input, originalPosition, input.length - originalPosition)),
                ChecksumValue.ofBytes(target.flip().position(3))
        );
    }

    /// Verifies a rejected finish target leaves the accumulator active.
    @Test
    public void finishTargetIsValidatedBeforeTerminalTransition() {
        ChecksumAccumulator.Width32 accumulator = Checksums.CRC32.newAccumulator();
        accumulator.update((byte) 1);
        assertThrows(BufferOverflowException.class, () -> accumulator.finish(ByteBuffer.allocate(3)));
        accumulator.update((byte) 2);
        assertEquals(Checksums.CRC32.computeLong(new byte[]{1, 2}), accumulator.finishLong());
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
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the format-local XZ CRC-64 implementation.
@NotNullByDefault
public final class CRC64XZAlgorithmTest {
    /// Verifies the canonical CRC-64/XZ check value.
    @Test
    public void matchesCanonicalCheckValue() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x995d_c9bb_df19_39faL, CRC64XZAlgorithm.INSTANCE.computeLong(input));
    }

    /// Verifies algorithm metadata, independent state, byte updates, terminal idempotence, and reset.
    @Test
    public void exposesReusablePrimitiveAccumulator() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        ChecksumAccumulator.Width64 first = CRC64XZAlgorithm.INSTANCE.newAccumulator();
        ChecksumAccumulator.Width64 second = CRC64XZAlgorithm.INSTANCE.newAccumulator();

        assertEquals("CRC-64/XZ", CRC64XZAlgorithm.INSTANCE.name());
        assertEquals("CRC-64/XZ", CRC64XZAlgorithm.INSTANCE.toString());
        assertSame(CRC64XZAlgorithm.INSTANCE, first.algorithm());
        assertNotSame(first, second);
        assertEquals(0L, second.finishLong());

        for (byte value : input) {
            first.update(value);
        }
        assertEquals(0x995d_c9bb_df19_39faL, first.finishLong());
        assertEquals(0x995d_c9bb_df19_39faL, first.finishLong());
        assertThrows(IllegalStateException.class, () -> first.update((byte) 0));

        first.reset();
        byte[] padded = new byte[input.length + 2];
        System.arraycopy(input, 0, padded, 1, input.length);
        first.update(padded, 1, input.length);
        assertEquals(0x995d_c9bb_df19_39faL, first.finishLong());
    }

    /// Verifies direct and read-only buffers are consumed from their current positions without changing their limits.
    @Test
    public void consumesRemainingByteBufferRanges() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] padded = new byte[input.length + 2];
        padded[0] = 11;
        System.arraycopy(input, 0, padded, 1, input.length);
        padded[padded.length - 1] = 22;

        ByteBuffer direct = ByteBuffer.allocateDirect(padded.length).put(padded).flip();
        direct.position(1).limit(1 + input.length);
        int directLimit = direct.limit();
        ChecksumAccumulator.Width64 directAccumulator = CRC64XZAlgorithm.INSTANCE.newAccumulator();
        directAccumulator.update(direct);
        assertEquals(directLimit, direct.position());
        assertEquals(directLimit, direct.limit());
        assertEquals(0x995d_c9bb_df19_39faL, directAccumulator.finishLong());

        ByteBuffer readOnly = ByteBuffer.wrap(padded).asReadOnlyBuffer();
        readOnly.position(1).limit(1 + input.length);
        int readOnlyLimit = readOnly.limit();
        ChecksumAccumulator.Width64 readOnlyAccumulator = CRC64XZAlgorithm.INSTANCE.newAccumulator();
        readOnlyAccumulator.update(readOnly);
        assertEquals(readOnlyLimit, readOnly.position());
        assertEquals(readOnlyLimit, readOnly.limit());
        assertEquals(0x995d_c9bb_df19_39faL, readOnlyAccumulator.finishLong());

        ChecksumAccumulator.Width64 emptyAccumulator = CRC64XZAlgorithm.INSTANCE.newAccumulator();
        emptyAccumulator.update(ByteBuffer.allocateDirect(0));
        assertEquals(0L, emptyAccumulator.finishLong());
    }

    /// Verifies nulls, invalid ranges, and updates after completion are rejected without implicit reset.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void validatesUpdateArgumentsAndLifecycle() {
        ChecksumAccumulator.Width64 accumulator = CRC64XZAlgorithm.INSTANCE.newAccumulator();
        byte[] source = {1, 2, 3};

        assertThrows(NullPointerException.class, () -> accumulator.update((byte[]) null));
        assertThrows(NullPointerException.class, () -> accumulator.update(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(source, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(source, 0, 4));
        assertThrows(NullPointerException.class, () -> accumulator.update((ByteBuffer) null));

        assertEquals(0L, accumulator.finishLong());
        assertThrows(IllegalStateException.class, () -> accumulator.update((byte) 0));
        assertThrows(IllegalStateException.class, () -> accumulator.update(source, 0, 0));
        assertThrows(IllegalStateException.class, () -> accumulator.update(ByteBuffer.allocate(0)));

        accumulator.reset();
        accumulator.update(source, 0, 0);
        assertEquals(0L, accumulator.finishLong());
    }
}

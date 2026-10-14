// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the format-local BZip2 block CRC implementation.
@NotNullByDefault
public final class BZip2CRC32Test {
    /// Verifies the canonical CRC-32/BZIP2 check value.
    @Test
    public void matchesCanonicalCheckValue() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        var accumulator = new BZip2CRC32();
        accumulator.update(input);
        assertEquals(0xfc89_1918L, accumulator.finishLong());
    }

    /// Verifies algorithm metadata, independent state, byte updates, terminal idempotence, and reset.
    @Test
    public void exposesReusablePrimitiveAccumulator() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        ChecksumAccumulator.Width32 first = new BZip2CRC32();
        ChecksumAccumulator.Width32 second = new BZip2CRC32();

        assertEquals("CRC-32/BZIP2", first.name());
        assertNotSame(first, second);
        assertEquals(0L, second.finishLong());

        for (byte value : input) {
            first.update(value);
        }
        assertEquals(0xfc89_1918, first.finishInt());
        assertEquals(0xfc89_1918L, first.finishLong());
        assertThrows(IllegalStateException.class, () -> first.update((byte) 0));

        first.reset();
        byte[] padded = new byte[input.length + 2];
        System.arraycopy(input, 0, padded, 1, input.length);
        first.update(padded, 1, input.length);
        assertEquals(0xfc89_1918L, first.finishLong());
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
        ChecksumAccumulator.Width32 directAccumulator = new BZip2CRC32();
        directAccumulator.update(direct);
        assertEquals(directLimit, direct.position());
        assertEquals(directLimit, direct.limit());
        assertEquals(0xfc89_1918L, directAccumulator.finishLong());

        ByteBuffer readOnly = ByteBuffer.wrap(padded).asReadOnlyBuffer();
        readOnly.position(1).limit(1 + input.length);
        int readOnlyLimit = readOnly.limit();
        ChecksumAccumulator.Width32 readOnlyAccumulator = new BZip2CRC32();
        readOnlyAccumulator.update(readOnly);
        assertEquals(readOnlyLimit, readOnly.position());
        assertEquals(readOnlyLimit, readOnly.limit());
        assertEquals(0xfc89_1918L, readOnlyAccumulator.finishLong());

        ChecksumAccumulator.Width32 emptyAccumulator = new BZip2CRC32();
        emptyAccumulator.update(ByteBuffer.allocateDirect(0));
        assertEquals(0L, emptyAccumulator.finishLong());
    }

    /// Verifies nulls, invalid ranges, and updates after completion are rejected without implicit reset.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void validatesUpdateArgumentsAndLifecycle() {
        ChecksumAccumulator.Width32 accumulator = new BZip2CRC32();
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

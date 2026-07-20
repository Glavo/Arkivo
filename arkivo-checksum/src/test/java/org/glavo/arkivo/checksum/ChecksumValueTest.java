// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable checksum value representation and copying behavior.
@NotNullByDefault
public final class ChecksumValueTest {
    /// Verifies factories own canonical bytes and expose only isolated or read-only storage.
    @Test
    public void factoriesDoNotAliasCallerStorage() {
        byte[] bytes = {0x01, 0x23, (byte) 0xff};
        ChecksumValue fromArray = ChecksumValue.ofBytes(bytes);
        bytes[0] = 0;
        assertEquals("0123ff", fromArray.toHexString());

        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 8, 7, 6});
        source.position(1).limit(3);
        ChecksumValue fromBuffer = ChecksumValue.ofBytes(source);
        assertEquals(1, source.position());
        assertEquals("0807", fromBuffer.toString());

        byte[] copy = fromArray.toByteArray();
        copy[0] = 0;
        assertEquals("0123ff", fromArray.toHexString());
        assertTrue(fromArray.toByteBuffer().isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> fromArray.toByteBuffer().put((byte) 0));
    }

    /// Verifies numeric factories use canonical big-endian bytes and preserve bit patterns.
    @Test
    public void numericFactoriesUseCanonicalBigEndianRepresentation() {
        ChecksumValue intValue = ChecksumValue.ofInt(0x89ab_cdef);
        assertEquals("89abcdef", intValue.toHexString());
        assertEquals(0x89ab_cdefL, intValue.longValue());

        ChecksumValue longValue = ChecksumValue.ofLong(0xfedc_ba98_7654_3210L, Long.BYTES);
        assertEquals("fedcba9876543210", longValue.toHexString());
        assertEquals(0xfedc_ba98_7654_3210L, longValue.longValue());
        assertThrows(IllegalArgumentException.class, () -> ChecksumValue.ofLong(0x100L, 1));
        assertThrows(IllegalArgumentException.class, () -> ChecksumValue.ofLong(0L, 0));
    }

    /// Verifies writes are atomic with respect to validation and preserve target limits.
    @Test
    public void writesValidateBeforeChangingTargetPosition() {
        ChecksumValue value = ChecksumValue.ofInt(0x0102_0304);
        ByteBuffer target = ByteBuffer.allocate(8);
        target.position(2);
        int limit = target.limit();
        value.writeTo(target);
        assertEquals(6, target.position());
        assertEquals(limit, target.limit());
        assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}), target.flip().position(2).slice());

        ByteBuffer undersized = ByteBuffer.allocate(3);
        assertThrows(BufferOverflowException.class, () -> value.writeTo(undersized));
        assertEquals(0, undersized.position());
        ByteBuffer readOnly = ByteBuffer.allocate(4).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () -> value.writeTo(readOnly));
        assertEquals(0, readOnly.position());
    }

    /// Verifies equality is based on canonical bytes.
    @Test
    public void equalityUsesCanonicalBytes() {
        ChecksumValue first = ChecksumValue.ofBytes(new byte[]{1, 2, 3});
        ChecksumValue equal = ChecksumValue.ofBytes(new byte[]{1, 2, 3});
        ChecksumValue different = ChecksumValue.ofBytes(new byte[]{1, 2, 4});
        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertFalse(first.equals(different));
        assertFalse(first.equals("010203"));
    }
}

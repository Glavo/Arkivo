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
        ByteBuffer firstView = fromArray.toByteBuffer();
        assertTrue(firstView.isReadOnly());
        firstView.position(firstView.limit());
        ByteBuffer secondView = fromArray.toByteBuffer();
        assertEquals(0, secondView.position());
        assertEquals(fromArray.byteSize(), secondView.remaining());
        assertThrows(ReadOnlyBufferException.class, () -> secondView.put((byte) 0));
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

    /// Verifies every supported numeric byte width and its unsigned fit boundary.
    @Test
    public void numericFactoriesCoverEverySupportedWidth() {
        for (int byteSize = 1; byteSize < Long.BYTES; byteSize++) {
            int bitSize = byteSize * Byte.SIZE;
            long maximum = (1L << bitSize) - 1L;
            int currentByteSize = byteSize;
            ChecksumValue maximumValue = ChecksumValue.ofLong(maximum, byteSize);
            assertEquals(byteSize, maximumValue.byteSize());
            assertEquals(maximum, maximumValue.longValue());
            assertEquals("ff".repeat(byteSize), maximumValue.toHexString());
            assertEquals("00".repeat(byteSize), ChecksumValue.ofLong(0L, byteSize).toHexString());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ChecksumValue.ofLong(maximum + 1L, currentByteSize)
            );
        }

        ChecksumValue fullWidth = ChecksumValue.ofLong(-1L, Long.BYTES);
        assertEquals(Long.BYTES, fullWidth.byteSize());
        assertEquals(-1L, fullWidth.longValue());
        assertEquals("ff".repeat(Long.BYTES), fullWidth.toHexString());
        assertThrows(IllegalArgumentException.class, () -> ChecksumValue.ofLong(-1L, Long.BYTES - 1));
        assertThrows(IllegalArgumentException.class, () -> ChecksumValue.ofLong(0L, Long.BYTES + 1));
    }

    /// Verifies empty representations are rejected without consuming buffers.
    @Test
    public void emptyRepresentationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChecksumValue.ofBytes(new byte[0]));

        ByteBuffer source = ByteBuffer.allocate(2);
        source.position(source.limit());
        int originalPosition = source.position();
        assertThrows(IllegalArgumentException.class, () -> ChecksumValue.ofBytes(source));
        assertEquals(originalPosition, source.position());
    }

    /// Verifies values wider than a long remain usable except through numeric conversion.
    @Test
    public void wideValuesRejectLongConversion() {
        ChecksumValue value = ChecksumValue.ofBytes(new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9
        });

        assertEquals(9, value.byteSize());
        assertEquals("010203040506070809", value.toHexString());
        assertThrows(IllegalStateException.class, value::longValue);
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

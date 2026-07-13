// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests fixed-width byte-array access in both byte orders.
@NotNullByDefault
public final class ByteArrayAccessTest {
    /// Verifies unaligned 16-bit reads and writes in both byte orders.
    @Test
    public void shortAccess() {
        byte[] bytes = filledBytes(5);

        ByteArrayAccess.writeShortBigEndian(bytes, 1, (short) 0x8123);
        assertArrayEquals(new byte[]{0x55, (byte) 0x81, 0x23, 0x55, 0x55}, bytes);
        assertEquals((short) 0x8123, ByteArrayAccess.readShortBigEndian(bytes, 1));
        assertEquals((short) 0x2381, ByteArrayAccess.readShortLittleEndian(bytes, 1));

        ByteArrayAccess.writeShortLittleEndian(bytes, 2, (short) 0xa1b2);
        assertArrayEquals(new byte[]{0x55, (byte) 0x81, (byte) 0xb2, (byte) 0xa1, 0x55}, bytes);
        assertEquals((short) 0xa1b2, ByteArrayAccess.readShortLittleEndian(bytes, 2));
        assertEquals((short) 0xb2a1, ByteArrayAccess.readShortBigEndian(bytes, 2));
    }

    /// Verifies unaligned 32-bit reads and writes in both byte orders.
    @Test
    public void intAccess() {
        byte[] bytes = filledBytes(8);

        ByteArrayAccess.writeIntBigEndian(bytes, 1, 0x8123_4567);
        assertArrayEquals(
                new byte[]{0x55, (byte) 0x81, 0x23, 0x45, 0x67, 0x55, 0x55, 0x55},
                bytes
        );
        assertEquals(0x8123_4567, ByteArrayAccess.readIntBigEndian(bytes, 1));
        assertEquals(0x6745_2381, ByteArrayAccess.readIntLittleEndian(bytes, 1));

        ByteArrayAccess.writeIntLittleEndian(bytes, 3, 0x89ab_cdef);
        assertArrayEquals(
                new byte[]{0x55, (byte) 0x81, 0x23, (byte) 0xef, (byte) 0xcd, (byte) 0xab,
                        (byte) 0x89, 0x55},
                bytes
        );
        assertEquals(0x89ab_cdef, ByteArrayAccess.readIntLittleEndian(bytes, 3));
        assertEquals(0xefcd_ab89, ByteArrayAccess.readIntBigEndian(bytes, 3));
    }

    /// Verifies unaligned 64-bit reads and writes in both byte orders.
    @Test
    public void longAccess() {
        byte[] bytes = filledBytes(12);

        ByteArrayAccess.writeLongBigEndian(bytes, 1, 0x8123_4567_89ab_cdefL);
        assertArrayEquals(
                new byte[]{0x55, (byte) 0x81, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab,
                        (byte) 0xcd, (byte) 0xef, 0x55, 0x55, 0x55},
                bytes
        );
        assertEquals(0x8123_4567_89ab_cdefL, ByteArrayAccess.readLongBigEndian(bytes, 1));
        assertEquals(0xefcd_ab89_6745_2381L, ByteArrayAccess.readLongLittleEndian(bytes, 1));

        ByteArrayAccess.writeLongLittleEndian(bytes, 3, 0x0123_4567_89ab_cdefL);
        assertArrayEquals(
                new byte[]{0x55, (byte) 0x81, 0x23, (byte) 0xef, (byte) 0xcd, (byte) 0xab,
                        (byte) 0x89, 0x67, 0x45, 0x23, 0x01, 0x55},
                bytes
        );
        assertEquals(0x0123_4567_89ab_cdefL, ByteArrayAccess.readLongLittleEndian(bytes, 3));
        assertEquals(0xefcd_ab89_6745_2301L, ByteArrayAccess.readLongBigEndian(bytes, 3));
    }

    /// Verifies invalid offsets fail instead of accessing outside the array.
    @Test
    public void boundsChecks() {
        byte[] bytes = new byte[Long.BYTES];

        assertThrows(IndexOutOfBoundsException.class,
                () -> ByteArrayAccess.readShortBigEndian(bytes, -1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> ByteArrayAccess.writeShortLittleEndian(bytes, bytes.length - 1, (short) 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - Integer.BYTES + 1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> ByteArrayAccess.writeIntBigEndian(bytes, bytes.length, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> ByteArrayAccess.readLongBigEndian(bytes, 1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> ByteArrayAccess.writeLongLittleEndian(bytes, -1, 0L));
    }

    /// Creates a byte array initialized with a recognizable guard value.
    private static byte[] filledBytes(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 0x55);
        return bytes;
    }
}
// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

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

    /// Verifies every access operation against `ByteBuffer` across deterministic random inputs and unaligned offsets.
    @Test
    public void randomizedAccessMatchesByteBuffer() {
        Random random = new Random(0x4152_4b49_564fL);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] source = new byte[Long.BYTES + random.nextInt(57)];
            random.nextBytes(source);

            int shortOffset = random.nextInt(source.length - Short.BYTES + 1);
            short shortValue = (short) random.nextInt();
            assertEquals(
                    ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN).getShort(shortOffset),
                    ByteArrayAccess.readShortBigEndian(source, shortOffset)
            );
            assertEquals(
                    ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getShort(shortOffset),
                    ByteArrayAccess.readShortLittleEndian(source, shortOffset)
            );
            byte[] expectedShortBigEndian = source.clone();
            ByteBuffer.wrap(expectedShortBigEndian).order(ByteOrder.BIG_ENDIAN).putShort(shortOffset, shortValue);
            byte[] actualShortBigEndian = source.clone();
            ByteArrayAccess.writeShortBigEndian(actualShortBigEndian, shortOffset, shortValue);
            assertArrayEquals(expectedShortBigEndian, actualShortBigEndian);
            byte[] expectedShortLittleEndian = source.clone();
            ByteBuffer.wrap(expectedShortLittleEndian).order(ByteOrder.LITTLE_ENDIAN).putShort(shortOffset, shortValue);
            byte[] actualShortLittleEndian = source.clone();
            ByteArrayAccess.writeShortLittleEndian(actualShortLittleEndian, shortOffset, shortValue);
            assertArrayEquals(expectedShortLittleEndian, actualShortLittleEndian);

            int intOffset = random.nextInt(source.length - Integer.BYTES + 1);
            int intValue = random.nextInt();
            assertEquals(
                    ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN).getInt(intOffset),
                    ByteArrayAccess.readIntBigEndian(source, intOffset)
            );
            assertEquals(
                    ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(intOffset),
                    ByteArrayAccess.readIntLittleEndian(source, intOffset)
            );
            byte[] expectedIntBigEndian = source.clone();
            ByteBuffer.wrap(expectedIntBigEndian).order(ByteOrder.BIG_ENDIAN).putInt(intOffset, intValue);
            byte[] actualIntBigEndian = source.clone();
            ByteArrayAccess.writeIntBigEndian(actualIntBigEndian, intOffset, intValue);
            assertArrayEquals(expectedIntBigEndian, actualIntBigEndian);
            byte[] expectedIntLittleEndian = source.clone();
            ByteBuffer.wrap(expectedIntLittleEndian).order(ByteOrder.LITTLE_ENDIAN).putInt(intOffset, intValue);
            byte[] actualIntLittleEndian = source.clone();
            ByteArrayAccess.writeIntLittleEndian(actualIntLittleEndian, intOffset, intValue);
            assertArrayEquals(expectedIntLittleEndian, actualIntLittleEndian);

            int longOffset = random.nextInt(source.length - Long.BYTES + 1);
            long longValue = random.nextLong();
            assertEquals(
                    ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN).getLong(longOffset),
                    ByteArrayAccess.readLongBigEndian(source, longOffset)
            );
            assertEquals(
                    ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getLong(longOffset),
                    ByteArrayAccess.readLongLittleEndian(source, longOffset)
            );
            byte[] expectedLongBigEndian = source.clone();
            ByteBuffer.wrap(expectedLongBigEndian).order(ByteOrder.BIG_ENDIAN).putLong(longOffset, longValue);
            byte[] actualLongBigEndian = source.clone();
            ByteArrayAccess.writeLongBigEndian(actualLongBigEndian, longOffset, longValue);
            assertArrayEquals(expectedLongBigEndian, actualLongBigEndian);
            byte[] expectedLongLittleEndian = source.clone();
            ByteBuffer.wrap(expectedLongLittleEndian).order(ByteOrder.LITTLE_ENDIAN).putLong(longOffset, longValue);
            byte[] actualLongLittleEndian = source.clone();
            ByteArrayAccess.writeLongLittleEndian(actualLongLittleEndian, longOffset, longValue);
            assertArrayEquals(expectedLongLittleEndian, actualLongLittleEndian);
        }
    }

    /// Verifies that every access operation rejects a null byte array.
    @Test
    public void nullChecks() {
        byte @Nullable [] bytes = null;

        assertThrows(NullPointerException.class, () -> ByteArrayAccess.readShortBigEndian(bytes, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.readShortLittleEndian(bytes, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.writeShortBigEndian(bytes, 0, (short) 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.writeShortLittleEndian(bytes, 0, (short) 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.readIntBigEndian(bytes, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.readIntLittleEndian(bytes, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.writeIntBigEndian(bytes, 0, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.writeIntLittleEndian(bytes, 0, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.readLongBigEndian(bytes, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.readLongLittleEndian(bytes, 0));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.writeLongBigEndian(bytes, 0, 0L));
        assertThrows(NullPointerException.class, () -> ByteArrayAccess.writeLongLittleEndian(bytes, 0, 0L));
    }

    /// Creates a byte array initialized with a recognizable guard value.
    private static byte[] filledBytes(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 0x55);
        return bytes;
    }
}

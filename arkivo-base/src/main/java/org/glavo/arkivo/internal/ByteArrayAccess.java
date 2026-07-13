// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/// Reads and writes fixed-width primitive values in byte arrays.
///
/// All offsets are byte offsets. The methods support unaligned access and perform the same null and
/// bounds checks as the corresponding byte-array view {@link VarHandle} operation.
@NotNullByDefault
public final class ByteArrayAccess {
    /// Accesses big-endian 16-bit values in byte arrays.
    private static final VarHandle BIG_ENDIAN_SHORTS =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);

    /// Accesses little-endian 16-bit values in byte arrays.
    private static final VarHandle LITTLE_ENDIAN_SHORTS =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /// Accesses big-endian 32-bit values in byte arrays.
    private static final VarHandle BIG_ENDIAN_INTS =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    /// Accesses little-endian 32-bit values in byte arrays.
    private static final VarHandle LITTLE_ENDIAN_INTS =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /// Accesses big-endian 64-bit values in byte arrays.
    private static final VarHandle BIG_ENDIAN_LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /// Accesses little-endian 64-bit values in byte arrays.
    private static final VarHandle LITTLE_ENDIAN_LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /// Prevents utility-class construction.
    private ByteArrayAccess() {
    }

    /// Reads a big-endian 16-bit value at the given byte offset.
    public static short readShortBigEndian(byte[] array, int offset) {
        return (short) BIG_ENDIAN_SHORTS.get(array, offset);
    }

    /// Reads a little-endian 16-bit value at the given byte offset.
    public static short readShortLittleEndian(byte[] array, int offset) {
        return (short) LITTLE_ENDIAN_SHORTS.get(array, offset);
    }

    /// Writes a big-endian 16-bit value at the given byte offset.
    public static void writeShortBigEndian(byte[] array, int offset, short value) {
        BIG_ENDIAN_SHORTS.set(array, offset, value);
    }

    /// Writes a little-endian 16-bit value at the given byte offset.
    public static void writeShortLittleEndian(byte[] array, int offset, short value) {
        LITTLE_ENDIAN_SHORTS.set(array, offset, value);
    }

    /// Reads a big-endian 32-bit value at the given byte offset.
    public static int readIntBigEndian(byte[] array, int offset) {
        return (int) BIG_ENDIAN_INTS.get(array, offset);
    }

    /// Reads a little-endian 32-bit value at the given byte offset.
    public static int readIntLittleEndian(byte[] array, int offset) {
        return (int) LITTLE_ENDIAN_INTS.get(array, offset);
    }

    /// Writes a big-endian 32-bit value at the given byte offset.
    public static void writeIntBigEndian(byte[] array, int offset, int value) {
        BIG_ENDIAN_INTS.set(array, offset, value);
    }

    /// Writes a little-endian 32-bit value at the given byte offset.
    public static void writeIntLittleEndian(byte[] array, int offset, int value) {
        LITTLE_ENDIAN_INTS.set(array, offset, value);
    }

    /// Reads a big-endian 64-bit value at the given byte offset.
    public static long readLongBigEndian(byte[] array, int offset) {
        return (long) BIG_ENDIAN_LONGS.get(array, offset);
    }

    /// Reads a little-endian 64-bit value at the given byte offset.
    public static long readLongLittleEndian(byte[] array, int offset) {
        return (long) LITTLE_ENDIAN_LONGS.get(array, offset);
    }

    /// Writes a big-endian 64-bit value at the given byte offset.
    public static void writeLongBigEndian(byte[] array, int offset, long value) {
        BIG_ENDIAN_LONGS.set(array, offset, value);
    }

    /// Writes a little-endian 64-bit value at the given byte offset.
    public static void writeLongLittleEndian(byte[] array, int offset, long value) {
        LITTLE_ENDIAN_LONGS.set(array, offset, value);
    }
}
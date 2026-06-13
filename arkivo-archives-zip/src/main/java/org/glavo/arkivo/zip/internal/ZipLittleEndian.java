// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/// Provides little-endian primitive helpers for ZIP stream parsing and writing.
@NotNullByDefault
final class ZipLittleEndian {
    /// Prevents instantiation.
    private ZipLittleEndian() {
    }

    /// Reads a little-endian unsigned 16-bit integer.
    static int readUnsignedShort(InputStream input) throws IOException {
        int b0 = input.read();
        int b1 = input.read();
        if ((b0 | b1) < 0) {
            throw new EOFException("Unexpected end of ZIP stream");
        }
        return b0 | (b1 << 8);
    }

    /// Reads a little-endian unsigned 16-bit integer from a byte array.
    static int readUnsignedShort(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset]) | (Byte.toUnsignedInt(value[offset + 1]) << 8);
    }

    /// Reads a little-endian signed 32-bit integer.
    static int readInt(InputStream input) throws IOException {
        int b0 = input.read();
        if (b0 < 0) {
            throw new EOFException("Unexpected end of ZIP stream");
        }
        return b0
                | (readRequiredByte(input) << 8)
                | (readRequiredByte(input) << 16)
                | (readRequiredByte(input) << 24);
    }

    /// Reads a little-endian signed 32-bit integer from a byte array.
    static int readInt(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset])
                | (Byte.toUnsignedInt(value[offset + 1]) << 8)
                | (Byte.toUnsignedInt(value[offset + 2]) << 16)
                | (Byte.toUnsignedInt(value[offset + 3]) << 24);
    }

    /// Reads a little-endian signed 32-bit integer, or `-1` when no bytes remain.
    static int readIntOrEnd(InputStream input) throws IOException {
        int b0 = input.read();
        if (b0 < 0) {
            return -1;
        }
        return b0
                | (readRequiredByte(input) << 8)
                | (readRequiredByte(input) << 16)
                | (readRequiredByte(input) << 24);
    }

    /// Reads one required byte.
    static int readRequiredByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of ZIP stream");
        }
        return value;
    }

    /// Writes a little-endian unsigned 16-bit value.
    static void writeShort(OutputStream output, long value) throws IOException {
        requireUInt16(value, "short value");
        output.write((int) (value & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
    }

    /// Writes a little-endian unsigned 32-bit value.
    static void writeInt(OutputStream output, long value) throws IOException {
        requireUInt32(value, "int value");
        output.write((int) (value & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
        output.write((int) ((value >>> 16) & 0xff));
        output.write((int) ((value >>> 24) & 0xff));
    }

    /// Writes a little-endian unsigned 64-bit value representable by a Java `long`.
    static void writeLong(OutputStream output, long value) throws IOException {
        requireUInt64(value, "long value");
        output.write((int) (value & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
        output.write((int) ((value >>> 16) & 0xff));
        output.write((int) ((value >>> 24) & 0xff));
        output.write((int) ((value >>> 32) & 0xff));
        output.write((int) ((value >>> 40) & 0xff));
        output.write((int) ((value >>> 48) & 0xff));
        output.write((int) ((value >>> 56) & 0xff));
    }

    /// Requires a value to fit in an unsigned 16-bit ZIP field.
    static void requireUInt16(long value, String name) {
        if (value < 0 || value > ZipConstants.UINT16_MAX) {
            throw new IllegalArgumentException(name + " is out of ZIP range");
        }
    }

    /// Requires a value to fit in an unsigned 32-bit ZIP field.
    static void requireUInt32(long value, String name) {
        if (value < 0 || value > ZipConstants.UINT32_MAX) {
            throw new IllegalArgumentException(name + " is out of ZIP32 range");
        }
    }

    /// Requires a value to fit in an unsigned 64-bit ZIP field representable by a Java `long`.
    static void requireUInt64(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " is out of ZIP64 range");
        }
    }
}

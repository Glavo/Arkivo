// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;

/// Provides XZ constants and canonical integer utilities shared by stream readers and writers.
@NotNullByDefault
final class XzSupport {
    /// The XZ stream header signature.
    static final byte @Unmodifiable [] HEADER_MAGIC = {
            (byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00
    };

    /// The XZ stream footer signature.
    static final byte @Unmodifiable [] FOOTER_MAGIC = {0x59, 0x5a};

    /// The no-check stream flag.
    static final int CHECK_NONE = 0;

    /// The CRC-32 stream flag.
    static final int CHECK_CRC32 = 1;

    /// The CRC-64 stream flag.
    static final int CHECK_CRC64 = 4;

    /// The SHA-256 stream flag.
    static final int CHECK_SHA256 = 10;

    /// The XZ Delta filter identifier.
    static final long FILTER_DELTA = 0x03L;

    /// The first XZ BCJ filter identifier.
    static final long FILTER_BCJ_X86 = 0x04L;

    /// The final XZ BCJ filter identifier.
    static final long FILTER_BCJ_RISCV = 0x0bL;

    /// The XZ LZMA2 filter identifier.
    static final long FILTER_LZMA2 = 0x21L;

    /// Prevents utility-class construction.
    private XzSupport() {
    }

    /// Returns the CRC-32 of one byte range.
    static long crc32(byte[] bytes, int offset, int length) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, offset, length);
        return crc32.getValue();
    }

    /// Returns whether one little-endian stored CRC-32 differs from a byte range.
    static boolean crc32Mismatch(byte[] bytes, int offset, int length, int storedOffset) {
        return getLittleEndian(bytes, storedOffset, Integer.BYTES) != crc32(bytes, offset, length);
    }

    /// Reads one canonical XZ variable-length integer.
    static long readVli(InputStream input) throws IOException {
        long value = 0L;
        for (int index = 0; index < 9; index++) {
            int current = readRequiredByte(input);
            if (index == 8 && (current & 0x80) != 0) {
                throw new IOException("XZ variable-length integer is too large");
            }
            value |= (long) (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                if (index != 0 && (current & 0x7f) == 0) {
                    throw new IOException("Non-canonical XZ variable-length integer");
                }
                return value;
            }
        }
        throw new IOException("XZ variable-length integer is too large");
    }

    /// Writes one canonical XZ variable-length integer.
    static void writeVli(OutputStream output, long value) throws IOException {
        if (value < 0L) {
            throw new IllegalArgumentException("XZ variable-length integers must be nonnegative");
        }
        do {
            int current = (int) value & 0x7f;
            value >>>= 7;
            output.write(value == 0L ? current : current | 0x80);
        } while (value != 0L);
    }

    /// Returns the smallest LZMA2 dictionary property covering a dictionary size.
    static int lzma2DictionaryProperty(int dictionarySize) {
        if (dictionarySize < LzmaProperties.MINIMUM_DICTIONARY_SIZE
                || dictionarySize > LzmaProperties.MAXIMUM_DICTIONARY_SIZE) {
            throw new IllegalArgumentException("Unsupported XZ LZMA2 dictionary size: " + dictionarySize);
        }
        for (int property = 0; property <= 37; property++) {
            int represented = (2 | property & 1) << ((property >>> 1) + 11);
            if (represented >= dictionarySize) {
                return property;
            }
        }
        throw new AssertionError(dictionarySize);
    }

    /// Decodes one supported LZMA2 dictionary property.
    static int lzma2DictionarySize(int property) throws IOException {
        if (property < 0 || property > 37) {
            throw new IOException("Unsupported XZ LZMA2 dictionary property: " + property);
        }
        return (2 | property & 1) << ((property >>> 1) + 11);
    }

    /// Reads an exact number of bytes.
    static void readFully(InputStream input, byte[] bytes, int offset, int length) throws IOException {
        int end = offset + length;
        while (offset < end) {
            int count = input.read(bytes, offset, end - offset);
            if (count < 0) {
                throw new EOFException("Truncated XZ stream");
            }
            if (count == 0) {
                bytes[offset++] = (byte) readRequiredByte(input);
            } else {
                offset += count;
            }
        }
    }

    /// Reads one required byte.
    static int readRequiredByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated XZ stream");
        }
        return value;
    }

    /// Returns an unsigned little-endian integer from an array.
    static long getLittleEndian(byte[] bytes, int offset, int length) {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) Byte.toUnsignedInt(bytes[offset + index]) << (index * 8);
        }
        return value;
    }

    /// Stores a fixed-width little-endian integer in an array.
    static void putLittleEndian(byte[] bytes, int offset, long value, int length) {
        for (int index = 0; index < length; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    /// Writes one little-endian CRC-32 value.
    static void writeCrc32(OutputStream output, long value) throws IOException {
        for (int index = 0; index < Integer.BYTES; index++) {
            output.write((int) (value >>> (index * 8)) & 0xff);
        }
    }
}

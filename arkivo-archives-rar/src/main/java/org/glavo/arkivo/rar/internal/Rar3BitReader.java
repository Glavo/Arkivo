// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Reads bounded most-significant-bit-first fields from an in-memory RAR3 descriptor.
@NotNullByDefault
final class Rar3BitReader {
    /// The immutable descriptor bytes.
    private final byte @Unmodifiable [] data;

    /// The next unread absolute bit position.
    private int bitPosition;

    /// Creates a reader over all supplied bytes.
    Rar3BitReader(byte[] data) {
        this(data, 0);
    }

    /// Creates a reader beginning at one byte offset.
    Rar3BitReader(byte[] data, int byteOffset) {
        Objects.requireNonNull(data, "data");
        if (byteOffset < 0 || byteOffset > data.length) {
            throw new IllegalArgumentException("Invalid RAR3 descriptor offset");
        }
        this.data = data.clone();
        bitPosition = byteOffset * 8;
    }

    /// Returns the number of unread bits.
    int remainingBits() {
        return data.length * 8 - bitPosition;
    }

    /// Reads an unsigned field containing at most 32 bits.
    int readBits(int count) throws IOException {
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("RAR3 bit count must be between 0 and 32");
        }
        if (count > remainingBits()) {
            throw new EOFException("Unexpected end of RAR3 descriptor");
        }
        int value = 0;
        for (int index = 0; index < count; index++) {
            int byteIndex = bitPosition >>> 3;
            int bitIndex = 7 - (bitPosition & 7);
            value = value << 1 | (data[byteIndex] >>> bitIndex & 1);
            bitPosition++;
        }
        return value;
    }

    /// Reads one RAR3 variable-width unsigned integer.
    long readEncodedUint32() throws IOException {
        int selector = readBits(2);
        if (selector != 1) {
            int count = 4 << selector;
            return Integer.toUnsignedLong(readBits(count));
        }
        int high = readBits(4);
        if (high == 0) {
            return Integer.toUnsignedLong(readBits(8) | 0xffff_ff00);
        }
        return high << 4 | readBits(4);
    }

    /// Reads exactly the requested number of unaligned bytes.
    byte[] readBytes(int count) throws IOException {
        if (count < 0) {
            throw new IllegalArgumentException("Negative RAR3 descriptor byte count");
        }
        byte[] result = new byte[count];
        for (int index = 0; index < count; index++) {
            result[index] = (byte) readBits(8);
        }
        return result;
    }
}

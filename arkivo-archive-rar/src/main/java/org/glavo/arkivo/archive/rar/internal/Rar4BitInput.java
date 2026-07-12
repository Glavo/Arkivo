// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/// Reads the most-significant-bit-first fields used by legacy RAR compressed data.
@NotNullByDefault
final class Rar4BitInput {
    /// The size of the reusable packed-input buffer.
    private static final int BUFFER_SIZE = 64 * 1024;

    /// The caller-owned packed input.
    private final InputStream input;

    /// The reusable byte buffer.
    private final byte[] buffer = new byte[BUFFER_SIZE];

    /// The next unread byte in {@link #buffer}.
    private int bufferOffset;

    /// The first unavailable byte in {@link #buffer}.
    private int bufferLimit;

    /// Fetched bits that have not yet been consumed.
    private long reservoir;

    /// The number of available low bits in {@link #reservoir}.
    private int reservoirBits;

    /// Creates one legacy RAR bit reader.
    Rar4BitInput(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /// Reads an unsigned field containing at most 32 bits.
    int readBits(int count) throws IOException {
        int result = peekBits(count);
        consumeBits(count);
        return result;
    }

    /// Peeks at an unsigned field containing at most 32 bits without consuming it.
    int peekBits(int count) throws IOException {
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("RAR bit count must be between 0 and 32");
        }
        if (count == 0) {
            return 0;
        }
        ensureAvailable(count);
        long mask = count == 32 ? 0xffff_ffffL : (1L << count) - 1L;
        return (int) (reservoir >>> (reservoirBits - count) & mask);
    }

    /// Discards the requested number of bits.
    void skipBits(int count) throws IOException {
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("RAR bit count must be between 0 and 32");
        }
        if (count == 0) {
            return;
        }
        ensureAvailable(count);
        consumeBits(count);
    }

    /// Removes high-order bits that were just consumed.
    private void consumeBits(int count) {
        reservoirBits -= count;
        if (reservoirBits == 0) {
            reservoir = 0L;
        } else {
            reservoir &= (1L << reservoirBits) - 1L;
        }
    }

    /// Discards bits up to the next byte boundary without imposing a padding value.
    void alignToByte() throws IOException {
        int consumedBits = (8 - reservoirBits) & 7;
        if (consumedBits != 0) {
            readBits(8 - consumedBits);
        }
    }

    /// Fetches enough bytes to satisfy one field read.
    private void ensureAvailable(int count) throws IOException {
        while (reservoirBits < count) {
            int value = readBufferedByte();
            if (value < 0) {
                throw new IOException("Unexpected end of legacy RAR compressed data");
            }
            reservoir = reservoir << 8 | value;
            reservoirBits += 8;
        }
    }

    /// Returns the next buffered byte or `-1` at physical end of input.
    private int readBufferedByte() throws IOException {
        while (bufferOffset >= bufferLimit) {
            bufferLimit = input.read(buffer);
            bufferOffset = 0;
            if (bufferLimit < 0) {
                return -1;
            }
        }
        return buffer[bufferOffset++] & 0xff;
    }
}

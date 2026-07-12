// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/// Reads the most-significant-bit-first fields used by RAR5 compressed blocks.
@NotNullByDefault
final class Rar5BitInput {
    /// Marks that reads are not currently constrained to one compressed block.
    private static final long NO_BLOCK_END = -1L;

    /// The size of the buffered packed-input window.
    private static final int BUFFER_SIZE = 64 * 1024;

    /// The caller-owned packed input.
    private final InputStream input;

    /// The reusable packed-input buffer.
    private final byte[] buffer = new byte[BUFFER_SIZE];

    /// The next unread byte in {@link #buffer}.
    private int bufferOffset;

    /// The first unavailable byte in {@link #buffer}.
    private int bufferLimit;

    /// Bits fetched from the input but not consumed by the decoder.
    private long reservoir;

    /// The number of low bits in {@link #reservoir} that remain available.
    private int reservoirBits;

    /// The total number of bytes fetched into the bit reservoir.
    private long fetchedBytes;

    /// The absolute exclusive bit position of the active compressed block.
    private long blockEndBit = NO_BLOCK_END;

    /// Creates a buffered bit reader over one packed entry stream.
    Rar5BitInput(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /// Returns the absolute number of packed bits consumed so far.
    long positionBits() {
        return Math.subtractExact(Math.multiplyExact(fetchedBytes, 8L), reservoirBits);
    }

    /// Constrains subsequent reads to the supplied exclusive block boundary.
    void setBlockEndBit(long blockEndBit) throws IOException {
        if (blockEndBit < positionBits()) {
            throw new IOException("RAR5 compressed block ends before its payload");
        }
        this.blockEndBit = blockEndBit;
    }

    /// Removes the active compressed-block boundary before reading its byte padding or next header.
    void clearBlockEnd() {
        blockEndBit = NO_BLOCK_END;
    }

    /// Returns whether the active compressed block has been consumed exactly.
    boolean atBlockEnd() {
        return blockEndBit != NO_BLOCK_END && positionBits() == blockEndBit;
    }

    /// Returns the number of bits left in the active compressed block.
    long remainingBlockBits() throws IOException {
        if (blockEndBit == NO_BLOCK_END) {
            throw new IOException("RAR5 compressed block boundary is unavailable");
        }
        return blockEndBit - positionBits();
    }

    /// Reads an unsigned field containing at most 32 bits.
    int readBits(int count) throws IOException {
        int value = peekBits(count);
        consumeBits(count);
        return value;
    }

    /// Peeks at an unsigned field containing at most 32 bits without consuming it.
    int peekBits(int count) throws IOException {
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("RAR5 bit count must be between 0 and 32");
        }
        if (count == 0) {
            return 0;
        }
        ensureWithinBlock(count);
        ensureAvailable(count);
        long mask = count == 32 ? 0xffff_ffffL : (1L << count) - 1L;
        return (int) ((reservoir >>> (reservoirBits - count)) & mask);
    }

    /// Discards the requested number of packed bits.
    void skipBits(int count) throws IOException {
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("RAR5 bit count must be between 0 and 32");
        }
        if (count == 0) {
            return;
        }
        ensureWithinBlock(count);
        ensureAvailable(count);
        consumeBits(count);
    }

    /// Advances to the next byte and rejects non-zero alignment padding.
    void alignToByte() throws IOException {
        int consumedInByte = (int) (positionBits() & 7L);
        if (consumedInByte == 0) {
            return;
        }
        int paddingBits = 8 - consumedInByte;
        if (readBits(paddingBits) != 0) {
            throw new IOException("RAR5 compressed block has non-zero alignment padding");
        }
    }

    /// Reads one byte while requiring byte alignment.
    int readAlignedByte() throws IOException {
        if ((positionBits() & 7L) != 0L) {
            throw new IOException("RAR5 block header is not byte aligned");
        }
        return readBits(8);
    }

    /// Ensures that one field does not cross the active compressed-block boundary.
    private void ensureWithinBlock(int count) throws IOException {
        if (blockEndBit != NO_BLOCK_END && positionBits() > blockEndBit - count) {
            throw new IOException("RAR5 compressed block was overread");
        }
    }

    /// Fetches enough bytes to expose the requested number of bits.
    private void ensureAvailable(int count) throws IOException {
        while (reservoirBits < count) {
            int value = readBufferedByte();
            if (value < 0) {
                throw new IOException("Unexpected end of RAR5 compressed data");
            }
            reservoir = (reservoir << 8) | value;
            reservoirBits += 8;
            fetchedBytes++;
        }
    }

    /// Removes high-order bits that have just been consumed.
    private void consumeBits(int count) {
        reservoirBits -= count;
        if (reservoirBits == 0) {
            reservoir = 0L;
        } else {
            reservoir &= (1L << reservoirBits) - 1L;
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

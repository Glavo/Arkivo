// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
// Portions adapted from zlib-ng; see NOTICE and LICENSES/Zlib.txt.
// This Java implementation differs from the original C sources.

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/// Collects little-endian packed fields into reusable engine-owned complete bytes.
@NotNullByDefault
final class DeflateBitOutput {
    /// Shared zero-capacity output for operations that completed no bytes.
    private static final @Unmodifiable ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0);
    /// Complete compressed-byte storage sized for one uncompressed block and its headers.
    private byte[] output = new byte[65536 + 64];

    /// Reusable view of complete compressed bytes awaiting transfer.
    private ByteBuffer outputView = ByteBuffer.wrap(output);

    /// Number of complete compressed bytes currently stored.
    private int outputSize;

    /// Packed pending bits, with the next output bit in bit zero.
    private long buffer;

    /// The number of pending bits.
    private int bitCount;

    /// Writes up to sixteen low-order bits.
    void writeBits(int count, int value) {
        if (count < 0 || count > 16) {
            throw new IllegalArgumentException("Deflate bit count must be between 0 and 16");
        }
        if (count == 0) return;
        long encoded = (long) value & ((1L << count) - 1L);
        buffer |= encoded << bitCount;
        int total = bitCount + count;
        if (total >= Long.SIZE) {
            ensureCapacity(Long.BYTES);
            ByteArrayAccess.writeLongLittleEndian(output, outputSize, buffer);
            outputSize += Long.BYTES;
            buffer = encoded >>> (Long.SIZE - bitCount);
            total -= Long.SIZE;
        }
        bitCount = total;
    }

    /// Writes aligned bytes directly to the completed output.
    void writeBytes(byte[] values, int offset, int length) {
        drainCompleteBytes();
        if (bitCount != 0) {
            throw new IllegalStateException("Deflate byte output is not aligned");
        }
        ensureCapacity(length);
        System.arraycopy(values, offset, output, outputSize, length);
        outputSize += length;
    }

    /// Pads pending bits through the next byte boundary.
    void alignToByte() {
        int padding = -bitCount & 7;
        writeBits(padding, 0);
    }

    /// Pads the final partial byte into the complete-byte output.
    void finish() {
        drainCompleteBytes();
        if (bitCount > 0) {
            writeByte((int) buffer);
            buffer = 0L;
            bitCount = 0;
        }
    }

    /// Transfers all complete bytes through a view that remains stable until the next write.
    ByteBuffer takeOutput() {
        drainCompleteBytes();
        if (outputSize == 0) {
            return EMPTY_OUTPUT;
        }
        outputView.clear().limit(outputSize);
        outputSize = 0;
        return outputView;
    }

    /// Returns the number of bits pending below the next byte boundary.
    int bitCount() {
        return bitCount & 7;
    }

    /// Publishes complete bytes while retaining only the unfinished low-order byte.
    private void drainCompleteBytes() {
        while (bitCount >= Byte.SIZE) {
            writeByte((int) buffer);
            buffer >>>= Byte.SIZE;
            bitCount -= Byte.SIZE;
        }
    }

    /// Restores an empty bitstream session.
    void reset() {
        outputSize = 0;
        outputView.clear();
        buffer = 0L;
        bitCount = 0;
    }

    /// Appends one completed byte to reusable storage.
    private void writeByte(int value) {
        ensureCapacity(1);
        output[outputSize++] = (byte) value;
    }

    /// Ensures reusable storage can append the requested byte count.
    private void ensureCapacity(int additional) {
        int required = outputSize + additional;
        if (required <= output.length) {
            return;
        }
        output = Arrays.copyOf(output, Math.max(required, output.length << 1));
        outputView = ByteBuffer.wrap(output);
    }
}


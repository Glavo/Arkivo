// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Provides checked positional reads and arithmetic for disk-image parsers.
@NotNullByDefault
final class ChannelIO {
    /// Creates no instances.
    private ChannelIO() {
    }

    /// Reads exactly the target's remaining bytes from an absolute channel offset.
    ///
    /// The target position advances by the requested byte count. The channel position is unspecified after success or
    /// failure because callers own the parser channel exclusively.
    ///
    /// @param source the source channel
    /// @param offset the non-negative absolute byte offset
    /// @param target the target buffer
    /// @throws EOFException if the source ends before the target is filled
    /// @throws IOException if positioning or reading fails, including zero progress
    static void readFully(SeekableByteChannel source, long offset, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (offset < 0L) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        source.position(offset);
        while (target.hasRemaining()) {
            int read = source.read(target);
            if (read < 0) {
                throw new EOFException("Unexpected end of disk image at offset " + source.position());
            }
            if (read == 0) {
                throw new IOException("Disk image read made no progress at offset " + source.position());
            }
        }
    }

    /// Reads an exact byte array from an absolute channel offset.
    ///
    /// @param source the source channel
    /// @param offset the non-negative absolute byte offset
    /// @param length the non-negative array length
    /// @return the newly allocated bytes
    /// @throws IOException if the range cannot be read completely
    static byte[] readBytes(SeekableByteChannel source, long offset, int length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        byte[] bytes = new byte[length];
        readFully(source, offset, ByteBuffer.wrap(bytes));
        return bytes;
    }

    /// Returns the exact sum of two non-negative values.
    ///
    /// @param first the first non-negative value
    /// @param second the second non-negative value
    /// @param description the value description used by malformed-image errors
    /// @return the exact non-negative sum
    /// @throws IOException if either value is negative or the sum overflows
    static long add(long first, long second, String description) throws IOException {
        if (first < 0L || second < 0L || first > Long.MAX_VALUE - second) {
            throw new IOException("Invalid or overflowing " + description);
        }
        return first + second;
    }

    /// Returns the exact product of two non-negative values.
    ///
    /// @param first the first non-negative value
    /// @param second the second non-negative value
    /// @param description the value description used by malformed-image errors
    /// @return the exact non-negative product
    /// @throws IOException if either value is negative or the product overflows
    static long multiply(long first, long second, String description) throws IOException {
        if (first < 0L || second < 0L || second != 0L && first > Long.MAX_VALUE / second) {
            throw new IOException("Invalid or overflowing " + description);
        }
        return first * second;
    }

    /// Validates a byte range within an enclosing size.
    ///
    /// @param offset the non-negative range offset
    /// @param length the non-negative range length
    /// @param size the non-negative enclosing size
    /// @param description the range description used by malformed-image errors
    /// @throws IOException if the range is invalid or lies outside the enclosing size
    static void requireRange(long offset, long length, long size, String description) throws IOException {
        long end = add(offset, length, description + " range");
        if (end > size) {
            throw new IOException(description + " range exceeds the disk image");
        }
    }
}

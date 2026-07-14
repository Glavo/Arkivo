// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Supplies buffered bytes from a Zstandard source while keeping logical and physical counts separate.
@NotNullByDefault
final class ZstdChannelInput {
    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// The input staging buffer.
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024);

    /// The number of bytes logically consumed.
    private long byteCount;

    /// The number of bytes obtained from the source.
    private long sourceByteCount;

    /// Creates a buffered source.
    ZstdChannelInput(ReadableByteChannel source) {
        this.source = Objects.requireNonNull(source, "source");
        buffer.limit(0);
    }

    /// Returns whether at least one input byte is available without consuming it.
    boolean hasByte() throws IOException {
        return buffer.hasRemaining() || fill();
    }

    /// Reads one byte, or returns -1 at physical end of input.
    int read() throws IOException {
        if (!hasByte()) {
            return -1;
        }
        byteCount++;
        return Byte.toUnsignedInt(buffer.get());
    }

    /// Reads one required byte.
    int readRequired() throws IOException {
        int value = read();
        if (value < 0) {
            throw new EOFException("Unexpected end of Zstandard frame");
        }
        return value;
    }

    /// Reads an unsigned little-endian integer of the requested byte width.
    long readLittleEndian(int length) throws IOException {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) readRequired() << (index * 8);
        }
        return value;
    }

    /// Reads exactly the requested number of bytes.
    byte[] readFully(int length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            if (!buffer.hasRemaining() && !fill()) {
                throw new EOFException("Unexpected end of Zstandard frame");
            }
            int copied = Math.min(length - offset, buffer.remaining());
            buffer.get(bytes, offset, copied);
            offset += copied;
            byteCount += copied;
        }
        return bytes;
    }

    /// Skips exactly the requested number of bytes without allocating for their contents.
    void skipFully(long length) throws IOException {
        if (length < 0L) {
            throw new IllegalArgumentException("length must not be negative");
        }
        long remaining = length;
        while (remaining > 0L) {
            if (!buffer.hasRemaining() && !fill()) {
                throw new EOFException("Unexpected end of Zstandard skippable frame");
            }
            int skipped = (int) Math.min(remaining, buffer.remaining());
            buffer.position(buffer.position() + skipped);
            byteCount += skipped;
            remaining -= skipped;
        }
    }

    /// Returns the number of logically consumed bytes.
    long byteCount() {
        return byteCount;
    }

    /// Returns the number of bytes obtained from the source.
    long sourceByteCount() {
        return sourceByteCount;
    }

    /// Returns a read-only view of bytes obtained but not consumed.
    @UnmodifiableView ByteBuffer unconsumedInput() {
        return buffer.asReadOnlyBuffer();
    }

    /// Refills the staging buffer and reports whether bytes are available.
    private boolean fill() throws IOException {
        buffer.clear();
        int read = source.read(buffer);
        if (read < 0) {
            buffer.limit(0);
            return false;
        }
        if (read == 0) {
            throw new IOException("Zstandard source channel made no progress");
        }
        sourceByteCount += read;
        buffer.flip();
        return true;
    }
}

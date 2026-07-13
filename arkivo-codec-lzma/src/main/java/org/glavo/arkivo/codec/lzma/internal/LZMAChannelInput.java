// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Supplies buffered bytes from an LZMA compressed-data channel.
@NotNullByDefault
final class LZMAChannelInput {
    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// The direct compressed-input staging buffer.
    private final ByteBuffer buffer;

    /// The number of logical compressed bytes consumed.
    private long byteCount;

    /// The number of compressed bytes obtained from the source.
    private long sourceByteCount;

    /// Creates a buffered byte source.
    LZMAChannelInput(ReadableByteChannel source, int bufferSize) {
        this.source = Objects.requireNonNull(source, "source");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("LZMA input buffer size must be positive");
        }
        buffer = ByteBuffer.allocateDirect(bufferSize);
        buffer.limit(0);
    }

    /// Reads one byte or returns `-1` at end of input.
    int read() throws IOException {
        if (!buffer.hasRemaining()) {
            buffer.clear();
            int read = source.read(buffer);
            if (read < 0) {
                return -1;
            }
            if (read == 0) {
                throw new IOException("LZMA source channel made no progress");
            }
            sourceByteCount += read;
            buffer.flip();
        }
        byteCount++;
        return Byte.toUnsignedInt(buffer.get());
    }

    /// Reads up to `length` bytes into the destination array.
    int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        int value = read();
        if (value < 0) {
            return -1;
        }
        bytes[offset] = (byte) value;
        int count = 1;
        int copied = Math.min(length - count, buffer.remaining());
        if (copied > 0) {
            buffer.get(bytes, offset + count, copied);
            byteCount += copied;
            count += copied;
        }
        return count;
    }

    /// Returns the number of buffered bytes immediately available.
    int available() {
        return buffer.remaining();
    }

    /// Returns the number of logical compressed bytes consumed.
    long byteCount() {
        return byteCount;
    }

    /// Returns the number of compressed bytes obtained from the source.
    long sourceByteCount() {
        return sourceByteCount;
    }

    /// Returns a read-only view of bytes obtained but not consumed.
    @UnmodifiableView ByteBuffer unconsumedInput() {
        return buffer.asReadOnlyBuffer();
    }
}

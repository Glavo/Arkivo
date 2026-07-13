// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Buffers LZMA compressed bytes before writing them to a channel.
@NotNullByDefault
final class LZMAChannelOutput {
    /// The compressed-data target.
    private final WritableByteChannel target;

    /// The direct compressed-output staging buffer.
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);

    /// The number of compressed bytes written to the target.
    private long byteCount;

    /// Creates a buffered byte target.
    LZMAChannelOutput(WritableByteChannel target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /// Stages one compressed byte.
    void write(int value) throws IOException {
        if (!buffer.hasRemaining()) {
            flush();
        }
        buffer.put((byte) value);
    }

    /// Stages a compressed byte range.
    void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        while (length > 0) {
            if (!buffer.hasRemaining()) {
                flush();
            }
            int copied = Math.min(length, buffer.remaining());
            buffer.put(bytes, offset, copied);
            offset += copied;
            length -= copied;
        }
    }

    /// Writes all staged bytes to the target.
    void flush() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            int written = target.write(buffer);
            if (written == 0) {
                throw new IOException("LZMA target channel made no progress");
            }
            byteCount += written;
        }
        buffer.clear();
    }

    /// Returns the number of compressed bytes written to the target.
    long byteCount() {
        return byteCount;
    }
}

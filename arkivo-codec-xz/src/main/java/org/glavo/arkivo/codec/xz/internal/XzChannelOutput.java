// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Buffers XZ bytes before writing them to a target channel.
@NotNullByDefault
final class XzChannelOutput {
    /// The compressed-data target.
    private final WritableByteChannel target;

    /// The direct compressed-output staging buffer.
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);

    /// The number of bytes written to the target.
    private long byteCount;

    /// Creates a buffered XZ target.
    XzChannelOutput(WritableByteChannel target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /// Stages one byte.
    void write(int value) throws IOException {
        if (!buffer.hasRemaining()) {
            flush();
        }
        buffer.put((byte) value);
    }

    /// Stages a byte range.
    void write(byte[] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    /// Stages a byte range.
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

    /// Stages every remaining byte from the source buffer.
    int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        int start = source.position();
        while (source.hasRemaining()) {
            if (!buffer.hasRemaining()) {
                flush();
            }
            int copied = Math.min(source.remaining(), buffer.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(copied);
            buffer.put(chunk);
            source.position(source.position() + copied);
        }
        return source.position() - start;
    }

    /// Writes every staged byte to the target.
    void flush() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            int written = target.write(buffer);
            if (written == 0) {
                throw new IOException("XZ target channel made no progress");
            }
            byteCount += written;
        }
        buffer.clear();
    }

    /// Returns the number of bytes written to the target.
    long byteCount() {
        return byteCount;
    }
}

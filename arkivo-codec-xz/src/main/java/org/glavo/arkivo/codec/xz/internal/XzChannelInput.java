// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Supplies buffered bytes from an XZ source channel with one-byte pushback.
@NotNullByDefault
final class XzChannelInput {
    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// The direct compressed-input staging buffer.
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);

    /// One pushed-back byte, or `-1` when absent.
    private int pushedBack = -1;

    /// The number of logical bytes consumed, excluding pushed-back bytes.
    private long byteCount;

    /// Creates a buffered XZ source.
    XzChannelInput(ReadableByteChannel source) {
        this.source = Objects.requireNonNull(source, "source");
        buffer.limit(0);
    }

    /// Reads one byte or returns `-1` at end of input.
    int read() throws IOException {
        if (pushedBack >= 0) {
            int value = pushedBack;
            pushedBack = -1;
            byteCount++;
            return value;
        }
        if (!buffer.hasRemaining() && !fill()) {
            return -1;
        }
        byteCount++;
        return Byte.toUnsignedInt(buffer.get());
    }

    /// Reads bytes into the destination array.
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

    /// Reads bytes into the destination buffer.
    int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (!target.hasRemaining()) {
            return 0;
        }
        int start = target.position();
        if (pushedBack >= 0) {
            target.put((byte) pushedBack);
            pushedBack = -1;
            byteCount++;
        }
        if (target.hasRemaining()) {
            if (!buffer.hasRemaining() && !fill()) {
                return target.position() == start ? -1 : target.position() - start;
            }
            int copied = Math.min(target.remaining(), buffer.remaining());
            ByteBuffer chunk = buffer.slice();
            chunk.limit(copied);
            target.put(chunk);
            buffer.position(buffer.position() + copied);
            byteCount += copied;
        }
        return target.position() - start;
    }

    /// Pushes back the most recently consumed byte.
    void unread(int value) {
        if (pushedBack >= 0) {
            throw new IllegalStateException("XZ input already contains a pushed-back byte");
        }
        pushedBack = value & 0xff;
        byteCount--;
    }

    /// Returns the number of logical bytes consumed.
    long byteCount() {
        return byteCount;
    }

    /// Refills the staging buffer and returns whether input is available.
    private boolean fill() throws IOException {
        buffer.clear();
        int read = source.read(buffer);
        if (read < 0) {
            buffer.limit(0);
            return false;
        }
        if (read == 0) {
            throw new IOException("XZ source channel made no progress");
        }
        buffer.flip();
        return true;
    }
}

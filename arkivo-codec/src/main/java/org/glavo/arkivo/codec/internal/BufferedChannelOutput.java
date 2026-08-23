// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Stages small encoded writes in a fixed-size direct buffer before forwarding them to a borrowed channel.
///
/// This object never closes its target. If a flush fails after partial progress, bytes not accepted by the target remain
/// staged and may be discarded with [#clear()] or retried with [#flush()].
@NotNullByDefault
public final class BufferedChannelOutput {
    /// Encoded-output staging capacity.
    private static final int BUFFER_CAPACITY = 8192;

    /// Borrowed encoded-data target.
    private final WritableByteChannel target;

    /// Encoded-output staging buffer.
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);

    /// Creates a buffered output over a borrowed channel.
    ///
    /// @param target the channel receiving flushed bytes
    /// @throws NullPointerException if {@code target} is {@code null}
    public BufferedChannelOutput(WritableByteChannel target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /// Stages one low-order byte from a value.
    ///
    /// @param value the value whose low eight bits are staged
    /// @throws IOException if a full staging buffer cannot be flushed
    public void write(int value) throws IOException {
        if (!buffer.hasRemaining()) {
            flush();
        }
        buffer.put((byte) value);
    }

    /// Stages a complete byte array.
    ///
    /// @param bytes the bytes to stage
    /// @throws IOException if staged bytes cannot be flushed as necessary
    /// @throws NullPointerException if {@code bytes} is {@code null}
    public void write(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        write(bytes, 0, bytes.length);
    }

    /// Stages a byte-array range.
    ///
    /// @param bytes the source array
    /// @param offset the first source index
    /// @param length the number of bytes to stage
    /// @throws IOException if staged bytes cannot be flushed as necessary
    /// @throws IndexOutOfBoundsException if the range is outside {@code bytes}
    /// @throws NullPointerException if {@code bytes} is {@code null}
    public void write(byte[] bytes, int offset, int length) throws IOException {
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

    /// Stages every remaining source byte.
    ///
    /// @param source the source buffer, advanced to its limit on success
    /// @return the number of staged bytes
    /// @throws IOException if staged bytes cannot be flushed as necessary
    /// @throws NullPointerException if {@code source} is {@code null}
    public int write(ByteBuffer source) throws IOException {
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

    /// Writes every staged byte to the borrowed target without closing it.
    ///
    /// @throws IOException if the target fails or makes no progress
    public void flush() throws IOException {
        buffer.flip();
        try {
            while (buffer.hasRemaining()) {
                if (target.write(buffer) <= 0) {
                    throw new IOException("Encoded-data target channel made no progress");
                }
            }
        } finally {
            if (buffer.hasRemaining()) {
                buffer.compact();
            } else {
                buffer.clear();
            }
        }
    }

    /// Discards every staged byte without writing or closing the target.
    public void clear() {
        buffer.clear();
    }
}

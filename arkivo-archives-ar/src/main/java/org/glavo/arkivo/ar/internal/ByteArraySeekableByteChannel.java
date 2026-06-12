// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Implements a read-only seekable byte channel over an immutable byte array snapshot.
@NotNullByDefault
final class ByteArraySeekableByteChannel implements SeekableByteChannel {
    /// The immutable content snapshot.
    private final byte @Unmodifiable [] content;

    /// The current channel position.
    private int position;

    /// Whether this channel is open.
    private boolean open = true;

    /// Creates a read-only channel over the given content.
    ByteArraySeekableByteChannel(byte @Unmodifiable [] content) {
        this.content = Objects.requireNonNull(content, "content").clone();
    }

    /// Reads bytes into the destination buffer.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        Objects.requireNonNull(destination, "destination");
        if (position >= content.length) {
            return -1;
        }
        int count = Math.min(destination.remaining(), content.length - position);
        destination.put(content, position, count);
        position += count;
        return count;
    }

    /// Writes are not supported.
    @Override
    public int write(ByteBuffer source) {
        throw new UnsupportedOperationException("AR byte channels are read-only");
    }

    /// Returns the current channel position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Sets the current channel position.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("position is out of range");
        }
        position = (int) newPosition;
        return this;
    }

    /// Returns the content size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return content.length;
    }

    /// Truncation is not supported.
    @Override
    public SeekableByteChannel truncate(long size) {
        throw new UnsupportedOperationException("AR byte channels are read-only");
    }

    /// Returns whether this channel is open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this channel.
    @Override
    public void close() {
        open = false;
    }

    /// Requires this channel to be open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}

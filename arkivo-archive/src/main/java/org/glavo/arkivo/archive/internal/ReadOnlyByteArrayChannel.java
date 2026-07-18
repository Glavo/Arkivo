// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Exposes an immutable byte-array snapshot as a read-only seekable channel.
@NotNullByDefault
public final class ReadOnlyByteArrayChannel implements SeekableByteChannel {
    /// The immutable content snapshot.
    private final byte @Unmodifiable [] content;

    /// The current channel position.
    private int position;

    /// Whether this channel remains open.
    private boolean open = true;

    /// Creates a channel over a defensive copy of the given content.
    ///
    /// @param content the byte content to copy
    public ReadOnlyByteArrayChannel(byte[] content) {
        this.content = Objects.requireNonNull(content, "content").clone();
    }

    /// Reads bytes from the current position.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        if (!destination.hasRemaining()) {
            return 0;
        }
        if (position >= content.length) {
            return -1;
        }
        int count = Math.min(destination.remaining(), content.length - position);
        destination.put(content, position, count);
        position += count;
        return count;
    }

    /// Rejects writes because the content snapshot is immutable.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        throw new NonWritableChannelException();
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
        if (newPosition < 0L || newPosition > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("newPosition is out of range");
        }
        position = Math.toIntExact(newPosition);
        return this;
    }

    /// Returns the immutable content size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return content.length;
    }

    /// Rejects truncation because the content snapshot is immutable.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether this channel remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this channel.
    @Override
    public void close() {
        open = false;
    }

    /// Requires this channel to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}

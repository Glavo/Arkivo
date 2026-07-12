// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Provides a read-only seekable channel over in-memory entry bytes.
@NotNullByDefault
final class SevenZipByteChannel implements SeekableByteChannel {
    /// The channel content.
    private final byte[] content;

    /// The current channel position.
    private int position;

    /// Whether this channel is open.
    private boolean open = true;

    /// Creates a byte channel over the given content.
    SevenZipByteChannel(byte[] content) {
        this.content = Objects.requireNonNull(content, "content");
    }

    /// Reads bytes into the destination buffer.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        Objects.requireNonNull(destination, "destination");
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

    /// Rejects writes.
    @Override
    public int write(ByteBuffer source) throws IOException {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        throw new UnsupportedOperationException("7z byte channels are read-only");
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
            throw new IllegalArgumentException("newPosition is out of range");
        }
        position = (int) newPosition;
        return this;
    }

    /// Returns the channel size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return content.length;
    }

    /// Rejects truncation.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        throw new UnsupportedOperationException("7z byte channels are read-only");
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

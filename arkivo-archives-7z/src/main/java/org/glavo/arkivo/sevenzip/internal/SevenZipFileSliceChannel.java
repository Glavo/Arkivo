// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Provides a read-only channel over a bounded slice of an archive channel.
@NotNullByDefault
final class SevenZipFileSliceChannel implements SeekableByteChannel {
    /// The underlying archive channel.
    private final SeekableByteChannel channel;

    /// The absolute archive offset where this slice starts.
    private final long start;

    /// The slice size in bytes.
    private final long size;

    /// The current slice-relative position.
    private long position;

    /// Whether this channel is open.
    private boolean open = true;

    /// Whether the underlying archive channel has been closed.
    private boolean channelClosed;

    /// Creates a channel over a bounded slice of an archive channel.
    SevenZipFileSliceChannel(SeekableByteChannel channel, long start, long size) {
        if (start < 0) {
            throw new IllegalArgumentException("start must be non-negative");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        this.channel = Objects.requireNonNull(channel, "channel");
        this.start = start;
        this.size = size;
    }

    /// Reads bytes into the destination buffer.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        Objects.requireNonNull(destination, "destination");
        if (!destination.hasRemaining()) {
            return 0;
        }
        if (position >= size) {
            return -1;
        }

        int originalLimit = destination.limit();
        long remaining = size - position;
        if (destination.remaining() > remaining) {
            destination.limit(destination.position() + (int) remaining);
        }
        try {
            channel.position(absolutePosition(start, position));
            int read = channel.read(destination);
            if (read > 0) {
                position += read;
            }
            return read;
        } finally {
            destination.limit(originalLimit);
        }
    }

    /// Rejects writes.
    @Override
    public int write(ByteBuffer source) throws IOException {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        throw new UnsupportedOperationException("7z byte channels are read-only");
    }

    /// Returns the current slice-relative position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Sets the current slice-relative position.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("newPosition must be non-negative");
        }
        position = Math.min(newPosition, size);
        return this;
    }

    /// Returns the slice size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return size;
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

    /// Closes this slice channel and the underlying archive channel.
    @Override
    public void close() throws IOException {
        if (!open && channelClosed) {
            return;
        }
        open = false;
        channel.close();
        channelClosed = true;
    }

    /// Requires this channel to be open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Adds a slice-relative position to an archive offset.
    private static long absolutePosition(long start, long position) throws IOException {
        try {
            return Math.addExact(start, position);
        } catch (ArithmeticException exception) {
            throw new IOException("7z slice offset is too large", exception);
        }
    }
}

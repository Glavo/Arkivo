// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
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
    ///
    /// Bytes delivered before a physical read fails advance the slice position. The destination limit is restored
    /// before returning or propagating a failure, so a later read resumes after any delivered bytes.
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
        int originalPosition = destination.position();
        long remaining = size - position;
        if (destination.remaining() > remaining) {
            destination.limit(destination.position() + (int) remaining);
        }
        try {
            channel.position(absolutePosition(start, position));
            return channel.read(destination);
        } finally {
            destination.limit(originalLimit);
            position += destination.position() - originalPosition;
        }
    }

    /// Rejects writes because archive slices are read-only.
    @Override
    public int write(ByteBuffer source) throws IOException {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        throw new NonWritableChannelException();
    }

    /// Returns the current slice-relative position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Sets the current slice-relative position, including positions beyond the slice end.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("newPosition must be non-negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the slice size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return size;
    }

    /// Validates the requested size and rejects truncation because archive slices are read-only.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        throw new NonWritableChannelException();
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Presents a fixed read-only slice of an owned seekable channel.
@NotNullByDefault
class SlicedSeekableByteChannel implements SeekableByteChannel {
    /// The underlying channel owned by this slice.
    private final SeekableByteChannel source;

    /// The slice offset in the underlying channel.
    private final long offset;

    /// The immutable slice size.
    private final long size;

    /// The current slice-relative position.
    private long position;

    /// Creates a fixed channel slice while preserving interruptibility.
    ///
    /// @param source the channel whose ownership is transferred
    /// @param offset the non-negative slice offset
    /// @param size the non-negative slice size
    /// @return a new owning read-only slice
    static SeekableByteChannel open(SeekableByteChannel source, long offset, long size) {
        return source instanceof InterruptibleChannel
                ? new InterruptibleSlice(source, offset, size)
                : new SlicedSeekableByteChannel(source, offset, size);
    }

    /// Creates a fixed channel slice.
    private SlicedSeekableByteChannel(SeekableByteChannel source, long offset, long size) {
        this.source = Objects.requireNonNull(source, "source");
        if (offset < 0L || size < 0L) {
            throw new IllegalArgumentException("offset and size must not be negative");
        }
        this.offset = offset;
        this.size = size;
    }

    /// Reads bytes from the current slice-relative position.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        if (!target.hasRemaining()) {
            return 0;
        }
        if (position >= size) {
            return -1;
        }
        int count = (int) Math.min(target.remaining(), size - position);
        source.position(offset + position);
        ByteBuffer slice = target.slice();
        slice.limit(count);
        int read = source.read(slice);
        if (read > 0) {
            target.position(target.position() + read);
            position += read;
        }
        return read;
    }

    /// Rejects writes because slices are read-only.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        throw new NonWritableChannelException();
    }

    /// Returns the current slice-relative position.
    @Override
    public long position() throws ClosedChannelException {
        ensureOpen();
        return position;
    }

    /// Sets the current slice-relative position.
    @Override
    public SeekableByteChannel position(long newPosition) throws ClosedChannelException {
        ensureOpen();
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the immutable slice size.
    @Override
    public long size() throws ClosedChannelException {
        ensureOpen();
        return size;
    }

    /// Validates the requested size and rejects truncation because slices are read-only.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether the underlying channel remains open.
    @Override
    public boolean isOpen() {
        return source.isOpen();
    }

    /// Closes the owned underlying channel.
    @Override
    public void close() throws IOException {
        source.close();
    }

    /// Rejects operations after close.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Marks a slice as interruptible when its owned source has that capability.
    @NotNullByDefault
    private static final class InterruptibleSlice
            extends SlicedSeekableByteChannel implements InterruptibleChannel {
        /// Creates an interruptible slice.
        private InterruptibleSlice(SeekableByteChannel source, long offset, long size) {
            super(source, offset, size);
        }
    }
}

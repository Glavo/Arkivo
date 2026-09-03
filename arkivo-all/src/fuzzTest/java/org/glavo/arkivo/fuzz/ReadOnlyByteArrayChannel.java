// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/// Exposes one fuzz input as a read-only in-memory seekable channel.
@NotNullByDefault
final class ReadOnlyByteArrayChannel implements SeekableByteChannel {
    /// The immutable channel content.
    private final byte @Unmodifiable [] content;

    /// The largest positive byte count returned by one read.
    private final int maximumReadSize;

    /// The current logical position, which may be beyond end-of-input.
    private long position;

    /// Whether the channel accepts further operations.
    private boolean open = true;

    /// Creates a channel over the supplied immutable fuzz input.
    ///
    /// @param bytes the content retained for the lifetime of this channel
    ReadOnlyByteArrayChannel(byte @Unmodifiable [] bytes) {
        this(bytes, Integer.MAX_VALUE);
    }

    /// Creates a channel over the supplied immutable fuzz input with bounded short reads.
    ///
    /// @param bytes the content retained for the lifetime of this channel
    /// @param maximumReadSize the positive maximum byte count returned by one read
    ReadOnlyByteArrayChannel(byte @Unmodifiable [] bytes, int maximumReadSize) {
        if (maximumReadSize <= 0) {
            throw new IllegalArgumentException("maximumReadSize must be positive");
        }
        content = bytes;
        this.maximumReadSize = maximumReadSize;
    }

    /// Reads available bytes into the target.
    ///
    /// @param target the writable target buffer
    /// @return the number of bytes read, or `-1` at end-of-input
    /// @throws IOException if this channel is closed
    @Override
    public int read(ByteBuffer target) throws IOException {
        requireOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        if (position >= content.length) {
            return -1;
        }
        int count = Math.min(
                Math.min(content.length - Math.toIntExact(position), target.remaining()),
                maximumReadSize
        );
        target.put(content, Math.toIntExact(position), count);
        position += count;
        return count;
    }

    /// Rejects writes.
    ///
    /// @param source the ignored source buffer
    /// @return this method never returns normally
    /// @throws NonWritableChannelException always
    @Override
    public int write(ByteBuffer source) throws NonWritableChannelException {
        throw new NonWritableChannelException();
    }

    /// Returns the current logical position.
    ///
    /// @return the current zero-based position
    /// @throws IOException if this channel is closed
    @Override
    public long position() throws IOException {
        requireOpen();
        return position;
    }

    /// Changes the current logical position.
    ///
    /// @param newPosition the nonnegative new position, which may exceed the content size
    /// @return this channel
    /// @throws IOException if this channel is closed
    /// @throws IllegalArgumentException if the position is negative
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        requireOpen();
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the fixed content size.
    ///
    /// @return the byte count exposed by this channel
    /// @throws IOException if this channel is closed
    @Override
    public long size() throws IOException {
        requireOpen();
        return content.length;
    }

    /// Rejects truncation.
    ///
    /// @param size the ignored requested size
    /// @return this method never returns normally
    /// @throws NonWritableChannelException always
    @Override
    public SeekableByteChannel truncate(long size) throws NonWritableChannelException {
        throw new NonWritableChannelException();
    }

    /// Returns whether this channel remains open.
    ///
    /// @return `true` until [#close()] is called
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this channel without changing its retained bytes.
    @Override
    public void close() {
        open = false;
    }

    /// Requires this channel to be open.
    ///
    /// @throws ClosedChannelException if the channel has been closed
    private void requireOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}

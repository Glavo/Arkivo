// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Adapts an output stream to a sequential writable channel with a stable byte position.
///
/// The position counts bytes from stream writes that completed normally. If a later write fails, completed chunks
/// remain consumed from the source buffer and included in the position. A failed stream write may have emitted bytes
/// without reporting their number; such bytes are not included in either position. Whether writing can resume after
/// failure depends on the wrapped stream. This channel is not safe for concurrent use.
@NotNullByDefault
public final class ForwardOnlyOutputChannel implements SeekableByteChannel {
    /// The stream adapter that owns the output and advances only successfully written buffer ranges.
    private final WritableByteChannel output;

    /// The current sequential write position.
    private long position;

    /// Creates a forward-only channel over the given output stream.
    ///
    /// @param output the output stream owned and closed by this channel
    public ForwardOnlyOutputChannel(OutputStream output) {
        this.output = StreamChannelAdapters.writableChannel(output);
    }

    /// Rejects reads because the wrapped endpoint is output-only.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        throw new NonReadableChannelException();
    }

    /// Writes all remaining bytes, retaining completed-chunk progress if a later chunk fails.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        Math.addExact(position, source.remaining());
        int start = source.position();
        try {
            return output.write(source);
        } finally {
            position += source.position() - start;
        }
    }

    /// Returns the current sequential write position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Accepts only the current position because prior output cannot be revisited.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        if (newPosition != position) {
            throw new UnsupportedOperationException("Output channel is forward-only");
        }
        return this;
    }

    /// Returns the number of bytes written through this channel.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return position;
    }

    /// Accepts only the current size because prior output cannot be truncated.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (size != position) {
            throw new UnsupportedOperationException("Forward-only output cannot be truncated");
        }
        return this;
    }

    /// Returns whether this channel remains open.
    @Override
    public boolean isOpen() {
        return output.isOpen();
    }

    /// Closes the wrapped output stream.
    ///
    /// A failed close leaves this channel logically open so a later call can retry cleanup.
    @Override
    public void close() throws IOException {
        output.close();
    }

    /// Requires this channel to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }
}

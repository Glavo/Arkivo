// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Adapts an output stream to a sequential writable channel with a stable byte position.
@NotNullByDefault
public final class ForwardOnlyOutputChannel implements SeekableByteChannel {
    /// The direct-buffer transfer size.
    private static final int TRANSFER_BUFFER_SIZE = 8192;

    /// The wrapped output stream.
    private final OutputStream output;

    /// Reusable transfer storage for non-array buffers.
    private byte @Nullable [] transferBuffer;

    /// The current sequential write position.
    private long position;

    /// Whether this channel remains open.
    private boolean open = true;

    /// Creates a forward-only channel over the given output stream.
    ///
    /// @param output the output stream owned and closed by this channel
    public ForwardOnlyOutputChannel(OutputStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /// Rejects reads because the wrapped endpoint is output-only.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        throw new NonReadableChannelException();
    }

    /// Writes all remaining source bytes at the current sequential position.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        int length = source.remaining();
        long newPosition = Math.addExact(position, length);
        if (source.hasArray()) {
            int offset = source.arrayOffset() + source.position();
            output.write(source.array(), offset, length);
            source.position(source.limit());
        } else if (length != 0) {
            byte[] buffer = transferBuffer;
            if (buffer == null) {
                buffer = new byte[TRANSFER_BUFFER_SIZE];
                transferBuffer = buffer;
            }
            while (source.hasRemaining()) {
                int count = Math.min(source.remaining(), buffer.length);
                source.get(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        position = newPosition;
        return length;
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
        return open;
    }

    /// Closes the wrapped output stream.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        output.close();
    }

    /// Requires this channel to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}

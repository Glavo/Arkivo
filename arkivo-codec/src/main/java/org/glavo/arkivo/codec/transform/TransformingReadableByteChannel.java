// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Applies a stateful byte transform while reading from a channel.
@NotNullByDefault
public final class TransformingReadableByteChannel implements ReadableByteChannel {
    /// The bounded filter working-buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// The upstream channel.
    private final ReadableByteChannel source;

    /// Whether this channel owns the upstream source.
    private final ChannelOwnership ownership;

    /// The stateful in-place transform.
    private final ByteTransform transform;

    /// The filter working buffer containing ready and pending bytes.
    private final byte[] buffer = new byte[BUFFER_SIZE];

    /// The channel view used to fill `buffer`.
    private final ByteBuffer inputBuffer = ByteBuffer.wrap(buffer);

    /// The first ready byte in `buffer`.
    private int position;

    /// The number of transformed bytes ready to return.
    private int ready;

    /// The number of trailing bytes awaiting more input.
    private int pending;

    /// A deferred source or transform failure.
    private @Nullable IOException failure;

    /// Whether the upstream source reached end-of-input.
    private boolean endReached;

    /// Whether this channel remains open.
    private boolean open = true;

    /// Creates a transforming channel retaining its source.
    public TransformingReadableByteChannel(ReadableByteChannel source, ByteTransform transform) {
        this(source, transform, ChannelOwnership.RETAIN);
    }

    /// Creates a transforming channel with explicit source ownership.
    public TransformingReadableByteChannel(
            ReadableByteChannel source,
            ByteTransform transform,
            ChannelOwnership ownership
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    /// Reads transformed bytes into the target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        if (failure != null) {
            throw failure;
        }

        try {
            int total = 0;
            while (true) {
                int copied = Math.min(ready, target.remaining());
                target.put(buffer, position, copied);
                position += copied;
                ready -= copied;
                total += copied;

                if (position + ready + pending == buffer.length) {
                    compact();
                }
                if (!target.hasRemaining() || endReached) {
                    return total == 0 && endReached ? -1 : total;
                }

                int writePosition = position + ready + pending;
                inputBuffer.position(writePosition);
                inputBuffer.limit(buffer.length);
                int count = source.read(inputBuffer);
                if (count < 0) {
                    endReached = true;
                    ready = pending;
                    pending = 0;
                } else if (count == 0) {
                    throw new IOException("Byte filter source channel made no progress");
                } else {
                    filterPending(count);
                }
            }
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        }
    }

    /// Returns whether this channel remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this channel and optionally its source.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        if (ownership == ChannelOwnership.CLOSE) {
            source.close();
        }
    }

    /// Adds newly read bytes and transforms the largest complete prefix.
    private void filterPending(int added) throws IOException {
        pending += added;
        int transformed = transform.transform(buffer, position, pending);
        if (transformed < 0 || transformed > pending) {
            throw new IOException("Byte filter returned an invalid transformed byte count");
        }
        ready = transformed;
        pending -= transformed;
        if (ready == 0 && position + pending == buffer.length) {
            throw new IOException("Byte filter made no progress with a full buffer");
        }
    }

    /// Moves ready and pending bytes to the beginning of the working buffer.
    private void compact() {
        System.arraycopy(buffer, position, buffer, 0, ready + pending);
        position = 0;
    }

    /// Requires this channel to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Applies a stateful byte transform before writing to a channel.
@NotNullByDefault
public final class TransformingWritableByteChannel implements WritableByteChannel {
    /// The bounded filter working-buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// The downstream channel.
    private final WritableByteChannel target;

    /// Whether this channel owns the downstream target.
    private final ChannelOwnership ownership;

    /// The stateful in-place transform.
    private final ByteTransform transform;

    /// The filter working buffer.
    private final byte[] buffer = new byte[BUFFER_SIZE];

    /// The first pending byte in `buffer`.
    private int position;

    /// The number of bytes awaiting enough lookahead.
    private int pending;

    /// A deferred target or transform failure.
    private @Nullable IOException failure;

    /// Whether all pending filter bytes have been forwarded.
    private boolean finished;

    /// Whether this channel remains open.
    private boolean open = true;

    /// Creates a transforming channel retaining its target.
    public TransformingWritableByteChannel(WritableByteChannel target, ByteTransform transform) {
        this(target, transform, ChannelOwnership.RETAIN);
    }

    /// Creates a transforming channel with explicit target ownership.
    public TransformingWritableByteChannel(
            WritableByteChannel target,
            ByteTransform transform,
            ChannelOwnership ownership
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    /// Consumes untransformed bytes from the source buffer.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureWritable();
        int start = source.position();
        while (source.hasRemaining()) {
            int copied = Math.min(source.remaining(), buffer.length - position - pending);
            source.get(buffer, position + pending, copied);
            pending += copied;
            filterPending();
            if (position + pending == buffer.length) {
                compact();
                if (pending == buffer.length) {
                    throw new IOException("Byte filter made no progress with a full buffer");
                }
            }
        }
        return source.position() - start;
    }

    /// Forwards the incomplete transform tail unchanged.
    public void finish() throws IOException {
        ensureOpen();
        if (finished) {
            return;
        }
        if (failure != null) {
            throw failure;
        }
        try {
            writeFully(ByteBuffer.wrap(buffer, position, pending));
            position = 0;
            pending = 0;
            finished = true;
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

    /// Finishes this transform and optionally closes its target.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        @Nullable Throwable closeFailure = null;
        try {
            finish();
        } catch (IOException | RuntimeException | Error exception) {
            closeFailure = exception;
        }
        open = false;
        if (ownership == ChannelOwnership.CLOSE) {
            try {
                target.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
        }
        rethrow(closeFailure);
    }

    /// Transforms and forwards the largest complete prefix.
    private void filterPending() throws IOException {
        int transformed = transform.transform(buffer, position, pending);
        if (transformed < 0 || transformed > pending) {
            throw new IOException("Byte filter returned an invalid transformed byte count");
        }
        try {
            writeFully(ByteBuffer.wrap(buffer, position, transformed));
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        }
        position += transformed;
        pending -= transformed;
    }

    /// Writes every remaining byte in the supplied buffer.
    private void writeFully(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            int written = target.write(bytes);
            if (written == 0) {
                throw new IOException("Byte filter target channel made no progress");
            }
        }
    }

    /// Moves pending bytes to the beginning of the working buffer.
    private void compact() {
        System.arraycopy(buffer, position, buffer, 0, pending);
        position = 0;
    }

    /// Requires this channel to remain open and unfinished.
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished) {
            throw new IOException("Byte filter channel has already finished");
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Requires this channel to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Rethrows a close-time failure with its original type.
    private static void rethrow(@Nullable Throwable throwable) throws IOException {
        if (throwable == null) {
            return;
        }
        if (throwable instanceof IOException exception) {
            throw exception;
        }
        if (throwable instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) throwable;
    }
}

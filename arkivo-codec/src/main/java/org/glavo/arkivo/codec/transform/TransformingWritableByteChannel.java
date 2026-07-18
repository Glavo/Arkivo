// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Applies a stateful byte transform before writing to a channel.
///
/// This channel is stateful and not safe for concurrent use. Successful writes consume every remaining source byte,
/// though an incomplete transform suffix may remain buffered. Transformed prefixes are written fully to the target; a
/// zero-progress target or a transform that fills the bounded working buffer without committing a prefix causes
/// `IOException`.
///
/// [#finish()] forwards the incomplete suffix unchanged, leaves the target open, and permanently ends writes through
/// this wrapper. [#close()] finishes once and then leaves a borrowed target open or closes an owned target. A finish
/// failure still closes this wrapper for writes and is not retried; a failed owned-target close can be retried.
@NotNullByDefault
public final class TransformingWritableByteChannel implements WritableByteChannel {
    /// The bounded filter working-buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// The downstream channel.
    private final WritableByteChannel target;

    /// Tracks closure of the owned downstream target.
    private final OwnedChannelCloser targetCloser;

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

    /// Creates a transforming channel that borrows and therefore does not close its target.
    ///
    /// @param target the downstream channel to write without closing
    /// @param transform the stateful transform to apply before writing to `target`
    public TransformingWritableByteChannel(WritableByteChannel target, ByteTransform transform) {
        this(target, transform, ResourceOwnership.BORROWED);
    }

    /// Creates a transforming channel with explicit target ownership.
    ///
    /// @param target the downstream channel to write
    /// @param transform the stateful transform to apply before writing to `target`
    /// @param ownership whether closing this wrapper also closes `target`
    public TransformingWritableByteChannel(
            WritableByteChannel target,
            ByteTransform transform,
            ResourceOwnership ownership
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.targetCloser = new OwnedChannelCloser(target, ownership);
    }

    /// Consumes untransformed bytes from the source buffer.
    ///
    /// On success the source position reaches its original limit and the limit is unchanged. On failure the position
    /// identifies bytes already accepted into this transform pipeline.
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

    /// Forwards the incomplete transform tail unchanged and ends this transform sequence.
    ///
    /// This method is idempotent after success and does not close the target. Later writes fail with `IOException`.
    ///
    /// @throws IOException if this channel is closed, a prior failure is pending, or the tail cannot be written
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

    /// Finishes this transform and applies its target-ownership policy.
    ///
    /// `isOpen()` becomes false even if finishing or target closure throws. A close failure is suppressed on an earlier
    /// finish failure, and later calls retry only an incomplete owned-target close.
    @Override
    public void close() throws IOException {
        @Nullable Throwable closeFailure = null;
        if (open) {
            try {
                finish();
            } catch (IOException | RuntimeException | Error exception) {
                closeFailure = exception;
            }
            open = false;
        }
        targetCloser.closeAfter(closeFailure);
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

}

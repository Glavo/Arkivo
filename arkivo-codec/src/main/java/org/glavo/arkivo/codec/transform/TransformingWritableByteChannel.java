// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.internal.OwnedChannelCloser;
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
///
/// A transform or downstream-write failure is terminal. Later writes and finish calls rethrow that failure without
/// invoking the transform or writing buffered bytes again. Argument validation failures do not enter this state.
@NotNullByDefault
public final class TransformingWritableByteChannel implements WritableByteChannel {
    /// The downstream channel.
    private final WritableByteChannel target;

    /// Tracks closure of the owned downstream target.
    private final OwnedChannelCloser targetCloser;

    /// The committed-prefix and lookahead state shared by all transform adapters.
    private final TransformBuffer buffer;

    /// A deferred target or transform failure.
    private @Nullable Throwable failure;

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
        this.buffer = new TransformBuffer(transform);
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
        try {
            while (source.hasRemaining()) {
                ByteBuffer writable = buffer.input();
                int copied = Math.min(source.remaining(), writable.remaining());
                source.get(writable.array(), writable.position(), copied);
                writable.position(writable.position() + copied);
                buffer.transform();
                writeFully(buffer.output());
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
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
        rethrowFailure();
        try {
            buffer.finish();
            writeFully(buffer.output());
            finished = true;
        } catch (IOException | RuntimeException | Error exception) {
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

    /// Writes every remaining byte in the supplied buffer.
    private void writeFully(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            int written = target.write(bytes);
            if (written == 0) {
                throw new IOException("Byte filter target channel made no progress");
            }
        }
    }

    /// Requires this channel to remain open and unfinished.
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished) {
            throw new IOException("Byte filter channel has already finished");
        }
        rethrowFailure();
    }

    /// Rethrows the terminal operation failure without changing its type or identity.
    private void rethrowFailure() throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /// Requires this channel to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

}

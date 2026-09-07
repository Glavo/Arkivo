// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.internal.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Applies a stateful byte transform while reading from a channel.
///
/// This channel is stateful and not safe for concurrent use. A nonempty read may block while obtaining lookahead and
/// advances only the target position; its limit is unchanged. When the source reaches end-of-input, an incomplete suffix
/// retained by the transform is returned unchanged before this channel reports `-1`.
///
/// A source or transform failure (`IOException`, `RuntimeException`, or `Error`) is retained and rethrown by later
/// nonempty reads without reading or transforming more data. Bytes already delivered to the target remain there.
/// Rejected arguments do not put this channel into a failed state. Closing
/// does not drain input. It leaves a borrowed source open and closes an owned source; a failed owned-source close can be
/// retried by calling [#close()] again.
@NotNullByDefault
public final class TransformingReadableByteChannel implements ReadableByteChannel {
    /// The upstream channel.
    private final ReadableByteChannel source;

    /// Tracks closure of the owned upstream source.
    private final OwnedChannelCloser sourceCloser;

    /// The committed-prefix and lookahead state shared by all transform adapters.
    private final TransformBuffer buffer;

    /// A deferred source or transform failure.
    private @Nullable Throwable failure;

    /// Whether the upstream source reached end-of-input.
    private boolean endReached;

    /// Whether this channel remains open.
    private boolean open = true;

    /// Creates a transforming channel that borrows and therefore does not close its source.
    ///
    /// @param source the upstream channel to read without closing
    /// @param transform the stateful transform to apply to bytes read from `source`
    public TransformingReadableByteChannel(ReadableByteChannel source, ByteTransform transform) {
        this(source, transform, ResourceOwnership.BORROWED);
    }

    /// Creates a transforming channel with explicit source ownership.
    ///
    /// The channel and transform are retained until this wrapper closes. `OWNED` makes [#close()] close the source;
    /// `BORROWED` leaves it open.
    ///
    /// @param source the upstream channel to read
    /// @param transform the stateful transform to apply to bytes read from `source`
    /// @param ownership whether closing this wrapper also closes `source`
    public TransformingReadableByteChannel(
            ReadableByteChannel source,
            ByteTransform transform,
            ResourceOwnership ownership
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.buffer = new TransformBuffer(transform);
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
    }

    /// Reads transformed bytes into the target buffer.
    ///
    /// An empty target returns zero without reading the source. Otherwise this method returns a positive count or `-1`;
    /// a source that returns zero before producing data causes `IOException` rather than a zero-progress result.
    /// A nonempty read-only target is rejected before consuming input or buffered data.
    ///
    /// @throws ReadOnlyBufferException if this channel is open and has not failed, and the target is nonempty and read-only
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        rethrowFailure();
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }

        try {
            int total = 0;
            while (true) {
                ByteBuffer ready = buffer.output();
                int copied = Math.min(ready.remaining(), target.remaining());
                target.put(ready.array(), ready.position(), copied);
                ready.position(ready.position() + copied);
                total += copied;

                if (!target.hasRemaining() || endReached) {
                    return total == 0 && endReached ? -1 : total;
                }

                int count = source.read(buffer.input());
                if (count < 0) {
                    endReached = true;
                    buffer.finish();
                } else if (count == 0) {
                    throw new IOException("Byte filter source channel made no progress");
                } else {
                    buffer.transform();
                }
            }
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

    /// Marks this channel closed and applies its source-ownership policy.
    ///
    /// `isOpen()` becomes false even if closing an owned source throws. A later call retries only source closure.
    @Override
    public void close() throws IOException {
        open = false;
        sourceCloser.close();
    }

    /// Rethrows a retained read failure without invoking the source or transform again.
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

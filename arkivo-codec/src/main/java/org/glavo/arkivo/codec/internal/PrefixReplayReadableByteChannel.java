// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Replays an owned prefix before continuing from a readable channel.
@NotNullByDefault
public class PrefixReplayReadableByteChannel implements ReadableByteChannel, ForceCloseableChannel {
    /// Bytes consumed while probing the backing channel.
    private final @UnmodifiableView ByteBuffer prefix;

    /// The channel positioned immediately after the replay prefix.
    private final ReadableByteChannel source;

    /// Whether closing this channel closes the source.
    private final ResourceOwnership ownership;

    /// Whether this channel remains open.
    private volatile boolean open = true;

    /// Whether source closure must be completed or retried.
    private boolean sourceCloseRequired;

    /// Whether the pending source closure must cross nested ownership boundaries.
    private boolean forceSourceCloseRequired;

    /// The number of interruptible reads that have begun but not returned.
    private int activeReads;

    /// Creates a prefix-replaying channel that preserves source interruption support.
    ///
    /// @param prefix    the remaining probe bytes replayed through an independent read-only view
    /// @param source    the channel positioned immediately after the probe bytes
    /// @param ownership whether closing the replay channel also closes {@code source}
    /// @return a replaying channel that implements [InterruptibleChannel] exactly when {@code source} does
    public static PrefixReplayReadableByteChannel create(
            ByteBuffer prefix,
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        if (source instanceof InterruptibleChannel) {
            return new InterruptiblePrefixReplayReadableByteChannel(prefix, source, ownership);
        }
        return new PrefixReplayReadableByteChannel(prefix, source, ownership);
    }

    /// Creates a prefix-replaying channel.
    ///
    /// @param prefix    the remaining probe bytes replayed through an independent read-only view
    /// @param source    the channel positioned immediately after the probe bytes
    /// @param ownership whether closing this replay channel also closes {@code source}
    private PrefixReplayReadableByteChannel(
            ByteBuffer prefix,
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").slice().asReadOnlyBuffer();
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    /// Reads replay bytes before reading from the backing source.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        if (prefix.hasRemaining()) {
            int count = Math.min(prefix.remaining(), target.remaining());
            ByteBuffer chunk = prefix.slice();
            chunk.limit(count);
            target.put(chunk);
            prefix.position(prefix.position() + count);
            return count;
        }
        return source.read(target);
    }

    /// Returns whether this channel and its backing source remain open.
    @Override
    public boolean isOpen() {
        return open && source.isOpen();
    }

    /// Closes this channel and optionally closes the backing source.
    @Override
    public synchronized void close() throws IOException {
        if (open) {
            open = false;
            if (ownership == ResourceOwnership.OWNED) {
                sourceCloseRequired = true;
            }
            if (activeReads > 0) {
                sourceCloseRequired = true;
                forceSourceCloseRequired = true;
            }
        }
        if (sourceCloseRequired) {
            if (forceSourceCloseRequired) {
                ForceCloseableChannel.forceClose(source);
            } else {
                source.close();
            }
            sourceCloseRequired = false;
            forceSourceCloseRequired = false;
        }
    }

    /// Closes this replay channel and its source regardless of ordinary source ownership.
    ///
    /// @throws IOException if the source cannot be closed
    @Override
    public synchronized void forceClose() throws IOException {
        open = false;
        sourceCloseRequired = true;
        forceSourceCloseRequired = true;
        ForceCloseableChannel.forceClose(source);
        sourceCloseRequired = false;
        forceSourceCloseRequired = false;
    }

    /// Requires this channel and its source to remain open.
    ///
    /// @throws ClosedChannelException if this replay channel or its source is closed
    protected final void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Closes the logical channel and its source after thread interruption.
    ///
    /// @param failure the interruption failure that receives any suppressed close failure
    protected final void closeAfterInterrupt(ClosedByInterruptException failure) {
        try {
            forceClose();
        } catch (IOException | RuntimeException | Error exception) {
            failure.addSuppressed(exception);
        }
    }

    /// Begins one interruptible read after validating channel and thread state.
    ///
    /// @throws ClosedChannelException     if this replay channel or its source is closed
    /// @throws ClosedByInterruptException if the current thread is already interrupted
    protected final void beginInterruptibleRead()
            throws ClosedChannelException, ClosedByInterruptException {
        synchronized (this) {
            ensureOpen();
            if (!Thread.currentThread().isInterrupted()) {
                activeReads++;
                return;
            }
        }
        ClosedByInterruptException failure = new ClosedByInterruptException();
        closeAfterInterrupt(failure);
        throw failure;
    }

    /// Completes one interruptible read.
    protected final synchronized void endInterruptibleRead() {
        if (activeReads <= 0) {
            throw new AssertionError("No interruptible read is active");
        }
        activeReads--;
    }

    /// Returns whether this replay channel is logically open independently of source state.
    ///
    /// @return `true` while this replay channel has not been closed
    protected final boolean isLogicallyOpen() {
        return open;
    }

    /// Marks this replay channel logically closed after its source reports asynchronous closure.
    protected final void markLogicallyClosed() {
        open = false;
    }

    /// Preserves interruption semantics while the replayed prefix is served from memory.
    @NotNullByDefault
    private static final class InterruptiblePrefixReplayReadableByteChannel
            extends PrefixReplayReadableByteChannel
            implements InterruptibleChannel {
        /// Creates an interruptible prefix-replaying channel.
        private InterruptiblePrefixReplayReadableByteChannel(
                ByteBuffer prefix,
                ReadableByteChannel source,
                ResourceOwnership ownership
        ) {
            super(prefix, source, ownership);
        }

        /// Reads replayed or source bytes with interruptible-channel pre-interruption behavior.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            beginInterruptibleRead();
            try {
                int read;
                try {
                    read = super.read(target);
                } catch (ClosedByInterruptException failure) {
                    closeAfterInterrupt(failure);
                    throw failure;
                } catch (AsynchronousCloseException failure) {
                    markLogicallyClosed();
                    throw failure;
                } catch (IOException failure) {
                    if (isLogicallyOpen()) {
                        throw failure;
                    }
                    AsynchronousCloseException asynchronousFailure = new AsynchronousCloseException();
                    asynchronousFailure.addSuppressed(failure);
                    throw asynchronousFailure;
                }

                if (Thread.currentThread().isInterrupted()) {
                    ClosedByInterruptException failure = new ClosedByInterruptException();
                    closeAfterInterrupt(failure);
                    throw failure;
                }
                if (!isLogicallyOpen()) {
                    throw new AsynchronousCloseException();
                }
                return read;
            } finally {
                endInterruptibleRead();
            }
        }
    }
}

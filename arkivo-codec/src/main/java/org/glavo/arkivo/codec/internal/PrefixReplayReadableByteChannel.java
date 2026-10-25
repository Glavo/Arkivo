// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.internal.ForceCloseable;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/// Replays an owned prefix before continuing from a readable channel.
@NotNullByDefault
public class PrefixReplayReadableByteChannel implements ReadableByteChannel, ForceCloseable {
    /// Serializes prefix and source reads independently of the lifecycle monitor.
    protected final Object readLock = new Object();

    /// Serializes ordinary close attempts without preventing forced source cancellation.
    private final Object closeLock = new Object();

    /// Serializes forced cleanup independently of an ordinary close that may be draining input.
    private final Object forceCloseLock = new Object();

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

    /// Protects exception bookkeeping independently of source closure and interruptible read state.
    private final Object failureLock = new Object();

    /// Delivered exception identities, retained across cancellation while graceful cleanup may still be in flight.
    /// Guarded by the failure lock.
    private @Nullable Set<Throwable> reportedFailures;

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
        this.prefix = Objects.requireNonNull(prefix, "prefix").asReadOnlyBuffer();
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    /// Reads replay bytes before reading from the backing source.
    @Override
    public int read(ByteBuffer target) throws IOException {
        synchronized (readLock) {
            return readLocked(target);
        }
    }

    /// Reads while holding the read lock.
    private int readLocked(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (target.isReadOnly()) {
            throw new IllegalArgumentException("Target buffer is read-only");
        }
        if (!target.hasRemaining()) {
            return 0;
        }
        if (prefix.hasRemaining()) {
            int count = Math.min(prefix.remaining(), target.remaining());
            target.put(target.position(), prefix, prefix.position(), count);
            target.position(target.position() + count);
            prefix.position(prefix.position() + count);
            return count;
        }
        try {
            return source.read(target);
        } catch (IOException | RuntimeException | Error failure) {
            recordFailure(failure);
            throw failure;
        }
    }

    /// Returns whether this channel and its backing source remain open.
    @Override
    public boolean isOpen() {
        return open && source.isOpen();
    }

    /// Closes this channel and optionally closes the backing source.
    ///
    /// Failed source cleanup can be retried. An exception already reported by a read or close is wrapped in a new
    /// `IOException` if it recurs during cleanup, so automatic resource closure preserves the primary exception.
    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            synchronized (this) {
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
                if (!sourceCloseRequired) return;
            }
            closeSource();
        }
    }

    /// Closes this replay channel and its source regardless of ordinary source ownership.
    ///
    /// @throws IOException if the source cannot be closed
    @Override
    public void forceClose() throws IOException {
        synchronized (forceCloseLock) {
            synchronized (this) {
                open = false;
                sourceCloseRequired = true;
                forceSourceCloseRequired = true;
            }
            closeSource();
        }
    }

    /// Performs source cleanup outside the lifecycle monitor, retaining any concurrent escalation to forced close.
    private void closeSource() throws IOException {
        boolean force;
        synchronized (this) {
            force = forceSourceCloseRequired;
        }
        try {
            if (force) {
                ForceCloseable.forceClose(source);
            } else {
                source.close();
            }
            synchronized (this) {
                if (force || !forceSourceCloseRequired) {
                    sourceCloseRequired = false;
                    if (!forceSourceCloseRequired) {
                        synchronized (failureLock) {
                            reportedFailures = null;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (!recordFailure(failure)) {
                IOException cleanup = new IOException("Replay source cleanup repeated an earlier failure", failure);
                recordFailure(cleanup);
                throw cleanup;
            }
            throw failure;
        }
    }

    /// Records an exception independently of source closure and lifecycle state transitions.
    private boolean recordFailure(Throwable failure) {
        synchronized (failureLock) {
            if (reportedFailures == null) {
                reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return reportedFailures.add(failure);
        }
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
            if (failure != exception && failure != exception.getCause()) {
                failure.addSuppressed(exception);
            }
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
            synchronized (readLock) {
                return readInterruptibly(target);
            }
        }

        /// Registers and completes one read after acquiring exclusive read access.
        private int readInterruptibly(ByteBuffer target) throws IOException {
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

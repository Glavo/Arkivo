// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/// Exposes independent logical read views over one owned seekable channel.
@NotNullByDefault
public final class SharedSeekableChannelSource implements ArkivoSeekableChannelSource {
    /// The owned physical channel.
    private final SeekableByteChannel channel;

    /// Serializes physical channel positioning and reads.
    private final ReentrantLock lock = new ReentrantLock();

    /// Serializes source close attempts without waiting for a blocked physical read.
    private final Object closeLock = new Object();

    /// Protects exception bookkeeping without holding a lock across physical I/O or closure.
    private final Object failureLock = new Object();

    /// The physical offset represented by logical position zero.
    private final long origin;

    /// The fixed logical archive size.
    private final long size;

    /// Whether source closure has completed successfully.
    private volatile boolean closed;

    /// Previously delivered physical-channel failures, guarded by the failure lock and allocated only on failure.
    private @Nullable Set<Throwable> reportedFailures;

    /// Creates an owning shared source and closes the channel if initial metadata access fails.
    ///
    /// @param channel the physical channel whose ownership is transferred after argument validation
    /// @return a source exposing independent logical views over the channel's initial remaining extent
    /// @throws IOException if the initial channel position or size cannot be read or is inconsistent
    public static SharedSeekableChannelSource open(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        try {
            return new SharedSeekableChannelSource(channel);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error closeException) {
                if (exception != closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw exception;
        }
    }
    /// Creates a shared source from the channel's current position through its current size.
    private SharedSeekableChannelSource(SeekableByteChannel channel) throws IOException {
        this.channel = Objects.requireNonNull(channel, "channel");
        long origin = channel.position();
        long physicalSize = channel.size();
        if (origin < 0L || origin > physicalSize) {
            throw new IOException("Seekable archive channel position is outside its size");
        }
        this.origin = origin;
        this.size = physicalSize - origin;
    }

    /// Opens a new position-independent logical view of the archive bytes.
    @Override
    public SeekableByteChannel openChannel() throws IOException {
        lock.lock();
        try {
            ensureSourceOpen();
            return new View();
        } finally {
            lock.unlock();
        }
    }

    /// Closes the physical channel and commits source closure only after success.
    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error failure) {
                if (!recordFailure(failure)) {
                    IOException repeated = new IOException("Shared archive source cleanup failed again", failure);
                    recordFailure(repeated);
                    throw repeated;
                }
                throw failure;
            }
            synchronized (failureLock) {
                closed = true;
                reportedFailures = null;
            }
        }
    }

    /// Records a physical failure unless cleanup has completed, and reports whether it has not been delivered before.
    private boolean recordFailure(Throwable failure) {
        synchronized (failureLock) {
            if (closed) {
                return true;
            }
            if (reportedFailures == null) {
                reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return reportedFailures.add(failure);
        }
    }

    /// Requires the source and physical channel to remain open.
    private void ensureSourceOpen() throws ClosedChannelException {
        if (closed || !channel.isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Provides one independent logical position over the shared physical channel.
    @NotNullByDefault
    private final class View implements SeekableByteChannel {
        /// The logical read position.
        private long position;

        /// Whether this logical view remains open.
        private boolean open = true;

        /// Reads from this view's logical position without exposing shared physical position state.
        ///
        /// Bytes placed in the target before a delegate failure remain consumed from both the target and this view.
        /// The target limit is not changed.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            lock.lock();
            try {
                ensureOpen();
                if (target.isReadOnly()) {
                    throw new IllegalArgumentException("Target buffer is read-only");
                }
                if (!target.hasRemaining()) {
                    return 0;
                }
                if (position >= size) {
                    return -1;
                }

                int count = (int) Math.min((long) target.remaining(), size - position);
                int targetPosition = target.position();
                ByteBuffer bounded = target.duplicate();
                bounded.limit(bounded.position() + count);
                try {
                    channel.position(origin + position);
                    try {
                        return channel.read(bounded);
                    } finally {
                        int transferred = bounded.position() - targetPosition;
                        if (transferred > 0) {
                            target.position(targetPosition + transferred);
                            position += transferred;
                        }
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    recordFailure(failure);
                    throw failure;
                }
            } finally {
                lock.unlock();
            }
        }

        /// Rejects writes because archive channel sources are read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            lock.lock();
            try {
                ensureOpen();
                throw new NonWritableChannelException();
            } finally {
                lock.unlock();
            }
        }

        /// Returns this view's logical position.
        @Override
        public long position() throws IOException {
            lock.lock();
            try {
                ensureOpen();
                return position;
            } finally {
                lock.unlock();
            }
        }

        /// Changes this view's logical position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            lock.lock();
            try {
                ensureOpen();
                position = newPosition;
                return this;
            } finally {
                lock.unlock();
            }
        }

        /// Returns the fixed logical archive size.
        @Override
        public long size() throws IOException {
            lock.lock();
            try {
                ensureOpen();
                return size;
            } finally {
                lock.unlock();
            }
        }

        /// Rejects truncation because archive channel sources are read-only.
        @Override
        public SeekableByteChannel truncate(long newSize) throws IOException {
            lock.lock();
            try {
                ensureOpen();
                if (newSize < 0L) {
                    throw new IllegalArgumentException("newSize must not be negative");
                }
                throw new NonWritableChannelException();
            } finally {
                lock.unlock();
            }
        }

        /// Returns whether this view and its source remain open.
        @Override
        public boolean isOpen() {
            lock.lock();
            try {
                return open && !closed && channel.isOpen();
            } finally {
                lock.unlock();
            }
        }

        /// Closes only this logical view.
        @Override
        public void close() {
            lock.lock();
            try {
                open = false;
            } finally {
                lock.unlock();
            }
        }

        /// Requires this view and its source to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
            ensureSourceOpen();
        }
    }
}

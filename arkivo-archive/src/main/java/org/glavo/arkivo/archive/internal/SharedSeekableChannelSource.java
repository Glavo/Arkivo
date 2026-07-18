// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// Exposes independent logical read views over one owned seekable channel.
@NotNullByDefault
public final class SharedSeekableChannelSource implements ArkivoSeekableChannelSource {
    /// The owned physical channel.
    private final SeekableByteChannel channel;

    /// Serializes physical channel positioning, reads, and closure.
    private final ReentrantLock lock = new ReentrantLock();

    /// The physical offset represented by logical position zero.
    private final long origin;

    /// The fixed logical archive size.
    private final long size;

    /// Whether source closure has completed successfully.
    private boolean closed;

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
                exception.addSuppressed(closeException);
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
        lock.lock();
        try {
            if (closed) {
                return;
            }
            channel.close();
            closed = true;
        } finally {
            lock.unlock();
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
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            lock.lock();
            try {
                ensureOpen();
                if (target.isReadOnly()) {
                    throw new ReadOnlyBufferException();
                }
                if (!target.hasRemaining()) {
                    return 0;
                }
                if (position >= size) {
                    return -1;
                }

                int count = (int) Math.min((long) target.remaining(), size - position);
                ByteBuffer bounded = target.duplicate();
                bounded.limit(bounded.position() + count);
                channel.position(origin + position);
                int read = channel.read(bounded);
                if (read > 0) {
                    target.position(target.position() + read);
                    position += read;
                }
                return read;
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
                throw new UnsupportedOperationException("Shared archive channel views are read-only");
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
                throw new UnsupportedOperationException("Shared archive channel views are read-only");
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

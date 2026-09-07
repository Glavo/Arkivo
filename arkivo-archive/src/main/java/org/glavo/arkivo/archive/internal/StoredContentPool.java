// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/// Owns staged contents and closes their backing storage after every content and channel has been released.
///
/// Closing the pool prevents new content and channel opens. It does not close channels still in use or wait for them;
/// their eventual closure advances deferred cleanup. Failed channel cleanup is retried before its content is released,
/// and failed content cleanup is retried before the storage is closed. A subsequent close retries incomplete cleanup.
/// Lifecycle operations are serialized; individual channels retain their underlying concurrency and blocking behavior.
@NotNullByDefault
public final class StoredContentPool implements ArkivoEditStorage {
    /// The storage whose ownership was transferred to this pool.
    private final ArkivoEditStorage storage;

    /// Contents whose resources have not yet been fully released.
    private final Set<Content> contents = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Whether new allocations and channel opens have been disabled.
    private boolean closing;

    /// Whether backing storage cleanup completed successfully.
    private boolean closed;

    /// Takes ownership of storage without allocating content or accessing its backing resources.
    ///
    /// @param storage the storage to own
    public StoredContentPool(ArkivoEditStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /// Creates pool-owned content whose channels are tracked independently from its release request.
    @Override
    public synchronized ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
        ensureOpen();
        Content content = new Content(storage.createContent(path, expectedSize));
        contents.add(content);
        return content;
    }

    /// Opens a read-only channel that releases its content when the channel is closed.
    ///
    /// The content must have been created by this pool. A failed open leaves it owned by the pool and does not request
    /// its release. An interruptible backing channel produces an interruptible returned channel.
    ///
    /// @param content the content to release with the returned channel
    /// @return a new independently positioned, read-only channel
    /// @throws IOException if content cannot be opened
    /// @throws IllegalArgumentException if the content belongs to another storage
    public synchronized SeekableByteChannel openReadChannel(ArkivoStoredContent content) throws IOException {
        Objects.requireNonNull(content, "content");
        ensureOpen();
        if (!(content instanceof Content managed) || managed.owner() != this) {
            throw new IllegalArgumentException("Content does not belong to this pool");
        }
        return managed.openChannel(Set.of(StandardOpenOption.READ), true);
    }

    /// Returns whether all content, channel, and storage cleanup has completed.
    ///
    /// @return whether every owned resource has been released successfully
    public synchronized boolean isClosed() {
        return closed;
    }

    /// Requests release of every content and retries incomplete cleanup without closing channels still in use.
    @Override
    public synchronized void close() throws IOException {
        closing = true;
        @Nullable Throwable failure = null;
        Iterator<Content> iterator = contents.iterator();
        while (iterator.hasNext()) {
            Content content = iterator.next();
            content.released = true;
            failure = cleanup(content, failure);
            if (content.closed) {
                iterator.remove();
            }
        }
        throwFailure(finishStorage(failure));
    }

    /// Releases closed channels before attempting content deletion, preserving the first failure.
    private @Nullable Throwable cleanup(Content content, @Nullable Throwable failure) {
        Iterator<ContentChannel> iterator = content.channels.iterator();
        while (iterator.hasNext()) {
            ContentChannel channel = iterator.next();
            if (!channel.open) {
                try {
                    channel.delegate.close();
                    iterator.remove();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = merge(failure, exception);
                }
            }
        }
        if (content.released && content.channels.isEmpty() && !content.closed) {
            try {
                content.delegate.close();
                content.closed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = merge(failure, exception);
            }
        }
        return failure;
    }

    /// Advances cleanup after a channel or content close request.
    private void release(Content content) throws IOException {
        @Nullable Throwable failure = cleanup(content, null);
        if (content.closed) {
            contents.remove(content);
        }
        throwFailure(finishStorage(failure));
    }

    /// Closes backing storage only after every tracked content has been released.
    private @Nullable Throwable finishStorage(@Nullable Throwable failure) {
        if (closing && !closed && contents.isEmpty()) {
            try {
                storage.close();
                closed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = merge(failure, exception);
            }
        }
        return failure;
    }

    /// Rejects allocation and opens after pool closure has been requested.
    private void ensureOpen() throws ClosedChannelException {
        if (closing) {
            throw new ClosedChannelException();
        }
    }

    /// Preserves failure identity and avoids self-suppression.
    private static Throwable merge(@Nullable Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    /// Rethrows a cleanup failure without changing its type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
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

    /// Defers backing-content release until all its independently owned channels have closed.
    @NotNullByDefault
    private final class Content implements ArkivoStoredContent {
        /// The content allocated by the configured storage.
        private final ArkivoStoredContent delegate;

        /// Open channels and channels whose close failed.
        private final Set<ContentChannel> channels = Collections.newSetFromMap(new IdentityHashMap<>());

        /// Whether content release has been requested.
        private boolean released;

        /// Whether backing-content cleanup completed.
        private boolean closed;

        /// Takes ownership of newly allocated content.
        private Content(ArkivoStoredContent delegate) {
            this.delegate = delegate;
        }

        /// Returns the pool that allocated this handle.
        private StoredContentPool owner() {
            return StoredContentPool.this;
        }

        /// Opens a channel whose successful closure releases only its own backing channel.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            synchronized (StoredContentPool.this) {
                return openChannel(options, false);
            }
        }

        /// Opens and registers a channel, optionally releasing this content when that channel closes.
        private SeekableByteChannel openChannel(Set<? extends OpenOption> options, boolean releaseOnClose)
                throws IOException {
            Objects.requireNonNull(options, "options");
            ensureOpen();
            if (released) {
                throw new ClosedChannelException();
            }
            SeekableByteChannel source = delegate.openChannel(options);
            ContentChannel channel = source instanceof InterruptibleChannel
                    ? new InterruptibleContentChannel(this, source, releaseOnClose)
                    : new ContentChannel(this, source, releaseOnClose);
            channels.add(channel);
            return channel;
        }

        /// Returns the size while the content is available for new operations.
        @Override
        public long size() throws IOException {
            synchronized (StoredContentPool.this) {
                ensureOpen();
                if (released) {
                    throw new ClosedChannelException();
                }
                return delegate.size();
            }
        }

        /// Requests content release without closing any channel still in use.
        @Override
        public void close() throws IOException {
            synchronized (StoredContentPool.this) {
                released = true;
                release(this);
            }
        }
    }

    /// Tracks channel cleanup independently from whether new I/O is permitted.
    @NotNullByDefault
    private class ContentChannel implements SeekableByteChannel {
        /// The content whose deletion depends on this channel closing.
        private final Content content;

        /// The independently owned backing channel.
        private final SeekableByteChannel delegate;

        /// Whether closure also requests content release and writes must be rejected.
        private final boolean releaseOnClose;

        /// Whether caller I/O is still permitted.
        private volatile boolean open = true;

        /// Registers the resources owned by one returned channel.
        private ContentChannel(Content content, SeekableByteChannel delegate, boolean releaseOnClose) {
            this.content = content;
            this.delegate = delegate;
            this.releaseOnClose = releaseOnClose;
        }

        /// Reads bytes while preserving the delegate's partial-progress behavior.
        @Override
        public int read(ByteBuffer target) throws IOException {
            checkOpen();
            return delegate.read(target);
        }

        /// Writes through ordinary content channels and rejects writes through transient read channels.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            checkOpen();
            if (releaseOnClose) {
                throw new NonWritableChannelException();
            }
            return delegate.write(source);
        }

        /// Returns the delegate position.
        @Override
        public long position() throws IOException {
            checkOpen();
            return delegate.position();
        }

        /// Changes the delegate position and returns this channel.
        @Override
        public SeekableByteChannel position(long position) throws IOException {
            checkOpen();
            delegate.position(position);
            return this;
        }

        /// Returns the current content size.
        @Override
        public long size() throws IOException {
            checkOpen();
            return delegate.size();
        }

        /// Truncates ordinary content channels and rejects truncation through transient read channels.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            checkOpen();
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            if (releaseOnClose) {
                throw new NonWritableChannelException();
            }
            delegate.truncate(size);
            return this;
        }

        /// Returns whether this wrapper and its backing channel remain open.
        @Override
        public boolean isOpen() {
            return open && delegate.isOpen();
        }

        /// Rejects further I/O immediately and retries any incomplete backing-resource cleanup.
        @Override
        public void close() throws IOException {
            synchronized (StoredContentPool.this) {
                open = false;
                content.released |= releaseOnClose;
                release(content);
            }
        }

        /// Rejects I/O after either this wrapper or its backing channel closes.
        private void checkOpen() throws ClosedChannelException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Preserves interruptibility when a stored-content channel supports it.
    @NotNullByDefault
    private final class InterruptibleContentChannel extends ContentChannel implements InterruptibleChannel {
        /// Creates a tracked channel over an interruptible source.
        private InterruptibleContentChannel(Content content, SeekableByteChannel delegate, boolean releaseOnClose) {
            super(content, delegate, releaseOnClose);
        }
    }
}

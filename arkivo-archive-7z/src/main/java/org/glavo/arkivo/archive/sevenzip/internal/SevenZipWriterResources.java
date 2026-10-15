// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Owns the physical channels and temporary paths used by a 7z writer.
///
/// Cancellation closes physical channels without flushing buffers, finishing coders, or publishing metadata.
/// Registration and cancellation may overlap; late channels are closed before they can be used. Failed closes remain
/// registered for retry. Temporary paths are deleted only during coordinated cleanup, not asynchronous cancellation.
/// Channel operations and temporary-path access are otherwise serialized by the owning file system.
@NotNullByDefault
final class SevenZipWriterResources implements AutoCloseable {
    /// Guards the resource set without covering physical I/O.
    private final Object resourceLock = new Object();

    /// Channels whose physical close has not returned successfully.
    private final List<OwnedChannel> channels = new ArrayList<>();

    /// Temporary paths retained even when a folder cannot finish construction.
    private final List<Path> temporaryFiles = new ArrayList<>();

    /// Whether output has been permanently cancelled.
    private volatile boolean cancelled;

    /// Creates and registers temporary storage before its first channel is opened.
    Path createTemporaryFile(String prefix) throws IOException {
        ensureActive();
        Path path = Files.createTempFile(prefix, ".tmp");
        temporaryFiles.add(path);
        return path;
    }

    /// Deletes one temporary path, retaining it when deletion fails.
    void deleteTemporaryFile(Path path) throws IOException {
        if (!temporaryFiles.contains(path)) return;
        Files.deleteIfExists(path);
        temporaryFiles.remove(path);
    }

    /// Registers an opened channel, including resources opened concurrently with cancellation.
    SeekableByteChannel own(SeekableByteChannel channel) throws IOException {
        OwnedChannel owned = new OwnedChannel(Objects.requireNonNull(channel, "channel"));
        boolean abort;
        synchronized (resourceLock) {
            channels.add(owned);
            abort = cancelled;
        }
        if (abort) {
            IOException failure = new IOException("7z channel opened after output cancellation");
            try {
                owned.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
            throw failure;
        }
        return owned;
    }

    /// Rejects output and metadata publication after cancellation.
    void ensureActive() throws IOException {
        if (cancelled) throw new IOException("7z output was cancelled by close");
    }

    /// Cancels current and future channel operations and returns any physical cleanup failure.
    @Nullable Throwable cancel() {
        synchronized (resourceLock) {
            cancelled = true;
        }
        return closeChannels();
    }

    /// Closes physical channels and deletes temporary paths, retaining failed resources for retry.
    @Override
    public void close() throws IOException {
        @Nullable Throwable failure = closeChannels();
        var iterator = temporaryFiles.iterator();
        while (iterator.hasNext()) {
            try {
                Files.deleteIfExists(iterator.next());
                iterator.remove();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        if (failure instanceof IOException exception) throw exception;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error exception) throw exception;
    }

    /// Closes a snapshot without holding the resource-set lock during I/O.
    private @Nullable Throwable closeChannels() {
        OwnedChannel[] snapshot;
        synchronized (resourceLock) {
            snapshot = channels.toArray(OwnedChannel[]::new);
        }
        @Nullable Throwable failure = null;
        for (OwnedChannel channel : snapshot) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        return failure;
    }

    /// Serializes close attempts independently of reads and writes and unregisters only successful closure.
    @NotNullByDefault
    private final class OwnedChannel implements SeekableByteChannel {
        /// The raw archive or temporary-file channel.
        private final SeekableByteChannel delegate;

        /// Whether physical close has been attempted.
        private volatile boolean closing;

        /// Whether physical close succeeded, guarded by this channel's monitor.
        private boolean closed;

        /// Takes ownership of one physical channel.
        private OwnedChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        /// Requires both the channel and its writer to remain available for I/O.
        private void ensureOpen() throws IOException {
            ensureActive();
            if (closing) throw new ClosedChannelException();
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            int count = delegate.read(target);
            ensureOpen();
            return count;
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            int count = delegate.write(source);
            ensureOpen();
            return count;
        }

        @Override
        public long position() throws IOException {
            ensureOpen();
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            ensureOpen();
            delegate.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            ensureOpen();
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return !closing && !cancelled && delegate.isOpen();
        }

        /// Closes the physical channel without acquiring any buffer, encoder, or file-system lock.
        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closing = true;
            delegate.close();
            closed = true;
            synchronized (resourceLock) {
                channels.remove(this);
            }
        }
    }
}

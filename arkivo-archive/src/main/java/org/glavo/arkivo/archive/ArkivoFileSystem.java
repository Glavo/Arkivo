// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/// Provides lifecycle and operation coordination for archive-backed file systems.
///
/// Subclasses participate in the selected [ArkivoFileSystemThreadSafety] contract by enclosing each implementation
/// operation in the token returned by [#beginReadOperation()] or [#beginWriteOperation()]. Read tokens may coexist;
/// write and close tokens wait for and exclude all other coordinated operations. This coordination classifies operations
/// across the file system but does not make concurrent calls on the same mutable stream, channel, iterator, or path
/// object safe.
///
/// Entry resources returned to callers should be passed through the appropriate `manage` method. With
/// [ArkivoFileSystemThreadSafety#CONCURRENT_READ], wrappers reject work after coordinated close but remain caller-owned.
/// With [ArkivoFileSystemThreadSafety#STRICT], close first force-closes registered wrappers. With
/// [ArkivoFileSystemThreadSafety#NONE], operation tokens are no-ops and `manage` methods return their arguments unchanged.
@NotNullByDefault
public abstract class ArkivoFileSystem extends FileSystem {
    /// The requested file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// The operation lock, or `null` when synchronization is disabled.
    private final @Nullable ReentrantReadWriteLock operationLock;

    /// Strict-mode resources that must close before the file system backing closes.
    private final Set<ManagedResource> managedResources =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /// Whether a close operation currently owns the exclusive operation lock.
    private volatile boolean lifecycleClosing;

    /// The thread executing coordinated close, or `null` outside a close operation.
    private volatile @Nullable Thread lifecycleCloseThread;

    /// Whether a close operation has completed and new coordinated operations must be rejected.
    private volatile boolean lifecycleClosed;

    /// Creates an archive-backed file system with concurrent reads and serialized writes.
    protected ArkivoFileSystem() {
        this(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
    }

    /// Creates an archive-backed file system with a thread-safety strategy.
    ///
    /// Subclasses must consistently use the operation and resource-management methods for this strategy to be effective.
    ///
    /// @param threadSafety the lifecycle coordination strategy
    protected ArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
        this.operationLock = threadSafety == ArkivoFileSystemThreadSafety.NONE
                ? null
                : new ReentrantReadWriteLock(true);
    }

    /// Returns the requested file system thread-safety strategy.
    ///
    /// @return this file system's lifecycle coordination strategy
    public final ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Begins one coordinated read operation.
    ///
    /// The call may block behind a write or close operation. After coordinated close completes it throws
    /// [ClosedFileSystemException]. The returned token must be closed, normally by try-with-resources.
    ///
    /// @return a token that releases the shared operation lock when closed
    /// @throws ClosedFileSystemException if coordinated close has completed or is running on another thread
    protected final Operation beginReadOperation() {
        return beginOperation(false);
    }

    /// Begins one coordinated mutating operation.
    ///
    /// The call may block until all earlier read and write operations release their tokens. After coordinated close
    /// completes it throws [ClosedFileSystemException]. The returned token must be closed.
    ///
    /// @return a token that releases the exclusive operation lock when closed
    /// @throws ClosedFileSystemException if coordinated close has completed or is running on another thread
    protected final Operation beginWriteOperation() {
        return beginOperation(true);
    }

    /// Begins a close operation that excludes other coordinated operations and closes strict-mode resources.
    ///
    /// The call blocks until all coordinated operations finish, then prevents new operations from starting. In strict
    /// mode it attempts to close every registered resource before returning. Subclass backing-resource cleanup belongs
    /// inside the returned token's scope; closing the token marks coordinated close complete, releases the exclusive
    /// lock, and reports any strict-resource cleanup failure.
    ///
    /// @return a token that completes coordinated close and releases the exclusive operation lock
    protected final CloseOperation beginCloseOperation() {
        @Nullable ReentrantReadWriteLock currentLock = operationLock;
        @Nullable Lock writeLock = null;
        if (currentLock != null) {
            writeLock = currentLock.writeLock();
            writeLock.lock();
        }
        lifecycleClosing = true;
        lifecycleCloseThread = Thread.currentThread();

        @Nullable Throwable resourceFailure = null;
        if (threadSafety == ArkivoFileSystemThreadSafety.STRICT) {
            ManagedResource[] resources;
            synchronized (managedResources) {
                resources = managedResources.toArray(ManagedResource[]::new);
            }
            for (ManagedResource resource : resources) {
                try {
                    resource.forceClose();
                } catch (IOException | RuntimeException | Error exception) {
                    resourceFailure = appendFailure(resourceFailure, exception);
                }
            }
        }
        return new CloseOperation(this, writeLock, resourceFailure);
    }

    /// Coordinates a seekable read-only channel with this file system's lifecycle.
    ///
    /// The returned wrapper owns the delegate and closes it when the wrapper closes. In strict mode the file system may
    /// close it first. The delegate itself is returned unchanged when synchronization is disabled.
    ///
    /// @param channel the channel whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated channel, or {@code channel} when synchronization is disabled
    protected final SeekableByteChannel manageReadChannel(SeekableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return operationLock == null ? channel : new ManagedSeekableByteChannel(this, channel, false);
    }

    /// Coordinates a forward-only readable channel with this file system's lifecycle.
    ///
    /// The returned wrapper owns the delegate and treats its reads as coordinated read operations.
    ///
    /// @param channel the channel whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated channel, or {@code channel} when synchronization is disabled
    protected final ReadableByteChannel manageReadableChannel(ReadableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return operationLock == null ? channel : new ManagedReadableByteChannel(this, channel);
    }

    /// Coordinates a seekable writable channel with this file system's lifecycle.
    ///
    /// Every wrapper operation, including reads and position queries, uses exclusive coordination because the channel
    /// represents mutable entry state. The wrapper owns the delegate.
    ///
    /// @param channel the channel whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated channel, or {@code channel} when synchronization is disabled
    protected final SeekableByteChannel manageWriteChannel(SeekableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return operationLock == null ? channel : new ManagedSeekableByteChannel(this, channel, true);
    }

    /// Coordinates a readable stream with this file system's lifecycle.
    ///
    /// The returned stream owns the delegate and coordinates reads, skips, availability, marks, and resets.
    ///
    /// @param input the stream whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated stream, or {@code input} when synchronization is disabled
    protected final InputStream manageInputStream(InputStream input) {
        Objects.requireNonNull(input, "input");
        return operationLock == null ? input : new ManagedInputStream(this, input);
    }

    /// Coordinates a writable stream with this file system's lifecycle.
    ///
    /// The returned stream owns the delegate and coordinates writes and flushes as exclusive operations.
    ///
    /// @param output the stream whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated stream, or {@code output} when synchronization is disabled
    protected final OutputStream manageOutputStream(OutputStream output) {
        Objects.requireNonNull(output, "output");
        return operationLock == null ? output : new ManagedOutputStream(this, output);
    }

    /// Coordinates a directory stream with this file system's lifecycle.
    ///
    /// The returned stream owns the delegate. Iterator traversal uses read coordination and iterator removal uses write
    /// coordination.
    ///
    /// @param <P> the directory entry type
    /// @param stream the directory stream whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated directory stream, or {@code stream} when synchronization is disabled
    protected final <P> DirectoryStream<P> manageDirectoryStream(DirectoryStream<P> stream) {
        Objects.requireNonNull(stream, "stream");
        return operationLock == null ? stream : new ManagedDirectoryStream<>(this, stream);
    }

    /// Begins a read or write operation and rejects work after coordinated close completion.
    private Operation beginOperation(boolean write) {
        @Nullable ReentrantReadWriteLock currentLock = operationLock;
        if (currentLock == null) {
            return Operation.NONE;
        }
        Lock selectedLock = write ? currentLock.writeLock() : currentLock.readLock();
        selectedLock.lock();
        if ((lifecycleClosing || lifecycleClosed) && lifecycleCloseThread != Thread.currentThread()) {
            selectedLock.unlock();
            throw new ClosedFileSystemException();
        }
        return new Operation(selectedLock);
    }

    /// Begins a resource close that remains legal after file system close completion.
    private Operation beginResourceCloseOperation(boolean write) {
        @Nullable ReentrantReadWriteLock currentLock = operationLock;
        if (currentLock == null) {
            return Operation.NONE;
        }
        Lock selectedLock = write ? currentLock.writeLock() : currentLock.readLock();
        selectedLock.lock();
        return new Operation(selectedLock);
    }

    /// Registers one strict-mode resource.
    private void registerResource(ManagedResource resource) {
        if (threadSafety == ArkivoFileSystemThreadSafety.STRICT) {
            synchronized (managedResources) {
                managedResources.add(resource);
            }
        }
    }

    /// Removes one strict-mode resource registration.
    private void unregisterResource(ManagedResource resource) {
        if (threadSafety == ArkivoFileSystemThreadSafety.STRICT) {
            synchronized (managedResources) {
                managedResources.remove(resource);
            }
        }
    }

    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure == null) {
            return exception;
        }
        failure.addSuppressed(exception);
        return failure;
    }

    /// Throws one coordinated close failure using its original type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Represents one held file system operation lock.
    ///
    /// Instances are scope tokens rather than reusable locks. Closing a token is idempotent; the no-synchronization
    /// strategy returns a shared no-op token.
    @NotNullByDefault
    protected static final class Operation implements AutoCloseable {
        /// The shared no-op operation used when synchronization is disabled.
        private static final Operation NONE = new Operation(null);

        /// The held lock, or `null` after this operation closes.
        private @Nullable Lock lock;

        /// Creates an operation over an optional held lock.
        private Operation(@Nullable Lock lock) {
            this.lock = lock;
        }

        /// Releases this operation lock once.
        @Override
        public void close() {
            @Nullable Lock currentLock = lock;
            if (currentLock != null) {
                lock = null;
                currentLock.unlock();
            }
        }
    }

    /// Represents an exclusive close operation and any strict resource-close failure.
    ///
    /// This token must be closed even when subclass backing cleanup fails. Its close operation is idempotent.
    @NotNullByDefault
    protected static final class CloseOperation implements AutoCloseable {
        /// The file system whose lifecycle this operation closes.
        private final ArkivoFileSystem fileSystem;

        /// The exclusive lock held during close, or `null` when synchronization is disabled.
        private final @Nullable Lock lock;

        /// A failure raised while strict resources were being closed, or `null` when all succeeded.
        private final @Nullable Throwable resourceFailure;

        /// Whether this close operation has finished.
        private boolean closed;

        /// Creates a close operation.
        private CloseOperation(
                ArkivoFileSystem fileSystem,
                @Nullable Lock lock,
                @Nullable Throwable resourceFailure
        ) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.lock = lock;
            this.resourceFailure = resourceFailure;
        }

        /// Marks coordinated close complete, releases the exclusive lock, and reports resource failures.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            fileSystem.lifecycleClosed = true;
            fileSystem.lifecycleClosing = false;
            fileSystem.lifecycleCloseThread = null;
            if (lock != null) {
                lock.unlock();
            }
            throwFailure(resourceFailure);
        }
    }

    /// Defines one resource that strict close can terminate directly.
    @NotNullByDefault
    private interface ManagedResource {
        /// Closes this resource while the file system already holds its exclusive operation lock.
        void forceClose() throws IOException;
    }

    /// Provides registration and idempotent close behavior for one managed resource.
    @NotNullByDefault
    private abstract static class AbstractManagedResource implements ManagedResource {
        /// The file system coordinating this resource.
        protected final ArkivoFileSystem fileSystem;

        /// Whether the delegate remains open.
        private volatile boolean resourceOpen = true;

        /// Creates and registers one managed resource.
        protected AbstractManagedResource(ArkivoFileSystem fileSystem) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            fileSystem.registerResource(this);
        }

        /// Closes this resource through the file system's resource-close operation.
        protected final void closeManaged(boolean write) throws IOException {
            try (Operation ignored = fileSystem.beginResourceCloseOperation(write)) {
                closeDirect();
            }
        }

        /// Closes this resource while the file system already excludes other operations.
        @Override
        public final void forceClose() throws IOException {
            closeDirect();
        }

        /// Returns whether this managed resource remains open.
        protected final boolean resourceOpen() {
            return resourceOpen;
        }

        /// Requires this managed resource to remain open.
        protected final void requireResourceOpen() throws ClosedChannelException {
            if (!resourceOpen) {
                throw new ClosedChannelException();
            }
        }

        /// Begins a channel operation and maps file system lifecycle rejection to channel close state.
        protected final Operation beginManagedChannelOperation(boolean write) throws ClosedChannelException {
            requireResourceOpen();
            try {
                return write ? fileSystem.beginWriteOperation() : fileSystem.beginReadOperation();
            } catch (ClosedFileSystemException exception) {
                ClosedChannelException closed = new ClosedChannelException();
                closed.initCause(exception);
                throw closed;
            }
        }

        /// Closes the delegate once and unregisters this resource after successful cleanup.
        private void closeDirect() throws IOException {
            if (!resourceOpen) {
                return;
            }
            closeDelegate();
            resourceOpen = false;
            fileSystem.unregisterResource(this);
        }

        /// Closes the concrete delegate.
        protected abstract void closeDelegate() throws IOException;
    }

    /// Coordinates one seekable channel with file system operations and close.
    @NotNullByDefault
    private static final class ManagedSeekableByteChannel extends AbstractManagedResource
            implements SeekableByteChannel {
        /// The underlying entry channel.
        private final SeekableByteChannel delegate;

        /// Whether channel operations must use the exclusive operation lock.
        private final boolean writable;

        /// Creates a managed seekable channel.
        private ManagedSeekableByteChannel(
                ArkivoFileSystem fileSystem,
                SeekableByteChannel delegate,
                boolean writable
        ) {
            super(fileSystem);
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.writable = writable;
        }

        /// Reads bytes under the channel's operation mode.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            try (Operation ignored = beginChannelOperation()) {
                requireResourceOpen();
                return delegate.read(destination);
            }
        }

        /// Writes bytes under the exclusive operation lock.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            try (Operation ignored = beginManagedChannelOperation(true)) {
                requireResourceOpen();
                return delegate.write(source);
            }
        }

        /// Returns the channel position.
        @Override
        public long position() throws IOException {
            try (Operation ignored = beginChannelOperation()) {
                requireResourceOpen();
                return delegate.position();
            }
        }

        /// Changes the channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            try (Operation ignored = beginChannelOperation()) {
                requireResourceOpen();
                delegate.position(newPosition);
                return this;
            }
        }

        /// Returns the channel size.
        @Override
        public long size() throws IOException {
            try (Operation ignored = beginChannelOperation()) {
                requireResourceOpen();
                return delegate.size();
            }
        }

        /// Truncates this writable channel.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            try (Operation ignored = beginManagedChannelOperation(true)) {
                requireResourceOpen();
                delegate.truncate(size);
                return this;
            }
        }

        /// Returns whether this wrapper and its delegate remain open.
        @Override
        public boolean isOpen() {
            return resourceOpen() && delegate.isOpen() && !fileSystem.lifecycleClosed;
        }

        /// Closes this channel and unregisters it from strict file system close.
        @Override
        public void close() throws IOException {
            closeManaged(writable);
        }

        /// Selects the operation mode for non-writing channel methods.
        private Operation beginChannelOperation() throws ClosedChannelException {
            return beginManagedChannelOperation(writable);
        }

        /// Closes the underlying channel.
        @Override
        protected void closeDelegate() throws IOException {
            delegate.close();
        }
    }

    /// Coordinates one forward-only readable channel with file system operations and close.
    @NotNullByDefault
    private static final class ManagedReadableByteChannel extends AbstractManagedResource
            implements ReadableByteChannel {
        /// The underlying readable channel.
        private final ReadableByteChannel delegate;

        /// Creates a managed readable channel.
        private ManagedReadableByteChannel(ArkivoFileSystem fileSystem, ReadableByteChannel delegate) {
            super(fileSystem);
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Reads bytes under the shared operation lock.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            try (Operation ignored = beginManagedChannelOperation(false)) {
                requireResourceOpen();
                return delegate.read(destination);
            }
        }

        /// Returns whether this wrapper and its delegate remain open.
        @Override
        public boolean isOpen() {
            return resourceOpen() && delegate.isOpen() && !fileSystem.lifecycleClosed;
        }

        /// Closes this channel and unregisters it from strict file system close.
        @Override
        public void close() throws IOException {
            closeManaged(false);
        }

        /// Closes the underlying channel.
        @Override
        protected void closeDelegate() throws IOException {
            delegate.close();
        }
    }

    /// Coordinates one readable stream with file system operations and close.
    @NotNullByDefault
    private static final class ManagedInputStream extends InputStream implements ManagedResource {
        /// The file system coordinating this stream.
        private final ArkivoFileSystem fileSystem;

        /// The underlying readable stream.
        private final InputStream delegate;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Creates and registers one managed input stream.
        private ManagedInputStream(ArkivoFileSystem fileSystem, InputStream delegate) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            fileSystem.registerResource(this);
        }

        /// Reads one byte under the shared operation lock.
        @Override
        public int read() throws IOException {
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                return delegate.read();
            }
        }

        /// Reads bytes under the shared operation lock.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                return delegate.read(buffer, offset, length);
            }
        }

        /// Skips bytes under the shared operation lock.
        @Override
        public long skip(long count) throws IOException {
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                return delegate.skip(count);
            }
        }

        /// Returns immediately available bytes under the shared operation lock.
        @Override
        public int available() throws IOException {
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                return delegate.available();
            }
        }

        /// Closes this stream and unregisters it from strict file system close.
        @Override
        public void close() throws IOException {
            try (Operation ignored = fileSystem.beginResourceCloseOperation(false)) {
                forceClose();
            }
        }

        /// Marks the delegate under the shared operation lock.
        @Override
        public synchronized void mark(int readLimit) {
            try (Operation ignored = fileSystem.beginReadOperation()) {
                requireOpenUnchecked();
                delegate.mark(readLimit);
            }
        }

        /// Resets the delegate under the shared operation lock.
        @Override
        public synchronized void reset() throws IOException {
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                delegate.reset();
            }
        }

        /// Returns whether the delegate supports marks.
        @Override
        public boolean markSupported() {
            return delegate.markSupported();
        }

        /// Closes this stream directly while strict close already owns the exclusive lock.
        @Override
        public void forceClose() throws IOException {
            if (!open) {
                return;
            }
            delegate.close();
            open = false;
            fileSystem.unregisterResource(this);
        }

        /// Requires this stream to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("Managed archive input stream is closed");
            }
        }

        /// Begins an input operation and maps file system lifecycle rejection to stream close state.
        private Operation beginInputOperation() throws IOException {
            requireOpen();
            try {
                return fileSystem.beginReadOperation();
            } catch (ClosedFileSystemException exception) {
                throw new IOException("Managed archive input stream is closed", exception);
            }
        }

        /// Requires this stream to remain open from a method that cannot declare checked exceptions.
        private void requireOpenUnchecked() {
            if (!open) {
                throw new IllegalStateException("Managed archive input stream is closed");
            }
        }
    }

    /// Coordinates one writable stream with file system operations and close.
    @NotNullByDefault
    private static final class ManagedOutputStream extends OutputStream implements ManagedResource {
        /// The file system coordinating this stream.
        private final ArkivoFileSystem fileSystem;

        /// The underlying writable stream.
        private final OutputStream delegate;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Creates and registers one managed output stream.
        private ManagedOutputStream(ArkivoFileSystem fileSystem, OutputStream delegate) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            fileSystem.registerResource(this);
        }

        /// Writes one byte under the exclusive operation lock.
        @Override
        public void write(int value) throws IOException {
            try (Operation ignored = beginOutputOperation()) {
                requireOpen();
                delegate.write(value);
            }
        }

        /// Writes bytes under the exclusive operation lock.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            try (Operation ignored = beginOutputOperation()) {
                requireOpen();
                delegate.write(buffer, offset, length);
            }
        }

        /// Flushes the delegate under the exclusive operation lock.
        @Override
        public void flush() throws IOException {
            try (Operation ignored = beginOutputOperation()) {
                requireOpen();
                delegate.flush();
            }
        }

        /// Closes this stream and unregisters it from strict file system close.
        @Override
        public void close() throws IOException {
            try (Operation ignored = fileSystem.beginResourceCloseOperation(true)) {
                forceClose();
            }
        }

        /// Closes this stream directly while strict close already owns the exclusive lock.
        @Override
        public void forceClose() throws IOException {
            if (!open) {
                return;
            }
            delegate.close();
            open = false;
            fileSystem.unregisterResource(this);
        }

        /// Requires this stream to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("Managed archive output stream is closed");
            }
        }

        /// Begins an output operation and maps file system lifecycle rejection to stream close state.
        private Operation beginOutputOperation() throws IOException {
            requireOpen();
            try {
                return fileSystem.beginWriteOperation();
            } catch (ClosedFileSystemException exception) {
                throw new IOException("Managed archive output stream is closed", exception);
            }
        }
    }

    /// Coordinates one directory stream and its iterator with file system operations and close.
    @NotNullByDefault
    private static final class ManagedDirectoryStream<P> implements DirectoryStream<P>, ManagedResource {
        /// The file system coordinating this directory stream.
        private final ArkivoFileSystem fileSystem;

        /// The underlying directory stream.
        private final DirectoryStream<P> delegate;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Creates and registers one managed directory stream.
        private ManagedDirectoryStream(ArkivoFileSystem fileSystem, DirectoryStream<P> delegate) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            fileSystem.registerResource(this);
        }

        /// Returns a coordinated iterator over this directory stream.
        @Override
        public Iterator<P> iterator() {
            try (Operation ignored = fileSystem.beginReadOperation()) {
                requireOpen();
                return new ManagedIterator<>(fileSystem, delegate.iterator());
            }
        }

        /// Closes this directory stream and unregisters it from strict file system close.
        @Override
        public void close() throws IOException {
            try (Operation ignored = fileSystem.beginResourceCloseOperation(false)) {
                forceClose();
            }
        }

        /// Closes this stream directly while strict close already owns the exclusive lock.
        @Override
        public void forceClose() throws IOException {
            if (!open) {
                return;
            }
            delegate.close();
            open = false;
            fileSystem.unregisterResource(this);
        }

        /// Requires this stream to remain open.
        private void requireOpen() {
            if (!open) {
                throw new ClosedDirectoryStreamException();
            }
        }
    }

    /// Coordinates one directory iterator with file system operations.
    @NotNullByDefault
    private static final class ManagedIterator<P> implements Iterator<P> {
        /// The file system coordinating iterator operations.
        private final ArkivoFileSystem fileSystem;

        /// The underlying iterator.
        private final Iterator<P> delegate;

        /// Creates a managed directory iterator.
        private ManagedIterator(ArkivoFileSystem fileSystem, Iterator<P> delegate) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns whether another entry is available.
        @Override
        public boolean hasNext() {
            try (Operation ignored = fileSystem.beginReadOperation()) {
                return delegate.hasNext();
            }
        }

        /// Returns the next directory entry.
        @Override
        public P next() {
            try (Operation ignored = fileSystem.beginReadOperation()) {
                return delegate.next();
            }
        }

        /// Removes the current entry when the delegate supports mutation.
        @Override
        public void remove() {
            try (Operation ignored = fileSystem.beginWriteOperation()) {
                delegate.remove();
            }
        }
    }

}

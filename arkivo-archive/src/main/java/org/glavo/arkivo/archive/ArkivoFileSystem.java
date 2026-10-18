// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StagedSeekableByteChannel;
import org.glavo.arkivo.internal.ForceCloseable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Channels;
import java.nio.channels.InterruptibleChannel;
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
/// across the file system. Managed channels also serialize data operations on each channel; callers must coordinate
/// sequences of separate calls, such as seeking followed by reading. This coordination does not make concurrent calls
/// on the same mutable stream or iterator safe.
///
/// NIO creation and renaming reject literal backslashes in entry names and a colon as the second character of the
/// first name component. Archive readers interpret these as separators or drive prefixes; such names cannot retain
/// their NIO path identity after the archive is reopened. Colons in other positions are permitted. Written entry names
/// must not contain unpaired UTF-16 surrogates; valid surrogate pairs are preserved.
/// Symbolic-link creation rejects targets containing NUL or unpaired UTF-16 surrogates with
/// `IllegalArgumentException`, without creating the link.
///
/// Entry resources returned to callers should be passed through the appropriate `manage` method. With
/// [ArkivoFileSystemThreadSafety#CONCURRENT_READ], wrappers reject work after coordinated close but remain caller-owned.
/// With [ArkivoFileSystemThreadSafety#STRICT], close first force-closes registered wrappers. With
/// [ArkivoFileSystemThreadSafety#NONE], operation tokens are no-ops and `manage` methods return their arguments unchanged.
/// A close exception already reported by an earlier close attempt is wrapped in a new `IOException`, retaining the
/// earlier exception as its cause so automatic cleanup does not cause self-suppression.
///
/// A built-in provider may retain a file system's URI registration after [#isOpen()] becomes `false` if output cleanup
/// is incomplete. Opening another file system for that URI then throws `FileSystemAlreadyExistsException`.
/// Call [#close()] again to retry pending cleanup. The registration remains reserved until publication and rollback
/// can no longer affect the destination archive.
@NotNullByDefault
public abstract class ArkivoFileSystem extends FileSystem {
    /// The requested file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// The operation lock, or `null` when synchronization is disabled.
    private final @Nullable ReentrantReadWriteLock operationLock;

    /// Strict-mode resources that must close before the file system backing closes.
    private final Set<ManagedResource> managedResources =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /// Coordinates ownership of the close transition between concurrent close callers.
    private final Object lifecycleMonitor = new Object();

    /// Whether a close operation currently owns the exclusive operation lock.
    private volatile boolean lifecycleClosing;

    /// The thread executing coordinated close, or `null` outside a close operation.
    private volatile @Nullable Thread lifecycleCloseThread;

    /// Whether a close operation has completed and new coordinated operations must be rejected.
    private volatile boolean lifecycleClosed;

    /// Close exceptions already delivered to callers, guarded by the lifecycle monitor.
    private @Nullable Set<Throwable> reportedCloseFailures;

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
    /// The call first prevents new operations from starting. In strict mode it then closes registered backing resources
    /// before waiting for active operations, allowing an interruptible delegate close to release a thread blocked in I/O.
    /// Staged body completion waits until exclusive coordination is held. Once all earlier operations have left,
    /// it completes staged bodies and closes any resource registered by an operation that was already active, then
    /// returns with exclusive coordination held. Subclass backing-resource cleanup belongs inside the returned token's
    /// scope; closing the token marks coordinated close complete, releases the lock, and reports resource failures.
    /// If subclass cleanup fails, the subclass must call [CloseOperation#rethrowFailure(Throwable)] with that failure
    /// and close the token in a `finally` block.
    /// If forced resource closure fails while another operation still holds the file system lock, this method
    /// reports the failure without waiting for that operation. The close transition is released so closure can be retried.
    ///
    /// @return a token that completes coordinated close and releases the exclusive operation lock
    /// @throws IOException if forced resource cleanup fails before exclusive coordination can be acquired
    protected final CloseOperation beginCloseOperation() throws IOException {
        Thread currentThread = Thread.currentThread();
        boolean interrupted = false;
        synchronized (lifecycleMonitor) {
            while (lifecycleClosing && lifecycleCloseThread != currentThread) {
                try {
                    lifecycleMonitor.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (lifecycleClosing) {
                throw new IllegalStateException("Reentrant archive file system close is not supported");
            }
            lifecycleClosing = true;
            lifecycleCloseThread = currentThread;
        }
        if (interrupted) {
            currentThread.interrupt();
        }

        @Nullable ReentrantReadWriteLock currentLock = operationLock;
        @Nullable Lock writeLock = null;
        @Nullable Throwable resourceFailure = null;
        try {
            if (threadSafety == ArkivoFileSystemThreadSafety.STRICT) {
                resourceFailure = closeStrictResources(resourceFailure);
            }
            if (currentLock != null) {
                writeLock = currentLock.writeLock();
                if (resourceFailure == null) {
                    writeLock.lock();
                } else if (!writeLock.tryLock()) {
                    // A failed cancellation may have left an I/O operation blocked; do not wait behind it.
                    writeLock = null;
                    throwFailure(prepareCloseFailure(resourceFailure));
                }
            }
            if (threadSafety == ArkivoFileSystemThreadSafety.STRICT) {
                resourceFailure = closeStrictResources(resourceFailure);
            }
            return new CloseOperation(this, writeLock, resourceFailure);
        } catch (IOException | RuntimeException | Error exception) {
            if (writeLock != null && currentLock != null && currentLock.isWriteLockedByCurrentThread()) {
                writeLock.unlock();
            }
            abandonCloseTransition();
            throw exception;
        }
    }

    /// Coordinates a seekable read-only channel with this file system's lifecycle.
    ///
    /// The returned wrapper owns the delegate and closes it when the wrapper closes. In strict mode the file system may
    /// close it first. The delegate itself is returned unchanged when synchronization is disabled.
    ///
    /// @param channel the channel whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated channel preserving [InterruptibleChannel] when implemented by `channel`, or
    ///         `channel` when synchronization is disabled
    protected final SeekableByteChannel manageReadChannel(SeekableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (operationLock == null) {
            return channel;
        }
        ManagedSeekableByteChannel managed = channel instanceof InterruptibleChannel
                ? new InterruptibleManagedSeekableByteChannel(this, channel, false)
                : new ManagedSeekableByteChannel(this, channel, false);
        registerResource(managed);
        return managed;
    }

    /// Coordinates a forward-only readable channel with this file system's lifecycle.
    ///
    /// The returned wrapper owns the delegate and treats its reads as coordinated read operations.
    ///
    /// @param channel the channel whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated channel preserving [InterruptibleChannel] when implemented by `channel`, or
    ///         `channel` when synchronization is disabled
    protected final ReadableByteChannel manageReadableChannel(ReadableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (operationLock == null) {
            return channel;
        }
        ManagedReadableByteChannel managed = channel instanceof InterruptibleChannel
                ? new InterruptibleManagedReadableByteChannel(this, channel)
                : new ManagedReadableByteChannel(this, channel);
        registerResource(managed);
        return managed;
    }

    /// Coordinates a seekable writable channel with this file system's lifecycle.
    ///
    /// Every wrapper operation, including reads and position queries, uses exclusive coordination because the channel
    /// represents mutable entry state. The wrapper owns the delegate.
    ///
    /// @param channel the channel whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated channel preserving [InterruptibleChannel] when implemented by `channel`, or
    ///         `channel` when synchronization is disabled
    protected final SeekableByteChannel manageWriteChannel(SeekableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (operationLock == null) {
            return channel;
        }
        ManagedSeekableByteChannel managed;
        if (channel instanceof StagedSeekableByteChannel staged) {
            managed = new ManagedStagedSeekableByteChannel(this, staged);
        } else {
            managed = channel instanceof InterruptibleChannel
                    ? new InterruptibleManagedSeekableByteChannel(this, channel, true)
                    : new ManagedSeekableByteChannel(this, channel, true);
        }
        registerResource(managed);
        return managed;
    }

    /// Coordinates a writable channel whose close publishes staged state.
    ///
    /// The preparation runs before waiting for active operations; the delegate closes under exclusive coordination.
    /// Preparation must be idempotent, must not throw, and must only close the backing resources needed to release
    /// blocking I/O. It must not publish staged state or depend on whichever entry is current when it runs.
    ///
    /// @param channel the owned entry channel
    /// @param prepareClose the preparation bound to this channel's backing resources
    /// @return a managed channel preserving its interruptible marker, or the delegate if synchronization is disabled
    protected final SeekableByteChannel manageWriteChannel(SeekableByteChannel channel, Runnable prepareClose) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(prepareClose, "prepareClose");
        if (operationLock == null) return channel;
        ManagedSeekableByteChannel managed = channel instanceof InterruptibleChannel
                ? new InterruptibleManagedSeekableByteChannel(this, channel, true, prepareClose)
                : new ManagedSeekableByteChannel(this, channel, true, prepareClose);
        registerResource(managed);
        return managed;
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

    /// Coordinates a stream whose close publishes staged state after active writes have returned.
    ///
    /// Preparation has the requirements of [#manageWriteChannel(SeekableByteChannel, Runnable)]. Stream close then
    /// runs under exclusive coordination, including when the caller closes the stream rather than the file system.
    ///
    /// @param output the owned entry stream
    /// @param prepareClose the preparation bound to this stream's backing resources
    /// @return a managed stream, or the delegate if synchronization is disabled
    protected final OutputStream manageOutputStream(OutputStream output, Runnable prepareClose) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(prepareClose, "prepareClose");
        return operationLock == null ? output : new ManagedOutputStream(this, output, null, prepareClose);
    }

    /// Adapts a writable entry channel to a stream with coordinated writes, flushes, and channel-owned closure.
    ///
    /// The channel is registered once, through [#manageWriteChannel(SeekableByteChannel)]. Stream closure delegates
    /// directly to that channel so staged completion can wait for the operation lock without holding a stream monitor.
    ///
    /// @param channel the entry channel whose ownership transfers to the returned stream
    /// @return a stream owning the managed channel
    protected final OutputStream manageOutputStream(SeekableByteChannel channel) {
        SeekableByteChannel managed = manageWriteChannel(channel);
        OutputStream output = channel instanceof StagedSeekableByteChannel staged
                ? staged.newOutputStream() : Channels.newOutputStream(managed);
        return operationLock == null ? output : new ManagedOutputStream(this, output, managed);
    }

    /// Coordinates a directory stream with this file system's lifecycle.
    ///
    /// The returned stream owns the delegate. With synchronization enabled, iterator traversal checks the file system
    /// lifecycle before and after each call, but holds no operation lock while invoking the delegate, which may execute
    /// user filters. The delegate must coordinate its iterator state with concurrent close. Managed iterators do not
    /// support removal.
    ///
    /// @param <P> the directory entry type
    /// @param stream the directory stream whose ownership is transferred to the returned managed resource
    /// @return a lifecycle-coordinated directory stream, or {@code stream} when synchronization is disabled
    protected final <P> DirectoryStream<P> manageDirectoryStream(DirectoryStream<P> stream) {
        Objects.requireNonNull(stream, "stream");
        return operationLock == null ? stream : new ManagedDirectoryStream<>(this, stream);
    }

    /// Begins a read or write operation and rejects new work after coordinated close starts.
    ///
    /// Reentrant calls remain part of the operation that already holds a lock, so close cannot invalidate an operation
    /// halfway through its implementation.
    private Operation beginOperation(boolean write) {
        @Nullable ReentrantReadWriteLock currentLock = operationLock;
        if (currentLock == null) {
            return Operation.NONE;
        }
        boolean nestedOperation = currentLock.isWriteLockedByCurrentThread()
                || currentLock.getReadHoldCount() != 0;
        Lock selectedLock = write ? currentLock.writeLock() : currentLock.readLock();
        selectedLock.lock();
        if ((lifecycleClosing || lifecycleClosed)
                && lifecycleCloseThread != Thread.currentThread()
                && !nestedOperation) {
            selectedLock.unlock();
            throw new ClosedFileSystemException();
        }
        return new Operation(selectedLock);
    }

    /// Registers one strict-mode resource after its delegate and subclass state have been initialized.
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

    /// Attempts to close the current strict-resource snapshot and returns the accumulated failure.
    private @Nullable Throwable closeStrictResources(@Nullable Throwable previousFailure) {
        ManagedResource[] resources;
        synchronized (managedResources) {
            resources = managedResources.toArray(ManagedResource[]::new);
        }
        @Nullable Throwable failure = previousFailure;
        for (ManagedResource resource : resources) {
            try {
                resource.forceClose();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        return failure;
    }

    /// Releases a claimed close transition after setup fails before a token can be returned.
    private void abandonCloseTransition() {
        synchronized (lifecycleMonitor) {
            lifecycleClosing = false;
            lifecycleCloseThread = null;
            lifecycleMonitor.notifyAll();
        }
    }

    /// Records a close failure before releasing coordination, wrapping only an already reported exception instance.
    private Throwable prepareCloseFailure(Throwable failure) {
        synchronized (lifecycleMonitor) {
            if (reportedCloseFailures == null) {
                reportedCloseFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            if (reportedCloseFailures.add(failure)) {
                return failure;
            }
            IOException wrapped = new IOException("Archive file system close repeated an earlier failure", failure);
            reportedCloseFailures.add(wrapped);
            return wrapped;
        }
    }

    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure == null) {
            return exception;
        }
        if (failure != exception) {
            failure.addSuppressed(exception);
        }
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
        private @Nullable Throwable resourceFailure;

        /// Whether this close operation has finished.
        private boolean closed;

        /// Whether subclass cleanup has already reported a failure through this token.
        private boolean failureReported;

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

        /// Attaches pending resource failures and rethrows a subclass cleanup failure.
        ///
        /// An identical failure is not attached to itself. After this call, closing the token still completes the
        /// lifecycle transition and releases the lock, but does not rethrow the transferred resource failure.
        /// An exception reported by an earlier close attempt is wrapped in a new `IOException`.
        ///
        /// @param primary the `IOException`, `RuntimeException`, or `Error` raised by subclass cleanup
        /// @throws IOException if cleanup raised an I/O failure or repeated a previously reported exception
        public void rethrowFailure(Throwable primary) throws IOException {
            Objects.requireNonNull(primary, "primary");
            @Nullable Throwable pending = resourceFailure;
            resourceFailure = null;
            failureReported = true;
            if (pending != null && pending != primary) {
                primary.addSuppressed(pending);
            }
            throwFailure(fileSystem.prepareCloseFailure(primary));
        }

        /// Marks coordinated close complete, releases the exclusive lock, and reports resource failures.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            @Nullable Throwable failure = null;
            try {
                if (resourceFailure != null) {
                    failure = fileSystem.prepareCloseFailure(resourceFailure);
                }
            } finally {
                synchronized (fileSystem.lifecycleMonitor) {
                    if (!failureReported && resourceFailure == null) {
                        fileSystem.reportedCloseFailures = null;
                    }
                    fileSystem.lifecycleClosed = true;
                    fileSystem.lifecycleClosing = false;
                    fileSystem.lifecycleCloseThread = null;
                    fileSystem.lifecycleMonitor.notifyAll();
                }
                if (lock != null) {
                    lock.unlock();
                }
            }
            throwFailure(failure);
        }
    }

    /// Defines one resource that strict close can terminate directly.
    @NotNullByDefault
    private interface ManagedResource {
        /// Closes this resource directly, including while another thread is active in its delegate.
        void forceClose() throws IOException;
    }

    /// Provides registration and idempotent close behavior for one managed resource.
    @NotNullByDefault
    private abstract static class AbstractManagedResource implements ManagedResource {
        /// The file system coordinating this resource.
        protected final ArkivoFileSystem fileSystem;

        /// Whether the delegate remains open.
        private volatile boolean resourceOpen = true;

        /// Creates an unregistered managed resource; the factory registers the fully constructed subclass.
        protected AbstractManagedResource(ArkivoFileSystem fileSystem) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        }

        /// Closes this resource through the file system's resource-close operation.
        protected final void closeManaged() throws IOException {
            closeDirect();
        }

        /// Closes this resource directly so interruptible I/O can be released before exclusive coordination is acquired.
        @Override
        public void forceClose() throws IOException {
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
        private synchronized void closeDirect() throws IOException {
            if (!resourceOpen) {
                return;
            }
            closeDelegate();
            markResourceClosed();
        }

        /// Unregisters a resource whose delegate and stateful completion have finished.
        protected final void markResourceClosed() {
            resourceOpen = false;
            fileSystem.unregisterResource(this);
        }

        /// Closes the concrete delegate.
        protected abstract void closeDelegate() throws IOException;
    }

    /// Coordinates one seekable channel with file system operations and close.
    @NotNullByDefault
    private static class ManagedSeekableByteChannel extends AbstractManagedResource
            implements SeekableByteChannel, ForceCloseable {
        /// Serializes position, data, and size operations independently of cancellation and physical closure.
        private final Object channelOperationLock = new Object();

        /// The underlying entry channel.
        private final SeekableByteChannel delegate;

        /// Whether channel operations must use the exclusive operation lock.
        private final boolean writable;

        /// Optional backing-resource closure that must precede coordinated delegate completion.
        private final @Nullable Runnable prepareClose;

        /// Creates a managed seekable channel.
        private ManagedSeekableByteChannel(
                ArkivoFileSystem fileSystem,
                SeekableByteChannel delegate,
                boolean writable
        ) {
            this(fileSystem, delegate, writable, null);
        }

        /// Creates a channel with optional two-phase closure.
        private ManagedSeekableByteChannel(ArkivoFileSystem fileSystem, SeekableByteChannel delegate,
                                           boolean writable, @Nullable Runnable prepareClose) {
            super(fileSystem);
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.writable = writable;
            this.prepareClose = prepareClose;
        }

        /// Reads bytes under the channel's operation mode.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            try (Operation ignored = beginChannelOperation()) {
                synchronized (channelOperationLock) {
                    requireResourceOpen();
                    return delegate.read(destination);
                }
            }
        }

        /// Writes bytes under the exclusive operation lock.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            try (Operation ignored = beginManagedChannelOperation(true)) {
                synchronized (channelOperationLock) {
                    requireResourceOpen();
                    return delegate.write(source);
                }
            }
        }

        /// Returns the channel position.
        @Override
        public long position() throws IOException {
            try (Operation ignored = beginChannelOperation()) {
                synchronized (channelOperationLock) {
                    requireResourceOpen();
                    return delegate.position();
                }
            }
        }

        /// Changes the channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            try (Operation ignored = beginChannelOperation()) {
                synchronized (channelOperationLock) {
                    requireResourceOpen();
                    delegate.position(newPosition);
                    return this;
                }
            }
        }

        /// Returns the channel size.
        @Override
        public long size() throws IOException {
            try (Operation ignored = beginChannelOperation()) {
                synchronized (channelOperationLock) {
                    requireResourceOpen();
                    return delegate.size();
                }
            }
        }

        /// Truncates this writable channel.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            try (Operation ignored = beginManagedChannelOperation(true)) {
                synchronized (channelOperationLock) {
                    requireResourceOpen();
                    delegate.truncate(size);
                    return this;
                }
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
            if (prepareClose == null) {
                closeManaged();
                return;
            }
            if (!resourceOpen()) return;
            prepareClose.run();
            Lock lock = Objects.requireNonNull(fileSystem.operationLock).writeLock();
            lock.lock();
            try {
                closeManaged();
            } finally {
                lock.unlock();
            }
        }

        /// Prepares staged closure before the operation lock, and completes it only while that lock is held.
        @Override
        public void forceClose() throws IOException {
            if (prepareClose == null) {
                if (delegate instanceof ForceCloseable forceCloseable) {
                    // A graceful close may hold the resource monitor while draining; cancellation must bypass it.
                    forceCloseable.forceClose();
                    markResourceClosed();
                } else {
                    super.forceClose();
                }
            } else if (resourceOpen()) {
                prepareClose.run();
                if (Objects.requireNonNull(fileSystem.operationLock).isWriteLockedByCurrentThread()) {
                    closeManaged();
                }
            }
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

    /// Separates asynchronous storage closure from coordinated publication of a staged body.
    @NotNullByDefault
    private static final class ManagedStagedSeekableByteChannel extends ManagedSeekableByteChannel {
        /// The staged channel whose completion changes file system state.
        private final StagedSeekableByteChannel staged;

        /// Creates a managed staged channel using the ordinary exclusive channel operations.
        private ManagedStagedSeekableByteChannel(ArkivoFileSystem fileSystem, StagedSeekableByteChannel staged) {
            super(fileSystem, staged, true);
            this.staged = staged;
        }

        /// Closes storage before waiting for exclusive coordination of its completion.
        @Override
        public void close() throws IOException {
            staged.closeStorage();
            // Closing an already registered body must remain possible after the lifecycle close transition starts.
            ReentrantReadWriteLock currentLock = Objects.requireNonNull(fileSystem.operationLock);
            Lock writeLock = currentLock.writeLock();
            writeLock.lock();
            try {
                completeClose();
            } finally {
                writeLock.unlock();
            }
        }

        /// Releases blocking storage I/O before the file system takes its lock, then completes under that lock.
        @Override
        public void forceClose() throws IOException {
            staged.closeStorage();
            if (Objects.requireNonNull(fileSystem.operationLock).isWriteLockedByCurrentThread()) {
                completeClose();
            }
        }

        /// Publishes or discards the closed body once while exclusive coordination is held.
        private void completeClose() throws IOException {
            try {
                staged.completeClose();
            } finally {
                markResourceClosed();
            }
        }
    }

    /// Preserves the interruptible-channel marker of one managed seekable delegate.
    @NotNullByDefault
    private static final class InterruptibleManagedSeekableByteChannel
            extends ManagedSeekableByteChannel implements InterruptibleChannel {
        /// Creates an interruptible managed seekable channel.
        private InterruptibleManagedSeekableByteChannel(
                ArkivoFileSystem fileSystem,
                SeekableByteChannel delegate,
                boolean writable
        ) {
            super(fileSystem, delegate, writable);
        }

        /// Preserves interruption semantics for a delegate with two-phase closure.
        private InterruptibleManagedSeekableByteChannel(ArkivoFileSystem fileSystem, SeekableByteChannel delegate,
                                                        boolean writable, Runnable prepareClose) {
            super(fileSystem, delegate, writable, prepareClose);
        }
    }

    /// Coordinates one forward-only readable channel with file system operations and close.
    @NotNullByDefault
    private static class ManagedReadableByteChannel extends AbstractManagedResource
            implements ReadableByteChannel, ForceCloseable {
        /// Serializes reads independently of cancellation and physical closure.
        private final Object channelOperationLock = new Object();

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
                synchronized (channelOperationLock) {
                    requireResourceOpen();
                    return delegate.read(destination);
                }
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
            closeManaged();
        }

        /// Cancels a delegate without waiting for a graceful close that may be draining input.
        @Override
        public void forceClose() throws IOException {
            if (delegate instanceof ForceCloseable forceCloseable) {
                forceCloseable.forceClose();
                markResourceClosed();
            } else {
                super.forceClose();
            }
        }

        /// Closes the underlying channel.
        @Override
        protected void closeDelegate() throws IOException {
            delegate.close();
        }
    }

    /// Preserves the interruptible-channel marker of one managed forward-only delegate.
    @NotNullByDefault
    private static final class InterruptibleManagedReadableByteChannel
            extends ManagedReadableByteChannel implements InterruptibleChannel {
        /// Creates an interruptible managed readable channel.
        private InterruptibleManagedReadableByteChannel(
                ArkivoFileSystem fileSystem,
                ReadableByteChannel delegate
        ) {
            super(fileSystem, delegate);
        }
    }

    /// Coordinates one readable stream with file system operations and close.
    @NotNullByDefault
    private static final class ManagedInputStream extends InputStream implements ManagedResource, ForceCloseable {
        /// Serializes forced cleanup without waiting for an ordinary close to finish draining.
        private final Object forceCloseLock = new Object();

        /// Whether subsequent cleanup must cancel rather than validate unread data.
        private volatile boolean abortRequested;

        /// Protects failure identities independently of reads and closure.
        private final Object failureLock = new Object();

        /// Previously delivered delegate failures, allocated only when needed.
        private @Nullable Set<Throwable> reportedFailures;

        /// The file system coordinating this stream.
        private final ArkivoFileSystem fileSystem;

        /// The underlying readable stream.
        private final InputStream delegate;

        /// Whether this stream remains open.
        private volatile boolean open = true;

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
                try {
                    return delegate.read();
                } catch (IOException | RuntimeException | Error failure) {
                    recordFailure(failure);
                    throw failure;
                }
            }
        }

        /// Reads bytes under the shared operation lock.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                try {
                    return delegate.read(buffer, offset, length);
                } catch (IOException | RuntimeException | Error failure) {
                    recordFailure(failure);
                    throw failure;
                }
            }
        }

        /// Skips bytes under the shared operation lock.
        @Override
        public long skip(long count) throws IOException {
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                try {
                    return delegate.skip(count);
                } catch (IOException | RuntimeException | Error failure) {
                    recordFailure(failure);
                    throw failure;
                }
            }
        }

        /// Returns immediately available bytes under the shared operation lock.
        @Override
        public int available() throws IOException {
            try (Operation ignored = beginInputOperation()) {
                requireOpen();
                try {
                    return delegate.available();
                } catch (IOException | RuntimeException | Error failure) {
                    recordFailure(failure);
                    throw failure;
                }
            }
        }

        /// Closes this stream and unregisters it from strict file system close.
        @Override
        public synchronized void close() throws IOException {
            if (!open) {
                return;
            }
            if (abortRequested) {
                forceClose();
            } else {
                try {
                    delegate.close();
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        throw new IOException("Input cleanup repeated an earlier failure", failure);
                    }
                    throw failure;
                }
                synchronized (forceCloseLock) {
                    if (!abortRequested) {
                        open = false;
                        fileSystem.unregisterResource(this);
                    }
                }
            }
        }

        /// Marks the delegate under the shared operation lock.
        @Override
        public void mark(int readLimit) {
            try (Operation ignored = fileSystem.beginReadOperation()) {
                synchronized (this) {
                    requireOpenUnchecked();
                    try {
                        delegate.mark(readLimit);
                    } catch (RuntimeException | Error failure) {
                        recordFailure(failure);
                        throw failure;
                    }
                }
            }
        }

        /// Resets the delegate under the shared operation lock.
        @Override
        public void reset() throws IOException {
            try (Operation ignored = beginInputOperation()) {
                synchronized (this) {
                    requireOpen();
                    try {
                        delegate.reset();
                    } catch (IOException | RuntimeException | Error failure) {
                        recordFailure(failure);
                        throw failure;
                    }
                }
            }
        }

        /// Returns whether the delegate supports marks.
        @Override
        public boolean markSupported() {
            try {
                return delegate.markSupported();
            } catch (RuntimeException | Error failure) {
                recordFailure(failure);
                throw failure;
            }
        }

        /// Cancels owned input before strict close waits for active file system operations.
        @Override
        public void forceClose() throws IOException {
            abortRequested = true;
            synchronized (forceCloseLock) {
                if (!open) {
                    return;
                }
                try {
                    ForceCloseable.forceClose(delegate);
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        throw new IOException("Input cleanup repeated an earlier failure", failure);
                    }
                    throw failure;
                }
                open = false;
                fileSystem.unregisterResource(this);
            }
        }

        /// Records a delivered failure without waiting for an input or close operation.
        private boolean recordFailure(Throwable failure) {
            synchronized (failureLock) {
                if (reportedFailures == null) {
                    reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                return reportedFailures.add(failure);
            }
        }

        /// Requires this stream to remain open.
        private void requireOpen() throws IOException {
            if (!open || abortRequested) {
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
            if (!open || abortRequested) {
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

        /// The separately registered channel that owns closure, or `null` for an ordinary stream delegate.
        private final @Nullable SeekableByteChannel closeChannel;

        /// Optional backing-resource closure that must precede coordinated stream completion.
        private final @Nullable Runnable prepareClose;

        /// Whether this stream remains open.
        private volatile boolean open = true;

        /// Creates and registers one managed output stream.
        private ManagedOutputStream(ArkivoFileSystem fileSystem, OutputStream delegate) {
            this(fileSystem, delegate, null);
        }

        /// Creates a stream whose closure is registered either directly or through its managed channel.
        private ManagedOutputStream(ArkivoFileSystem fileSystem, OutputStream delegate,
                                    @Nullable SeekableByteChannel closeChannel) {
            this(fileSystem, delegate, closeChannel, null);
        }

        /// Creates a stream with optional asynchronous preparation and coordinated completion.
        private ManagedOutputStream(ArkivoFileSystem fileSystem, OutputStream delegate,
                                    @Nullable SeekableByteChannel closeChannel, @Nullable Runnable prepareClose) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.closeChannel = closeChannel;
            this.prepareClose = prepareClose;
            if (closeChannel == null) {
                fileSystem.registerResource(this);
            }
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
            if (closeChannel != null) {
                closeChannel.close();
                open = false;
            } else if (prepareClose != null) {
                if (!open) return;
                prepareClose.run();
                Lock lock = Objects.requireNonNull(fileSystem.operationLock).writeLock();
                lock.lock();
                try {
                    closeDirect();
                } finally {
                    lock.unlock();
                }
            } else {
                forceClose();
            }
        }

        /// Prepares staged closure before the operation lock, and completes it only while that lock is held.
        @Override
        public void forceClose() throws IOException {
            if (!open) return;
            if (prepareClose != null) {
                prepareClose.run();
                if (!Objects.requireNonNull(fileSystem.operationLock).isWriteLockedByCurrentThread()) return;
            }
            closeDirect();
        }

        /// Closes and unregisters the stream after any required operation coordination has been acquired.
        private synchronized void closeDirect() throws IOException {
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
        private volatile boolean open = true;

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
            forceClose();
        }

        /// Closes this stream directly while strict close already owns the exclusive lock.
        @Override
        public synchronized void forceClose() throws IOException {
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
            checkFileSystemOpen();
            boolean result = delegate.hasNext();
            checkFileSystemOpen();
            return result;
        }

        /// Returns the next directory entry.
        @Override
        public P next() {
            checkFileSystemOpen();
            P result = delegate.next();
            checkFileSystemOpen();
            return result;
        }

        /// Checks lifecycle state without retaining a lock across a user filter callback.
        private void checkFileSystemOpen() {
            try (Operation ignored = fileSystem.beginReadOperation()) {
                // Release coordination before calling the delegate.
            }
        }

    }

}

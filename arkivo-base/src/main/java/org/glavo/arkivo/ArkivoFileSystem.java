// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

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
import java.nio.file.OpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/// Provides shared behavior for archive-backed file systems.
@NotNullByDefault
public abstract class ArkivoFileSystem extends FileSystem {
    /// The common environment option that controls the requested file system thread-safety strategy.
    public static final ArkivoFileSystemOption<ArkivoFileSystemThreadSafety> THREAD_SAFETY =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "threadSafety",
                    ArkivoFileSystemThreadSafety.class,
                    ArkivoFileSystem::threadSafetyOptionValue
            );

    /// The common environment option that controls how the backing archive path is opened.
    ///
    /// The typed option value is a `Set<OpenOption>`. Raw environment maps may also provide an `OpenOption[]`,
    /// a single `OpenOption`, or a `Collection` of `OpenOption` values.
    public static final ArkivoFileSystemOption<Set<OpenOption>> OPEN_OPTIONS =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "openOptions",
                    openOptionsType(),
                    ArkivoFileSystem::openOptionsValue
            );

    /// The common environment option for staging new and replacement archive entry content.
    public static final ArkivoFileSystemOption<ArkivoEditStorage> EDIT_STORAGE =
            ArkivoFileSystemOption.of("arkivo", "editStorage", ArkivoEditStorage.class);

    /// The common environment option for choosing where assembled archive bytes are committed.
    public static final ArkivoFileSystemOption<ArkivoCommitTarget> COMMIT_TARGET =
            ArkivoFileSystemOption.of("arkivo", "commitTarget", ArkivoCommitTarget.class);

    /// The common environment option for deciding whether direct source-file mutation may be used.
    public static final ArkivoFileSystemOption<ArkivoSourceMutationPolicy> SOURCE_MUTATION_POLICY =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "sourceMutationPolicy",
                    ArkivoSourceMutationPolicy.class
            );

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

    /// Creates an archive-backed file system.
    protected ArkivoFileSystem() {
        this(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
    }

    /// Creates an archive-backed file system with a thread-safety strategy.
    protected ArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
        this.operationLock = threadSafety == ArkivoFileSystemThreadSafety.NONE
                ? null
                : new ReentrantReadWriteLock(true);
    }

    /// Returns the requested file system thread-safety strategy.
    public final ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Begins one coordinated read operation.
    protected final Operation beginReadOperation() {
        return beginOperation(false);
    }

    /// Begins one coordinated mutating operation.
    protected final Operation beginWriteOperation() {
        return beginOperation(true);
    }

    /// Begins a close operation that excludes other coordinated operations and closes strict-mode resources.
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
    protected final SeekableByteChannel manageReadChannel(SeekableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return operationLock == null ? channel : new ManagedSeekableByteChannel(this, channel, false);
    }

    /// Coordinates a forward-only readable channel with this file system's lifecycle.
    protected final ReadableByteChannel manageReadableChannel(ReadableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return operationLock == null ? channel : new ManagedReadableByteChannel(this, channel);
    }

    /// Coordinates a seekable writable channel with this file system's lifecycle.
    protected final SeekableByteChannel manageWriteChannel(SeekableByteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return operationLock == null ? channel : new ManagedSeekableByteChannel(this, channel, true);
    }

    /// Coordinates a readable stream with this file system's lifecycle.
    protected final InputStream manageInputStream(InputStream input) {
        Objects.requireNonNull(input, "input");
        return operationLock == null ? input : new ManagedInputStream(this, input);
    }

    /// Coordinates a writable stream with this file system's lifecycle.
    protected final OutputStream manageOutputStream(OutputStream output) {
        Objects.requireNonNull(output, "output");
        return operationLock == null ? output : new ManagedOutputStream(this, output);
    }

    /// Coordinates a directory stream with this file system's lifecycle.
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

    /// Converts a raw thread-safety option value.
    private static ArkivoFileSystemThreadSafety threadSafetyOptionValue(Object value) {
        if (value instanceof ArkivoFileSystemThreadSafety threadSafety) {
            return threadSafety;
        }
        if (value instanceof String stringValue) {
            return ArkivoFileSystemThreadSafety.parse(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected ArkivoFileSystemThreadSafety or String for key: " + THREAD_SAFETY.key()
        );
    }

    /// Converts a raw open options value.
    private static @Unmodifiable Set<OpenOption> openOptionsValue(Object value) {
        LinkedHashSet<OpenOption> options = new LinkedHashSet<>();
        if (value instanceof OpenOption[] array) {
            for (OpenOption option : array) {
                options.add(Objects.requireNonNull(option, "option"));
            }
            return Set.copyOf(options);
        }
        if (value instanceof OpenOption option) {
            return Set.of(option);
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof OpenOption option)) {
                    throw new IllegalArgumentException(
                            "Expected OpenOption values for key: " + OPEN_OPTIONS.key()
                    );
                }
                options.add(option);
            }
            return Set.copyOf(options);
        }
        throw new IllegalArgumentException(
                "Expected Set<OpenOption>, OpenOption[], OpenOption, or Collection<? extends OpenOption> for key: "
                        + OPEN_OPTIONS.key()
        );
    }

    /// Returns the erased runtime class used by the typed open options environment option.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Class<Set<OpenOption>> openOptionsType() {
        return (Class) Set.class;
    }
}

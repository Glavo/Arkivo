// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the shared archive file system concurrency and lifecycle contract.
@NotNullByDefault
public final class ArkivoFileSystemConcurrencyTest {
    /// Maximum time allowed for a cooperating test thread to enter or complete an operation.
    private static final long OPERATION_TIMEOUT_SECONDS = 10L;

    /// Directory containing managed-channel test content.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies that read operations overlap while write operations remain exclusive.
    @Test
    public void concurrentReadsOverlapAndWritesWait() throws Exception {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch readsEntered = new CountDownLatch(2);
        CountDownLatch releaseReads = new CountDownLatch(1);

        try {
            Future<Void> firstRead = executor.submit(() -> {
                fileSystem.holdRead(readsEntered, releaseReads);
                return null;
            });
            Future<Void> secondRead = executor.submit(() -> {
                fileSystem.holdRead(readsEntered, releaseReads);
                return null;
            });
            assertTrue(readsEntered.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Future<Void> write = executor.submit(() -> {
                fileSystem.runWrite();
                return null;
            });
            assertThrows(TimeoutException.class, () -> write.get(100L, TimeUnit.MILLISECONDS));

            releaseReads.countDown();
            firstRead.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondRead.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            write.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseReads.countDown();
            fileSystem.close();
            executor.shutdownNow();
        }
    }

    /// Verifies that close waits for an in-flight managed resource operation without closing its delegate.
    @Test
    public void concurrentReadCloseWaitsForResourceOperation() throws Exception {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        BlockingReadableByteChannel delegate = new BlockingReadableByteChannel(readEntered, releaseRead);
        ReadableByteChannel channel = fileSystem.managedReadableChannel(delegate);

        try {
            Future<Integer> read = executor.submit(() -> channel.read(ByteBuffer.allocate(1)));
            assertTrue(readEntered.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<Void> close = executor.submit(() -> {
                fileSystem.close();
                return null;
            });
            assertThrows(TimeoutException.class, () -> close.get(100L, TimeUnit.MILLISECONDS));

            releaseRead.countDown();
            read.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            close.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertTrue(delegate.isOpen());
            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            channel.close();
            assertFalse(delegate.isOpen());
        } finally {
            releaseRead.countDown();
            channel.close();
            fileSystem.close();
            executor.shutdownNow();
        }
    }

    /// Verifies concurrent close callers serialize and restore interruption observed while waiting for the owner.
    @Test
    public void concurrentCloseCallersWaitAndRestoreInterruption() throws Exception {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);

        try {
            Future<Void> owner = executor.submit(() -> {
                fileSystem.holdClose(closeEntered, releaseClose);
                return null;
            });
            assertTrue(closeEntered.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Future<Boolean> waiter = executor.submit(() -> {
                Thread.currentThread().interrupt();
                waiterStarted.countDown();
                fileSystem.close();
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(waiterStarted.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiter.get(100L, TimeUnit.MILLISECONDS));

            releaseClose.countDown();
            owner.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(waiter.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse(fileSystem.isOpen());
        } finally {
            releaseClose.countDown();
            fileSystem.close();
            executor.shutdownNow();
        }
    }

    /// Verifies one close token is idempotent while a nested close on its owning thread is rejected.
    @Test
    public void enforcesCloseTokenLifecycle() throws IOException {
        TestFileSystem reentrant = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        IllegalStateException failure = assertThrows(IllegalStateException.class, reentrant::runReentrantClose);
        assertEquals("Reentrant archive file system close is not supported", failure.getMessage());
        assertFalse(reentrant.isOpen());

        TestFileSystem repeatedToken = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        repeatedToken.closeWithRepeatedToken();
        assertFalse(repeatedToken.isOpen());
        repeatedToken.close();
    }

    /// Verifies that a temporary read resource can close inside an enclosing shared operation.
    @Test
    public void readResourceCanCloseInsideReadOperation() throws Exception {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        TrackingInputStream delegate = new TrackingInputStream();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Void> operation = executor.submit(() -> {
                fileSystem.readAndCloseManagedInput(delegate);
                return null;
            });
            operation.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertFalse(delegate.isOpen());
        } finally {
            fileSystem.close();
            executor.shutdownNow();
        }
    }

    /// Verifies that strict close terminates every supported managed resource type.
    @Test
    public void strictCloseClosesManagedResources() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.STRICT);
        TrackingSeekableByteChannel seekableDelegate = new TrackingSeekableByteChannel();
        TrackingReadableByteChannel readableDelegate = new TrackingReadableByteChannel();
        TrackingInputStream inputDelegate = new TrackingInputStream();
        TrackingOutputStream outputDelegate = new TrackingOutputStream();
        TrackingDirectoryStream directoryDelegate = new TrackingDirectoryStream();
        SeekableByteChannel seekable = fileSystem.managedReadChannel(seekableDelegate);
        ReadableByteChannel readable = fileSystem.managedReadableChannel(readableDelegate);
        InputStream input = fileSystem.managedInputStream(inputDelegate);
        OutputStream output = fileSystem.managedOutputStream(outputDelegate);
        DirectoryStream<Path> directory = fileSystem.managedDirectoryStream(directoryDelegate);
        Iterator<Path> iterator = directory.iterator();

        fileSystem.close();

        assertFalse(seekableDelegate.isOpen());
        assertFalse(readableDelegate.isOpen());
        assertFalse(inputDelegate.isOpen());
        assertFalse(outputDelegate.isOpen());
        assertFalse(directoryDelegate.isOpen());
        assertFalse(seekable.isOpen());
        assertFalse(readable.isOpen());
        assertThrows(ClosedChannelException.class, () -> seekable.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> readable.read(ByteBuffer.allocate(1)));
        assertThrows(IOException.class, input::read);
        assertThrows(IOException.class, () -> output.write(1));
        assertThrows(ClosedFileSystemException.class, iterator::hasNext);
    }

    /// Verifies strict close aggregates initial resource failures while retrying them after lock acquisition.
    @Test
    public void strictCloseAggregatesAndRetriesResourceFailures() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.STRICT);
        TrackingInputStream firstDelegate = new TrackingInputStream("first close failure", 1);
        TrackingInputStream secondDelegate = new TrackingInputStream("second close failure", 1);
        InputStream first = fileSystem.managedInputStream(firstDelegate);
        InputStream second = fileSystem.managedInputStream(secondDelegate);

        IOException failure = assertThrows(IOException.class, fileSystem::close);

        assertEquals(1, failure.getSuppressed().length);
        assertEquals(
                Set.of("first close failure", "second close failure"),
                Set.of(failure.getMessage(), failure.getSuppressed()[0].getMessage())
        );
        assertFalse(fileSystem.isOpen());
        assertFalse(firstDelegate.isOpen());
        assertFalse(secondDelegate.isOpen());
        assertEquals(2, firstDelegate.closeCalls());
        assertEquals(2, secondDelegate.closeCalls());
        assertThrows(IOException.class, first::read);
        assertThrows(IOException.class, second::read);

        fileSystem.close();
        assertEquals(2, firstDelegate.closeCalls());
        assertEquals(2, secondDelegate.closeCalls());
    }

    /// Verifies strict close preserves unchecked resource failures and avoids suppressing an exception onto itself.
    @Test
    public void strictClosePreservesUncheckedResourceFailures() {
        assertStrictCloseFailure(new IllegalStateException("runtime close failure"));
        assertStrictCloseFailure(new AssertionError("error close failure"));
    }

    /// Verifies that strict close terminates a blocking interruptible read before waiting for its operation lock.
    @Test
    public void strictCloseReleasesBlockingInterruptibleRead() throws Exception {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.STRICT);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch closeReleasedRead = new CountDownLatch(1);
        BlockingInterruptibleReadableByteChannel delegate =
                new BlockingInterruptibleReadableByteChannel(readEntered, closeReleasedRead);
        ReadableByteChannel channel = fileSystem.managedReadableChannel(delegate);

        try {
            Future<Integer> read = executor.submit(() -> channel.read(ByteBuffer.allocate(1)));
            assertTrue(readEntered.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<Void> close = executor.submit(() -> {
                fileSystem.close();
                return null;
            });

            close.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException readFailure = assertThrows(
                    ExecutionException.class,
                    () -> read.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
            assertInstanceOf(AsynchronousCloseException.class, readFailure.getCause());
            assertFalse(delegate.isOpen());
            assertFalse(channel.isOpen());
        } finally {
            closeReleasedRead.countDown();
            channel.close();
            fileSystem.close();
            executor.shutdownNow();
        }
    }

    /// Verifies that lifecycle wrappers preserve interruptible-channel type information.
    @Test
    public void managedChannelsPreserveInterruptibleMarker() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        CountDownLatch ignoredEntered = new CountDownLatch(1);
        CountDownLatch ignoredRelease = new CountDownLatch(1);
        BlockingInterruptibleReadableByteChannel readable =
                new BlockingInterruptibleReadableByteChannel(ignoredEntered, ignoredRelease);
        InterruptibleTrackingSeekableByteChannel seekable = new InterruptibleTrackingSeekableByteChannel();
        TrackingSeekableByteChannel plainWritable = new TrackingSeekableByteChannel();

        try (ReadableByteChannel managedReadable = fileSystem.managedReadableChannel(readable);
             SeekableByteChannel managedSeekable = fileSystem.managedReadChannel(seekable);
             SeekableByteChannel managedPlainWritable = fileSystem.managedWriteChannel(plainWritable)) {
            assertInstanceOf(InterruptibleChannel.class, managedReadable);
            assertInstanceOf(InterruptibleChannel.class, managedSeekable);
            assertFalse(managedPlainWritable instanceof InterruptibleChannel);
        } finally {
            ignoredRelease.countDown();
            fileSystem.close();
        }
    }

    /// Verifies stream wrappers retain delegate ownership but map coordinated file system closure to stream failures.
    @Test
    public void concurrentReadStreamsMapFileSystemClosure() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        TrackingInputStream inputDelegate = new TrackingInputStream();
        TrackingOutputStream outputDelegate = new TrackingOutputStream();
        InputStream input = fileSystem.managedInputStream(inputDelegate);
        OutputStream output = fileSystem.managedOutputStream(outputDelegate);

        fileSystem.close();

        assertTrue(inputDelegate.isOpen());
        assertTrue(outputDelegate.isOpen());
        IOException inputFailure = assertThrows(IOException.class, input::read);
        assertEquals("Managed archive input stream is closed", inputFailure.getMessage());
        assertInstanceOf(ClosedFileSystemException.class, inputFailure.getCause());
        IOException outputFailure = assertThrows(IOException.class, () -> output.write(1));
        assertEquals("Managed archive output stream is closed", outputFailure.getMessage());
        assertInstanceOf(ClosedFileSystemException.class, outputFailure.getCause());

        input.close();
        output.close();
        assertFalse(inputDelegate.isOpen());
        assertFalse(outputDelegate.isOpen());
    }

    /// Verifies managed resources delegate their complete mutable operation surfaces and enforce terminal state.
    @Test
    public void managedResourcesDelegateCompleteOperationSurface() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        try {
            Path path = temporaryDirectory.resolve("managed.bin");
            try (SeekableByteChannel channel = fileSystem.managedWriteChannel(Files.newByteChannel(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            ))) {
                assertEquals(4, channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
                assertEquals(4L, channel.position());
                assertEquals(4L, channel.size());
                assertSame(channel, channel.position(1L));
                ByteBuffer target = ByteBuffer.allocate(2);
                assertEquals(2, channel.read(target));
                assertArrayEquals(new byte[]{2, 3}, target.array());
                assertSame(channel, channel.truncate(3L));
                assertEquals(3L, channel.size());
            }

            ByteArrayInputStream inputDelegate = new ByteArrayInputStream(new byte[]{10, 11, 12, 13, 14});
            InputStream input = fileSystem.managedInputStream(inputDelegate);
            assertTrue(input.markSupported());
            assertEquals(10, input.read());
            input.mark(5);
            byte[] inputTarget = new byte[4];
            assertEquals(2, input.read(inputTarget, 1, 2));
            assertArrayEquals(new byte[]{0, 11, 12, 0}, inputTarget);
            input.reset();
            assertEquals(1L, input.skip(1L));
            assertEquals(3, input.available());
            assertEquals(12, input.read());
            input.close();
            input.close();
            assertThrows(IOException.class, input::read);
            assertThrows(IOException.class, input::reset);
            assertThrows(IllegalStateException.class, () -> input.mark(1));

            ByteArrayOutputStream outputDelegate = new ByteArrayOutputStream();
            OutputStream output = fileSystem.managedOutputStream(outputDelegate);
            output.write(1);
            output.write(new byte[]{2, 3, 4}, 1, 2);
            output.flush();
            output.close();
            output.close();
            assertArrayEquals(new byte[]{1, 3, 4}, outputDelegate.toByteArray());
            assertThrows(IOException.class, () -> output.write(5));
            assertThrows(IOException.class, output::flush);

            Path first = Path.of("first");
            Path second = Path.of("second");
            MutableDirectoryStream directoryDelegate = new MutableDirectoryStream(List.of(first, second));
            DirectoryStream<Path> directory = fileSystem.managedDirectoryStream(directoryDelegate);
            Iterator<Path> iterator = directory.iterator();
            assertTrue(iterator.hasNext());
            assertEquals(first, iterator.next());
            iterator.remove();
            assertEquals(List.of(second), directoryDelegate.entries());
            assertEquals(second, iterator.next());
            assertFalse(iterator.hasNext());
            directory.close();
            directory.close();
            assertFalse(directoryDelegate.isOpen());
            assertThrows(ClosedDirectoryStreamException.class, directory::iterator);
        } finally {
            fileSystem.close();
        }
    }

    /// Verifies the no-argument constructor selects concurrent-read coordination.
    @Test
    public void defaultStrategyUsesConcurrentReads() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem();
        try {
            assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
            TrackingReadableByteChannel delegate = new TrackingReadableByteChannel();
            try (ReadableByteChannel managed = fileSystem.managedReadableChannel(delegate)) {
                assertNotSame(delegate, managed);
            }
        } finally {
            fileSystem.close();
        }
    }

    /// Verifies that the none strategy returns raw resources and adds no close behavior.
    @Test
    public void noneStrategyDoesNotWrapResources() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.NONE);
        TrackingSeekableByteChannel readChannel = new TrackingSeekableByteChannel();
        TrackingSeekableByteChannel writeChannel = new TrackingSeekableByteChannel();
        TrackingReadableByteChannel readableChannel = new TrackingReadableByteChannel();
        TrackingInputStream input = new TrackingInputStream();
        TrackingOutputStream output = new TrackingOutputStream();
        TrackingDirectoryStream directory = new TrackingDirectoryStream();

        assertSame(readChannel, fileSystem.managedReadChannel(readChannel));
        assertSame(writeChannel, fileSystem.managedWriteChannel(writeChannel));
        assertSame(readableChannel, fileSystem.managedReadableChannel(readableChannel));
        assertSame(input, fileSystem.managedInputStream(input));
        assertSame(output, fileSystem.managedOutputStream(output));
        assertSame(directory, fileSystem.managedDirectoryStream(directory));

        fileSystem.runRead();
        fileSystem.runWrite();
        fileSystem.close();

        assertTrue(readChannel.isOpen());
        assertTrue(writeChannel.isOpen());
        assertTrue(readableChannel.isOpen());
        assertTrue(input.isOpen());
        assertTrue(output.isOpen());
        assertTrue(directory.isOpen());
        readChannel.read(ByteBuffer.allocate(1));
        readableChannel.read(ByteBuffer.allocate(1));
        input.read();
        output.write(1);
        directory.iterator().hasNext();
        fileSystem.runRead();
        fileSystem.runWrite();
    }

    /// Verifies one strict close failure retains its concrete type and original identity.
    private static <T extends Throwable> void assertStrictCloseFailure(T failure) {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.STRICT);
        UncheckedCloseInputStream delegate = new UncheckedCloseInputStream(failure);
        fileSystem.managedInputStream(delegate);

        Throwable thrown = assertThrows(failure.getClass(), fileSystem::close);

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(2, delegate.closeCalls());
        assertFalse(fileSystem.isOpen());
    }

    /// Provides a minimal file system that exposes the shared coordination primitives to tests.
    @NotNullByDefault
    private static final class TestFileSystem extends ArkivoFileSystem {
        /// Whether this test file system remains open.
        private volatile boolean open = true;

        /// Creates a test file system with the default strategy.
        private TestFileSystem() {
        }

        /// Creates a test file system with the requested strategy.
        private TestFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
            super(threadSafety);
        }

        /// Holds a shared operation until the release latch opens.
        private void holdRead(CountDownLatch entered, CountDownLatch release) throws InterruptedException {
            try (Operation ignored = beginReadOperation()) {
                entered.countDown();
                release.await();
            }
        }

        /// Runs one shared operation.
        private void runRead() {
            try (Operation ignored = beginReadOperation()) {
                // The token lifecycle is the behavior under test.
            }
        }

        /// Runs one exclusive operation.
        private void runWrite() {
            try (Operation ignored = beginWriteOperation()) {
                // The lock acquisition is the behavior under test.
            }
        }

        /// Holds an exclusive close transition until the release latch opens.
        private void holdClose(CountDownLatch entered, CountDownLatch release) throws InterruptedException, IOException {
            try (CloseOperation ignored = beginCloseOperation()) {
                open = false;
                entered.countDown();
                release.await();
            }
        }

        /// Attempts a nested close while the current thread owns the close transition.
        private void runReentrantClose() throws IOException {
            try (CloseOperation ignored = beginCloseOperation()) {
                open = false;
                try (CloseOperation nested = beginCloseOperation()) {
                    // The nested operation is expected to be rejected before this scope is entered.
                }
            }
        }

        /// Closes one lifecycle token twice to verify token-level idempotence.
        private void closeWithRepeatedToken() throws IOException {
            CloseOperation operation = beginCloseOperation();
            open = false;
            operation.close();
            operation.close();
        }

        /// Opens and closes a managed input stream inside one shared operation.
        private void readAndCloseManagedInput(InputStream input) throws IOException {
            try (Operation ignored = beginReadOperation();
                 InputStream managed = manageInputStream(input)) {
                managed.read();
            }
        }

        /// Exposes readable channel management.
        private ReadableByteChannel managedReadableChannel(ReadableByteChannel channel) {
            return manageReadableChannel(channel);
        }

        /// Exposes read-only seekable channel management.
        private SeekableByteChannel managedReadChannel(SeekableByteChannel channel) {
            return manageReadChannel(channel);
        }

        /// Exposes writable seekable channel management.
        private SeekableByteChannel managedWriteChannel(SeekableByteChannel channel) {
            return manageWriteChannel(channel);
        }

        /// Exposes input stream management.
        private InputStream managedInputStream(InputStream input) {
            return manageInputStream(input);
        }

        /// Exposes output stream management.
        private OutputStream managedOutputStream(OutputStream output) {
            return manageOutputStream(output);
        }

        /// Exposes directory stream management.
        private DirectoryStream<Path> managedDirectoryStream(DirectoryStream<Path> stream) {
            return manageDirectoryStream(stream);
        }

        /// Returns the default provider for the test file system.
        @Override
        public FileSystemProvider provider() {
            return java.nio.file.FileSystems.getDefault().provider();
        }

        /// Closes the coordinated test lifecycle.
        @Override
        public void close() throws IOException {
            try (CloseOperation ignored = beginCloseOperation()) {
                open = false;
            }
        }

        /// Returns whether this test file system remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Returns that this test file system is read-only.
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /// Returns the default path separator.
        @Override
        public String getSeparator() {
            return java.nio.file.FileSystems.getDefault().getSeparator();
        }

        /// Returns no roots because paths are delegated to the default file system.
        @Override
        public Iterable<Path> getRootDirectories() {
            return List.of();
        }

        /// Returns no file stores.
        @Override
        public Iterable<FileStore> getFileStores() {
            return List.of();
        }

        /// Returns no custom attribute views.
        @Override
        public Set<String> supportedFileAttributeViews() {
            return Set.of();
        }

        /// Creates a path through the default file system.
        @Override
        public Path getPath(String first, String... more) {
            return java.nio.file.FileSystems.getDefault().getPath(first, more);
        }

        /// Creates a path matcher through the default file system.
        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            return java.nio.file.FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
        }

        /// Returns the default user principal lookup service.
        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService();
        }

        /// Rejects watch service creation for this test file system.
        @Override
        public WatchService newWatchService() {
            throw new UnsupportedOperationException("Test watch services are not supported");
        }
    }

    /// Provides a readable channel whose read remains active until released.
    @NotNullByDefault
    private static final class BlockingReadableByteChannel implements ReadableByteChannel {
        /// Signals when a read operation begins.
        private final CountDownLatch entered;

        /// Releases the active read operation.
        private final CountDownLatch release;

        /// Whether this channel remains open.
        private volatile boolean open = true;

        /// Creates a blocking readable channel.
        private BlockingReadableByteChannel(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        /// Blocks until released and then reports end of input.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            requireOpen();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading", exception);
            }
            return -1;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to remain open.
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Provides an interruptible channel whose active read is released only by close.
    @NotNullByDefault
    private static final class BlockingInterruptibleReadableByteChannel
            implements ReadableByteChannel, InterruptibleChannel {
        /// Signals when a read operation begins.
        private final CountDownLatch entered;

        /// Releases the active read after close.
        private final CountDownLatch release;

        /// Whether this channel remains open.
        private volatile boolean open = true;

        /// Creates a blocking interruptible test channel.
        private BlockingInterruptibleReadableByteChannel(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        /// Waits for close and reports asynchronous closure.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading", exception);
            }
            if (!open) {
                throw new AsynchronousCloseException();
            }
            return -1;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel and releases its active read.
        @Override
        public void close() {
            open = false;
            release.countDown();
        }
    }

    /// Provides a readable channel that records close state.
    @NotNullByDefault
    private static final class TrackingReadableByteChannel implements ReadableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Reports end of input while open.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            requireOpen();
            return -1;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to remain open.
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Provides a seekable channel that records close state.
    @NotNullByDefault
    private static class TrackingSeekableByteChannel implements SeekableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// The current channel position.
        private long position;

        /// Reports end of input while open.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            requireOpen();
            return -1;
        }

        /// Rejects writes because this test channel is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            requireOpen();
            throw new UnsupportedOperationException("Test channel is read-only");
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            requireOpen();
            return position;
        }

        /// Changes the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            requireOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("Position is negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the empty channel size.
        @Override
        public long size() throws IOException {
            requireOpen();
            return 0L;
        }

        /// Rejects truncation because this test channel is read-only.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            requireOpen();
            throw new UnsupportedOperationException("Test channel is read-only");
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to remain open.
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Provides a seekable channel carrying the interruptible marker.
    @NotNullByDefault
    private static final class InterruptibleTrackingSeekableByteChannel
            extends TrackingSeekableByteChannel implements InterruptibleChannel {
        /// Creates an open interruptible tracking channel.
        private InterruptibleTrackingSeekableByteChannel() {
        }
    }

    /// Provides an input stream that records close state.
    @NotNullByDefault
    private static final class TrackingInputStream extends InputStream {
        /// Failure reported while configured close attempts remain.
        private final IOException closeFailure;

        /// Number of close failures still scheduled.
        private int closeFailuresRemaining;

        /// Number of close calls received.
        private int closeCalls;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Creates a stream whose close always succeeds.
        private TrackingInputStream() {
            this("close failure", 0);
        }

        /// Creates a stream with the requested initial close failures.
        private TrackingInputStream(String closeFailureMessage, int closeFailuresRemaining) {
            this.closeFailure = new IOException(closeFailureMessage);
            this.closeFailuresRemaining = closeFailuresRemaining;
        }

        /// Reports end of input while open.
        @Override
        public int read() throws IOException {
            requireOpen();
            return -1;
        }

        /// Closes this stream.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw closeFailure;
            }
            open = false;
        }

        /// Returns whether this stream remains open.
        private boolean isOpen() {
            return open;
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }

        /// Requires this stream to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("Test input stream is closed");
            }
        }
    }

    /// Provides an input stream that reports the same unchecked failure from every close attempt.
    @NotNullByDefault
    private static final class UncheckedCloseInputStream extends InputStream {
        /// Unchecked failure reported by every close attempt.
        private final Throwable failure;

        /// Number of close calls received.
        private int closeCalls;

        /// Creates a stream that reports the supplied runtime exception or error.
        private UncheckedCloseInputStream(Throwable failure) {
            if (!(failure instanceof RuntimeException) && !(failure instanceof Error)) {
                throw new IllegalArgumentException("failure must be unchecked");
            }
            this.failure = failure;
        }

        /// Reports physical end of input.
        @Override
        public int read() {
            return -1;
        }

        /// Reports the configured unchecked failure.
        @Override
        public void close() {
            closeCalls++;
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Provides an output stream that records close state.
    @NotNullByDefault
    private static final class TrackingOutputStream extends OutputStream {
        /// Whether this stream remains open.
        private boolean open = true;

        /// Accepts one byte while open.
        @Override
        public void write(int value) throws IOException {
            requireOpen();
        }

        /// Closes this stream.
        @Override
        public void close() {
            open = false;
        }

        /// Returns whether this stream remains open.
        private boolean isOpen() {
            return open;
        }

        /// Requires this stream to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("Test output stream is closed");
            }
        }
    }

    /// Provides a directory stream that records close state.
    @NotNullByDefault
    private static final class TrackingDirectoryStream implements DirectoryStream<Path> {
        /// Whether this stream remains open.
        private boolean open = true;

        /// Returns an empty iterator while open.
        @Override
        public Iterator<Path> iterator() {
            if (!open) {
                throw new IllegalStateException("Test directory stream is closed");
            }
            return List.<Path>of().iterator();
        }

        /// Closes this stream.
        @Override
        public void close() {
            open = false;
        }

        /// Returns whether this stream remains open.
        private boolean isOpen() {
            return open;
        }
    }

    /// Provides a mutable directory stream for exercising iterator traversal and removal.
    @NotNullByDefault
    private static final class MutableDirectoryStream implements DirectoryStream<Path> {
        /// Mutable directory entries returned by the iterator.
        private final ArrayList<Path> entries;

        /// Whether this directory stream remains open.
        private boolean open = true;

        /// Creates a directory stream over copied entries.
        private MutableDirectoryStream(List<Path> entries) {
            this.entries = new ArrayList<>(entries);
        }

        /// Returns a mutable iterator while this stream is open.
        @Override
        public Iterator<Path> iterator() {
            if (!open) {
                throw new ClosedDirectoryStreamException();
            }
            return entries.iterator();
        }

        /// Closes this directory stream.
        @Override
        public void close() {
            open = false;
        }

        /// Returns an immutable snapshot of current entries.
        private List<Path> entries() {
            return List.copyOf(entries);
        }

        /// Returns whether this directory stream remains open.
        private boolean isOpen() {
            return open;
        }
    }
}

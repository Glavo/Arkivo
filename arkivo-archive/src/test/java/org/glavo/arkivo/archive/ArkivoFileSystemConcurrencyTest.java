// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the shared archive file system concurrency and lifecycle contract.
@NotNullByDefault
public final class ArkivoFileSystemConcurrencyTest {
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
            assertTrue(readsEntered.await(2L, TimeUnit.SECONDS));

            Future<Void> write = executor.submit(() -> {
                fileSystem.runWrite();
                return null;
            });
            assertThrows(TimeoutException.class, () -> write.get(100L, TimeUnit.MILLISECONDS));

            releaseReads.countDown();
            firstRead.get(2L, TimeUnit.SECONDS);
            secondRead.get(2L, TimeUnit.SECONDS);
            write.get(2L, TimeUnit.SECONDS);
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
            assertTrue(readEntered.await(2L, TimeUnit.SECONDS));
            Future<Void> close = executor.submit(() -> {
                fileSystem.close();
                return null;
            });
            assertThrows(TimeoutException.class, () -> close.get(100L, TimeUnit.MILLISECONDS));

            releaseRead.countDown();
            read.get(2L, TimeUnit.SECONDS);
            close.get(2L, TimeUnit.SECONDS);

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
            operation.get(2L, TimeUnit.SECONDS);
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

    /// Verifies that the none strategy returns raw resources and adds no close behavior.
    @Test
    public void noneStrategyDoesNotWrapResources() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(ArkivoFileSystemThreadSafety.NONE);
        TrackingSeekableByteChannel channel = new TrackingSeekableByteChannel();
        TrackingInputStream input = new TrackingInputStream();
        TrackingDirectoryStream directory = new TrackingDirectoryStream();

        assertSame(channel, fileSystem.managedReadChannel(channel));
        assertSame(input, fileSystem.managedInputStream(input));
        assertSame(directory, fileSystem.managedDirectoryStream(directory));

        fileSystem.close();

        assertTrue(channel.isOpen());
        assertTrue(input.isOpen());
        assertTrue(directory.isOpen());
        channel.read(ByteBuffer.allocate(1));
        input.read();
        directory.iterator().hasNext();
    }

    /// Provides a minimal file system that exposes the shared coordination primitives to tests.
    @NotNullByDefault
    private static final class TestFileSystem extends ArkivoFileSystem {
        /// Whether this test file system remains open.
        private volatile boolean open = true;

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

        /// Runs one exclusive operation.
        private void runWrite() {
            try (Operation ignored = beginWriteOperation()) {
                // The lock acquisition is the behavior under test.
            }
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
    private static final class TrackingSeekableByteChannel implements SeekableByteChannel {
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

    /// Provides an input stream that records close state.
    @NotNullByDefault
    private static final class TrackingInputStream extends InputStream {
        /// Whether this stream remains open.
        private boolean open = true;

        /// Reports end of input while open.
        @Override
        public int read() throws IOException {
            requireOpen();
            return -1;
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
                throw new IOException("Test input stream is closed");
            }
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
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Checks direct 7z output cancellation before coordinated entry and folder completion.
@NotNullByDefault
final class SevenZipConcurrentOutputCloseTest {
    /// Isolates each archive on the native file system.
    @TempDir
    private Path directory;

    /// Exercises direct output, delayed compression, encryption, and staged branch conversion.
    @NotNullByDefault
    private enum Encoding {
        /// Uncompressed packed streams.
        COPY,
        /// Buffered LZMA2 packed streams.
        LZMA2,
        /// LZMA2 streams with AES padding.
        AES,
        /// Four staged BCJ2 packed streams.
        BCJ2;

        /// Selects a strict writer with the specified solid-folder boundary.
        SevenZipArchiveOptions.Create options(int solidFileCount) {
            var options = SevenZipArchiveOptions.CREATE_DEFAULTS.withCommon(
                    ArchiveCreateOptions.DEFAULT.withThreadSafety(ArkivoFileSystemThreadSafety.STRICT))
                    .withSolidFileCount(solidFileCount)
                    .withCompression(this == COPY || this == BCJ2
                            ? SevenZipCompression.copy() : SevenZipCompression.lzma2(4096));
            if (this == AES) options = options.withPasswordProvider(password());
            if (this == BCJ2) options = options.withFilters(
                    SevenZipFilterChain.of(SevenZipFilter.of(SevenZipFilterMethod.BCJ2)));
            return options;
        }
    }

    /// Supplies the same ASCII password to both sides of encrypted round trips.
    private static ArkivoPasswordProvider password() {
        return ArkivoPasswordProvider.fixed(new byte[]{'t', 'e', 's', 't'});
    }

    /// Opens the second entry through either public writable entry API.
    private static OutputStream openBody(SevenZipArkivoFileSystem fileSystem, boolean channel) throws IOException {
        return channel ? Channels.newOutputStream(Files.newByteChannel(fileSystem.getPath("second"),
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)))
                : Files.newOutputStream(fileSystem.getPath("second"));
    }

    /// Combines coder, entry API, and physical-write completion behavior.
    private static Stream<Arguments> cancellations() {
        return Stream.of(Encoding.values()).flatMap(encoding ->
                Stream.of(false, true).flatMap(channel -> Stream.of(false, true)
                        .map(success -> Arguments.of(encoding, channel, success))));
    }

    /// Cancels writes even when they are completing the preceding folder rather than the current entry.
    @ParameterizedTest
    @MethodSource("cancellations")
    void strictCloseCancelsActiveWrite(Encoding encoding, boolean channel, boolean successfulWrite) throws Exception {
        Path path = directory.resolve("archive.7z");
        GatedChannel target = new GatedChannel(Files.newByteChannel(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE), successfulWrite);
        var fileSystem = SevenZipArkivoFileSystem.create(
                new OutputPath(path, new OutputFileSystem(target)), encoding.options(1));
        try (var first = Files.newOutputStream(fileSystem.getPath("first"))) {
            first.write(new byte[]{(byte) 0xE8, 0, 0, 0, 0, (byte) 0xE9, 1, 0, 0, 0});
        }
        OutputStream body = openBody(fileSystem, channel);
        byte[] content = new byte[256 * 1024];
        new Random(42).nextBytes(content);
        target.armed.set(true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var write = executor.submit(() -> failure(() -> body.write(content)));
            assertTrue(target.writing.await(5, TimeUnit.SECONDS), "Physical write must begin");
            var close = executor.submit(() -> failure(fileSystem::close));
            assertTrue(target.closed.await(5, TimeUnit.SECONDS), "Close must reach the physical channel");
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS),
                    "Metadata must not be finalized while the write is active");
            target.release.countDown();
            @Nullable Throwable writeFailure = write.get(5, TimeUnit.SECONDS);
            assertInstanceOf(IOException.class, writeFailure);
            if (!successfulWrite) assertSame(target.writeFailure, writeFailure);
            assertInstanceOf(IOException.class, close.get(5, TimeUnit.SECONDS));
            byte[] incomplete = Files.readAllBytes(path);
            body.close();
            fileSystem.close();
            fileSystem.close();
            assertArrayEquals(incomplete, Files.readAllBytes(path));
            assertEquals(0, target.writesAfterClose);
            assertEquals(1, target.closeCalls);
        } finally {
            target.release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            failure(body::close);
            failure(fileSystem::close);
            target.close();
        }
    }

    /// Failed physical cleanup remains retryable without completing a cancelled folder or archive.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictCloseRetriesFailedPhysicalClose(boolean channel) throws Exception {
        Path path = directory.resolve("retry.7z");
        GatedChannel target = new GatedChannel(Files.newByteChannel(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE), false);
        var fileSystem = SevenZipArkivoFileSystem.create(
                new OutputPath(path, new OutputFileSystem(target)), Encoding.BCJ2.options(1));
        try (var first = Files.newOutputStream(fileSystem.getPath("first"))) {
            first.write(new byte[]{1, 2, 3});
        }
        OutputStream body = openBody(fileSystem, channel);
        target.armed.set(true);
        target.failClose = true;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var write = executor.submit(() -> failure(() -> body.write(new byte[]{4, 5, 6})));
            assertTrue(target.writing.await(5, TimeUnit.SECONDS));
            var close = executor.submit(() -> failure(fileSystem::close));
            assertTrue(target.closed.await(5, TimeUnit.SECONDS));
            target.release.countDown();
            assertSame(target.writeFailure, write.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, close.get(5, TimeUnit.SECONDS));
            assertTrue(target.isOpen(), "Failed close must retain the physical resource");
            byte[] partial = Files.readAllBytes(path);
            int attempts = target.closeCalls;
            target.failClose = false;
            fileSystem.close();
            assertEquals(attempts + 1, target.closeCalls);
            fileSystem.close();
            body.close();
            assertEquals(attempts + 1, target.closeCalls);
            assertFalse(target.isOpen());
            assertEquals(0, target.writesAfterClose);
            assertArrayEquals(partial, Files.readAllBytes(path));
        } finally {
            target.release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            target.failClose = false;
            failure(body::close);
            failure(fileSystem::close);
            target.close();
        }
    }

    /// Normal entry closure retains shared solid coders, including repeated closure of an older entry.
    @ParameterizedTest
    @EnumSource(Encoding.class)
    void strictCloseFinishesIdleSolidEntry(Encoding encoding) throws Exception {
        Path path = directory.resolve("solid.7z");
        GatedChannel target = new GatedChannel(Files.newByteChannel(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE), false);
        try (var fileSystem = SevenZipArkivoFileSystem.create(
                new OutputPath(path, new OutputFileSystem(target)), encoding.options(4))) {
            OutputStream first = Files.newOutputStream(fileSystem.getPath("first"));
            first.write(new byte[]{1, 2, 3});
            first.close();
            OutputStream second = openBody(fileSystem, true);
            first.close();
            first.close();
            second.write(new byte[]{4, 5, 6});
        }
        assertEquals(1, target.closeCalls);
        try (var fileSystem = SevenZipArkivoFileSystem.open(path,
                SevenZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(password()))) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fileSystem.getPath("first")));
            assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(fileSystem.getPath("second")));
        }
    }

    /// Captures an operation result so worker failures can be asserted on the test thread.
    private static @Nullable Throwable failure(IOOperation operation) {
        try {
            operation.run();
            return null;
        } catch (IOException | RuntimeException | Error exception) {
            return exception;
        }
    }

    /// An operation that may report an I/O failure.
    @FunctionalInterface
    @NotNullByDefault
    private interface IOOperation {
        /// Performs the operation.
        void run() throws IOException;
    }

    /// Wraps a real path through a public provider that supplies the gated output channel.
    ///
    /// @param path the backing native path
    /// @param fileSystem the file system supplying the gated destination
    @NotNullByDefault
    private record OutputPath(Path path, OutputFileSystem fileSystem) implements Path {
        /// Returns the channel-injecting file system.
        public FileSystem getFileSystem() { return fileSystem; }
        /// Reports absolute state.
        public boolean isAbsolute() { return path.isAbsolute(); }
        /// Returns the root.
        public @Nullable Path getRoot() { return path.getRoot(); }
        /// Returns the final name.
        public @Nullable Path getFileName() { return path.getFileName(); }
        /// Returns the parent.
        public @Nullable Path getParent() { return path.getParent(); }
        /// Counts name components.
        public int getNameCount() { return path.getNameCount(); }
        /// Selects one name.
        public Path getName(int index) { return path.getName(index); }
        /// Selects a name range.
        public Path subpath(int begin, int end) { return path.subpath(begin, end); }
        /// Checks a prefix.
        public boolean startsWith(Path other) { return path.startsWith(unwrap(other)); }
        /// Checks a suffix.
        public boolean endsWith(Path other) { return path.endsWith(unwrap(other)); }
        /// Normalizes while retaining the public provider.
        public Path normalize() { return new OutputPath(path.normalize(), fileSystem); }
        /// Resolves another path.
        public Path resolve(Path other) { return new OutputPath(path.resolve(unwrap(other)), fileSystem); }
        /// Relativizes another path.
        public Path relativize(Path other) { return path.relativize(unwrap(other)); }
        /// Returns the real file URI.
        public URI toUri() { return path.toUri(); }
        /// Absolutizes while retaining the public provider.
        public Path toAbsolutePath() { return new OutputPath(path.toAbsolutePath(), fileSystem); }
        /// Resolves the real path.
        public Path toRealPath(LinkOption... options) throws IOException { return new OutputPath(path.toRealPath(options), fileSystem); }
        /// Delegates watch registration.
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
            return path.register(watcher, events, modifiers);
        }
        /// Compares underlying paths.
        public int compareTo(Path other) { return path.compareTo(unwrap(other)); }
        /// Returns platform path text.
        public String toString() { return path.toString(); }
        /// Unwraps paths passed back to the native provider.
        static Path unwrap(Path path) { return path instanceof OutputPath wrapped ? wrapped.path : path; }
    }
    /// Minimal public file system retaining native path semantics and substituting one output channel.
    @NotNullByDefault
    private static final class OutputFileSystem extends FileSystem {
        /// The configured provider.
        final OutputProvider provider;
        /// Creates the provider facade.
        OutputFileSystem(GatedChannel channel) { provider = new OutputProvider(channel); }
        /// Returns the custom provider.
        public FileSystemProvider provider() { return provider; }
        /// Leaves the native file system open.
        public void close() { }
        /// Always remains open during the test.
        public boolean isOpen() { return true; }
        /// Supports writing.
        public boolean isReadOnly() { return false; }
        /// Returns the native separator.
        public String getSeparator() { return FileSystems.getDefault().getSeparator(); }
        /// Returns native roots.
        public Iterable<Path> getRootDirectories() { return FileSystems.getDefault().getRootDirectories(); }
        /// Returns native stores.
        public Iterable<FileStore> getFileStores() { return FileSystems.getDefault().getFileStores(); }
        /// Returns native attribute views.
        public Set<String> supportedFileAttributeViews() { return FileSystems.getDefault().supportedFileAttributeViews(); }
        /// Creates a wrapped path.
        public Path getPath(String first, String... more) { return new OutputPath(Path.of(first, more), this); }
        /// Delegates matching.
        public PathMatcher getPathMatcher(String expression) { return FileSystems.getDefault().getPathMatcher(expression); }
        /// Delegates principal lookup.
        public UserPrincipalLookupService getUserPrincipalLookupService() { return FileSystems.getDefault().getUserPrincipalLookupService(); }
        /// Delegates watch creation.
        public WatchService newWatchService() throws IOException { return FileSystems.getDefault().newWatchService(); }
    }
    /// Delegates all file operations except opening the one test destination.
    @NotNullByDefault
    private static final class OutputProvider extends FileSystemProvider {
        /// The close-aware destination supplied to the archive writer.
        final GatedChannel channel;
        /// Creates a provider over the destination.
        OutputProvider(GatedChannel channel) { this.channel = channel; }
        /// Identifies this local provider.
        public String getScheme() { return "gated-output"; }
        /// Rejects URI creation.
        public FileSystem newFileSystem(URI uri, Map<String, ?> environment) { throw new UnsupportedOperationException(); }
        /// Rejects URI lookup.
        public FileSystem getFileSystem(URI uri) { throw new UnsupportedOperationException(); }
        /// Rejects URI paths.
        public Path getPath(URI uri) { throw new UnsupportedOperationException(); }
        /// Supplies the channel through the standard NIO provider hook.
        public SeekableByteChannel newByteChannel(Path path, Set<? extends java.nio.file.OpenOption> options, FileAttribute<?>... attrs) { return channel; }
        /// Delegates directory reads.
        public DirectoryStream<Path> newDirectoryStream(Path path, DirectoryStream.Filter<? super Path> filter) throws IOException { return Files.newDirectoryStream(OutputPath.unwrap(path), filter); }
        /// Delegates directory creation.
        public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException { Files.createDirectory(OutputPath.unwrap(path), attrs); }
        /// Delegates deletion.
        public void delete(Path path) throws IOException { Files.delete(OutputPath.unwrap(path)); }
        /// Delegates copying.
        public void copy(Path source, Path target, CopyOption... options) throws IOException { Files.copy(OutputPath.unwrap(source), OutputPath.unwrap(target), options); }
        /// Delegates moving.
        public void move(Path source, Path target, CopyOption... options) throws IOException { Files.move(OutputPath.unwrap(source), OutputPath.unwrap(target), options); }
        /// Delegates identity checks.
        public boolean isSameFile(Path source, Path target) throws IOException { return Files.isSameFile(OutputPath.unwrap(source), OutputPath.unwrap(target)); }
        /// Delegates hidden status.
        public boolean isHidden(Path path) throws IOException { return Files.isHidden(OutputPath.unwrap(path)); }
        /// Delegates store lookup.
        public FileStore getFileStore(Path path) throws IOException { return Files.getFileStore(OutputPath.unwrap(path)); }
        /// Delegates access checks.
        public void checkAccess(Path path, AccessMode... modes) throws IOException { Path actual = OutputPath.unwrap(path); actual.getFileSystem().provider().checkAccess(actual, modes); }
        /// Delegates typed attribute views.
        public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type, LinkOption... options) { return Files.getFileAttributeView(OutputPath.unwrap(path), type, options); }
        /// Delegates typed attributes.
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException { return Files.readAttributes(OutputPath.unwrap(path), type, options); }
        /// Delegates named attributes.
        public Map<String, Object> readAttributes(Path path, String names, LinkOption... options) throws IOException { return Files.readAttributes(OutputPath.unwrap(path), names, options); }
        /// Delegates attribute writes.
        public void setAttribute(Path path, String name, Object value, LinkOption... options) throws IOException { Files.setAttribute(OutputPath.unwrap(path), name, value, options); }
    }
    /// Holds one partial physical write independently of raw channel closure.
    @NotNullByDefault
    private static final class GatedChannel implements SeekableByteChannel {
        /// The native destination.
        private final SeekableByteChannel delegate;
        /// Enables the one held write after initialization.
        private final AtomicBoolean armed = new AtomicBoolean();
        /// Signals that one byte was written.
        private final CountDownLatch writing = new CountDownLatch(1);
        /// Signals that physical close was reached.
        private final CountDownLatch closed = new CountDownLatch(1);
        /// Separately releases the held writer after cancellation has been asserted.
        private final CountDownLatch release = new CountDownLatch(1);
        /// The exact physical failure expected by the caller.
        private final IOException writeFailure = new IOException("Partial physical write aborted");
        /// Whether the held call returns success after cancellation.
        private final boolean successfulWrite;
        /// Whether physical close persistently fails without releasing the destination.
        private volatile boolean failClose;
        /// Counts physical close invocations.
        private int closeCalls;
        /// Counts forbidden writes after physical closure.
        private int writesAfterClose;

        /// Wraps a destination with a configurable held-write result.
        private GatedChannel(SeekableByteChannel delegate, boolean successfulWrite) {
            this.delegate = delegate;
            this.successfulWrite = successfulWrite;
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            if (closed.getCount() == 0) writesAfterClose++;
            if (!armed.compareAndSet(true, false)) return delegate.write(source);
            int limit = source.limit();
            source.limit(source.position() + 1);
            try {
                delegate.write(source);
            } finally {
                source.limit(limit);
            }
            writing.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Write release timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            if (!successfulWrite) throw writeFailure;
            return 1;
        }

        @Override
        public int read(ByteBuffer target) throws IOException { return delegate.read(target); }

        @Override
        public long position() throws IOException { return delegate.position(); }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }

        @Override
        public long size() throws IOException { return delegate.size(); }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() { return delegate.isOpen(); }

        @Override
        public void close() throws IOException {
            closeCalls++;
            try {
                if (failClose) throw new IOException("Physical close failed");
                delegate.close();
            } finally {
                closed.countDown();
            }
        }
    }
}

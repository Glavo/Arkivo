// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks ownership and failure preservation for path-based format detection.
@NotNullByDefault
final class FormatPathDetectionTest {
    /// Confirms that the fixture exercises both recognized and unknown inputs with successful cleanup.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesAfterSuccessfulDetection(boolean archive) throws IOException {
        for (boolean recognized : new boolean[]{false, true}) {
            ProbeProvider provider = new ProbeProvider(archive, null, null);
            provider.recognized = recognized;
            assertSame(recognized
                    ? (archive ? ArkivoFormats.require("zip") : CompressionFormats.require("gzip"))
                    : null, provider.detect());
            provider.assertClosedOnce();
        }
    }

    /// Keeps the read failure primary whether cleanup repeats it or reports a distinct failure.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesReadFailure(boolean archive) {
        for (int kind = 0; kind < 3; kind++) {
            for (boolean shared : new boolean[]{false, true}) {
                Throwable primary = failure(kind);
                Throwable cleanup = shared ? primary : failure(kind);
                ProbeProvider provider = new ProbeProvider(archive, primary, cleanup);
                assertSame(primary, assertThrows(primary.getClass(), provider::detect));
                assertArrayEquals(shared ? new Throwable[0] : new Throwable[]{cleanup}, primary.getSuppressed());
                provider.assertClosedOnce();
            }
        }
    }

    /// Reports cleanup failures even when probing completed normally or found no format.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesCloseFailureAfterDetection(boolean archive) {
        for (int kind = 0; kind < 3; kind++) {
            for (boolean recognized : new boolean[]{false, true}) {
                Throwable cleanup = failure(kind);
                ProbeProvider provider = new ProbeProvider(archive, null, cleanup);
                provider.recognized = recognized;
                assertSame(cleanup, assertThrows(cleanup.getClass(), provider::detect));
                assertEquals(0, cleanup.getSuppressed().length);
                provider.assertClosedOnce();
            }
        }
    }

    /// Creates a fresh checked, unchecked, or error instance for one scenario.
    private static Throwable failure(int kind) {
        return switch (kind) {
            case 0 -> new IOException("Probe failure");
            case 1 -> new IllegalStateException("Probe failure");
            case 2 -> new AssertionError("Probe failure");
            default -> throw new AssertionError(kind);
        };
    }

    /// Supplies a single instrumented input without owning the caller's file system.
    @NotNullByDefault
    private static final class ProbeProvider extends FileSystemProvider {
        /// Selects archive rather than compression detection.
        private final boolean archive;
        /// Failure delivered by reading, or null to supply signature bytes.
        private final @Nullable Throwable readFailure;
        /// Failure delivered by channel cleanup, or null for successful cleanup.
        private final @Nullable Throwable closeFailure;
        /// File system returned by the synthetic path.
        private final ProbeFileSystem fileSystem = new ProbeFileSystem(this);
        /// Whether to return an installed signature.
        private boolean recognized;
        /// Number of physical opens.
        private int opens;
        /// Number of read calls.
        private int reads;
        /// Number of physical close attempts.
        private int closes;
        /// Current position, initially nonzero to check restoration before cleanup.
        private long position = 3L;

        /// Configures one path-based detection attempt.
        private ProbeProvider(boolean archive, @Nullable Throwable readFailure, @Nullable Throwable closeFailure) {
            this.archive = archive;
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        /// Invokes the public path overload on this provider.
        private @Nullable Object detect() throws IOException {
            Path path = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("getFileSystem")) {
                            return fileSystem;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            return archive ? ArkivoFormats.detect(path) : CompressionFormats.detect(path);
        }

        /// Checks that detection releases only its channel and restores its initial position.
        private void assertClosedOnce() {
            assertEquals(1, opens);
            if (readFailure != null) {
                assertEquals(1, reads);
            } else {
                assertTrue(reads > 0);
            }
            assertEquals(1, closes);
            assertEquals(3L, position);
            assertEquals(0, fileSystem.closes);
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attributes) {
            assertEquals(Set.of(StandardOpenOption.READ), options);
            opens++;
            return (SeekableByteChannel) Proxy.newProxyInstance(SeekableByteChannel.class.getClassLoader(),
                    new Class<?>[]{SeekableByteChannel.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "read": {
                                reads++;
                                if (readFailure != null) {
                                    throw readFailure;
                                }
                                ByteBuffer target = (ByteBuffer) arguments[0];
                                if (position >= 4096L) {
                                    return -1;
                                }
                                int length = (int) Math.min(target.remaining(), 4096L - position);
                                byte[] signature = archive
                                        ? new byte[]{'P', 'K', 3, 4}
                                        : new byte[]{31, (byte) 139, 8};
                                for (int index = 0; index < length; index++, position++) {
                                    target.put(recognized && position >= 3L && position < 3L + signature.length
                                            ? signature[(int) position - 3] : (byte) 0x55);
                                }
                                return length;
                            }
                            case "close":
                                closes++;
                                if (closeFailure != null) {
                                    throw closeFailure;
                                }
                                return null;
                            case "position":
                                if (arguments == null) {
                                    return position;
                                }
                                position = (long) arguments[0];
                                return proxy;
                            case "size":
                                return 4096L;
                            case "isOpen":
                                return true;
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        @Override
        public String getScheme() { return "detection-test"; }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> environment) { throw new UnsupportedOperationException(); }

        @Override
        public FileSystem getFileSystem(URI uri) { return fileSystem; }

        @Override
        public Path getPath(URI uri) { throw new UnsupportedOperationException(); }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path path, DirectoryStream.Filter<? super Path> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createDirectory(Path path, FileAttribute<?>... attributes) { throw new UnsupportedOperationException(); }

        @Override
        public void delete(Path path) { throw new UnsupportedOperationException(); }

        @Override
        public void copy(Path source, Path target, CopyOption... options) { throw new UnsupportedOperationException(); }

        @Override
        public void move(Path source, Path target, CopyOption... options) { throw new UnsupportedOperationException(); }

        @Override
        public boolean isSameFile(Path first, Path second) { throw new UnsupportedOperationException(); }

        @Override
        public boolean isHidden(Path path) { throw new UnsupportedOperationException(); }

        @Override
        public FileStore getFileStore(Path path) { throw new UnsupportedOperationException(); }

        @Override
        public void checkAccess(Path path, AccessMode... modes) { throw new UnsupportedOperationException(); }

        @Override
        public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type,
                                                                            LinkOption... options) { return null; }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                               LinkOption... options) { throw new UnsupportedOperationException(); }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes,
                                                  LinkOption... options) { throw new UnsupportedOperationException(); }

        @Override
        public void setAttribute(Path path, String name, Object value, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
    }

    /// Provides the synthetic path's storage association and detects accidental ownership transfer.
    @NotNullByDefault
    private static final class ProbeFileSystem extends FileSystem {
        /// Provider used to open the probe channel.
        private final ProbeProvider provider;
        /// Number of attempts to close the caller's file system.
        private int closes;

        /// Associates the file system with its provider.
        private ProbeFileSystem(ProbeProvider provider) { this.provider = provider; }

        @Override
        public FileSystemProvider provider() { return provider; }

        @Override
        public void close() { closes++; }

        @Override
        public boolean isOpen() { return true; }

        @Override
        public boolean isReadOnly() { return true; }

        @Override
        public String getSeparator() { return "/"; }

        @Override
        public Iterable<Path> getRootDirectories() { throw new UnsupportedOperationException(); }

        @Override
        public Iterable<FileStore> getFileStores() { throw new UnsupportedOperationException(); }

        @Override
        public @Unmodifiable Set<String> supportedFileAttributeViews() { return Set.of(); }

        @Override
        public Path getPath(String first, String... more) { throw new UnsupportedOperationException(); }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) { throw new UnsupportedOperationException(); }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() { throw new UnsupportedOperationException(); }

        @Override
        public WatchService newWatchService() { throw new UnsupportedOperationException(); }
    }
}

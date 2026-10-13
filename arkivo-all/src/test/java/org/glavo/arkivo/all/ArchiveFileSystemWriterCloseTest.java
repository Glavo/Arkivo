// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that closing an archive file system retries its unfinished forward-writer cleanup.
@NotNullByDefault
final class ArchiveFileSystemWriterCloseTest {
    /// Repeated close failures must not discard the writer or repeat archive finalization.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void retriesWriterCleanupWithoutWritingAgain(String format) throws IOException {
        RetryOutput output = new RetryOutput();
        OutputFileSystem source = new OutputFileSystem(new OutputProvider(output));
        Path delegate = Path.of("close-retry." + format).toAbsolutePath();
        Path path = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getFileSystem")) {
                        return source;
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        ArkivoFileSystem archive = ArkivoFormats.createFileSystem(format, path);
        Files.writeString(archive.getPath("entry"), "contents");
        assertThrows(IOException.class, archive::close);
        assertFalse(archive.isOpen());
        assertEquals(1, output.closeCalls);
        byte[] finished = output.toByteArray();
        assertThrows(IOException.class, archive::close);
        assertEquals(2, output.closeCalls);
        assertArrayEquals(finished, output.toByteArray());
        archive.close();
        assertEquals(3, output.closeCalls);
        assertTrue(output.closed);
        assertArrayEquals(finished, output.toByteArray());
        archive.close();
        assertEquals(3, output.closeCalls);
    }

    /// Retains written archive bytes and rejects its first two cleanup attempts.
    @NotNullByDefault
    private static final class RetryOutput extends ByteArrayOutputStream {
        /// Number of attempted closes.
        private int closeCalls;
        /// Whether cleanup succeeded.
        private boolean closed;

        @Override
        public void close() throws IOException {
            if (++closeCalls <= 2) {
                throw new IOException("Output cleanup failed");
            }
            closed = true;
        }
    }

    /// Supplies only the output operation used by forward archive creation.
    @NotNullByDefault
    private static final class OutputProvider extends FileSystemProvider {
        /// The single archive destination.
        private final OutputStream output;

        /// Creates a provider that routes archive output to the supplied stream.
        private OutputProvider(OutputStream output) {
            this.output = output;
        }

        @Override
        public OutputStream newOutputStream(Path path, OpenOption... options) {
            return output;
        }

        @Override
        public String getScheme() {
            return "close-retry";
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> environment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path getPath(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path path, DirectoryStream.Filter<? super Path> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createDirectory(Path path, FileAttribute<?>... attributes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSameFile(Path first, Path second) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHidden(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileStore getFileStore(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type,
                                                                            LinkOption... options) {
            return null;
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                               LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes,
                                                  LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value,
                                 LinkOption... options) {
            throw new UnsupportedOperationException();
        }
    }

    /// Associates the injected provider with the caller-supplied archive path.
    @NotNullByDefault
    private static final class OutputFileSystem extends FileSystem {
        /// The provider of the test archive output.
        private final FileSystemProvider provider;

        /// Creates a file system exposing the supplied output provider.
        private OutputFileSystem(FileSystemProvider provider) {
            this.provider = provider;
        }

        @Override
        public FileSystemProvider provider() {
            return provider;
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public String getSeparator() {
            return "/";
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Unmodifiable Set<String> supportedFileAttributeViews() {
            return Set.of();
        }

        @Override
        public Path getPath(String first, String... more) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            throw new UnsupportedOperationException();
        }

        @Override
        public WatchService newWatchService() {
            throw new UnsupportedOperationException();
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/// Provides JDK file system provider entry points for ZIP archives.
@NotNullByDefault
public final class ZipArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the ZIP Arkivo file system provider.
    public static final String SCHEME = "arkivo+zip";

    /// The shared provider instance used by Arkivo convenience factories.
    private static final ZipArkivoFileSystemProvider INSTANCE = new ZipArkivoFileSystemProvider();

    /// Creates a ZIP file system provider.
    public ZipArkivoFileSystemProvider() {
    }

    /// Returns the shared ZIP file system provider instance.
    public static ZipArkivoFileSystemProvider instance() {
        return INSTANCE;
    }

    /// Returns the URI scheme handled by this provider.
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /// Opens a ZIP archive file system from a provider URI.
    @Override
    public ArkivoFileSystem newFileSystem(URI uri, Map<String, ?> environment) throws IOException {
        requireSupportedScheme(uri);
        ZipArkivoFileSystemConfig.fromEnvironment(environment);
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Opens a ZIP archive file system from an archive path.
    @Override
    public ArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        ZipArkivoFileSystemConfig.fromEnvironment(environment);
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Returns an open ZIP archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        requireSupportedScheme(uri);
        throw new FileSystemNotFoundException(uri.toString());
    }

    /// Returns a path inside an open ZIP archive file system.
    @Override
    public Path getPath(URI uri) {
        requireSupportedScheme(uri);
        throw new FileSystemNotFoundException(uri.toString());
    }

    /// Opens a byte channel for a path inside a ZIP archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Opens a directory stream for a path inside a ZIP archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Creates a directory inside a ZIP archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Deletes a path inside a ZIP archive file system.
    @Override
    public void delete(Path path) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Copies a path inside or across ZIP archive file systems.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Moves a path inside or across ZIP archive file systems.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Returns whether two ZIP archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Returns whether a ZIP archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Returns the file store for a ZIP archive path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Checks access to a ZIP archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Returns a file attribute view for a ZIP archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Reads file attributes for a ZIP archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Reads named file attributes for a ZIP archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Sets a named file attribute for a ZIP archive path.
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Requires a provider URI to use the ZIP Arkivo scheme.
    private static void requireSupportedScheme(URI uri) {
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }
}

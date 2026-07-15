// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Provides JDK file system provider entry points for RAR archives.
@NotNullByDefault
public final class RarArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the RAR Arkivo file system provider.
    public static final String SCHEME = "arkivo+rar";

    /// The shared provider instance used by Arkivo convenience factories.
    private static final RarArkivoFileSystemProvider INSTANCE = new RarArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ArkivoFileSystemProviderSupport.Registry<RarArkivoFileSystem> fileSystems =
            new ArkivoFileSystemProviderSupport.Registry<>();

    /// Creates a RAR file system provider.
    public RarArkivoFileSystemProvider() {
    }

    /// Returns the shared RAR file system provider instance.
    public static RarArkivoFileSystemProvider instance() {
        return INSTANCE;
    }

    /// Returns the URI scheme handled by this provider.
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /// Opens a RAR archive file system from a provider URI.
    @Override
    public ArkivoFileSystem newFileSystem(URI uri, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, false);
        return fileSystems.open(parsedUri.archiveUri(), closeAction -> RarArkivoFileSystemImpl.open(
                this,
                parsedUri.archivePath(),
                parsedUri.archiveUri(),
                environment,
                closeAction
        ));
    }

    /// Opens a RAR archive file system from an archive path.
    @Override
    public RarArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.requirePathFormat(path, RarArkivoFormat.instance());
        return openPath(path, environment);
    }

    /// Opens a RAR archive path whose format has already been selected explicitly.
    RarArkivoFileSystem openPath(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return RarArkivoFileSystemImpl.open(this, path, path.toUri().normalize(), environment, () -> {
        });
    }

    /// Returns an open RAR archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystems.require(parseUri(uri, false).archiveUri());
    }

    /// Returns a path inside an open RAR archive file system.
    @Override
    public Path getPath(URI uri) {
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, true);
        return fileSystems.require(parsedUri.archiveUri()).getPath(
                Objects.requireNonNull(parsedUri.entryPath(), "entryPath")
        );
    }

    /// Opens a byte channel for a path inside a RAR archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        return readFileSystem(path).newByteChannel(
                ArkivoFileSystemProviderSupport.resolveReadChannelPath(path, options),
                options,
                attributes
        );
    }

    /// Opens an input stream for a path inside a RAR archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newInputStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                options
        );
    }

    /// RAR file systems are read-only.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Opens a directory stream for a path inside a RAR archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        return readFileSystem(directory).newDirectoryStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(directory),
                filter
        );
    }

    /// RAR file systems are read-only.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) {
        throw new ReadOnlyFileSystemException();
    }

    /// RAR file systems are read-only.
    @Override
    public void delete(Path path) {
        throw new ReadOnlyFileSystemException();
    }

    /// Copies a path inside a RAR archive file system to another file system.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        readFileSystem(source);
        ArkivoFileSystemProviderSupport.copy(source, target, options);
    }

    /// RAR file systems are read-only.
    @Override
    public void move(Path source, Path target, CopyOption... options) {
        readFileSystem(source);
        throw new ReadOnlyFileSystemException();
    }

    /// Returns whether two RAR archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        readFileSystem(path);
        return ArkivoFileSystemProviderSupport.isSameFile(path, other);
    }

    /// Returns whether a RAR archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        readFileSystem(path).checkAccess(path);
        return false;
    }

    /// Returns the file store for a RAR archive path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return readFileSystem(path).fileStore(path);
    }

    /// Checks access to a RAR archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        readFileSystem(path).checkAccess(path, modes);
    }

    /// Returns a file attribute view for a RAR archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        return readFileSystem(path).getFileAttributeView(path, type, options);
    }

    /// Reads file attributes for a RAR archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                type,
                options
        );
    }

    /// Reads named file attributes for a RAR archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                attributes,
                options
        );
    }

    /// Reads a symbolic link target from a RAR archive path.
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return readFileSystem(link).readSymbolicLink(link);
    }

    /// RAR file systems are read-only.
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Returns the RAR file system implementation that owns a path.
    private static RarArkivoFileSystemImpl readFileSystem(Path path) {
        if (path.getFileSystem() instanceof RarArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Parses a RAR provider URI through the shared archive URI grammar.
    private static ArkivoFileSystemProviderSupport.ParsedUri parseUri(URI uri, boolean requireEntryPath) {
        return ArkivoFileSystemProviderSupport.parseUri(uri, SCHEME, "RAR", requireEntryPath);
    }
}

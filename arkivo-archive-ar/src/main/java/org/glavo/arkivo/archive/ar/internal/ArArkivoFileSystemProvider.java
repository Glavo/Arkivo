// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.ar.ArArkivoFileSystem;
import org.glavo.arkivo.archive.ar.ArArkivoFormat;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemImpl;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Provides JDK file system provider entry points for AR archives.
@NotNullByDefault
public final class ArArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the AR Arkivo file system provider.
    public static final String SCHEME = ArArkivoFormat.instance().uriScheme();

    /// The shared provider instance used by Arkivo convenience factories.
    private static final ArArkivoFileSystemProvider INSTANCE = new ArArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ArkivoFileSystemProviderSupport.Registry<ArArkivoFileSystem> fileSystems =
            new ArkivoFileSystemProviderSupport.Registry<>();

    /// Creates an AR file system provider.
    public ArArkivoFileSystemProvider() {
    }

    /// Returns the shared AR file system provider instance.
    public static ArArkivoFileSystemProvider instance() {
        return INSTANCE;
    }

    /// Returns the URI scheme handled by this provider.
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /// Opens an AR archive file system from a provider URI.
    @Override
    public ArkivoFileSystem newFileSystem(URI uri, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, false);
        return fileSystems.open(parsedUri.archiveUri(), closeAction -> ArArkivoFileSystemImpl.open(
                this,
                parsedUri.archivePath(),
                parsedUri.archiveUri(),
                ArchiveOptions.fromEnvironment(environment),
                closeAction
        ));
    }

    /// Opens an AR archive file system from an archive path.
    @Override
    public ArArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.requirePathFormat(path, ArArkivoFormat.instance());
        return openPath(path, ArchiveOptions.fromEnvironment(environment));
    }

    /// Opens an AR archive path whose format has already been selected explicitly.
    public ArArkivoFileSystem openPath(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ArArkivoFileSystemImpl.open(this, path, path.toUri().normalize(), options, () -> {
        });
    }

    /// Returns an open AR archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystems.require(parseUri(uri, false).archiveUri());
    }

    /// Returns a path inside an open AR archive file system.
    @Override
    public Path getPath(URI uri) {
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, true);
        return fileSystems.require(parsedUri.archiveUri()).getPath(
                Objects.requireNonNull(parsedUri.entryPath(), "entryPath")
        );
    }

    /// Opens a byte channel for a path inside an AR archive file system.
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

    /// Opens an input stream for a path inside an AR archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newInputStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                options
        );
    }

    /// Opens an output stream for a path inside a writable AR archive file system.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newOutputStream(path, options);
    }

    /// Opens a directory stream for a path inside an AR archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        return readFileSystem(directory).newDirectoryStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(directory),
                filter
        );
    }

    /// Creates a directory entry inside a writable AR archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        readFileSystem(directory).createDirectory(directory, attributes);
    }

    /// Creates a symbolic link entry inside a writable AR archive file system.
    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        readFileSystem(link).createSymbolicLink(link, target, attributes);
    }

    /// Deletes a member from an update-mode AR archive file system.
    @Override
    public void delete(Path path) throws IOException {
        readFileSystem(path).delete(path);
    }

    /// Copies a path inside an AR archive file system to another file system.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        readFileSystem(source);
        ArkivoFileSystemProviderSupport.copy(source, target, options);
    }

    /// Moves a member inside an update-mode AR archive file system.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        ArArkivoFileSystemImpl fileSystem = readFileSystem(source);
        ArkivoFileSystemProviderSupport.requireSameFileSystemMove(source, target);
        fileSystem.move(source, target, options);
    }

    /// Returns whether two AR archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        readFileSystem(path);
        return ArkivoFileSystemProviderSupport.isSameFile(path, other);
    }

    /// Returns whether an AR archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        readFileSystem(path).checkAccess(path);
        return false;
    }

    /// Returns the file store for an AR archive path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return readFileSystem(path).fileStore(path);
    }

    /// Checks access to an AR archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        readFileSystem(path).checkAccess(path, modes);
    }

    /// Returns a file attribute view for an AR archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        return readFileSystem(path).getFileAttributeView(path, type, options);
    }

    /// Reads file attributes for an AR archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                type,
                options
        );
    }

    /// Reads named file attributes for an AR archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                attributes,
                options
        );
    }

    /// Reads a symbolic link target from an AR archive path.
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return readFileSystem(link).readSymbolicLink(link);
    }

    /// Sets a supported member attribute in update mode.
    @Override
    public void setAttribute(Path path, String attribute, @Nullable Object value, LinkOption... options)
            throws IOException {
        readFileSystem(path).setAttribute(path, attribute, value, options);
    }

    /// Returns the AR file system implementation that owns a path.
    private static ArArkivoFileSystemImpl readFileSystem(Path path) {
        if (path.getFileSystem() instanceof ArArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Parses an AR provider URI through the shared archive URI grammar.
    private static ArkivoFileSystemProviderSupport.ParsedUri parseUri(URI uri, boolean requireEntryPath) {
        return ArkivoFileSystemProviderSupport.parseUri(uri, SCHEME, "AR", requireEntryPath);
    }
}

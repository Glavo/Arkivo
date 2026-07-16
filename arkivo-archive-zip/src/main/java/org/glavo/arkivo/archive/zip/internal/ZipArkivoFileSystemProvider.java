// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoFormat;
import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
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

/// Provides JDK file system provider entry points for ZIP archives.
@NotNullByDefault
public final class ZipArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the ZIP Arkivo file system provider.
    public static final String SCHEME = ZipArkivoFormat.instance().uriScheme();

    /// The shared provider instance used by Arkivo convenience factories.
    private static final ZipArkivoFileSystemProvider INSTANCE = new ZipArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ArkivoFileSystemProviderSupport.Registry<ZipArkivoFileSystem> fileSystems =
            new ArkivoFileSystemProviderSupport.Registry<>();

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
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, false);
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(environment));
        return fileSystems.open(parsedUri.archiveUri(), closeAction -> config.archiveWritable()
                ? new ZipArkivoWritableFileSystemImpl(
                this,
                parsedUri.archivePath(),
                config,
                closeAction,
                true
        )
                : new ZipArkivoReadOnlyFileSystemImpl(
                this,
                parsedUri.archivePath(),
                null,
                config,
                closeAction
        ));
    }

    /// Opens a ZIP archive file system from an archive path.
    @Override
    public ZipArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.requirePathFormat(path, ZipArkivoFormat.instance());
        return openPath(path, ArchiveOptions.fromEnvironment(environment));
    }

    /// Opens a ZIP archive path whose format has already been selected explicitly.
    public ZipArkivoFileSystem openPath(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromOptions(options);
        return config.archiveWritable()
                ? new ZipArkivoWritableFileSystemImpl(this, path, config, null, true)
                : new ZipArkivoReadOnlyFileSystemImpl(this, path, null, config);
    }

    /// Returns an open ZIP archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystems.require(parseUri(uri, false).archiveUri());
    }

    /// Returns a path inside an open ZIP archive file system.
    @Override
    public Path getPath(URI uri) {
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, true);
        return fileSystems.require(parsedUri.archiveUri()).getPath(
                Objects.requireNonNull(parsedUri.entryPath(), "entryPath")
        );
    }

    /// Opens a byte channel for a path inside a ZIP archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadChannelPath(path, options);
        return operations(path).newByteChannel(readPath, options, attributes);
    }

    /// Opens an input stream for a path inside a ZIP archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadPath(path, options);
        return operations(path).newInputStream(readPath, options);
    }

    /// Opens an output stream for a path inside a writable ZIP archive file system.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return operations(path).newOutputStream(path, options);
    }

    /// Opens a directory stream for a path inside a ZIP archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter) throws IOException {
        Path readDirectory = ArkivoFileSystemProviderSupport.resolveReadPath(directory);
        return operations(directory).newDirectoryStream(readDirectory, filter);
    }

    /// Creates a directory inside a writable ZIP archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        operations(directory).createDirectory(directory, attributes);
    }

    /// Creates a symbolic link inside a writable ZIP archive file system.
    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        operations(link).createSymbolicLink(link, target, attributes);
    }

    /// Deletes a path inside a ZIP archive file system.
    @Override
    public void delete(Path path) throws IOException {
        operations(path).delete(path);
    }

    /// Copies a path inside or across ZIP archive file systems.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        fileSystem(source);
        ArkivoFileSystemProviderSupport.copy(source, target, options);
    }

    /// Moves a path inside or across ZIP archive file systems.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        ArkivoFileSystemProviderSupport.requireSameFileSystemMove(source, target);
        operations(source).move(source, target, options);
    }

    /// Returns whether two ZIP archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        fileSystem(path);
        return ArkivoFileSystemProviderSupport.isSameFile(path, other);
    }

    /// Returns whether a ZIP archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        fileSystem(path);
        checkAccess(path);
        return false;
    }

    /// Returns the file store for a ZIP archive path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        ZipFileSystemOperations operations = operations(path);
        operations.checkAccess(path);
        return operations.fileStore();
    }

    /// Checks access to a ZIP archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        operations(path).checkAccess(path, modes);
    }

    /// Returns a file attribute view for a ZIP archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return operations(path).getFileAttributeView(path, type, options);
    }

    /// Reads file attributes for a ZIP archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadPath(path, options);
        return operations(path).readAttributes(readPath, type);
    }

    /// Reads named file attributes for a ZIP archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadPath(path, options);
        return operations(path).readAttributes(readPath, attributes);
    }

    /// Reads a symbolic link target from a ZIP archive path.
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return operations(link).readSymbolicLink(link);
    }

    /// Sets a named file attribute for a ZIP archive path.
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        operations(path).setAttribute(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                attribute,
                value
        );
    }

    /// Returns the ZIP file system implementation that owns a path.
    private static ZipArkivoFileSystem fileSystem(Path path) {
        if (path.getFileSystem() instanceof ZipArkivoFileSystem fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Returns the provider-facing ZIP file system operations that own a path.
    private static ZipFileSystemOperations operations(Path path) {
        if (path.getFileSystem() instanceof ZipFileSystemOperations operations) {
            return operations;
        }
        throw new ProviderMismatchException();
    }

    /// Parses a ZIP provider URI through the shared archive URI grammar.
    private static ArkivoFileSystemProviderSupport.ParsedUri parseUri(URI uri, boolean requireEntryPath) {
        return ArkivoFileSystemProviderSupport.parseUri(uri, SCHEME, "ZIP", requireEntryPath);
    }
}

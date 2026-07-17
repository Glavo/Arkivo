// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFormat;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemImpl;
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

/// Provides JDK file system provider entry points for 7z archives.
@NotNullByDefault
public final class SevenZipArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the 7z Arkivo file system provider.
    public static final String SCHEME = SevenZipArkivoFormat.instance().uriScheme();

    /// The shared provider instance used by Arkivo convenience factories.
    private static final SevenZipArkivoFileSystemProvider INSTANCE = new SevenZipArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ArkivoFileSystemProviderSupport.Registry<SevenZipArkivoFileSystem> fileSystems =
            new ArkivoFileSystemProviderSupport.Registry<>();

    /// Creates a 7z file system provider.
    public SevenZipArkivoFileSystemProvider() {
    }

    /// Returns the shared 7z file system provider instance.
    public static SevenZipArkivoFileSystemProvider instance() {
        return INSTANCE;
    }

    /// Returns the URI scheme handled by this provider.
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /// Opens a 7z archive file system from a provider URI.
    @Override
    public ArkivoFileSystem newFileSystem(URI uri, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, false);
        SevenZipArkivoFileSystemConfig config =
                SevenZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(environment));
        return fileSystems.open(parsedUri.archiveUri(), closeAction -> new SevenZipArkivoFileSystemImpl(
                this,
                parsedUri.archivePath(),
                null,
                config,
                closeAction
        ));
    }

    /// Opens a 7z archive file system from an archive path.
    @Override
    public SevenZipArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.requirePathFormat(path, SevenZipArkivoFormat.instance());
        return openPath(path, ArchiveOptions.fromEnvironment(environment));
    }

    /// Opens a 7z archive path whose format has already been selected explicitly.
    public SevenZipArkivoFileSystem openPath(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromOptions(options);
        return openPath(path, config);
    }

    /// Opens a 7z path from an already validated strongly typed configuration.
    public SevenZipArkivoFileSystem openPath(Path path, SevenZipArkivoFileSystemConfig config) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(config, "config");
        return new SevenZipArkivoFileSystemImpl(this, path, null, config);
    }

    /// Returns an open 7z archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystems.require(parseUri(uri, false).archiveUri());
    }

    /// Returns a path inside an open 7z archive file system.
    @Override
    public Path getPath(URI uri) {
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, true);
        return fileSystems.require(parsedUri.archiveUri()).getPath(
                Objects.requireNonNull(parsedUri.entryPath(), "entryPath")
        );
    }

    /// Opens a byte channel for a path inside a 7z archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        return sevenZipFileSystem(path).newByteChannel(
                ArkivoFileSystemProviderSupport.resolveReadChannelPath(path, options),
                options,
                attributes
        );
    }

    /// Opens an input stream for a path inside a 7z archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return sevenZipFileSystem(path).newInputStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                options
        );
    }

    /// Opens an output stream for a path inside a writable 7z archive file system.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return sevenZipFileSystem(path).newOutputStream(path, options);
    }

    /// Opens a directory stream for a path inside a 7z archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        return sevenZipFileSystem(directory).newDirectoryStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(directory),
                filter
        );
    }

    /// Creates a directory inside a writable 7z archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        sevenZipFileSystem(directory).createDirectory(directory, attributes);
    }

    /// Creates a symbolic link inside a writable 7z archive file system.
    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        sevenZipFileSystem(link).createSymbolicLink(link, target, attributes);
    }

    /// Deletes a path inside an update-mode 7z archive file system.
    @Override
    public void delete(Path path) throws IOException {
        sevenZipFileSystem(path).delete(path);
    }

    /// Copies a path inside or across 7z archive file systems.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        sevenZipFileSystem(source);
        ArkivoFileSystemProviderSupport.copy(source, target, options);
    }

    /// Moves a path inside an update-mode 7z archive file system.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        SevenZipArkivoFileSystemImpl fileSystem = sevenZipFileSystem(source);
        ArkivoFileSystemProviderSupport.requireSameFileSystemMove(source, target);
        fileSystem.move(source, target, options);
    }

    /// Returns whether two 7z archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        sevenZipFileSystem(path);
        return ArkivoFileSystemProviderSupport.isSameFile(path, other);
    }

    /// Returns whether a 7z archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        sevenZipFileSystem(path).checkAccess(path);
        return false;
    }

    /// Returns the file store for a 7z archive path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        SevenZipArkivoFileSystemImpl fileSystem = sevenZipFileSystem(path);
        fileSystem.checkAccess(path);
        return fileSystem.fileStore();
    }

    /// Checks access to a 7z archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        sevenZipFileSystem(path).checkAccess(path, modes);
    }

    /// Returns a file attribute view for a 7z archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        return sevenZipFileSystem(path).getFileAttributeView(path, type, options);
    }

    /// Reads file attributes for a 7z archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(
            Path path,
            Class<A> type,
            LinkOption... options
    ) throws IOException {
        return sevenZipFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                type
        );
    }

    /// Reads named file attributes for a 7z archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return sevenZipFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                attributes
        );
    }

    /// Reads a symbolic link target from a 7z archive path.
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return sevenZipFileSystem(link).readSymbolicLink(link);
    }

    /// Sets a named file attribute for an update-mode 7z archive path.
    @Override
    public void setAttribute(Path path, String attribute, @Nullable Object value, LinkOption... options)
            throws IOException {
        sevenZipFileSystem(path).setAttribute(path, attribute, value, options);
    }

    /// Returns the 7z file system implementation that owns a path.
    private static SevenZipArkivoFileSystemImpl sevenZipFileSystem(Path path) {
        if (path.getFileSystem() instanceof SevenZipArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Parses a 7z provider URI through the shared archive URI grammar.
    private static ArkivoFileSystemProviderSupport.ParsedUri parseUri(URI uri, boolean requireEntryPath) {
        return ArkivoFileSystemProviderSupport.parseUri(uri, SCHEME, "7z", requireEntryPath);
    }
}

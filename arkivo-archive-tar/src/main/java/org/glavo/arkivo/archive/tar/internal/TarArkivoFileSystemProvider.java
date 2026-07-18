// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoFormat;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemImpl;
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

/// Provides JDK file system provider entry points for TAR archives.
@NotNullByDefault
public final class TarArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the TAR Arkivo file system provider.
    public static final String SCHEME = TarArkivoFormat.instance().uriScheme();

    /// The shared provider instance used by Arkivo convenience factories.
    private static final TarArkivoFileSystemProvider INSTANCE = new TarArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ArkivoFileSystemProviderSupport.Registry<TarArkivoFileSystem> fileSystems =
            new ArkivoFileSystemProviderSupport.Registry<>();

    /// Creates a TAR file system provider.
    public TarArkivoFileSystemProvider() {
    }

    /// Returns the shared TAR file system provider instance.
    ///
    /// @return the process-wide provider used by TAR convenience factories
    public static TarArkivoFileSystemProvider instance() {
        return INSTANCE;
    }

    /// Returns the URI scheme handled by this provider.
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /// Opens a TAR archive file system from a provider URI.
    @Override
    public ArkivoFileSystem newFileSystem(URI uri, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, false);
        return fileSystems.open(parsedUri.archiveUri(), closeAction -> TarArkivoFileSystemImpl.open(
                this,
                parsedUri.archivePath(),
                parsedUri.archiveUri(),
                ArchiveOptions.fromEnvironment(environment),
                closeAction
        ));
    }

    /// Opens a TAR archive file system from an archive path.
    @Override
    public TarArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemProviderSupport.requirePathFormat(path, TarArkivoFormat.instance());
        return openPath(path, ArchiveOptions.fromEnvironment(environment));
    }

    /// Opens a TAR archive path whose format has already been selected explicitly.
    ///
    /// @param path the archive path to read, create, or update according to `options`
    /// @param options the generic access, compression, staging, publication, limits, and thread-safety configuration
    /// @return a new indexed TAR file system whose close operation releases resources and completes writable sessions
    /// @throws IOException if the source, output, codec, or staging resources cannot be initialized
    public TarArkivoFileSystem openPath(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return TarArkivoFileSystemImpl.open(this, path, path.toUri().normalize(), options, () -> {
        });
    }

    /// Returns an open TAR archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystems.require(parseUri(uri, false).archiveUri());
    }

    /// Returns a path inside an open TAR archive file system.
    @Override
    public Path getPath(URI uri) {
        ArkivoFileSystemProviderSupport.ParsedUri parsedUri = parseUri(uri, true);
        return fileSystems.require(parsedUri.archiveUri()).getPath(
                Objects.requireNonNull(parsedUri.entryPath(), "entryPath")
        );
    }

    /// Opens a byte channel for a path inside a TAR archive file system.
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

    /// Opens an input stream for a path inside a TAR archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newInputStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                options
        );
    }

    /// Opens an output stream for a path inside a writable TAR archive file system.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newOutputStream(path, options);
    }

    /// Opens a directory stream for a path inside a TAR archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        return readFileSystem(directory).newDirectoryStream(
                ArkivoFileSystemProviderSupport.resolveReadPath(directory),
                filter
        );
    }

    /// Creates a directory entry inside a writable TAR archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        readFileSystem(directory).createDirectory(directory, attributes);
    }

    /// Creates a symbolic link entry inside a writable TAR archive file system.
    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        readFileSystem(link).createSymbolicLink(link, target, attributes);
    }

    /// Creates a hard link entry inside a writable TAR archive file system.
    @Override
    public void createLink(Path link, Path existing) throws IOException {
        readFileSystem(link).createLink(link, existing);
    }

    /// Deletes an entry from an update-mode TAR file system.
    @Override
    public void delete(Path path) throws IOException {
        readFileSystem(path).delete(path);
    }

    /// Copies a path inside a TAR archive file system to another file system.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        readFileSystem(source);
        ArkivoFileSystemProviderSupport.copy(source, target, options);
    }

    /// Moves an entry inside an update-mode TAR file system.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        TarArkivoFileSystemImpl fileSystem = readFileSystem(source);
        ArkivoFileSystemProviderSupport.requireSameFileSystemMove(source, target);
        fileSystem.move(source, target, options);
    }

    /// Returns whether two TAR archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        readFileSystem(path);
        return ArkivoFileSystemProviderSupport.isSameFile(path, other);
    }

    /// Returns whether a TAR archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        readFileSystem(path).checkAccess(path);
        return false;
    }

    /// Returns the file store for a TAR archive path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return readFileSystem(path).fileStore(path);
    }

    /// Checks access to a TAR archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        readFileSystem(path).checkAccess(path, modes);
    }

    /// Returns a file attribute view for a TAR archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        return readFileSystem(path).getFileAttributeView(path, type, options);
    }

    /// Reads file attributes for a TAR archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                type,
                options
        );
    }

    /// Reads named file attributes for a TAR archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return readFileSystem(path).readAttributes(
                ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                attributes,
                options
        );
    }

    /// Reads a symbolic link target from a TAR archive path.
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return readFileSystem(link).readSymbolicLink(link);
    }

    /// Sets an entry attribute in an update-mode TAR file system.
    @Override
    public void setAttribute(Path path, String attribute, @Nullable Object value, LinkOption... options) throws IOException {
        readFileSystem(path).setAttribute(path, attribute, value, options);
    }

    /// Returns the TAR file system implementation that owns a path.
    private static TarArkivoFileSystemImpl readFileSystem(Path path) {
        if (path.getFileSystem() instanceof TarArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Parses a TAR provider URI through the shared archive URI grammar.
    private static ArkivoFileSystemProviderSupport.ParsedUri parseUri(URI uri, boolean requireEntryPath) {
        return ArkivoFileSystemProviderSupport.parseUri(uri, SCHEME, "TAR", requireEntryPath);
    }
}

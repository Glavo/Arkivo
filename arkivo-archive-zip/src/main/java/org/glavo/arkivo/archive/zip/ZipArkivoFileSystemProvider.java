// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.glavo.arkivo.archive.zip.internal.StreamingZipArkivoFileSystemImpl;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemImpl;
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

/// Provides JDK file system provider entry points for ZIP archives.
@NotNullByDefault
public final class ZipArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the ZIP Arkivo file system provider.
    public static final String SCHEME = "arkivo+zip";

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
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return fileSystems.open(parsedUri.archiveUri(), closeAction -> config.archiveWritable()
                ? new StreamingZipArkivoFileSystemImpl(
                        this,
                        parsedUri.archivePath(),
                        config,
                        closeAction,
                        true
                )
                : new ZipArkivoFileSystemImpl(
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
        return openPath(path, environment);
    }

    /// Opens a ZIP archive path whose format has already been selected explicitly.
    ZipArkivoFileSystem openPath(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return config.archiveWritable()
                ? new StreamingZipArkivoFileSystemImpl(this, path, config, null, true)
                : new ZipArkivoFileSystemImpl(this, path, null, config);
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
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadChannelPath(path, options);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.newByteChannel(readPath, options, attributes);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.newByteChannel(readPath, options, attributes);
        }
        throw new ProviderMismatchException();
    }

    /// Opens an input stream for a path inside a ZIP archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadPath(path, options);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.newInputStream(readPath, options);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.newInputStream(readPath, options);
        }
        throw new ProviderMismatchException();
    }

    /// Opens an output stream for a path inside a writable streaming ZIP archive file system.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.newOutputStream(path, options);
        }
        throw new ReadOnlyFileSystemException();
    }

    /// Opens a directory stream for a path inside a ZIP archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(directory);
        Path readDirectory = ArkivoFileSystemProviderSupport.resolveReadPath(directory);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.newDirectoryStream(readDirectory, filter);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.newDirectoryStream(readDirectory, filter);
        }
        throw new ProviderMismatchException();
    }

    /// Creates a directory inside a writable streaming ZIP archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(directory);
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            writeFileSystem.createDirectory(directory, attributes);
            return;
        }
        throw new ReadOnlyFileSystemException();
    }

    /// Creates a symbolic link inside a writable streaming ZIP archive file system.
    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(link);
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            writeFileSystem.createSymbolicLink(link, target, attributes);
            return;
        }
        throw new ReadOnlyFileSystemException();
    }

    /// Deletes a path inside a ZIP archive file system.
    @Override
    public void delete(Path path) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            writeFileSystem.delete(path);
            return;
        }
        throw new ReadOnlyFileSystemException();
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
        ZipArkivoFileSystem fileSystem = fileSystem(source);
        ArkivoFileSystemProviderSupport.requireSameFileSystemMove(source, target);
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            writeFileSystem.move(source, target, options);
            return;
        }
        throw new ReadOnlyFileSystemException();
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
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            readFileSystem.checkAccess(path);
            return readFileSystem.fileStore();
        }
        return fileSystem.getFileStores().iterator().next();
    }

    /// Checks access to a ZIP archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            readFileSystem.checkAccess(path, modes);
            return;
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            writeFileSystem.checkAccess(path, modes);
            return;
        }
        throw new ProviderMismatchException();
    }

    /// Returns a file attribute view for a ZIP archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.getFileAttributeView(path, type, options);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.getFileAttributeView(path, type, options);
        }
        return null;
    }

    /// Reads file attributes for a ZIP archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadPath(path, options);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.readAttributes(readPath, type);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.readAttributes(readPath, type);
        }
        throw new ProviderMismatchException();
    }

    /// Reads named file attributes for a ZIP archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        Path readPath = ArkivoFileSystemProviderSupport.resolveReadPath(path, options);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.readAttributes(readPath, attributes);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.readAttributes(readPath, attributes);
        }
        throw new ProviderMismatchException();
    }

    /// Reads a symbolic link target from a ZIP archive path.
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(link);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.readSymbolicLink(link);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.readSymbolicLink(link);
        }
        throw new ProviderMismatchException();
    }

    /// Sets a named file attribute for a ZIP archive path.
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            writeFileSystem.setAttribute(
                    ArkivoFileSystemProviderSupport.resolveReadPath(path, options),
                    attribute,
                    value
            );
            return;
        }
        throw new ReadOnlyFileSystemException();
    }

    /// Returns the ZIP file system implementation that owns a path.
    private static ZipArkivoFileSystem fileSystem(Path path) {
        if (path.getFileSystem() instanceof ZipArkivoFileSystem fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Returns the read-only ZIP file system implementation that owns a path.
    private static ZipArkivoFileSystemImpl readFileSystem(Path path) {
        if (path.getFileSystem() instanceof ZipArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ReadOnlyFileSystemException();
    }

    /// Parses a ZIP provider URI through the shared archive URI grammar.
    private static ArkivoFileSystemProviderSupport.ParsedUri parseUri(URI uri, boolean requireEntryPath) {
        return ArkivoFileSystemProviderSupport.parseUri(uri, SCHEME, "ZIP", requireEntryPath);
    }
}

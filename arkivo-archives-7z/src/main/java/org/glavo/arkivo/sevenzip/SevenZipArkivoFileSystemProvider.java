// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoFileSystemConfig;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoFileSystemImpl;
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
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Provides JDK file system provider entry points for 7z archives.
@NotNullByDefault
public final class SevenZipArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the 7z Arkivo file system provider.
    public static final String SCHEME = "arkivo+7z";

    /// The shared provider instance used by Arkivo convenience factories.
    private static final SevenZipArkivoFileSystemProvider INSTANCE = new SevenZipArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ConcurrentHashMap<URI, SevenZipArkivoFileSystem> fileSystems = new ConcurrentHashMap<>();

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
        ParsedSevenZipUri parsedUri = ParsedSevenZipUri.parse(uri, false);
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(environment);

        while (true) {
            SevenZipArkivoFileSystem existing = fileSystems.get(parsedUri.archiveUri);
            if (existing != null) {
                if (existing.isOpen()) {
                    throw new FileSystemAlreadyExistsException(parsedUri.archiveUri.toString());
                }
                fileSystems.remove(parsedUri.archiveUri, existing);
                continue;
            }

            SevenZipArkivoFileSystem[] holder = new SevenZipArkivoFileSystem[1];
            Runnable closeAction = () -> {
                SevenZipArkivoFileSystem fileSystem = holder[0];
                if (fileSystem != null) {
                    fileSystems.remove(parsedUri.archiveUri, fileSystem);
                }
            };
            SevenZipArkivoFileSystem fileSystem =
                    new SevenZipArkivoFileSystemImpl(this, parsedUri.archivePath, null, config, closeAction);
            holder[0] = fileSystem;

            existing = fileSystems.putIfAbsent(parsedUri.archiveUri, fileSystem);
            if (existing == null) {
                return fileSystem;
            }

            fileSystem.close();
        }
    }

    /// Opens a 7z archive file system from an archive path.
    @Override
    public SevenZipArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(environment);
        return new SevenZipArkivoFileSystemImpl(this, path, null, config);
    }

    /// Returns an open 7z archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return requireFileSystem(ParsedSevenZipUri.parse(uri, false));
    }

    /// Returns a path inside an open 7z archive file system.
    @Override
    public Path getPath(URI uri) {
        ParsedSevenZipUri parsedUri = ParsedSevenZipUri.parse(uri, true);
        return requireFileSystem(parsedUri).getPath(parsedUri.entryPath);
    }

    /// Opens a byte channel for a path inside a 7z archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        return sevenZipFileSystem(path).newByteChannel(path, options, attributes);
    }

    /// Opens an input stream for a path inside a 7z archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return sevenZipFileSystem(path).newInputStream(path, options);
    }

    /// Opens an output stream for a path inside a 7z archive file system.
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Opens a directory stream for a path inside a 7z archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        return sevenZipFileSystem(directory).newDirectoryStream(directory, filter);
    }

    /// Creates a directory inside a 7z archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) {
        throw new ReadOnlyFileSystemException();
    }

    /// Deletes a path inside a 7z archive file system.
    @Override
    public void delete(Path path) {
        throw new ReadOnlyFileSystemException();
    }

    /// Copies a path inside or across 7z archive file systems.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        boolean replaceExisting = false;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else {
                throw new UnsupportedOperationException("Unsupported 7z copy option: " + option);
            }
        }
        if (Files.exists(target) && !replaceExisting) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        try (InputStream input = newInputStream(source)) {
            if (replaceExisting) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(input, target);
            }
        }
    }

    /// Moves a path inside or across 7z archive file systems.
    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Returns whether two 7z archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        sevenZipFileSystem(path).checkAccess(path);
        return path.equals(other);
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
        return sevenZipFileSystem(path).getFileAttributeView(path, type);
    }

    /// Reads file attributes for a 7z archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(
            Path path,
            Class<A> type,
            LinkOption... options
    ) throws IOException {
        return sevenZipFileSystem(path).readAttributes(path, type);
    }

    /// Reads named file attributes for a 7z archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return sevenZipFileSystem(path).readAttributes(path, attributes);
    }

    /// Sets a named file attribute for a 7z archive path.
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Returns the 7z file system implementation that owns a path.
    private static SevenZipArkivoFileSystemImpl sevenZipFileSystem(Path path) {
        if (path.getFileSystem() instanceof SevenZipArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Returns the open file system registered for a parsed URI.
    private SevenZipArkivoFileSystem requireFileSystem(ParsedSevenZipUri parsedUri) {
        SevenZipArkivoFileSystem fileSystem = fileSystems.get(parsedUri.archiveUri);
        if (fileSystem != null && fileSystem.isOpen()) {
            return fileSystem;
        }
        if (fileSystem != null) {
            fileSystems.remove(parsedUri.archiveUri, fileSystem);
        }
        throw new FileSystemNotFoundException(parsedUri.archiveUri.toString());
    }

    /// Requires a provider URI to use the 7z Arkivo scheme.
    private static void requireSupportedScheme(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }

    /// Stores parsed components from a 7z Arkivo provider URI.
    @NotNullByDefault
    private static final class ParsedSevenZipUri {
        /// The nested archive URI.
        private final URI archiveUri;

        /// The archive path resolved from the nested archive URI.
        private final Path archivePath;

        /// The decoded entry path, or `null` when the URI identifies only an archive file system.
        private final @Nullable String entryPath;

        /// Creates parsed 7z URI components.
        private ParsedSevenZipUri(URI archiveUri, Path archivePath, @Nullable String entryPath) {
            this.archiveUri = archiveUri;
            this.archivePath = archivePath;
            this.entryPath = entryPath;
        }

        /// Parses a 7z Arkivo provider URI.
        private static ParsedSevenZipUri parse(URI uri, boolean requireEntryPath) {
            requireSupportedScheme(uri);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("7z Arkivo URI must not contain query or fragment: " + uri);
            }

            String schemeSpecificPart = uri.getRawSchemeSpecificPart();
            int separator = schemeSpecificPart.indexOf("!/");
            if (separator < 0 && requireEntryPath) {
                throw new IllegalArgumentException("7z Arkivo entry URI must contain !/: " + uri);
            }

            String archivePart = separator >= 0
                    ? schemeSpecificPart.substring(0, separator)
                    : schemeSpecificPart;
            if (archivePart.isEmpty()) {
                throw new IllegalArgumentException("7z Arkivo URI must contain an archive URI: " + uri);
            }

            URI archiveUri = URI.create(archivePart).normalize();
            Path archivePath = Path.of(archiveUri);
            String entryPath = separator >= 0
                    ? decodeEntryPath(schemeSpecificPart.substring(separator + 2))
                    : null;
            return new ParsedSevenZipUri(archiveUri, archivePath, entryPath);
        }

        /// Decodes a raw entry path from a 7z Arkivo provider URI.
        private static String decodeEntryPath(String rawEntryPath) {
            if (rawEntryPath.isEmpty()) {
                return "/";
            }
            String decodedPath = URI.create("arkivo-entry:/" + rawEntryPath).getPath();
            return decodedPath != null ? decodedPath : "/";
        }
    }
}

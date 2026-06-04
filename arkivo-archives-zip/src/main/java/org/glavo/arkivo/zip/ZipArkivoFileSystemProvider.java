// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
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

/// Provides JDK file system provider entry points for ZIP archives.
@NotNullByDefault
public final class ZipArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the ZIP Arkivo file system provider.
    public static final String SCHEME = "arkivo+zip";

    /// The shared provider instance used by Arkivo convenience factories.
    private static final ZipArkivoFileSystemProvider INSTANCE = new ZipArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ConcurrentHashMap<URI, ZipArkivoFileSystemImpl> fileSystems = new ConcurrentHashMap<>();

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
        ParsedZipUri parsedUri = ParsedZipUri.parse(uri, false);
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        while (true) {
            ZipArkivoFileSystemImpl existing = fileSystems.get(parsedUri.archiveUri);
            if (existing != null) {
                if (existing.isOpen()) {
                    throw new FileSystemAlreadyExistsException(parsedUri.archiveUri.toString());
                }
                fileSystems.remove(parsedUri.archiveUri, existing);
                continue;
            }

            ZipArkivoFileSystemImpl[] holder = new ZipArkivoFileSystemImpl[1];
            Runnable closeAction = () -> {
                ZipArkivoFileSystemImpl fileSystem = holder[0];
                if (fileSystem != null) {
                    fileSystems.remove(parsedUri.archiveUri, fileSystem);
                }
            };
            ZipArkivoFileSystemImpl fileSystem =
                    new ZipArkivoFileSystemImpl(this, parsedUri.archivePath, null, config, closeAction);
            holder[0] = fileSystem;

            existing = fileSystems.putIfAbsent(parsedUri.archiveUri, fileSystem);
            if (existing == null) {
                return fileSystem;
            }

            fileSystem.close();
        }
    }

    /// Opens a ZIP archive file system from an archive path.
    @Override
    public ZipArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return new ZipArkivoFileSystemImpl(this, path, null, config);
    }

    /// Returns an open ZIP archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return requireFileSystem(ParsedZipUri.parse(uri, false));
    }

    /// Returns a path inside an open ZIP archive file system.
    @Override
    public Path getPath(URI uri) {
        ParsedZipUri parsedUri = ParsedZipUri.parse(uri, true);
        return requireFileSystem(parsedUri).getPath(parsedUri.entryPath);
    }

    /// Opens a byte channel for a path inside a ZIP archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        return fileSystem(path).newByteChannel(path, options, attributes);
    }

    /// Opens an input stream for a path inside a ZIP archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return fileSystem(path).newInputStream(path, options);
    }

    /// Opens a directory stream for a path inside a ZIP archive file system.
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return fileSystem(directory).newDirectoryStream(directory, filter);
    }

    /// Creates a directory inside a ZIP archive file system.
    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Deletes a path inside a ZIP archive file system.
    @Override
    public void delete(Path path) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Copies a path inside or across ZIP archive file systems.
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        boolean replaceExisting = false;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else {
                throw new UnsupportedOperationException("Unsupported ZIP copy option: " + option);
            }
        }

        BasicFileAttributes attributes = readAttributes(source, BasicFileAttributes.class);
        if (attributes.isDirectory()) {
            if (Files.exists(target)) {
                if (!replaceExisting) {
                    throw new FileAlreadyExistsException(target.toString());
                }
            } else {
                Files.createDirectories(target);
            }
            return;
        }

        if (Files.exists(target) && !replaceExisting) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try (java.io.InputStream input = newInputStream(source)) {
            if (replaceExisting) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(input, target);
            }
        }
    }

    /// Moves a path inside or across ZIP archive file systems.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Returns whether two ZIP archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        fileSystem(path).checkAccess(path);
        return path.equals(other);
    }

    /// Returns whether a ZIP archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        fileSystem(path).checkAccess(path);
        return false;
    }

    /// Returns the file store for a ZIP archive path.
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        fileSystem(path).checkAccess(path);
        return fileSystem(path).fileStore();
    }

    /// Checks access to a ZIP archive path.
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        fileSystem(path).checkAccess(path, modes);
    }

    /// Returns a file attribute view for a ZIP archive path.
    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return fileSystem(path).getFileAttributeView(path, type);
    }

    /// Reads file attributes for a ZIP archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        return fileSystem(path).readAttributes(path, type);
    }

    /// Reads named file attributes for a ZIP archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return fileSystem(path).readAttributes(path, attributes);
    }

    /// Sets a named file attribute for a ZIP archive path.
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Returns the ZIP file system implementation that owns a path.
    private static ZipArkivoFileSystemImpl fileSystem(Path path) {
        if (path.getFileSystem() instanceof ZipArkivoFileSystemImpl fileSystem) {
            return fileSystem;
        }
        throw new ProviderMismatchException();
    }

    /// Returns the open file system registered for a parsed URI.
    private ZipArkivoFileSystemImpl requireFileSystem(ParsedZipUri parsedUri) {
        ZipArkivoFileSystemImpl fileSystem = fileSystems.get(parsedUri.archiveUri);
        if (fileSystem != null && fileSystem.isOpen()) {
            return fileSystem;
        }
        if (fileSystem != null) {
            fileSystems.remove(parsedUri.archiveUri, fileSystem);
        }
        throw new FileSystemNotFoundException(parsedUri.archiveUri.toString());
    }

    /// Requires a provider URI to use the ZIP Arkivo scheme.
    private static void requireSupportedScheme(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }

    /// Stores parsed components from a ZIP Arkivo provider URI.
    @NotNullByDefault
    private static final class ParsedZipUri {
        /// The nested archive URI.
        private final URI archiveUri;

        /// The archive path resolved from the nested archive URI.
        private final Path archivePath;

        /// The decoded entry path, or `null` when the URI identifies only an archive file system.
        private final @Nullable String entryPath;

        /// Creates parsed ZIP URI components.
        private ParsedZipUri(URI archiveUri, Path archivePath, @Nullable String entryPath) {
            this.archiveUri = archiveUri;
            this.archivePath = archivePath;
            this.entryPath = entryPath;
        }

        /// Parses a ZIP Arkivo provider URI.
        private static ParsedZipUri parse(URI uri, boolean requireEntryPath) {
            requireSupportedScheme(uri);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("ZIP Arkivo URI must not contain query or fragment: " + uri);
            }

            String schemeSpecificPart = uri.getRawSchemeSpecificPart();
            int separator = schemeSpecificPart.indexOf("!/");
            if (separator < 0 && requireEntryPath) {
                throw new IllegalArgumentException("ZIP Arkivo entry URI must contain !/: " + uri);
            }

            String archivePart = separator >= 0
                    ? schemeSpecificPart.substring(0, separator)
                    : schemeSpecificPart;
            if (archivePart.isEmpty()) {
                throw new IllegalArgumentException("ZIP Arkivo URI must contain an archive URI: " + uri);
            }

            URI archiveUri = URI.create(archivePart).normalize();
            Path archivePath = Path.of(archiveUri);
            String entryPath = separator >= 0
                    ? decodeEntryPath(schemeSpecificPart.substring(separator + 2))
                    : null;
            return new ParsedZipUri(archiveUri, archivePath, entryPath);
        }

        /// Decodes a raw entry path from a ZIP Arkivo provider URI.
        private static String decodeEntryPath(String rawEntryPath) {
            if (rawEntryPath.isEmpty()) {
                return "/";
            }
            String decodedPath = URI.create("arkivo-entry:/" + rawEntryPath).getPath();
            return decodedPath != null ? decodedPath : "/";
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoFileSystem;
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

/// Provides JDK file system provider entry points for RAR archives.
@NotNullByDefault
public final class RarArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the RAR Arkivo file system provider.
    public static final String SCHEME = "arkivo+rar";

    /// The shared provider instance used by Arkivo convenience factories.
    private static final RarArkivoFileSystemProvider INSTANCE = new RarArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ConcurrentHashMap<URI, RarArkivoFileSystem> fileSystems = new ConcurrentHashMap<>();

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
        ParsedRarUri parsedUri = ParsedRarUri.parse(uri, false);

        while (true) {
            RarArkivoFileSystem existing = fileSystems.get(parsedUri.archiveUri);
            if (existing != null) {
                if (existing.isOpen()) {
                    throw new FileSystemAlreadyExistsException(parsedUri.archiveUri.toString());
                }
                fileSystems.remove(parsedUri.archiveUri, existing);
                continue;
            }

            RarArkivoFileSystem[] holder = new RarArkivoFileSystem[1];
            Runnable closeAction = () -> {
                RarArkivoFileSystem fileSystem = holder[0];
                if (fileSystem != null) {
                    fileSystems.remove(parsedUri.archiveUri, fileSystem);
                }
            };
            RarArkivoFileSystem fileSystem =
                    RarArkivoFileSystemImpl.open(this, parsedUri.archivePath, parsedUri.archiveUri, environment, closeAction);
            holder[0] = fileSystem;

            existing = fileSystems.putIfAbsent(parsedUri.archiveUri, fileSystem);
            if (existing == null) {
                return fileSystem;
            }

            fileSystem.close();
        }
    }

    /// Opens a RAR archive file system from an archive path.
    @Override
    public RarArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return RarArkivoFileSystemImpl.open(this, path, path.toUri().normalize(), environment, () -> {
        });
    }

    /// Returns an open RAR archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return requireFileSystem(ParsedRarUri.parse(uri, false));
    }

    /// Returns a path inside an open RAR archive file system.
    @Override
    public Path getPath(URI uri) {
        ParsedRarUri parsedUri = ParsedRarUri.parse(uri, true);
        return requireFileSystem(parsedUri).getPath(parsedUri.entryPath);
    }

    /// Opens a byte channel for a path inside a RAR archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        return readFileSystem(path).newByteChannel(path, options, attributes);
    }

    /// Opens an input stream for a path inside a RAR archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newInputStream(path, options);
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
        return readFileSystem(directory).newDirectoryStream(directory, filter);
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
        boolean replaceExisting = false;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else {
                throw new UnsupportedOperationException("Unsupported RAR copy option: " + option);
            }
        }

        BasicFileAttributes attributes = readAttributes(source, BasicFileAttributes.class);
        if (attributes.isDirectory()) {
            if (Files.exists(target)) {
                if (!replaceExisting) {
                    throw new java.nio.file.FileAlreadyExistsException(target.toString());
                }
                if (Files.isDirectory(target)) {
                    return;
                }
                Files.delete(target);
            }
            Files.createDirectories(target);
            return;
        }

        try (InputStream input = newInputStream(source)) {
            if (replaceExisting) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(input, target);
            }
        }
    }

    /// RAR file systems are read-only.
    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /// Returns whether two RAR archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        readFileSystem(path).checkAccess(path);
        return path.equals(other);
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
        return readFileSystem(path).readAttributes(path, type, options);
    }

    /// Reads named file attributes for a RAR archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return readFileSystem(path).readAttributes(path, attributes, options);
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

    /// Returns the open file system registered for a parsed URI.
    private RarArkivoFileSystem requireFileSystem(ParsedRarUri parsedUri) {
        RarArkivoFileSystem fileSystem = fileSystems.get(parsedUri.archiveUri);
        if (fileSystem != null && fileSystem.isOpen()) {
            return fileSystem;
        }
        if (fileSystem != null) {
            fileSystems.remove(parsedUri.archiveUri, fileSystem);
        }
        throw new FileSystemNotFoundException(parsedUri.archiveUri.toString());
    }

    /// Requires a provider URI to use the RAR Arkivo scheme.
    private static void requireSupportedScheme(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }

    /// Stores parsed components from a RAR Arkivo provider URI.
    @NotNullByDefault
    private static final class ParsedRarUri {
        /// The nested archive URI.
        private final URI archiveUri;

        /// The archive path resolved from the nested archive URI.
        private final Path archivePath;

        /// The decoded entry path, or `null` when the URI identifies only an archive file system.
        private final @Nullable String entryPath;

        /// Creates parsed RAR URI components.
        private ParsedRarUri(URI archiveUri, Path archivePath, @Nullable String entryPath) {
            this.archiveUri = archiveUri;
            this.archivePath = archivePath;
            this.entryPath = entryPath;
        }

        /// Parses a RAR Arkivo provider URI.
        private static ParsedRarUri parse(URI uri, boolean requireEntryPath) {
            requireSupportedScheme(uri);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("RAR Arkivo URI must not contain query or fragment: " + uri);
            }

            String schemeSpecificPart = uri.getRawSchemeSpecificPart();
            int separator = schemeSpecificPart.indexOf("!/");
            if (separator < 0 && requireEntryPath) {
                throw new IllegalArgumentException("RAR Arkivo entry URI must contain !/: " + uri);
            }

            String archivePart = separator >= 0
                    ? schemeSpecificPart.substring(0, separator)
                    : schemeSpecificPart;
            if (archivePart.isEmpty()) {
                throw new IllegalArgumentException("RAR Arkivo URI must contain an archive URI: " + uri);
            }

            URI archiveUri = URI.create(archivePart).normalize();
            Path archivePath = Path.of(archiveUri);
            String entryPath = separator >= 0
                    ? decodeEntryPath(schemeSpecificPart.substring(separator + 2))
                    : null;
            return new ParsedRarUri(archiveUri, archivePath, entryPath);
        }

        /// Decodes a raw entry path from a RAR Arkivo provider URI.
        private static String decodeEntryPath(String rawEntryPath) {
            if (rawEntryPath.isEmpty()) {
                return "/";
            }
            String decodedPath = URI.create("arkivo-entry:/" + rawEntryPath).getPath();
            return decodedPath != null ? decodedPath : "/";
        }
    }
}

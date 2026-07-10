// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.tar.internal.TarArkivoFileSystemImpl;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Provides JDK file system provider entry points for TAR archives.
@NotNullByDefault
public final class TarArkivoFileSystemProvider extends FileSystemProvider {
    /// The URI scheme handled by the TAR Arkivo file system provider.
    public static final String SCHEME = "arkivo+tar";

    /// The shared provider instance used by Arkivo convenience factories.
    private static final TarArkivoFileSystemProvider INSTANCE = new TarArkivoFileSystemProvider();

    /// The file systems opened through URI entry points.
    private final ConcurrentHashMap<URI, TarArkivoFileSystem> fileSystems = new ConcurrentHashMap<>();

    /// Creates a TAR file system provider.
    public TarArkivoFileSystemProvider() {
    }

    /// Returns the shared TAR file system provider instance.
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
        ParsedTarUri parsedUri = ParsedTarUri.parse(uri, false);

        while (true) {
            TarArkivoFileSystem existing = fileSystems.get(parsedUri.archiveUri);
            if (existing != null) {
                if (existing.isOpen()) {
                    throw new FileSystemAlreadyExistsException(parsedUri.archiveUri.toString());
                }
                fileSystems.remove(parsedUri.archiveUri, existing);
                continue;
            }

            TarArkivoFileSystem[] holder = new TarArkivoFileSystem[1];
            Runnable closeAction = () -> {
                TarArkivoFileSystem fileSystem = holder[0];
                if (fileSystem != null) {
                    fileSystems.remove(parsedUri.archiveUri, fileSystem);
                }
            };
            TarArkivoFileSystem fileSystem =
                    TarArkivoFileSystemImpl.open(this, parsedUri.archivePath, parsedUri.archiveUri, environment, closeAction);
            holder[0] = fileSystem;

            existing = fileSystems.putIfAbsent(parsedUri.archiveUri, fileSystem);
            if (existing == null) {
                return fileSystem;
            }

            fileSystem.close();
        }
    }

    /// Opens a TAR archive file system from an archive path.
    @Override
    public TarArkivoFileSystem newFileSystem(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return TarArkivoFileSystemImpl.open(this, path, path.toUri().normalize(), environment, () -> {
        });
    }

    /// Returns an open TAR archive file system for a provider URI.
    @Override
    public FileSystem getFileSystem(URI uri) {
        return requireFileSystem(ParsedTarUri.parse(uri, false));
    }

    /// Returns a path inside an open TAR archive file system.
    @Override
    public Path getPath(URI uri) {
        ParsedTarUri parsedUri = ParsedTarUri.parse(uri, true);
        return requireFileSystem(parsedUri).getPath(parsedUri.entryPath);
    }

    /// Opens a byte channel for a path inside a TAR archive file system.
    @Override
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        return readFileSystem(path).newByteChannel(path, options, attributes);
    }

    /// Opens an input stream for a path inside a TAR archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return readFileSystem(path).newInputStream(path, options);
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
        return readFileSystem(directory).newDirectoryStream(directory, filter);
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
        boolean replaceExisting = false;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else {
                throw new UnsupportedOperationException("Unsupported TAR copy option: " + option);
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

    /// Moves an entry inside an update-mode TAR file system.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        TarArkivoFileSystemImpl fileSystem = readFileSystem(source);
        if (target.getFileSystem() != fileSystem) {
            throw new ProviderMismatchException("TAR moves require source and target in the same file system");
        }
        fileSystem.move(source, target, options);
    }

    /// Returns whether two TAR archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        readFileSystem(path).checkAccess(path);
        return path.equals(other);
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
        return readFileSystem(path).readAttributes(path, type, options);
    }

    /// Reads named file attributes for a TAR archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return readFileSystem(path).readAttributes(path, attributes, options);
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

    /// Returns the open file system registered for a parsed URI.
    private TarArkivoFileSystem requireFileSystem(ParsedTarUri parsedUri) {
        TarArkivoFileSystem fileSystem = fileSystems.get(parsedUri.archiveUri);
        if (fileSystem != null && fileSystem.isOpen()) {
            return fileSystem;
        }
        if (fileSystem != null) {
            fileSystems.remove(parsedUri.archiveUri, fileSystem);
        }
        throw new FileSystemNotFoundException(parsedUri.archiveUri.toString());
    }

    /// Requires a provider URI to use the TAR Arkivo scheme.
    private static void requireSupportedScheme(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
    }

    /// Stores parsed components from a TAR Arkivo provider URI.
    @NotNullByDefault
    private static final class ParsedTarUri {
        /// The nested archive URI.
        private final URI archiveUri;

        /// The archive path resolved from the nested archive URI.
        private final Path archivePath;

        /// The decoded entry path, or `null` when the URI identifies only an archive file system.
        private final @Nullable String entryPath;

        /// Creates parsed TAR URI components.
        private ParsedTarUri(URI archiveUri, Path archivePath, @Nullable String entryPath) {
            this.archiveUri = archiveUri;
            this.archivePath = archivePath;
            this.entryPath = entryPath;
        }

        /// Parses a TAR Arkivo provider URI.
        private static ParsedTarUri parse(URI uri, boolean requireEntryPath) {
            requireSupportedScheme(uri);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("TAR Arkivo URI must not contain query or fragment: " + uri);
            }

            String schemeSpecificPart = uri.getRawSchemeSpecificPart();
            int separator = schemeSpecificPart.indexOf("!/");
            if (separator < 0 && requireEntryPath) {
                throw new IllegalArgumentException("TAR Arkivo entry URI must contain !/: " + uri);
            }

            String archivePart = separator >= 0
                    ? schemeSpecificPart.substring(0, separator)
                    : schemeSpecificPart;
            if (archivePart.isEmpty()) {
                throw new IllegalArgumentException("TAR Arkivo URI must contain an archive URI: " + uri);
            }

            URI archiveUri = URI.create(archivePart).normalize();
            Path archivePath = Path.of(archiveUri);
            String entryPath = separator >= 0
                    ? decodeEntryPath(schemeSpecificPart.substring(separator + 2))
                    : null;
            return new ParsedTarUri(archiveUri, archivePath, entryPath);
        }

        /// Decodes a raw entry path from a TAR Arkivo provider URI.
        private static String decodeEntryPath(String rawEntryPath) {
            if (rawEntryPath.isEmpty()) {
                return "/";
            }
            String decodedPath = URI.create("arkivo-entry:/" + rawEntryPath).getPath();
            return decodedPath != null ? decodedPath : "/";
        }
    }
}

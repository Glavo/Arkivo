// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.zip.internal.StreamingZipArkivoFileSystemImpl;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl;
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
    private final ConcurrentHashMap<URI, ZipArkivoFileSystem> fileSystems = new ConcurrentHashMap<>();

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
            ZipArkivoFileSystem existing = fileSystems.get(parsedUri.archiveUri);
            if (existing != null) {
                if (existing.isOpen()) {
                    throw new FileSystemAlreadyExistsException(parsedUri.archiveUri.toString());
                }
                fileSystems.remove(parsedUri.archiveUri, existing);
                continue;
            }

            ZipArkivoFileSystem[] holder = new ZipArkivoFileSystem[1];
            Runnable closeAction = () -> {
                ZipArkivoFileSystem fileSystem = holder[0];
                if (fileSystem != null) {
                    fileSystems.remove(parsedUri.archiveUri, fileSystem);
                }
            };
            ZipArkivoFileSystem fileSystem = config.archiveWritable()
                    ? new StreamingZipArkivoFileSystemImpl(this, parsedUri.archivePath, config, closeAction, true)
                    : new ZipArkivoFileSystemImpl(this, parsedUri.archivePath, null, config, closeAction);
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
        return config.archiveWritable()
                ? new StreamingZipArkivoFileSystemImpl(this, path, config, null, true)
                : new ZipArkivoFileSystemImpl(this, path, null, config);
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
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.newByteChannel(path, options, attributes);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.newByteChannel(path, options, attributes);
        }
        throw new ProviderMismatchException();
    }

    /// Opens an input stream for a path inside a ZIP archive file system.
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.newInputStream(path, options);
        }
        throw new UnsupportedOperationException("Streaming ZIP output does not expose input streams");
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
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.newDirectoryStream(directory, filter);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.newDirectoryStream(directory, filter);
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
        boolean replaceExisting = false;
        boolean followLinks = true;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else if (option == LinkOption.NOFOLLOW_LINKS) {
                followLinks = false;
            } else {
                throw new UnsupportedOperationException("Unsupported ZIP copy option: " + option);
            }
        }

        BasicFileAttributes attributes = readAttributes(source, BasicFileAttributes.class);
        if (attributes.isSymbolicLink()) {
            if (followLinks) {
                copy(resolveLinkTarget(source), target, options);
                return;
            }
            if (Files.exists(target)) {
                if (!replaceExisting) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                Files.delete(target);
            }
            Files.createSymbolicLink(target, readSymbolicLink(source));
            return;
        }
        if (attributes.isDirectory()) {
            if (Files.exists(target)) {
                if (!replaceExisting) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                if (Files.isDirectory(target)) {
                    return;
                }
                Files.delete(target);
            }
            Files.createDirectories(target);
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

    /// Resolves a symbolic link target against the link parent when the target is relative.
    private static Path resolveLinkTarget(Path link) throws IOException {
        Path target = Files.readSymbolicLink(link);
        if (target.isAbsolute()) {
            return target;
        }
        Path parent = link.getParent();
        return parent != null ? parent.resolve(target).normalize() : target.normalize();
    }

    /// Moves a path inside or across ZIP archive file systems.
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /// Returns whether two ZIP archive paths refer to the same file.
    @Override
    public boolean isSameFile(Path path, Path other) throws IOException {
        readFileSystem(path).checkAccess(path);
        return path.equals(other);
    }

    /// Returns whether a ZIP archive path is hidden.
    @Override
    public boolean isHidden(Path path) throws IOException {
        readFileSystem(path).checkAccess(path);
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
            return readFileSystem.getFileAttributeView(path, type);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.getFileAttributeView(path, type);
        }
        return null;
    }

    /// Reads file attributes for a ZIP archive path.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.readAttributes(path, type);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.readAttributes(path, type);
        }
        throw new ProviderMismatchException();
    }

    /// Reads named file attributes for a ZIP archive path.
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        ZipArkivoFileSystem fileSystem = fileSystem(path);
        if (fileSystem instanceof ZipArkivoFileSystemImpl readFileSystem) {
            return readFileSystem.readAttributes(path, attributes);
        }
        if (fileSystem instanceof StreamingZipArkivoFileSystemImpl writeFileSystem) {
            return writeFileSystem.readAttributes(path, attributes);
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

    /// Returns the open file system registered for a parsed URI.
    private ZipArkivoFileSystem requireFileSystem(ParsedZipUri parsedUri) {
        ZipArkivoFileSystem fileSystem = fileSystems.get(parsedUri.archiveUri);
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

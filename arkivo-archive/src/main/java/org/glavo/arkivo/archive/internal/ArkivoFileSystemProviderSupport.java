// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/// Provides common URI parsing and registered-instance lifecycle behavior for archive file system providers.
@NotNullByDefault
public final class ArkivoFileSystemProviderSupport {
    /// The synthetic scheme used only to decode archive entry paths.
    private static final String ENTRY_URI_PREFIX = "arkivo-entry:/";

    /// Prevents instantiation.
    private ArkivoFileSystemProviderSupport() {
    }

    /// Resolves a path for a read operation that follows symbolic links.
    public static Path resolveReadPath(Path path) throws IOException {
        return Objects.requireNonNull(path, "path").toRealPath();
    }

    /// Resolves a path for an input-stream operation unless `NOFOLLOW_LINKS` is requested.
    ///
    /// Unsupported options are left for the concrete provider to reject without performing an early path lookup.
    public static Path resolveReadPath(Path path, OpenOption... options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        boolean followLinks = true;
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == LinkOption.NOFOLLOW_LINKS) {
                followLinks = false;
            } else if (option != StandardOpenOption.READ) {
                return path;
            }
        }
        return followLinks ? path.toRealPath() : path;
    }

    /// Resolves a path for a read-only byte-channel operation unless `NOFOLLOW_LINKS` is requested.
    ///
    /// Channels with write, creation, or provider-specific options retain their lexical path so mutation semantics are
    /// decided by the concrete archive file system.
    public static Path resolveReadChannelPath(Path path, Set<? extends OpenOption> options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        boolean followLinks = true;
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == LinkOption.NOFOLLOW_LINKS) {
                followLinks = false;
            } else if (option != StandardOpenOption.READ) {
                return path;
            }
        }
        return followLinks ? path.toRealPath() : path;
    }

    /// Resolves a path for an attribute read unless `NOFOLLOW_LINKS` is requested.
    public static Path resolveReadPath(Path path, LinkOption... options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        for (LinkOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == LinkOption.NOFOLLOW_LINKS) {
                return path;
            }
        }
        return path.toRealPath();
    }

    /// Creates a lazily resolved path for a file attribute view.
    ///
    /// Attribute-view lookup cannot report `IOException`, so symbolic links are resolved only when an operation is
    /// invoked on the returned view path.
    public static AttributeViewPath attributeViewPath(Path path, LinkOption... options) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        boolean followLinks = true;
        for (LinkOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == LinkOption.NOFOLLOW_LINKS) {
                followLinks = false;
            }
        }
        return new AttributeViewPath(path, followLinks);
    }

    /// Stores one attribute-view path and its symbolic-link traversal policy.
    ///
    /// @param path the lexical path supplied when the view was created
    /// @param followLinks whether view operations resolve symbolic links
    @NotNullByDefault
    public record AttributeViewPath(Path path, boolean followLinks) {
        /// Creates a validated attribute-view path.
        public AttributeViewPath {
            Objects.requireNonNull(path, "path");
        }

        /// Resolves the path for one attribute-view operation.
        public Path resolve() throws IOException {
            return followLinks ? path.toRealPath() : path;
        }
    }

    /// Copies one archive path to another file system with standard non-recursive NIO semantics.
    ///
    /// Regular file bodies are streamed, directories are copied without descendants, and symbolic links are followed
    /// unless `NOFOLLOW_LINKS` is requested. `REPLACE_EXISTING` and `COPY_ATTRIBUTES` are supported; other options are
    /// rejected before the target is modified.
    public static void copy(Path source, Path target, CopyOption... options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");

        boolean replaceExisting = false;
        boolean copyAttributes = false;
        boolean followLinks = true;
        for (CopyOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else if (option == StandardCopyOption.COPY_ATTRIBUTES) {
                copyAttributes = true;
            } else if (option == LinkOption.NOFOLLOW_LINKS) {
                followLinks = false;
            } else {
                throw new UnsupportedOperationException("Unsupported archive copy option: " + option);
            }
        }

        BasicFileAttributes sourceAttributes = followLinks
                ? Files.readAttributes(source, BasicFileAttributes.class)
                : Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (source.equals(target)) {
            return;
        }

        boolean copiedSymbolicLink = sourceAttributes.isSymbolicLink();
        boolean initializeAttributes = copyAttributes && target.getFileSystem() instanceof ArkivoFileSystem;
        FileAttribute<?>[] initialAttributes = initializeAttributes
                ? new FileAttribute<?>[]{lastModifiedTimeAttribute(sourceAttributes.lastModifiedTime())}
                : new FileAttribute<?>[0];
        boolean attributesInitialized = false;
        if (copiedSymbolicLink) {
            prepareCopyTarget(target, replaceExisting, false);
            Files.createSymbolicLink(target, Files.readSymbolicLink(source), initialAttributes);
            attributesInitialized = initializeAttributes;
        } else if (sourceAttributes.isDirectory()) {
            if (prepareCopyTarget(target, replaceExisting, true)) {
                Files.createDirectories(target, initialAttributes);
                attributesInitialized = initializeAttributes;
            }
        } else if (initializeAttributes) {
            prepareCopyTarget(target, replaceExisting, false);
            try (InputStream input = Files.newInputStream(source);
                 SeekableByteChannel channel = Files.newByteChannel(
                         target,
                         Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                         initialAttributes
                 );
                 OutputStream output = Channels.newOutputStream(channel)) {
                input.transferTo(output);
            }
            attributesInitialized = true;
        } else {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !replaceExisting) {
                throw new FileAlreadyExistsException(target.toString());
            }
            try (InputStream input = Files.newInputStream(source)) {
                if (replaceExisting) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(input, target);
                }
            }
        }

        if (copyAttributes && !attributesInitialized) {
            copyBasicAttributes(sourceAttributes, target, copiedSymbolicLink);
        }
    }

    /// Requires a move target to belong to the same file system as its source.
    ///
    /// Provider-native archive moves are namespace operations and never degrade into a cross-file-system copy followed
    /// by deletion. The check is performed before format implementations inspect or mutate either path.
    public static void requireSameFileSystemMove(Path source, Path target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.getFileSystem() != target.getFileSystem()) {
            throw new ProviderMismatchException("Archive moves require source and target in the same file system");
        }
    }

    /// Returns a creation attribute carrying one basic last-modified time.
    private static FileAttribute<FileTime> lastModifiedTimeAttribute(FileTime value) {
        Objects.requireNonNull(value, "value");
        return new FileAttribute<>() {
            /// Returns the basic attribute name.
            @Override
            public String name() {
                return "basic:lastModifiedTime";
            }

            /// Returns the requested last-modified time.
            @Override
            public FileTime value() {
                return value;
            }
        };
    }

    /// Prepares a directory or symbolic-link copy target and returns whether a new directory must be created.
    private static boolean prepareCopyTarget(
            Path target,
            boolean replaceExisting,
            boolean sourceDirectory
    ) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (!replaceExisting) {
            throw new FileAlreadyExistsException(target.toString());
        }

        BasicFileAttributes targetAttributes = Files.readAttributes(
                target,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (sourceDirectory && targetAttributes.isDirectory()) {
            return false;
        }
        Files.delete(target);
        return true;
    }

    /// Copies basic timestamps to a completed target entry.
    private static void copyBasicAttributes(
            BasicFileAttributes sourceAttributes,
            Path target,
            boolean symbolicLink
    ) throws IOException {
        BasicFileAttributeView view = Files.getFileAttributeView(
                target,
                BasicFileAttributeView.class,
                symbolicLink ? new LinkOption[]{LinkOption.NOFOLLOW_LINKS} : new LinkOption[0]
        );
        view.setTimes(
                sourceAttributes.lastModifiedTime(),
                sourceAttributes.lastAccessTime(),
                sourceAttributes.creationTime()
        );
    }

    /// Returns whether two paths in the same file system resolve to the same archive entry.
    ///
    /// Paths from different file systems cannot identify the same archive entry. Symbolic links are followed through
    /// `Path.toRealPath()`, and non-null file keys provide a final identity comparison for distinct real paths.
    public static boolean isSameFile(Path path, Path other) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(other, "other");
        if (path.getFileSystem() != other.getFileSystem()) {
            return false;
        }

        Path realPath = path.toRealPath();
        Path realOther = other.toRealPath();
        if (realPath.equals(realOther)) {
            return true;
        }

        BasicFileAttributes attributes = Files.readAttributes(
                realPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        @Nullable Object fileKey = attributes.fileKey();
        return fileKey != null && fileKey.equals(Files.readAttributes(
                realOther,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        ).fileKey());
    }

    /// Requires a path to identify the expected archive format before an installed provider claims it.
    ///
    /// Existing files are identified by content when possible so a misleading extension cannot route an archive to
    /// the wrong provider. A matching extension is used when no installed format recognizes the content, including
    /// missing archives opened with `CREATE`, damaged archives that should report a format-specific error, and
    /// compressed archives whose outer stream hides the archive signature.
    ///
    /// @throws UnsupportedOperationException if the path belongs to another installed format or cannot be associated
    ///                                       with the expected format
    public static void requirePathFormat(Path path, ArkivoFormat expectedFormat) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expectedFormat, "expectedFormat");

        @Nullable ArkivoFormat detectedFormat;
        try {
            detectedFormat = ArkivoFormats.detect(path);
        } catch (NoSuchFileException exception) {
            if (hasFileExtension(path, expectedFormat)) {
                return;
            }
            throw unsupportedPath(path, expectedFormat);
        }

        if (detectedFormat != null) {
            if (expectedFormat.name().equals(detectedFormat.name())) {
                return;
            }
            throw unsupportedPath(path, expectedFormat);
        }
        if (hasFileExtension(path, expectedFormat)) {
            return;
        }
        throw unsupportedPath(path, expectedFormat);
    }

    /// Returns whether the final path component has one of the format's declared extensions.
    private static boolean hasFileExtension(Path path, ArkivoFormat format) {
        @Nullable Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String normalizedName = fileName.toString().toLowerCase(Locale.ROOT);
        for (String extension : format.fileExtensions()) {
            if (normalizedName.endsWith("." + extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /// Creates the exception used to decline a path during JDK provider iteration.
    private static UnsupportedOperationException unsupportedPath(Path path, ArkivoFormat format) {
        return new UnsupportedOperationException(
                "Path is not recognized as a " + format.name() + " archive: " + path
        );
    }

    /// Parses an Arkivo provider URI containing a nested archive URI and an optional `!/entry` suffix.
    ///
    /// @param uri provider URI to parse
    /// @param scheme provider scheme
    /// @param formatName format name used in validation messages
    /// @param requireEntryPath whether the URI must identify an entry
    public static ParsedUri parseUri(
            URI uri,
            String scheme,
            String formatName,
            boolean requireEntryPath
    ) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(formatName, "formatName");
        if (!scheme.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }

        String schemeSpecificPart = uri.getRawSchemeSpecificPart();
        if (uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || schemeSpecificPart.indexOf('?') >= 0) {
            throw new IllegalArgumentException(
                    formatName + " Arkivo URI must not contain query or fragment: " + uri
            );
        }

        int separator = schemeSpecificPart.indexOf("!/");
        if (separator < 0 && requireEntryPath) {
            throw new IllegalArgumentException(
                    formatName + " Arkivo entry URI must contain !/: " + uri
            );
        }

        String archivePart = separator >= 0
                ? schemeSpecificPart.substring(0, separator)
                : schemeSpecificPart;
        if (archivePart.isEmpty()) {
            throw new IllegalArgumentException(
                    formatName + " Arkivo URI must contain an archive URI: " + uri
            );
        }

        URI archiveUri = URI.create(archivePart).normalize();
        Path archivePath = Path.of(archiveUri);
        @Nullable String entryPath = separator >= 0
                ? decodeEntryPath(schemeSpecificPart.substring(separator + 2))
                : null;
        return new ParsedUri(archiveUri, archivePath, entryPath);
    }

    /// Decodes one raw provider-URI entry path.
    private static String decodeEntryPath(String rawEntryPath) {
        if (rawEntryPath.isEmpty()) {
            return "/";
        }
        @Nullable String decodedPath = URI.create(ENTRY_URI_PREFIX + rawEntryPath).getPath();
        return decodedPath != null ? decodedPath : "/";
    }

    /// Stores the normalized nested archive URI, its resolved path, and an optional decoded entry path.
    ///
    /// @param archiveUri normalized nested archive URI
    /// @param archivePath archive path resolved from the nested URI
    /// @param entryPath decoded entry path, or `null` when the URI identifies only the file system
    @NotNullByDefault
    public record ParsedUri(URI archiveUri, Path archivePath, @Nullable String entryPath) {
        /// Validates parsed URI components.
        public ParsedUri {
            Objects.requireNonNull(archiveUri, "archiveUri");
            Objects.requireNonNull(archivePath, "archivePath");
        }
    }

    /// Opens a file system that unregisters itself by invoking the supplied close action.
    ///
    /// @param <F> concrete file system type
    @FunctionalInterface
    @NotNullByDefault
    public interface RegisteredFileSystemFactory<F extends FileSystem> {
        /// Opens one candidate file system.
        ///
        /// @param closeAction action the file system must invoke when it closes
        F open(Runnable closeAction) throws IOException;
    }

    /// Maintains the open file systems registered by one provider instance.
    ///
    /// @param <F> concrete file system type
    @NotNullByDefault
    public static final class Registry<F extends FileSystem> {
        /// File systems indexed by normalized nested archive URI.
        private final ConcurrentHashMap<URI, F> fileSystems = new ConcurrentHashMap<>();

        /// Creates an empty registry.
        public Registry() {
        }

        /// Opens and atomically registers a file system for an archive URI.
        ///
        /// The factory may be called only after stale registrations have been removed. A candidate that loses a
        /// concurrent publication race is closed before this method retries or reports the winning open instance.
        public F open(URI archiveUri, RegisteredFileSystemFactory<F> factory) throws IOException {
            Objects.requireNonNull(archiveUri, "archiveUri");
            Objects.requireNonNull(factory, "factory");
            while (true) {
                F existing = fileSystems.get(archiveUri);
                if (existing != null) {
                    if (existing.isOpen()) {
                        throw new FileSystemAlreadyExistsException(archiveUri.toString());
                    }
                    fileSystems.remove(archiveUri, existing);
                    continue;
                }

                AtomicReference<@Nullable F> candidateReference = new AtomicReference<>();
                Runnable closeAction = () -> {
                    @Nullable F candidate = candidateReference.get();
                    if (candidate != null) {
                        fileSystems.remove(archiveUri, candidate);
                    }
                };
                F candidate = factory.open(closeAction);
                candidateReference.set(candidate);

                existing = fileSystems.putIfAbsent(archiveUri, candidate);
                if (existing == null) {
                    return candidate;
                }

                candidate.close();
                if (existing.isOpen()) {
                    throw new FileSystemAlreadyExistsException(archiveUri.toString());
                }
                fileSystems.remove(archiveUri, existing);
            }
        }

        /// Returns the open file system registered for an archive URI.
        public F require(URI archiveUri) {
            Objects.requireNonNull(archiveUri, "archiveUri");
            F fileSystem = fileSystems.get(archiveUri);
            if (fileSystem != null && fileSystem.isOpen()) {
                return fileSystem;
            }
            if (fileSystem != null) {
                fileSystems.remove(archiveUri, fileSystem);
            }
            throw new FileSystemNotFoundException(archiveUri.toString());
        }
    }
}

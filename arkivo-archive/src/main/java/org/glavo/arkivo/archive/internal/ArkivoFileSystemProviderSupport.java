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
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
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

    /// Rejects an empty selection or names absent from a completed attribute selection, except for the wildcard.
    ///
    /// Callers must include recognized attributes in `values` even when their values are `null`.
    ///
    /// @param names the requested attribute names
    /// @param values the selected attributes supported by the view
    /// @throws IllegalArgumentException if no names are supplied or a name other than `*` has no corresponding attribute
    public static void validateAttributeNames(Set<String> names, Map<String, ?> values) {
        if (names.isEmpty()) {
            throw new IllegalArgumentException("No attributes specified");
        }
        for (String name : names) {
            if (!name.equals("*") && !values.containsKey(name)) {
                throw new IllegalArgumentException("Unrecognized attribute: " + name);
            }
        }
    }

    /// Removes creation and truncation options ignored by read-only byte-channel opens.
    ///
    /// The input set is not modified. All options are preserved when `WRITE` or `APPEND` is present;
    /// otherwise `CREATE`, `CREATE_NEW`, and `TRUNCATE_EXISTING` are removed. Other options remain
    /// subject to validation by the concrete provider.
    ///
    /// @param options the requested byte-channel options
    /// @return the original set if unchanged, or an immutable filtered set
    public static Set<? extends OpenOption> normalizeChannelOptions(Set<? extends OpenOption> options) {
        Objects.requireNonNull(options, "options");
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND)
                || !(options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW)
                || options.contains(StandardOpenOption.TRUNCATE_EXISTING))) {
            return options;
        }
        Set<OpenOption> filtered = new HashSet<>(options);
        filtered.remove(StandardOpenOption.CREATE);
        filtered.remove(StandardOpenOption.CREATE_NEW);
        filtered.remove(StandardOpenOption.TRUNCATE_EXISTING);
        return Set.copyOf(filtered);
    }

    /// Resolves a path for a read operation that follows symbolic links.
    ///
    /// @param path the lexical path to resolve
    /// @return the checked normalized absolute real path
    /// @throws IOException if the path does not exist or cannot be resolved
    public static Path resolveReadPath(Path path) throws IOException {
        return Objects.requireNonNull(path, "path").toRealPath();
    }

    /// Resolves a path for an input-stream operation, leaving the final link unfollowed with `NOFOLLOW_LINKS`.
    ///
    /// Unsupported options are left for the concrete provider to reject without performing an early path lookup.
    ///
    /// @param path the lexical input path
    /// @param options the input-stream open options
    /// @return the resolved read path, or the lexical path for unsupported options
    /// @throws IOException if required symbolic-link resolution fails
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
        return resolvePath(path, followLinks);
    }

    /// Resolves a read-only byte-channel path, leaving the final link unfollowed with `NOFOLLOW_LINKS`.
    ///
    /// Channels with write, creation, or provider-specific options retain their lexical path so mutation semantics are
    /// decided by the concrete archive file system.
    ///
    /// @param path the lexical byte-channel path
    /// @param options the byte-channel open options
    /// @return the resolved read path, or the lexical path for write, creation, or provider-specific options
    /// @throws IOException if required symbolic-link resolution fails
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
        return resolvePath(path, followLinks);
    }

    /// Resolves parent links and follows the final link unless `NOFOLLOW_LINKS` is requested.
    ///
    /// @param path the lexical attribute path
    /// @param options the symbolic-link traversal options
    /// @return the checked archive path with parent links resolved, or the unchanged non-archive path for no-follow reads
    /// @throws IOException if required symbolic-link resolution fails
    public static Path resolveReadPath(Path path, LinkOption... options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        boolean followLinks = true;
        for (LinkOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == LinkOption.NOFOLLOW_LINKS) {
                followLinks = false;
            }
        }
        return resolvePath(path, followLinks);
    }

    /// Resolves archive path components without conflating terminal-link lookup with no-follow real-path spelling.
    private static Path resolvePath(Path path, boolean followFinalLink) throws IOException {
        if (path instanceof AbstractArkivoPath<?> archivePath) {
            return archivePath.resolvePath(followFinalLink);
        }
        return followFinalLink ? path.toRealPath() : path;
    }

    /// Resolves an archive mutation path without requiring a new final entry to exist.
    ///
    /// @param path the archive path to resolve
    /// @param followFinalLink whether the operation follows the final symbolic link
    /// @return an absolute path with existing parent components resolved
    /// @throws IOException if required components do not exist or cannot be resolved
    /// @throws ProviderMismatchException if the path is not an Arkivo path
    public static Path resolveMutationPath(Path path, boolean followFinalLink)
            throws IOException {
        return resolveMutationPath(path, followFinalLink, false);
    }

    /// Resolves a mutation path for a format that may create implicit parent directories.
    ///
    /// This method does not create entries. When a parent is missing, the unresolved suffix must contain no `.` or `..`
    /// elements. Existing parents and symbolic links are resolved before returning the target path.
    ///
    /// @param path the archive path to resolve
    /// @param followFinalLink whether to follow the terminal symbolic link
    /// @param createParents whether missing parent names may be returned for subsequent creation
    /// @return the absolute target path, with every existing parent resolved
    /// @throws IOException if a required component cannot be resolved or the result cannot be represented as an entry name
    /// @throws ProviderMismatchException if the path is not an Arkivo path
    public static Path resolveMutationPath(Path path, boolean followFinalLink, boolean createParents)
            throws IOException {
        Objects.requireNonNull(path, "path");
        if (!(path instanceof AbstractArkivoPath<?> archivePath)) {
            throw new ProviderMismatchException();
        }
        Path resolved = archivePath.resolvePath(followFinalLink, true, createParents);
        validateWritablePath(resolved);
        return resolved;
    }

    /// Rejects NIO names that would change identity when written and subsequently indexed.
    ///
    /// Archive readers interpret backslashes as separators and reject drive-prefixed entry names. NIO path
    /// components retain these characters literally, so writing them cannot preserve the requested entry identity.
    /// This check neither changes path components nor validates symbolic-link target text.
    ///
    /// @param path the path whose normalized archive-local name would be written
    /// @throws FileSystemException if the name contains a backslash, an unpaired surrogate, or begins with a drive prefix
    public static void validateWritablePath(Path path) throws FileSystemException {
        Path normalized = path.toAbsolutePath().normalize();
        String text = normalized.toString();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value) && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(value)) {
                throw new FileSystemException(path.toString(), null, "Archive entry names cannot contain unpaired surrogates");
            }
        }
        if (text.indexOf('\\') >= 0) {
            throw new FileSystemException(path.toString(), null, "Archive entry names cannot contain literal backslashes");
        }
        if (normalized.getNameCount() > 0) {
            String first = normalized.getName(0).toString();
            if (first.length() >= 2 && first.charAt(1) == ':') {
                throw new FileSystemException(path.toString(), null, "Archive entry names cannot begin with a drive prefix");
            }
        }
    }

    /// Rejects NUL and unpaired UTF-16 surrogates in link targets.
    ///
    /// This method does not normalize the target or require that it exists.
    ///
    /// @param target the symbolic-link or hard-link target text
    /// @throws IllegalArgumentException if the target contains NUL or an unpaired UTF-16 surrogate
    public static void validateLinkTarget(String target) {
        Objects.requireNonNull(target, "target");
        for (int index = 0; index < target.length(); index++) {
            char value = target.charAt(index);
            if (value == 0) {
                throw new IllegalArgumentException("Archive link targets cannot contain NUL");
            }
            if (Character.isHighSurrogate(value) && index + 1 < target.length()
                    && Character.isLowSurrogate(target.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(value)) {
                throw new IllegalArgumentException("Archive link targets cannot contain unpaired surrogates");
            }
        }
    }

    /// Creates a lazily resolved path for a file attribute view.
    ///
    /// Attribute-view lookup cannot report `IOException`, so symbolic links are resolved only when an operation is
    /// invoked on the returned view path.
    ///
    /// @param path the lexical path supplied to the attribute-view factory
    /// @param options the symbolic-link traversal options
    /// @return a validated lazy path and traversal policy
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
        ///
        /// @return the entry path with parent links resolved and the configured terminal-link policy applied
        /// @throws IOException if required symbolic-link resolution fails
        public Path resolve() throws IOException {
            return resolvePath(path, followLinks);
        }
    }

    /// Copies one archive path to another file system with standard non-recursive NIO semantics.
    ///
    /// Regular file bodies are streamed, directories are copied without descendants, and symbolic links are followed
    /// unless `NOFOLLOW_LINKS` is requested. `REPLACE_EXISTING` and `COPY_ATTRIBUTES` are supported; other options are
    /// rejected before the target is modified. Copying onto the same entry has no effect. A distinct nonempty target
    /// directory cannot be replaced. Target symbolic links are replaced rather than followed.
    ///
    /// @param source the source entry path
    /// @param target the destination entry path
    /// @param options the supported standard copy options
    /// @throws IOException if the source cannot be read or the target cannot be created or updated
    /// @throws UnsupportedOperationException if an option is unsupported
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
        if (source.equals(target) || sameCopyEntry(source, target, sourceAttributes, followLinks)) {
            return;
        }

        boolean copiedSymbolicLink = sourceAttributes.isSymbolicLink();
        boolean initializeAttributes = copyAttributes && target.getFileSystem() instanceof ArkivoFileSystem;
        FileAttribute<?>[] initialAttributes = initializeAttributes
                ? new FileAttribute<?>[]{lastModifiedTimeAttribute(sourceAttributes.lastModifiedTime())}
                : new FileAttribute<?>[0];
        boolean attributesInitialized = false;
        if (replaceExisting && (copiedSymbolicLink || sourceAttributes.isDirectory() || initializeAttributes)) {
            Files.deleteIfExists(target);
        }
        if (copiedSymbolicLink) {
            Files.createSymbolicLink(target, Files.readSymbolicLink(source), initialAttributes);
            attributesInitialized = initializeAttributes;
        } else if (sourceAttributes.isDirectory()) {
            Files.createDirectory(target, initialAttributes);
            attributesInitialized = initializeAttributes;
        } else if (initializeAttributes) {
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
    ///
    /// @param source the move source path
    /// @param target the move target path
    /// @throws ProviderMismatchException if the paths belong to different file-system instances
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

    /// Compares entry identity without following a target symbolic link that copying would replace.
    private static boolean sameCopyEntry(
            Path source,
            Path target,
            BasicFileAttributes sourceAttributes,
            boolean followLinks
    ) throws IOException {
        if (source.getFileSystem() != target.getFileSystem()) {
            return false;
        }
        final BasicFileAttributes targetAttributes;
        try {
            targetAttributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ignored) {
            return false;
        }
        @Nullable Object sourceKey = sourceAttributes.fileKey();
        if (sourceKey != null && sourceKey.equals(targetAttributes.fileKey())) {
            return true;
        }
        Path realSource = resolvePath(source, followLinks);
        return realSource.equals(resolvePath(target, false));
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
    /// Equal paths return `true` without accessing the file system. Paths from different file systems cannot identify
    /// the same archive entry. Symbolic links are followed through `Path.toRealPath()`, and non-null file keys provide
    /// a final identity comparison for distinct real paths.
    ///
    /// @param path the first path to compare
    /// @param other the second path to compare
    /// @return {@code true} if both paths resolve to the same entry in the same file system
    /// @throws IOException if either same-file-system path cannot be resolved or inspected
    public static boolean isSameFile(Path path, Path other) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(other, "other");
        if (path.equals(other)) {
            return true;
        }
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
    /// @param path the archive path to classify
    /// @param expectedFormat the format represented by the candidate provider
    /// @throws UnsupportedOperationException if the path belongs to another installed format or cannot be associated
    /// with the expected format
    /// @throws IOException if existing path content cannot be probed
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
    /// Exclamation marks in the source URI are escaped to distinguish them from the outer entry delimiter.
    /// An opaque source URI additionally escapes its existing percent signs; that quoting layer is removed before
    /// the source URI is passed to its provider. Equivalent Unicode and percent-escaped spellings resolve to the
    /// same source path. Registry identity follows the source provider's path equality rules.
    /// Source path components are retained without lexical normalization or symbolic-link resolution.
    ///
    /// @param uri provider URI to parse
    /// @param scheme provider scheme
    /// @param formatName format name used in validation messages
    /// @param requireEntryPath whether the URI must identify an entry
    /// @return the nested archive URI in the source provider's spelling, archive path, and optional decoded entry path
    /// @throws IllegalArgumentException if the provider URI is malformed or violates the requested shape
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

        URI archiveUri = URI.create(archivePart.replace("!", "%21"));
        if (archiveUri.isOpaque()) {
            archiveUri = decodeOpaqueArchiveUri(archiveUri.toString());
        }
        Path archivePath = Path.of(archiveUri);
        archiveUri = URI.create(archivePath.toUri().toASCIIString());
        @Nullable String entryPath = separator >= 0
                ? decodeEntryPath(schemeSpecificPart.substring(separator + 2))
                : null;
        return new ParsedUri(archiveUri, archivePath, entryPath);
    }

    /// Removes one quoting layer from an opaque source URI without decoding its original URI escapes.
    private static URI decodeOpaqueArchiveUri(String text) {
        StringBuilder decoded = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '%' && index + 2 < text.length() && text.charAt(index + 1) == '2') {
                char last = text.charAt(index + 2);
                if (last == '1' || last == '5') {
                    decoded.append(last == '1' ? '!' : '%');
                    index += 2;
                    continue;
                }
            }
            decoded.append(value);
        }
        return URI.create(decoded.toString());
    }

    /// Decodes one raw provider-URI entry path.
    private static String decodeEntryPath(String rawEntryPath) {
        if (rawEntryPath.isEmpty()) {
            return "/";
        }
        @Nullable String decodedPath = URI.create(ENTRY_URI_PREFIX + rawEntryPath).getPath();
        return decodedPath != null ? decodedPath : "/";
    }

    /// Stores the source provider's nested archive URI, its path, and an optional decoded entry path.
    ///
    /// @param archiveUri nested archive URI in the source provider's ASCII spelling
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

    /// Opens a file system that releases its URI reservation by invoking the supplied close action.
    ///
    /// @param <F> concrete file system type
    @FunctionalInterface
    @NotNullByDefault
    public interface RegisteredFileSystemFactory<F extends FileSystem> {
        /// Opens a file system while its URI is reserved against other open attempts.
        ///
        /// @param closeAction action the file system must invoke after output publication and any pending rollback
        ///                    have finished; setting its open state to false does not release the reservation
        /// @return a newly opened file system
        /// @throws IOException if the archive file system cannot be opened
        F open(Runnable closeAction) throws IOException;
    }

    /// Maintains the open file systems registered by one provider instance.
    ///
    /// @param <F> concrete file system type
    @NotNullByDefault
    public static final class Registry<F extends FileSystem> {
        /// Source-path reservations whose reference is null until the factory publishes its file system.
        private final ConcurrentHashMap<Path, AtomicReference<@Nullable F>> fileSystems = new ConcurrentHashMap<>();

        /// Creates an empty registry.
        public Registry() {
        }

        /// Reserves an archive source path, opens its file system, and publishes the completed instance.
        ///
        /// The reservation is acquired before invoking the factory, which may modify the backing archive. Attempts to
        /// open an equal source path fail while its factory is running or until its close action releases the reservation.
        /// Factories for distinct source paths can run concurrently. A factory failure releases only its own reservation.
        /// Source paths are compared using their provider's equality rules, without calling `normalize` or `toRealPath`.
        ///
        /// @param archivePath the resolved source path identifying the registration
        /// @param factory the callback that opens a file system honoring the supplied unregister action
        /// @return the newly opened and registered file system
        /// @throws IOException if the file system cannot be opened
        /// @throws FileSystemAlreadyExistsException if the source path is already reserved
        public F open(Path archivePath, RegisteredFileSystemFactory<F> factory) throws IOException {
            Objects.requireNonNull(archivePath, "archivePath");
            Objects.requireNonNull(factory, "factory");
            AtomicReference<@Nullable F> reservation = new AtomicReference<>();
            if (fileSystems.putIfAbsent(archivePath, reservation) != null) {
                throw new FileSystemAlreadyExistsException(archivePath.toString());
            }
            try {
                F fileSystem = Objects.requireNonNull(factory.open(
                        () -> fileSystems.remove(archivePath, reservation)), "opened file system");
                reservation.set(fileSystem);
                return fileSystem;
            } catch (IOException | RuntimeException | Error exception) {
                fileSystems.remove(archivePath, reservation);
                throw exception;
            }
        }

        /// Returns the open file system registered for an archive source path.
        ///
        /// @param archivePath the resolved source path identifying the registration
        /// @return the registered open file system
        /// @throws FileSystemNotFoundException if no open file system is registered
        public F require(Path archivePath) {
            Objects.requireNonNull(archivePath, "archivePath");
            @Nullable AtomicReference<@Nullable F> reservation = fileSystems.get(archivePath);
            @Nullable F fileSystem = reservation == null ? null : reservation.get();
            if (fileSystem != null && fileSystem.isOpen()) {
                return fileSystem;
            }
            throw new FileSystemNotFoundException(archivePath.toString());
        }
    }
}

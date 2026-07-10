// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.rar.RarArkivoEntryAttributeView;
import org.glavo.arkivo.rar.RarArkivoEntryAttributes;
import org.glavo.arkivo.rar.RarArkivoFileSystem;
import org.glavo.arkivo.rar.RarArkivoFileSystemProvider;
import org.glavo.arkivo.rar.RarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements a read-only RAR archive file system backed by an in-memory entry index.
@NotNullByDefault
public final class RarArkivoFileSystemImpl extends RarArkivoFileSystem {
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS =
            Set.of("basic", "owner", "posix", "rar");

    /// The file system provider that owns this file system.
    private final RarArkivoFileSystemProvider provider;

    /// The source archive path, or `null` when this file system is backed by explicit volumes.
    private final @Nullable Path archivePath;

    /// The archive URI used by generated entry URIs, or `null` when backed by explicit volumes.
    private final @Nullable URI archiveUri;

    /// The volume source, or `null` when this file system is backed by a single archive path.
    private final @Nullable ArkivoVolumeSource volumes;

    /// The action invoked when this file system closes.
    private final Runnable closeAction;

    /// The synthetic root path.
    private final RarArkivoPath rootPath;

    /// The file store view for this archive.
    private final RarFileStore fileStore = new RarFileStore();

    /// Entry nodes by normalized archive path.
    private final @Unmodifiable Map<String, Node> nodes;

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Whether the owned volume source has been closed.
    private boolean volumesClosed;

    /// Whether the close action has completed.
    private boolean closeActionCompleted;

    /// Creates a RAR file system instance.
    private RarArkivoFileSystemImpl(
            RarArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable URI archiveUri,
            @Nullable ArkivoVolumeSource volumes,
            ArkivoFileSystemThreadSafety threadSafety,
            Map<String, Node> nodes,
            Runnable closeAction
    ) {
        super(threadSafety);
        if ((archivePath == null) != (archiveUri == null)) {
            throw new IllegalArgumentException("archivePath and archiveUri must both be present or absent");
        }
        if (archivePath == null && volumes == null) {
            throw new IllegalArgumentException("archivePath or volumes must be provided");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = archivePath != null ? archivePath.toAbsolutePath().normalize() : null;
        this.archiveUri = archiveUri;
        this.volumes = volumes;
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.nodes = Map.copyOf(nodes);
        this.rootPath = RarArkivoPath.root(this);
    }

    /// Opens a RAR file system from an archive path.
    public static RarArkivoFileSystemImpl open(
            RarArkivoFileSystemProvider provider,
            Path archivePath,
            URI archiveUri,
            Map<String, ?> environment,
            Runnable closeAction
    ) throws IOException {
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemThreadSafety threadSafety = ArkivoFileSystem.THREAD_SAFETY.readOrDefault(
                environment,
                ArkivoFileSystemThreadSafety.CONCURRENT_READ
        );
        Set<OpenOption> openOptions = ArkivoFileSystem.OPEN_OPTIONS.readOrDefault(
                environment,
                Set.of(StandardOpenOption.READ)
        );
        for (OpenOption option : openOptions) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("RAR archive file systems are read-only");
            }
        }

        List<Path> splitVolumePaths = RarSplitVolumePaths.discover(archivePath);
        try (InputStream input = splitVolumePaths != null
                ? new RarVolumeInputStream(index -> {
            if (index < 0 || index >= splitVolumePaths.size()) {
                return null;
            }
            return Files.newByteChannel(splitVolumePaths.get((int) index), openOptions);
        })
                : Files.newInputStream(archivePath, openOptions.toArray(OpenOption[]::new))) {
            return new RarArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    null,
                    threadSafety,
                    readNodes(input),
                    closeAction
            );
        }
    }

    /// Opens a RAR file system from explicit archive volumes.
    public static RarArkivoFileSystemImpl open(
            RarArkivoFileSystemProvider provider,
            ArkivoVolumeSource volumes,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        ArkivoFileSystemThreadSafety threadSafety = ArkivoFileSystem.THREAD_SAFETY.readOrDefault(
                environment,
                ArkivoFileSystemThreadSafety.CONCURRENT_READ
        );
        Set<OpenOption> openOptions = ArkivoFileSystem.OPEN_OPTIONS.readOrDefault(
                environment,
                Set.of(StandardOpenOption.READ)
        );
        for (OpenOption option : openOptions) {
            if (option != StandardOpenOption.READ) {
                UnsupportedOperationException exception =
                        new UnsupportedOperationException("RAR archive file systems are read-only");
                try {
                    volumes.close();
                } catch (IOException | RuntimeException | Error closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
        }

        try (InputStream input = new RarVolumeInputStream(volumes)) {
            return new RarArkivoFileSystemImpl(
                    provider,
                    null,
                    null,
                    volumes,
                    threadSafety,
                    readNodes(input),
                    () -> {
                    }
            );
        } catch (IOException | RuntimeException | Error exception) {
            try {
                volumes.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Returns the provider that owns this file system.
    @Override
    public RarArkivoFileSystemProvider provider() {
        return provider;
    }

    /// Closes this file system.
    @Override
    public void close() throws IOException {
        try (CloseOperation ignored = beginCloseOperation()) {
            if (!open && volumesClosed && closeActionCompleted) {
                return;
            }
            open = false;
            Throwable failure = null;
            if (!volumesClosed) {
                try {
                    if (volumes != null) {
                        volumes.close();
                    }
                    volumesClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            if (!closeActionCompleted) {
                try {
                    closeAction.run();
                    closeActionCompleted = true;
                } catch (RuntimeException | Error exception) {
                    if (failure != null) {
                        failure.addSuppressed(exception);
                    } else {
                        failure = exception;
                    }
                }
            }
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /// Returns whether this file system is open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Returns whether this file system is read-only.
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /// Returns the RAR archive path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the root directories in this file system.
    @Override
    public Iterable<Path> getRootDirectories() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return List.of(rootPath);
        }
    }

    /// Returns file stores for this file system.
    @Override
    public Iterable<FileStore> getFileStores() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return List.of(fileStore);
        }
    }

    /// Returns supported attribute view names.
    @Override
    public Set<String> supportedFileAttributeViews() {
        try (Operation ignored = beginReadOperation()) {
            return SUPPORTED_ATTRIBUTE_VIEWS;
        }
    }

    /// Returns a path inside this RAR file system.
    @Override
    public Path getPath(String first, String... more) {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return RarArkivoPath.of(this, first, more);
        }
    }

    /// Returns a path matcher for RAR paths.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(syntaxAndPattern, "syntaxAndPattern");
            int separator = syntaxAndPattern.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("Path matcher syntax must be syntax:pattern");
            }
            String syntax = syntaxAndPattern.substring(0, separator);
            String pattern = syntaxAndPattern.substring(separator + 1);
            if ("regex".equals(syntax)) {
                java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
                return path -> compiled.matcher(path.toString()).matches();
            }
            if ("glob".equals(syntax)) {
                PathMatcher matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                return path -> matcher.matches(Path.of(path.toString()));
            }
            throw new UnsupportedOperationException("Unsupported path matcher syntax: " + syntax);
        }
    }

    /// Returns the RAR user principal lookup service.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        try (Operation ignored = beginReadOperation()) {
            return RarPosixSupport.userPrincipalLookupService();
        }
    }

    /// Watch services are not supported by RAR file systems.
    @Override
    public java.nio.file.WatchService newWatchService() {
        throw new UnsupportedOperationException("RAR watch services are not supported");
    }

    /// Returns the archive URI used by generated entry URIs.
    @Nullable URI archiveUri() {
        return archiveUri;
    }

    /// Opens an input stream for an entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            validateReadOptions(Set.of(options));
            Node node = requireNode(path);
            if (node.directory()) {
                throw new FileSystemException(path.toString(), null, "RAR entry is a directory");
            }
            byte @Nullable @Unmodifiable [] content = node.content();
            if (content == null) {
                throw new IOException("RAR entry content is not available: " + path);
            }
            return manageInputStream(new ByteArrayInputStream(content));
        }
    }

    /// Opens a read-only byte channel for an entry.
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(attributes, "attributes");
            validateReadOptions(options);
            Node node = requireNode(path);
            if (node.directory()) {
                throw new FileSystemException(path.toString(), null, "RAR entry is a directory");
            }
            byte @Nullable @Unmodifiable [] content = node.content();
            if (content == null) {
                throw new IOException("RAR entry content is not available: " + path);
            }
            return manageReadChannel(new ByteArraySeekableByteChannel(content));
        }
    }

    /// Opens a directory stream for an entry.
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(filter, "filter");
            Node node = requireNode(directory);
            if (!node.directory()) {
                throw new FileSystemException(directory.toString(), null, "RAR entry is not a directory");
            }

            ArrayList<Path> accepted = new ArrayList<>();
            for (String childPath : node.children().values()) {
                Path child = rootPath.resolve(childPath);
                try {
                    if (filter.accept(child)) {
                        accepted.add(child);
                    }
                } catch (IOException exception) {
                    throw new DirectoryIteratorException(exception);
                }
            }
            return manageDirectoryStream(new ListDirectoryStream(accepted));
        }
    }

    /// Checks access to an entry.
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            requireNode(path);
            for (AccessMode mode : modes) {
                if (mode != AccessMode.READ) {
                    throw new ReadOnlyFileSystemException();
                }
            }
        }
    }

    /// Returns this archive's file store.
    public FileStore fileStore(Path path) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            requireNode(path);
            return fileStore;
        }
    }

    /// Reads a symbolic link target.
    public Path readSymbolicLink(Path link) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Node node = requireNode(link);
            String linkName = node.attributes().linkName();
            if (!node.attributes().isSymbolicLink() || linkName == null) {
                throw new NotLinkException(link.toString());
            }
            return getPath(linkName);
        }
    }

    /// Returns an attribute view for a path.
    public <V extends java.nio.file.attribute.FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(options, "options");
            if (type == BasicFileAttributeView.class) {
                return type.cast(new BasicView(path));
            }
            if (type == FileOwnerAttributeView.class) {
                return type.cast(new OwnerView(path));
            }
            if (type == PosixFileAttributeView.class) {
                return type.cast(new PosixView(path));
            }
            if (type == RarArkivoEntryAttributeView.class) {
                return type.cast(new RarView(path));
            }
            return null;
        }
    }

    /// Reads attributes for a path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(options, "options");
            RarEntryAttributes attributes = requireNode(path).attributes();
            if (type == BasicFileAttributes.class
                    || type == RarArkivoEntryAttributes.class
                    || type == PosixFileAttributes.class) {
                return type.cast(attributes);
            }
            throw new UnsupportedOperationException("Unsupported RAR attribute type: " + type.getName());
        }
    }

    /// Reads named attributes for a path.
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readAttributesLocked(path, attributes, options);
        }
    }

    /// Reads named attributes while the caller holds the shared operation lock.
    private Map<String, Object> readAttributesLocked(Path path, String attributes, LinkOption... options)
            throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(options, "options");
        RarEntryAttributes entryAttributes = requireNode(path).attributes();
        HashMap<String, Object> values = new HashMap<>();
        RequestedAttributes requestedAttributes = RequestedAttributes.parse(attributes);
        boolean all = requestedAttributes.contains("*");
        if (requestedAttributes.ownerView()) {
            putOwnerAttributes(values, entryAttributes, requestedAttributes, all);
        } else if (requestedAttributes.posixView()) {
            putPosixAttributes(values, entryAttributes, requestedAttributes, all);
        } else if (requestedAttributes.rarView()) {
            putRarAttributes(values, entryAttributes, requestedAttributes, all);
        } else {
            putBasicAttributes(values, entryAttributes, requestedAttributes, all);
        }
        return Collections.unmodifiableMap(values);
    }

    /// Adds selected basic attributes to the result map.
    private static void putBasicAttributes(
            Map<String, Object> values,
            BasicFileAttributes entryAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        if (all || requestedAttributes.contains("size")) {
            values.put("size", entryAttributes.size());
        }
        if (all || requestedAttributes.contains("lastModifiedTime")) {
            values.put("lastModifiedTime", entryAttributes.lastModifiedTime());
        }
        if (all || requestedAttributes.contains("lastAccessTime")) {
            values.put("lastAccessTime", entryAttributes.lastAccessTime());
        }
        if (all || requestedAttributes.contains("creationTime")) {
            values.put("creationTime", entryAttributes.creationTime());
        }
        if (all || requestedAttributes.contains("isDirectory")) {
            values.put("isDirectory", entryAttributes.isDirectory());
        }
        if (all || requestedAttributes.contains("isRegularFile")) {
            values.put("isRegularFile", entryAttributes.isRegularFile());
        }
        if (all || requestedAttributes.contains("isSymbolicLink")) {
            values.put("isSymbolicLink", entryAttributes.isSymbolicLink());
        }
        if (all || requestedAttributes.contains("isOther")) {
            values.put("isOther", entryAttributes.isOther());
        }
        if (all || requestedAttributes.contains("fileKey")) {
            values.put("fileKey", entryAttributes.fileKey());
        }
    }

    /// Adds selected RAR attributes to the result map.
    private static void putRarAttributes(
            Map<String, Object> values,
            RarEntryAttributes entryAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        putBasicAttributes(values, entryAttributes, requestedAttributes, all);
        if (all || requestedAttributes.contains("path")) {
            values.put("path", entryAttributes.path());
        }
        if (all || requestedAttributes.contains("hostOs")) {
            values.put("hostOs", entryAttributes.hostOs());
        }
        if (all || requestedAttributes.contains("fileAttributes")) {
            values.put("fileAttributes", entryAttributes.fileAttributes());
        }
        if (all || requestedAttributes.contains("compressionMethod")) {
            values.put("compressionMethod", entryAttributes.compressionMethod());
        }
        if (all || requestedAttributes.contains("packedSize")) {
            values.put("packedSize", entryAttributes.packedSize());
        }
        if (all || requestedAttributes.contains("unpackedSize")) {
            values.put("unpackedSize", entryAttributes.unpackedSize());
        }
        if (all || requestedAttributes.contains("dataCrc32")) {
            values.put("dataCrc32", entryAttributes.dataCrc32());
        }
        if (all || requestedAttributes.contains("blake2spHash")) {
            values.put("blake2spHash", entryAttributes.blake2spHash());
        }
        if (all || requestedAttributes.contains("isEncrypted")) {
            values.put("isEncrypted", entryAttributes.isEncrypted());
        }
        if (all || requestedAttributes.contains("continuesFromPreviousVolume")) {
            values.put("continuesFromPreviousVolume", entryAttributes.continuesFromPreviousVolume());
        }
        if (all || requestedAttributes.contains("continuesInNextVolume")) {
            values.put("continuesInNextVolume", entryAttributes.continuesInNextVolume());
        }
        if (all || requestedAttributes.contains("linkName")) {
            values.put("linkName", entryAttributes.linkName());
        }
        if (all || requestedAttributes.contains("redirectionType")) {
            values.put("redirectionType", entryAttributes.redirectionType());
        }
        if (all || requestedAttributes.contains("redirectionFlags")) {
            values.put("redirectionFlags", entryAttributes.redirectionFlags());
        }
        if (all || requestedAttributes.contains("redirectionTarget")) {
            values.put("redirectionTarget", entryAttributes.redirectionTarget());
        }
        if (all || requestedAttributes.contains("redirectionTargetDirectory")) {
            values.put("redirectionTargetDirectory", entryAttributes.redirectionTargetDirectory());
        }
        if (all || requestedAttributes.contains("userName")) {
            values.put("userName", entryAttributes.userName());
        }
        if (all || requestedAttributes.contains("groupName")) {
            values.put("groupName", entryAttributes.groupName());
        }
        if (all || requestedAttributes.contains("userId")) {
            values.put("userId", entryAttributes.userId());
        }
        if (all || requestedAttributes.contains("groupId")) {
            values.put("groupId", entryAttributes.groupId());
        }
    }

    /// Adds selected owner attributes to the result map.
    private static void putOwnerAttributes(
            Map<String, Object> values,
            PosixFileAttributes entryAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        if (all || requestedAttributes.contains("owner")) {
            values.put("owner", entryAttributes.owner());
        }
    }

    /// Adds selected POSIX attributes to the result map.
    private static void putPosixAttributes(
            Map<String, Object> values,
            PosixFileAttributes entryAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        putBasicAttributes(values, entryAttributes, requestedAttributes, all);
        putOwnerAttributes(values, entryAttributes, requestedAttributes, all);
        if (all || requestedAttributes.contains("group")) {
            values.put("group", entryAttributes.group());
        }
        if (all || requestedAttributes.contains("permissions")) {
            values.put("permissions", entryAttributes.permissions());
        }
    }

    /// Requires this file system to be open.
    private void ensureOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Returns a node for a path.
    private Node requireNode(Path path) throws IOException {
        ensureOpen();
        String normalizedPath = normalizedNodePath(path);
        Node node = nodes.get(normalizedPath);
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        return node;
    }

    /// Returns a normalized node path.
    private String normalizedNodePath(Path path) {
        RarArkivoPath rarPath = RarArkivoPath.require(path, this);
        RarArkivoPath normalized = (RarArkivoPath) rarPath.toAbsolutePath().normalize();
        return normalized.archivePath();
    }

    /// Validates that only read options are requested.
    private static void validateReadOptions(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("RAR file systems are read-only");
            }
        }
    }

    /// Reads all entry nodes from a RAR stream.
    private static Map<String, Node> readNodes(InputStream input) throws IOException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        Node root = new Node("", rootAttributes(), true, null);
        nodes.put("", root);

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(input)) {
            while (reader.next()) {
                RarEntryAttributes attributes = (RarEntryAttributes) reader.readAttributes(RarArkivoEntryAttributes.class);
                String path = normalizeEntryPath(attributes.path());
                ensureParents(nodes, path);
                RarEntryAttributes nodeAttributes = attributes;
                byte @Nullable @Unmodifiable [] content = null;
                if (isContentRedirection(attributes)) {
                    @Nullable String targetPath = normalizeRedirectionTargetPath(attributes.redirectionTarget());
                    Node target = targetPath != null ? nodes.get(targetPath) : null;
                    if (target != null && !target.directory() && target.content() != null) {
                        content = target.content();
                        nodeAttributes = attributesWithResolvedSize(attributes, content.length);
                    }
                } else if (attributes.isRegularFile() && attributes.compressionMethod() == 0
                        && !attributes.isEncrypted()
                        && !attributes.continuesFromPreviousVolume()) {
                    try (InputStream entryInput = reader.openInputStream()) {
                        content = entryInput.readAllBytes();
                    }
                }
                putNode(nodes, new Node(path, nodeAttributes, nodeAttributes.isDirectory(), content));
            }
        }
        return nodes;
    }

    /// Returns whether an entry redirects content to another archive member.
    private static boolean isContentRedirection(RarEntryAttributes attributes) {
        int redirectionType = attributes.redirectionType();
        return redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK
                || redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY;
    }

    /// Returns a normalized archive-local redirection target path, or `null` when the target is unusable.
    private static @Nullable String normalizeRedirectionTargetPath(@Nullable String target) throws IOException {
        if (target == null || target.startsWith("/") || target.startsWith("\\")
                || target.length() >= 2 && target.charAt(1) == ':') {
            return null;
        }

        boolean hasName = false;
        int start = 0;
        while (start <= target.length()) {
            int end = nextPathSeparator(target, start);
            String name = target.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    return null;
                }
                hasName = true;
            }
            start = end + 1;
        }
        return hasName ? normalizeEntryPath(target.replace('\\', '/')) : null;
    }

    /// Returns an attribute snapshot with a resolved regular file size.
    private static RarEntryAttributes attributesWithResolvedSize(RarEntryAttributes attributes, long size) {
        return new RarEntryAttributes(
                attributes.path(),
                attributes.isDirectory(),
                attributes.isSymbolicLink(),
                attributes.isOther(),
                attributes.linkName(),
                attributes.redirectionType(),
                attributes.redirectionFlags(),
                attributes.redirectionTarget(),
                attributes.userName(),
                attributes.groupName(),
                attributes.userId(),
                attributes.groupId(),
                attributes.hostOs(),
                attributes.fileAttributes(),
                attributes.compressionMethod(),
                attributes.packedSize(),
                size,
                attributes.dataCrc32(),
                attributes.blake2spHash(),
                attributes.isEncrypted(),
                attributes.continuesFromPreviousVolume(),
                attributes.continuesInNextVolume(),
                attributes.lastModifiedTime(),
                attributes.creationTime(),
                attributes.lastAccessTime()
        );
    }

    /// Adds implicit parent directory nodes.
    private static void ensureParents(Map<String, Node> nodes, String path) throws IOException {
        int separator = path.indexOf('/');
        while (separator >= 0) {
            String parent = path.substring(0, separator);
            if (!parent.isEmpty() && !nodes.containsKey(parent)) {
                ensureParents(nodes, parent);
                putNode(nodes, new Node(parent, syntheticDirectoryAttributes(parent), true, null));
            }
            separator = path.indexOf('/', separator + 1);
        }
    }

    /// Adds or replaces one node in the node map.
    private static void putNode(Map<String, Node> nodes, Node node) throws IOException {
        Node existing = nodes.get(node.path());
        if (existing != null && !(existing.syntheticDirectory() && node.directory())) {
            throw new IOException("Duplicate RAR entry path: " + node.path());
        }
        @Nullable LinkedHashMap<String, String> existingChildren = existing != null ? existing.children() : null;
        if (!node.path().isEmpty()) {
            Node parent = nodes.get(parentPath(node.path()));
            if (parent != null) {
                parent.children().put(fileName(node.path()), node.path());
            }
        }
        nodes.put(node.path(), node);
        if (existingChildren != null) {
            node.children().putAll(existingChildren);
        }
    }

    /// Normalizes an entry path into node map form.
    private static String normalizeEntryPath(String path) throws IOException {
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IOException("RAR entry is missing a path");
        }
        return normalized;
    }

    /// Returns the index of the next archive path separator, or the path length.
    private static int nextPathSeparator(String path, int start) {
        int forwardSlash = path.indexOf('/', start);
        int backslash = path.indexOf('\\', start);
        if (forwardSlash < 0) {
            return backslash >= 0 ? backslash : path.length();
        }
        if (backslash < 0) {
            return forwardSlash;
        }
        return Math.min(forwardSlash, backslash);
    }

    /// Returns the parent path for a normalized node path.
    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    /// Returns the final file name for a normalized node path.
    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /// Returns synthetic root attributes.
    private static RarEntryAttributes rootAttributes() {
        return syntheticDirectoryAttributes("/");
    }

    /// Returns synthetic directory attributes.
    private static RarEntryAttributes syntheticDirectoryAttributes(String path) {
        FileTime time = FileTime.fromMillis(0L);
        return new RarEntryAttributes(
                path,
                true,
                false,
                false,
                null,
                RarArkivoEntryAttributes.NO_REDIRECTION_TYPE,
                0L,
                null,
                null,
                null,
                RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE,
                RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE,
                RarArkivoEntryAttributes.HOST_OS_UNIX,
                040755L,
                0,
                0L,
                0L,
                RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE,
                null,
                false,
                false,
                false,
                time,
                time,
                time
        );
    }

    /// Stores parsed named attribute selection.
    ///
    /// @param view  the selected attribute view name
    /// @param names the selected attribute names
    @NotNullByDefault
    private record RequestedAttributes(String view, @Unmodifiable Set<String> names) {
        /// Parses a named attribute selection.
        private static RequestedAttributes parse(String attributes) {
            int separator = attributes.indexOf(':');
            String view = separator >= 0 ? attributes.substring(0, separator) : "basic";
            String names = separator >= 0 ? attributes.substring(separator + 1) : attributes;
            if (!"basic".equals(view) && !"owner".equals(view) && !"posix".equals(view) && !"rar".equals(view)) {
                throw new UnsupportedOperationException("Unsupported RAR attribute view: " + view);
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("RAR attribute names must not be empty");
            }
            if ("*".equals(names)) {
                return new RequestedAttributes(view, Set.of("*"));
            }
            return new RequestedAttributes(view, Set.of(names.split(",")));
        }

        /// Returns whether this request targets the RAR attribute view.
        private boolean rarView() {
            return "rar".equals(view);
        }

        /// Returns whether this request targets the owner attribute view.
        private boolean ownerView() {
            return "owner".equals(view);
        }

        /// Returns whether this request targets the POSIX attribute view.
        private boolean posixView() {
            return "posix".equals(view);
        }

        /// Returns whether the given attribute was requested.
        private boolean contains(String name) {
            return names.contains(name);
        }
    }

    /// Stores one RAR file system node.
    @NotNullByDefault
    private static final class Node {
        /// The normalized archive path.
        private final String path;

        /// The node attributes.
        private final RarEntryAttributes attributes;

        /// Whether this node is a directory.
        private final boolean directory;

        /// The cached stored file content, or `null` when unavailable.
        private final byte @Nullable @Unmodifiable [] content;

        /// Child node paths keyed by child name.
        private final LinkedHashMap<String, String> children = new LinkedHashMap<>();

        /// Creates one node.
        private Node(
                String path,
                RarEntryAttributes attributes,
                boolean directory,
                byte @Nullable @Unmodifiable [] content
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.attributes = Objects.requireNonNull(attributes, "attributes");
            this.directory = directory;
            this.content = content != null ? content.clone() : null;
        }

        /// Returns the normalized archive path.
        private String path() {
            return path;
        }

        /// Returns the node attributes.
        private RarEntryAttributes attributes() {
            return attributes;
        }

        /// Returns whether this node is a directory.
        private boolean directory() {
            return directory;
        }

        /// Returns the cached stored file content, or `null` when unavailable.
        private byte @Nullable @Unmodifiable [] content() {
            return content;
        }

        /// Returns whether this is an implicit directory.
        private boolean syntheticDirectory() {
            return directory && attributes.packedSize() == 0L && attributes.path().equals(path);
        }

        /// Returns mutable child path map while the file system index is being built.
        private LinkedHashMap<String, String> children() {
            return children;
        }
    }

    /// Implements a read-only basic attribute view.
    @NotNullByDefault
    private final class BasicView implements BasicFileAttributeView {
        /// The path whose attributes are exposed.
        private final Path path;

        /// Creates an attribute view.
        private BasicView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns this view's name.
        @Override
        public String name() {
            return "basic";
        }

        /// Reads this path's attributes.
        @Override
        public BasicFileAttributes readAttributes() throws IOException {
            return RarArkivoFileSystemImpl.this.readAttributes(path, BasicFileAttributes.class);
        }

        /// RAR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a read-only RAR attribute view.
    @NotNullByDefault
    private final class RarView implements RarArkivoEntryAttributeView {
        /// The path whose attributes are exposed.
        private final Path path;

        /// Creates a RAR attribute view.
        private RarView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Reads this path's RAR attributes.
        @Override
        public RarArkivoEntryAttributes readAttributes() throws IOException {
            return RarArkivoFileSystemImpl.this.readAttributes(path, RarArkivoEntryAttributes.class);
        }

        /// RAR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a read-only owner attribute view.
    @NotNullByDefault
    private final class OwnerView implements FileOwnerAttributeView {
        /// The path whose owner is exposed.
        private final Path path;

        /// Creates an owner attribute view.
        private OwnerView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns this view's name.
        @Override
        public String name() {
            return "owner";
        }

        /// Returns this path's owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return RarArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class).owner();
        }

        /// RAR attributes are read-only.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a read-only POSIX attribute view.
    @NotNullByDefault
    private final class PosixView implements PosixFileAttributeView {
        /// The path whose POSIX attributes are exposed.
        private final Path path;

        /// Creates a POSIX attribute view.
        private PosixView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns this view's name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads this path's POSIX attributes.
        @Override
        public PosixFileAttributes readAttributes() throws IOException {
            return RarArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class);
        }

        /// Returns this path's owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        /// RAR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }

        /// RAR attributes are read-only.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new ReadOnlyFileSystemException();
        }

        /// RAR attributes are read-only.
        @Override
        public void setGroup(GroupPrincipal group) {
            Objects.requireNonNull(group, "group");
            throw new ReadOnlyFileSystemException();
        }

        /// RAR attributes are read-only.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a simple RAR file store.
    @NotNullByDefault
    private final class RarFileStore extends FileStore {
        /// Returns the archive file store name.
        @Override
        public String name() {
            Path path = archivePath;
            return path != null ? path.toString() : "rar";
        }

        /// Returns the archive file store type.
        @Override
        public String type() {
            return "rar";
        }

        /// Returns whether this file store is read-only.
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /// Returns total space when known.
        @Override
        public long getTotalSpace() throws IOException {
            Path path = archivePath;
            return path != null ? Files.size(path) : 0L;
        }

        /// Returns unallocated space.
        @Override
        public long getUnallocatedSpace() {
            return 0L;
        }

        /// Returns usable space.
        @Override
        public long getUsableSpace() {
            return 0L;
        }

        /// Returns whether this store supports an attribute view.
        @Override
        public boolean supportsFileAttributeView(Class<? extends java.nio.file.attribute.FileAttributeView> type) {
            return type == BasicFileAttributeView.class
                    || type == FileOwnerAttributeView.class
                    || type == PosixFileAttributeView.class
                    || type == RarArkivoEntryAttributeView.class;
        }

        /// Returns whether this store supports an attribute view.
        @Override
        public boolean supportsFileAttributeView(String name) {
            return SUPPORTED_ATTRIBUTE_VIEWS.contains(name);
        }

        /// Returns a file store attribute view.
        @Override
        public <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(Class<V> type) {
            return null;
        }

        /// Returns a file store attribute.
        @Override
        public Object getAttribute(String attribute) throws IOException {
            return ArkivoFileStoreAttributes.get(this, attribute);
        }
    }

    /// Implements a directory stream over a fixed list.
    @NotNullByDefault
    private static final class ListDirectoryStream implements DirectoryStream<Path> {
        /// The paths exposed by this stream.
        private final @Unmodifiable List<Path> paths;

        /// Whether this stream is open.
        private boolean open = true;

        /// Creates a directory stream.
        private ListDirectoryStream(List<Path> paths) {
            this.paths = List.copyOf(paths);
        }

        /// Returns an iterator over paths.
        @Override
        public Iterator<Path> iterator() {
            if (!open) {
                throw new IllegalStateException("RAR directory stream is closed");
            }
            return paths.iterator();
        }

        /// Closes this stream.
        @Override
        public void close() {
            open = false;
        }
    }
}

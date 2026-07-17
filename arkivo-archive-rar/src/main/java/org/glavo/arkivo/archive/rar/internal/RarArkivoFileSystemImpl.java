// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.glavo.arkivo.archive.internal.FixedDirectoryStream;
import org.glavo.arkivo.archive.internal.StoredContentSupport;
import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributeView;
import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.glavo.arkivo.archive.rar.RarArkivoFileSystem;
import org.glavo.arkivo.archive.rar.RarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements a read-only RAR archive file system backed by an in-memory entry index.
@NotNullByDefault
public final class RarArkivoFileSystemImpl extends RarArkivoFileSystem {
    /// The internal NIO environment key for a password provider.
    private static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.rar", "passwordProvider", ArkivoPasswordProvider.class);
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS =
            Set.of("basic", "owner", "posix", "rar");

    /// The file system provider that owns this file system.
    private final RarArkivoFileSystemProvider provider;

    /// The source archive path, or `null` when this file system is backed by explicit volumes.
    private final @Nullable Path archivePath;

    /// The archive URI used by generated entry URIs, or `null` when backed by explicit volumes.
    private final @Nullable URI archiveUri;

    /// The repeatable volume source used for metadata scans and lazy entry decoding.
    private final ArkivoVolumeSource volumes;

    /// The immutable options reused by lazy entry decoders.
    private final ArchiveOptions options;

    /// The action invoked when this file system closes.
    private final Runnable closeAction;

    /// The synthetic root path.
    private final RarArkivoPath rootPath;

    /// The file store view for this archive.
    private final RarFileStore fileStore = new RarFileStore();

    /// Entry nodes by normalized archive path.
    private final @Unmodifiable Map<String, Node> nodes;

    /// The storage that owns cached stored-entry bodies.
    private final ArkivoEditStorage editStorage;

    /// Cached stored-entry bodies owned by this file system, tracked by identity for redirections.
    private final Set<ArkivoStoredContent> ownedContents;

    /// The lock protecting lazy content materialization and storage lifecycle state.
    private final Object contentLifecycleLock = new Object();

    /// Active channel counts for cached bodies, tracked by content identity.
    private final IdentityHashMap<ArkivoStoredContent, Integer> activeContentUseCounts = new IdentityHashMap<>();

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Whether the owned volume source has been closed.
    private boolean volumesClosed;

    /// Whether cached content and its owning storage have been closed.
    private boolean editStorageClosed;

    /// Whether the close action has completed.
    private boolean closeActionCompleted;

    /// Creates a RAR file system instance.
    private RarArkivoFileSystemImpl(
            RarArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable URI archiveUri,
            ArkivoVolumeSource volumes,
            ArkivoFileSystemThreadSafety threadSafety,
            Map<String, Node> nodes,
            ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            ArchiveOptions options,
            Runnable closeAction
    ) {
        super(threadSafety);
        if ((archivePath == null) != (archiveUri == null)) {
            throw new IllegalArgumentException("archivePath and archiveUri must both be present or absent");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = archivePath != null ? archivePath.toAbsolutePath().normalize() : null;
        this.archiveUri = archiveUri;
        this.volumes = Objects.requireNonNull(volumes, "volumes");
        this.options = Objects.requireNonNull(options, "options");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.nodes = Map.copyOf(nodes);
        this.editStorage = Objects.requireNonNull(editStorage, "editStorage");
        this.ownedContents = ownedContents;
        this.rootPath = RarArkivoPath.root(this);
    }

    /// Opens a RAR file system from an archive path.
    public static RarArkivoFileSystemImpl open(
            RarArkivoFileSystemProvider provider,
            Path archivePath,
            URI archiveUri,
            ArchiveOptions options,
            Runnable closeAction
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        ArkivoFileSystemThreadSafety threadSafety = options.getOrDefault(ArchiveEnvironmentOptions.THREAD_SAFETY, ArkivoFileSystemThreadSafety.CONCURRENT_READ
        );
        Set<OpenOption> openOptions = options.getOrDefault(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(StandardOpenOption.READ)
        );
        for (OpenOption option : openOptions) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("RAR archive file systems are read-only");
            }
        }

        List<Path> splitVolumePaths = RarSplitVolumePaths.discover(archivePath);
        ArkivoVolumeSource volumes = ArkivoVolumeSource.of(
                splitVolumePaths != null ? splitVolumePaths : List.of(archivePath)
        );
        ArkivoEditStorage editStorage = StoredContentSupport.selectStorage(options);
        Set<ArkivoStoredContent> ownedContents = StoredContentSupport.newIdentitySet();
        try {
            Map<String, Node> nodes;
            try (InputStream input = new RarVolumeInputStream(volumes)) {
                nodes = readNodes(input, options);
            }
            return new RarArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    volumes,
                    threadSafety,
                    nodes,
                    editStorage,
                    ownedContents,
                    options,
                    closeAction
            );
        } catch (IOException | RuntimeException | Error exception) {
            StoredContentSupport.closeAfterOpenFailure(editStorage, ownedContents, exception);
            closeSourceAfterOpenFailure(volumes, exception);
            throw exception;
        }
    }

    /// Opens a RAR file system from explicit archive volumes.
    public static RarArkivoFileSystemImpl open(
            RarArkivoFileSystemProvider provider,
            ArkivoVolumeSource volumes,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        ArkivoFileSystemThreadSafety threadSafety = options.getOrDefault(ArchiveEnvironmentOptions.THREAD_SAFETY, ArkivoFileSystemThreadSafety.CONCURRENT_READ
        );
        Set<OpenOption> openOptions = options.getOrDefault(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(StandardOpenOption.READ)
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

        ArkivoEditStorage editStorage;
        try {
            editStorage = StoredContentSupport.selectStorage(options);
        } catch (RuntimeException | Error exception) {
            try {
                volumes.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
        Set<ArkivoStoredContent> ownedContents = StoredContentSupport.newIdentitySet();
        try {
            Map<String, Node> nodes;
            try (InputStream input = new RarVolumeInputStream(volumes)) {
                nodes = readNodes(input, options);
            }
            return new RarArkivoFileSystemImpl(
                    provider,
                    null,
                    null,
                    volumes,
                    threadSafety,
                    nodes,
                    editStorage,
                    ownedContents,
                    options,
                    () -> {
                    }
            );
        } catch (IOException | RuntimeException | Error exception) {
            StoredContentSupport.closeAfterOpenFailure(editStorage, ownedContents, exception);
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
            if (!open
                    && isEditStorageClosed()
                    && volumesClosed
                    && closeActionCompleted) {
                return;
            }
            open = false;
            @Nullable Throwable failure = closeIndexedStorage(null);
            if (!volumesClosed) {
                try {
                    volumes.close();
                    volumesClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            if (!closeActionCompleted) {
                try {
                    closeAction.run();
                    closeActionCompleted = true;
                } catch (RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            throwFailure(failure);
        }
    }

    /// Returns whether cached content and its owning storage have completed cleanup.
    private boolean isEditStorageClosed() {
        synchronized (contentLifecycleLock) {
            return editStorageClosed;
        }
    }

    /// Closes inactive cached bodies and their storage, retaining active or failed resources for a later retry.
    private @Nullable Throwable closeIndexedStorage(@Nullable Throwable failure) {
        synchronized (contentLifecycleLock) {
            Iterator<ArkivoStoredContent> iterator = ownedContents.iterator();
            while (iterator.hasNext()) {
                ArkivoStoredContent content = iterator.next();
                if (activeContentUseCounts.containsKey(content)) {
                    continue;
                }
                try {
                    content.close();
                    iterator.remove();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            if (!editStorageClosed
                    && ownedContents.isEmpty()
                    && activeContentUseCounts.isEmpty()) {
                try {
                    editStorage.close();
                    editStorageClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                }
            }
        }
        return failure;
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
            return manageInputStream(Channels.newInputStream(openContentChannel(node)));
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
            return manageReadChannel(openContentChannel(node));
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
            return manageDirectoryStream(new FixedDirectoryStream<>(accepted));
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
            ArkivoFileSystemProviderSupport.AttributeViewPath viewPath =
                    ArkivoFileSystemProviderSupport.attributeViewPath(path, options);
            if (type == BasicFileAttributeView.class) {
                return type.cast(new BasicView(viewPath));
            }
            if (type == FileOwnerAttributeView.class) {
                return type.cast(new OwnerView(viewPath));
            }
            if (type == PosixFileAttributeView.class) {
                return type.cast(new PosixView(viewPath));
            }
            if (type == RarArkivoEntryAttributeView.class) {
                return type.cast(new RarView(viewPath));
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
    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure != null) {
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
            return failure;
        }
        return exception;
    }

    /// Throws the given failure while preserving its checked or unchecked type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Opens one independently positioned channel over a lazily materialized body.
    private SeekableByteChannel openContentChannel(Node node) throws IOException {
        synchronized (contentLifecycleLock) {
            ensureOpen();
            ArkivoStoredContent content = materializeContent(node, new LinkedHashSet<>());
            SeekableByteChannel channel = content.openChannel(Set.of(StandardOpenOption.READ));
            activeContentUseCounts.merge(content, 1, Integer::sum);
            return new CachedContentReadByteChannel(content, channel);
        }
    }

    /// Resolves or decodes one entry body while holding the content lifecycle lock.
    private ArkivoStoredContent materializeContent(Node node, Set<String> redirectionPath) throws IOException {
        @Nullable ArkivoStoredContent cached = node.content();
        if (cached != null) {
            return cached;
        }

        @Nullable String targetPath = node.redirectionTarget();
        if (targetPath != null) {
            if (!redirectionPath.add(node.path())) {
                throw new IOException("Cyclic RAR content redirection: " + node.path());
            }
            Node target = nodes.get(targetPath);
            if (target == null || target.directory()) {
                throw new IOException("RAR redirected entry target is unavailable: " + targetPath);
            }
            ArkivoStoredContent content = materializeContent(target, redirectionPath);
            node.setContent(content);
            return content;
        }
        if (!node.bodyReadable()) {
            throw new IOException("RAR entry content is not available: " + node.path());
        }

        try (InputStream input = new RarVolumeInputStream(volumes);
             RarArkivoStreamingReader reader = new RarArkivoStreamingReaderImpl(
                     input,
                     options.get(PASSWORD_PROVIDER),
                     options,
                     false
            )) {
            RarArkivoStreamingReaderImpl readerImpl = (RarArkivoStreamingReaderImpl) reader;
            while (reader.next()) {
                RarEntryAttributes attributes =
                        (RarEntryAttributes) reader.readAttributes(RarArkivoEntryAttributes.class);
                String path = normalizeEntryPath(attributes.path());
                if (!node.path().equals(path)) {
                    continue;
                }
                requireMatchingContentMetadata(node.attributes(), attributes);
                if (!readerImpl.isCurrentBodyReadable()) {
                    throw new IOException("RAR entry content is no longer readable: " + node.path());
                }
                ArkivoStoredContent content;
                try (InputStream entryInput = reader.openInputStream()) {
                    content = StoredContentSupport.storeInput(
                            editStorage,
                            ownedContents,
                            node.path(),
                            node.attributes().unpackedSize(),
                            entryInput
                    );
                }
                node.setContent(content);
                return content;
            }
        }
        throw new IOException("RAR entry disappeared while materializing content: " + node.path());
    }

    /// Rejects source content whose physical metadata no longer matches the indexed entry.
    private static void requireMatchingContentMetadata(
            RarEntryAttributes indexed,
            RarEntryAttributes current
    ) throws IOException {
        if (indexed.compressionMethod() != current.compressionMethod()
                || indexed.packedSize() != current.packedSize()
                || indexed.unpackedSize() != current.unpackedSize()
                || indexed.dataCrc32() != current.dataCrc32()
                || indexed.isEncrypted() != current.isEncrypted()
                || indexed.continuesFromPreviousVolume() != current.continuesFromPreviousVolume()
                || indexed.continuesInNextVolume() != current.continuesInNextVolume()) {
            throw new IOException("RAR entry changed after the file system index was created: " + indexed.path());
        }
    }

    /// Releases one cached-body channel and advances deferred storage cleanup after file system close.
    private @Nullable Throwable releaseContentUse(
            ArkivoStoredContent content,
            @Nullable Throwable failure
    ) {
        synchronized (contentLifecycleLock) {
            Integer count = activeContentUseCounts.get(content);
            if (count == null) {
                return appendFailure(failure, new IOException("RAR cached content use was not registered"));
            }
            if (count == 1) {
                activeContentUseCounts.remove(content);
            } else {
                activeContentUseCounts.put(content, count - 1);
            }
            return open ? failure : closeIndexedStorage(failure);
        }
    }

    /// Closes a volume source after file-system setup fails.
    private static void closeSourceAfterOpenFailure(ArkivoVolumeSource volumes, Throwable failure) {
        try {
            volumes.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
        }
    }
    /// Reads entry metadata without retaining decoded file bodies.
    private static Map<String, Node> readNodes(
            InputStream input,
            ArchiveOptions options
    ) throws IOException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        Node root = new Node("", rootAttributes(), true, false, null);
        nodes.put("", root);
        boolean encryptedContentAvailable = options.get(PASSWORD_PROVIDER) != null;

        try (RarArkivoStreamingReader reader = new RarArkivoStreamingReaderImpl(
                input,
                options.get(PASSWORD_PROVIDER),
                options,
                false
        )) {
            RarArkivoStreamingReaderImpl readerImpl = (RarArkivoStreamingReaderImpl) reader;
            while (reader.next()) {
                RarEntryAttributes attributes =
                        (RarEntryAttributes) reader.readAttributes(RarArkivoEntryAttributes.class);
                String path = normalizeEntryPath(attributes.path());
                ensureParents(nodes, path);
                @Nullable String redirectionTarget = isContentRedirection(attributes)
                        ? normalizeRedirectionTargetPath(attributes.redirectionTarget())
                        : null;
                boolean bodyReadable = redirectionTarget == null
                        && attributes.isRegularFile()
                        && readerImpl.isCurrentBodyReadable()
                        && (!attributes.isEncrypted() || encryptedContentAvailable)
                        && !attributes.continuesFromPreviousVolume();
                putNode(nodes, new Node(
                        path,
                        attributes,
                        attributes.isDirectory(),
                        bodyReadable,
                        redirectionTarget
                ));
            }
        }
        resolveRedirectionSizes(nodes);
        return nodes;
    }

    /// Resolves redirected entry sizes after every possible target has been indexed.
    private static void resolveRedirectionSizes(Map<String, Node> nodes) {
        for (int pass = 0; pass < nodes.size(); pass++) {
            boolean changed = false;
            for (Node node : nodes.values()) {
                @Nullable String targetPath = node.redirectionTarget();
                if (targetPath == null) {
                    continue;
                }
                Node target = nodes.get(targetPath);
                if (target == null || target.directory()) {
                    continue;
                }
                long targetSize = target.attributes().unpackedSize();
                if (targetSize != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                        && node.attributes().unpackedSize() != targetSize) {
                    node.setAttributes(attributesWithResolvedSize(node.attributes(), targetSize));
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
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
                putNode(nodes, new Node(parent, syntheticDirectoryAttributes(parent), true, false, null));
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

    /// Stores one RAR file system node and its lazily materialized content.
    @NotNullByDefault
    private static final class Node {
        /// The normalized archive path.
        private final String path;

        /// The node attributes, including any resolved redirection size.
        private RarEntryAttributes attributes;

        /// Whether this node is a directory.
        private final boolean directory;

        /// Whether this node has a directly decodable physical body.
        private final boolean bodyReadable;

        /// The normalized content-redirection target, or `null` for physical entries.
        private final @Nullable String redirectionTarget;

        /// The cached stored file content, or `null` before materialization or when unavailable.
        private @Nullable ArkivoStoredContent content;

        /// Child node paths keyed by child name.
        private final LinkedHashMap<String, String> children = new LinkedHashMap<>();

        /// Creates one indexed node.
        private Node(
                String path,
                RarEntryAttributes attributes,
                boolean directory,
                boolean bodyReadable,
                @Nullable String redirectionTarget
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.attributes = Objects.requireNonNull(attributes, "attributes");
            this.directory = directory;
            this.bodyReadable = bodyReadable;
            this.redirectionTarget = redirectionTarget;
        }

        /// Returns the normalized archive path.
        private String path() {
            return path;
        }

        /// Returns the node attributes.
        private RarEntryAttributes attributes() {
            return attributes;
        }

        /// Replaces attributes after resolving redirected content size.
        private void setAttributes(RarEntryAttributes attributes) {
            this.attributes = Objects.requireNonNull(attributes, "attributes");
        }

        /// Returns whether this node is a directory.
        private boolean directory() {
            return directory;
        }

        /// Returns whether this node has a directly decodable physical body.
        private boolean bodyReadable() {
            return bodyReadable;
        }

        /// Returns the normalized content-redirection target, or `null`.
        private @Nullable String redirectionTarget() {
            return redirectionTarget;
        }

        /// Returns the cached stored file content, or `null` before materialization.
        private @Nullable ArkivoStoredContent content() {
            return content;
        }

        /// Installs cached content after successful materialization.
        private void setContent(ArkivoStoredContent content) {
            this.content = Objects.requireNonNull(content, "content");
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
    /// Exposes one cached body through an independently positioned read-only channel.
    @NotNullByDefault
    private final class CachedContentReadByteChannel implements SeekableByteChannel {
        /// The cached body whose active-use count is released on close.
        private final ArkivoStoredContent content;

        /// The independently positioned stored-content channel.
        private final SeekableByteChannel channel;

        /// Whether this wrapper remains open.
        private boolean channelOpen = true;

        /// Creates one registered channel over cached content.
        private CachedContentReadByteChannel(ArkivoStoredContent content, SeekableByteChannel channel) {
            this.content = Objects.requireNonNull(content, "content");
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        /// Reads cached entry bytes.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureChannelOpen();
            return channel.read(destination);
        }

        /// Rejects writes to cached read-only content.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureChannelOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current cached-content position.
        @Override
        public long position() throws IOException {
            ensureChannelOpen();
            return channel.position();
        }

        /// Changes the current cached-content position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureChannelOpen();
            channel.position(newPosition);
            return this;
        }

        /// Returns the cached body size.
        @Override
        public long size() throws IOException {
            ensureChannelOpen();
            return channel.size();
        }

        /// Rejects truncation of cached read-only content.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureChannelOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether this wrapper and its stored-content channel remain open.
        @Override
        public boolean isOpen() {
            return channelOpen && channel.isOpen();
        }

        /// Closes this channel and releases its active cached-content use.
        @Override
        public void close() throws IOException {
            if (!channelOpen) {
                return;
            }
            channelOpen = false;
            @Nullable Throwable failure = null;
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            failure = releaseContentUse(content, failure);
            throwFailure(failure);
        }

        /// Requires this channel to remain open.
        private void ensureChannelOpen() throws ClosedChannelException {
            if (!channelOpen) {
                throw new ClosedChannelException();
            }
        }
    }
    /// Implements a read-only basic attribute view.
    @NotNullByDefault
    private final class BasicView implements BasicFileAttributeView {
        /// The path whose attributes are exposed.
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates an attribute view.
        private BasicView(ArkivoFileSystemProviderSupport.AttributeViewPath path) {
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
            return RarArkivoFileSystemImpl.this.readAttributes(path.resolve(), BasicFileAttributes.class);
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
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates a RAR attribute view.
        private RarView(ArkivoFileSystemProviderSupport.AttributeViewPath path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Reads this path's RAR attributes.
        @Override
        public RarArkivoEntryAttributes readAttributes() throws IOException {
            return RarArkivoFileSystemImpl.this.readAttributes(path.resolve(), RarArkivoEntryAttributes.class);
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
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates an owner attribute view.
        private OwnerView(ArkivoFileSystemProviderSupport.AttributeViewPath path) {
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
            return RarArkivoFileSystemImpl.this.readAttributes(path.resolve(), PosixFileAttributes.class).owner();
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
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates a POSIX attribute view.
        private PosixView(ArkivoFileSystemProviderSupport.AttributeViewPath path) {
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
            return RarArkivoFileSystemImpl.this.readAttributes(path.resolve(), PosixFileAttributes.class);
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

}

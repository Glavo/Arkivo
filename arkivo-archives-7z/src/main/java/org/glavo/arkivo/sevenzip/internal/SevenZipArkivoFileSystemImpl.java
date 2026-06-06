// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements the initial read-only 7z archive file system shell.
@NotNullByDefault
public final class SevenZipArkivoFileSystemImpl extends SevenZipArkivoFileSystem {
    /// The 7z provider that created this file system.
    private final SevenZipArkivoFileSystemProvider provider;

    /// The archive path, or `null` when this file system is backed by explicit volumes.
    private final @Nullable Path archivePath;

    /// The volume source, or `null` when this file system is backed by a single archive path.
    private final @Nullable ArkivoVolumeSource volumes;

    /// The parsed file system configuration.
    private final SevenZipArkivoFileSystemConfig config;

    /// The fixed 7z signature header.
    private final SevenZipSignatureHeader signatureHeader;

    /// Parsed entries by absolute path text.
    private final Map<String, SevenZipEntryMetadata> entries;

    /// Child paths by absolute parent path text.
    private final Map<String, List<Path>> children;

    /// The action invoked after this file system closes.
    private final @Nullable Runnable closeAction;

    /// The root path.
    private final SevenZipArkivoPath root;

    /// Whether this file system is open.
    private boolean open = true;

    /// Creates a 7z file system implementation.
    public SevenZipArkivoFileSystemImpl(
            SevenZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        this(provider, archivePath, volumes, config, null);
    }

    /// Creates a 7z file system implementation with a close action.
    public SevenZipArkivoFileSystemImpl(
            SevenZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            SevenZipArkivoFileSystemConfig config,
            @Nullable Runnable closeAction
    ) throws IOException {
        super(Objects.requireNonNull(config, "config").threadSafety());
        if (archivePath == null && volumes == null) {
            throw new IllegalArgumentException("archivePath or volumes must be provided");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = archivePath != null ? archivePath.toAbsolutePath().normalize() : null;
        this.volumes = volumes;
        this.config = config;
        this.closeAction = closeAction;
        this.root = SevenZipArkivoPath.root(this);
        SevenZipArchiveMetadata archiveMetadata = readArchiveMetadata();
        this.signatureHeader = archiveMetadata.signatureHeader();
        this.entries = entriesByPath(archiveMetadata.entries());
        this.children = childrenByPath(this.entries);
    }

    /// Returns the archive URI, or `null` when this file system is backed by explicit volumes.
    public @Nullable URI archiveUri() {
        return archivePath != null ? archivePath.toUri().normalize() : null;
    }

    /// Returns the file store exposed by this file system.
    public FileStore fileStore() {
        return SevenZipFileStore.READ_ONLY;
    }

    /// Returns the major 7z format version stored in the signature header.
    @Override
    public int majorVersion() {
        return signatureHeader.majorVersion();
    }

    /// Returns the minor 7z format version stored in the signature header.
    @Override
    public int minorVersion() {
        return signatureHeader.minorVersion();
    }

    /// Returns the offset of the next header relative to the first byte after the signature header.
    @Override
    public long nextHeaderOffset() {
        return signatureHeader.nextHeaderOffset();
    }

    /// Returns the size in bytes of the next header.
    @Override
    public long nextHeaderSize() {
        return signatureHeader.nextHeaderSize();
    }

    /// Returns the expected CRC-32 value of the next header bytes.
    @Override
    public long nextHeaderCrc32() {
        return signatureHeader.nextHeaderCrc32();
    }

    /// Returns the provider that created this file system.
    @Override
    public SevenZipArkivoFileSystemProvider provider() {
        return provider;
    }

    /// Closes this file system.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        try {
            if (volumes != null) {
                volumes.close();
            }
        } finally {
            if (closeAction != null) {
                closeAction.run();
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

    /// Returns the path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the root directories.
    @Override
    public @Unmodifiable Iterable<Path> getRootDirectories() {
        ensureOpen();
        return List.of(root);
    }

    /// Returns the file stores.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        ensureOpen();
        return List.of(SevenZipFileStore.READ_ONLY);
    }

    /// Returns supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        ensureOpen();
        return Set.of("basic", "7z");
    }

    /// Returns a path in this file system.
    @Override
    public Path getPath(String first, String... more) {
        ensureOpen();
        return SevenZipArkivoPath.of(this, first, more);
    }

    /// Returns a path matcher for this file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        ensureOpen();
        return ArkivoPathMatchers.create(syntaxAndPattern);
    }

    /// Returns no user principal lookup service.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("7z user principal lookup is not supported");
    }

    /// Returns no watch service.
    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("7z watch services are not supported");
    }

    /// Opens a byte channel for an entry.
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(attributes, "attributes");
        SevenZipEntryMetadata metadata = requireEntry(path);
        if (metadata.directory()) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        if (metadata.dataOffset() == SevenZipEntryMetadata.NO_DATA_OFFSET) {
            return new SevenZipByteChannel(new byte[0]);
        }
        if (SevenZipLZMADecoder.isCopy(metadata.methodId())) {
            return new SevenZipFileSliceChannel(openArchiveChannel(), metadata.dataOffset(), metadata.size());
        }
        if (SevenZipLZMADecoder.isLZMA(metadata.methodId())) {
            return new SevenZipByteChannel(readDecodedEntry(metadata));
        }
        if (SevenZipLZMADecoder.isLZMA2(metadata.methodId())) {
            return new SevenZipByteChannel(readDecodedEntry(metadata));
        }
        throw new UnsupportedOperationException("Unsupported 7z entry method");
    }

    /// Opens an input stream for an entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        Objects.requireNonNull(options, "options");
        SevenZipEntryMetadata metadata = requireEntry(path);
        if (metadata.directory()) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        if (metadata.dataOffset() == SevenZipEntryMetadata.NO_DATA_OFFSET) {
            return new ByteArrayInputStream(new byte[0]);
        }
        InputStream input = Channels.newInputStream(new SevenZipFileSliceChannel(
                openArchiveChannel(),
                metadata.dataOffset(),
                metadata.packedSize()
        ));
        if (SevenZipLZMADecoder.isCopy(metadata.methodId())) {
            return input;
        }
        if (SevenZipLZMADecoder.isLZMA(metadata.methodId())) {
            return SevenZipLZMADecoder.openLZMA(input, metadata.size(), metadata.coderProperties());
        }
        if (SevenZipLZMADecoder.isLZMA2(metadata.methodId())) {
            return SevenZipLZMADecoder.openLZMA2(input, metadata.coderProperties());
        }
        throw new UnsupportedOperationException("Unsupported 7z entry method");
    }

    /// Opens a directory stream.
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        Objects.requireNonNull(filter, "filter");
        String pathText = requireExistingPath(directory);
        SevenZipEntryMetadata metadata = entries.get(pathText);
        if (metadata != null && !metadata.directory()) {
            throw new java.nio.file.NotDirectoryException(directory.toString());
        }
        return new EntryDirectoryStream(children.getOrDefault(pathText, List.of()), filter);
    }

    /// Checks access to a path.
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        requireExistingPath(path);
        for (AccessMode mode : modes) {
            Objects.requireNonNull(mode, "mode");
            if (mode != AccessMode.READ) {
                throw new AccessDeniedException(path.toString());
            }
        }
    }

    /// Returns a file attribute view for a path.
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type) {
        Objects.requireNonNull(type, "type");
        try {
            requireExistingPath(path);
        } catch (IOException exception) {
            return null;
        }
        if (type == BasicFileAttributeView.class) {
            try {
                return type.cast(new BasicAttributeView(readAttributes(path, BasicFileAttributes.class)));
            } catch (IOException exception) {
                return null;
            }
        }
        return null;
    }

    /// Reads file attributes for a path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException {
        Objects.requireNonNull(type, "type");
        String pathText = requireExistingPath(path);
        if (type == BasicFileAttributes.class || type == SevenZipArkivoEntryAttributes.class) {
            SevenZipEntryMetadata metadata = entries.get(pathText);
            if (metadata == null && type == SevenZipArkivoEntryAttributes.class) {
                throw new UnsupportedOperationException("The synthetic 7z root has no 7z entry attributes");
            }
            return type.cast(metadata != null
                    ? new SevenZipEntryAttributes(metadata)
                    : SevenZipRootAttributes.INSTANCE);
        }
        throw new UnsupportedOperationException("Unsupported 7z attribute type: " + type.getName());
    }

    /// Reads named file attributes for a path.
    public Map<String, Object> readAttributes(Path path, String attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        String pathText = requireExistingPath(path);
        if (!attributes.startsWith("basic:") && !attributes.startsWith("7z:")) {
            throw new UnsupportedOperationException("Unsupported 7z attribute view: " + attributes);
        }
        SevenZipEntryMetadata metadata = entries.get(pathText);
        boolean directory = metadata == null || metadata.directory();
        BasicFileAttributes basicAttributes = metadata != null
                ? new SevenZipEntryAttributes(metadata)
                : SevenZipRootAttributes.INSTANCE;
        if (attributes.startsWith("7z:")) {
            if (metadata == null) {
                throw new UnsupportedOperationException("The synthetic 7z root has no 7z entry attributes");
            }
            return Map.of(
                    "path", metadata.path(),
                    "windowsAttributes", metadata.windowsAttributes(),
                    "creationTime", basicAttributes.creationTime(),
                    "lastAccessTime", basicAttributes.lastAccessTime(),
                    "lastModifiedTime", basicAttributes.lastModifiedTime(),
                    "size", metadata.size()
            );
        }
        return Map.of(
                "isDirectory", directory,
                "isRegularFile", !directory,
                "isSymbolicLink", false,
                "isOther", false,
                "size", metadata != null ? metadata.size() : 0L,
                "creationTime", basicAttributes.creationTime(),
                "lastAccessTime", basicAttributes.lastAccessTime(),
                "lastModifiedTime", basicAttributes.lastModifiedTime()
        );
    }

    /// Requires this file system to be open.
    private void ensureOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Requires a path to exist in this file system and returns its absolute path text.
    private String requireExistingPath(Path path) throws IOException {
        ensureOpen();
        if (!(path instanceof SevenZipArkivoPath sevenZipPath) || sevenZipPath.getFileSystem() != this) {
            throw new java.nio.file.ProviderMismatchException();
        }
        String pathText = sevenZipPath.toAbsolutePath().normalize().toString();
        if ("/".equals(pathText) || entries.containsKey(pathText)) {
            return pathText;
        }
        throw new NoSuchFileException(path.toString());
    }

    /// Returns metadata for a non-root entry.
    private SevenZipEntryMetadata requireEntry(Path path) throws IOException {
        String pathText = requireExistingPath(path);
        SevenZipEntryMetadata metadata = entries.get(pathText);
        if (metadata == null) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        return metadata;
    }

    /// Reads archive metadata from the archive storage.
    private SevenZipArchiveMetadata readArchiveMetadata() throws IOException {
        try (SeekableByteChannel channel = openArchiveChannel()) {
            return SevenZipHeaderReader.readArchiveMetadata(channel);
        }
    }

    /// Opens the underlying archive channel.
    private SeekableByteChannel openArchiveChannel() throws IOException {
        if (archivePath != null) {
            return Files.newByteChannel(archivePath, config.openOptions());
        }
        if (volumes != null) {
            SeekableByteChannel channel = volumes.openVolume(0);
            if (channel == null) {
                throw new IOException("7z volume source did not provide the first volume");
            }
            return channel;
        }
        throw new IOException("7z archive storage is not available");
    }

    /// Reads a decoded entry into memory for seekable channel access.
    private byte[] readDecodedEntry(SevenZipEntryMetadata metadata) throws IOException {
        if (metadata.size() > Integer.MAX_VALUE) {
            throw new IOException("7z entry is too large for seekable decoded access");
        }
        try (InputStream input = newInputStream(getPath(metadata.path()))) {
            return input.readAllBytes();
        }
    }

    /// Returns parsed entries keyed by normalized absolute path text.
    private Map<String, SevenZipEntryMetadata> entriesByPath(List<SevenZipEntryMetadata> parsedEntries) {
        LinkedHashMap<String, SevenZipEntryMetadata> result = new LinkedHashMap<>();
        for (SevenZipEntryMetadata metadata : parsedEntries) {
            String pathText = getPath(metadata.path()).toAbsolutePath().normalize().toString();
            if ("/".equals(pathText)) {
                throw new IllegalArgumentException("7z entries cannot use the root path as a file name");
            }
            if (result.put(pathText, metadata) != null) {
                throw new IllegalArgumentException("Duplicate 7z entry path: " + metadata.path());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Returns directory children keyed by normalized absolute parent path text.
    private Map<String, List<Path>> childrenByPath(Map<String, SevenZipEntryMetadata> entries) {
        LinkedHashMap<String, ArrayList<Path>> result = new LinkedHashMap<>();
        result.put("/", new ArrayList<>());
        for (String pathText : entries.keySet()) {
            Path path = getPath(pathText);
            Path parent = path.getParent();
            String parentText = parent != null ? parent.toString() : "/";
            result.computeIfAbsent(parentText, ignored -> new ArrayList<>()).add(path);
        }

        LinkedHashMap<String, List<Path>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayList<Path>> entry : result.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }

    /// Directory stream over parsed 7z child paths.
    @NotNullByDefault
    private static final class EntryDirectoryStream implements DirectoryStream<Path> {
        /// The child paths to expose.
        private final List<Path> children;

        /// The directory stream filter.
        private final DirectoryStream.Filter<? super Path> filter;

        /// Whether this stream is open.
        private boolean open = true;

        /// Creates a directory stream.
        private EntryDirectoryStream(List<Path> children, DirectoryStream.Filter<? super Path> filter) {
            this.children = children;
            this.filter = filter;
        }

        /// Returns a filtered iterator.
        @Override
        public Iterator<Path> iterator() {
            if (!open) {
                throw new IllegalStateException("Directory stream is closed");
            }
            open = false;
            ArrayList<Path> accepted = new ArrayList<>();
            for (Path child : children) {
                try {
                    if (filter.accept(child)) {
                        accepted.add(child);
                    }
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }
            return List.copyOf(accepted).iterator();
        }

        /// Closes this stream.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Basic attribute view for one 7z path.
    @NotNullByDefault
    private static final class BasicAttributeView implements BasicFileAttributeView {
        /// The attributes returned by this view.
        private final BasicFileAttributes attributes;

        /// Creates a basic attribute view.
        private BasicAttributeView(BasicFileAttributes attributes) {
            this.attributes = attributes;
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "basic";
        }

        /// Reads attributes.
        @Override
        public BasicFileAttributes readAttributes() {
            return attributes;
        }

        /// Rejects root time mutation.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }
    }
}

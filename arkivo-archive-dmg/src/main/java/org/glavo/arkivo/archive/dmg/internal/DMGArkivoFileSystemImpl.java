// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.dmg.DMGArchiveOptions;
import org.glavo.arkivo.archive.dmg.DMGArkivoFileSystem;
import org.glavo.arkivo.archive.dmg.DMGPartition;
import org.glavo.arkivo.archive.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.glavo.arkivo.archive.internal.ArkivoPathMatchers;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.archive.internal.FixedDirectoryStream;
import org.glavo.arkivo.archive.internal.PreservingUserPrincipalLookupService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements a read-only HFS Plus file system backed by one decoded DMG partition.
@NotNullByDefault
public final class DMGArkivoFileSystemImpl extends DMGArkivoFileSystem {
    /// The supported standard file attribute views.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS = Set.of("basic", "owner", "posix");

    /// The provider that owns this file system.
    private final DMGArkivoFileSystemProvider provider;

    /// The archive URI used by generated entry URIs, or `null` for an explicit source.
    private final @Nullable URI archiveUri;

    /// The parsed image owned by this file system.
    private final UDIFImage image;

    /// The indexed HFS Plus volume.
    private final HFSPlusVolume volume;

    /// The callback that removes provider registrations on close.
    private final Runnable closeAction;

    /// The synthetic root path.
    private final DMGArkivoPath rootPath;

    /// The single HFS Plus file store.
    private final DMGFileStore fileStore = new DMGFileStore();

    /// Whether the file system remains open.
    private volatile boolean open = true;

    /// Creates one parsed file system.
    private DMGArkivoFileSystemImpl(
            DMGArkivoFileSystemProvider provider,
            @Nullable URI archiveUri,
            UDIFImage image,
            HFSPlusVolume volume,
            DMGArchiveOptions.Read options,
            Runnable closeAction
    ) {
        super(options);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archiveUri = archiveUri;
        this.image = Objects.requireNonNull(image, "image");
        this.volume = Objects.requireNonNull(volume, "volume");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.rootPath = DMGArkivoPath.root(this);
    }

    /// Opens an owned repeatable DMG source and indexes its selected HFS Plus partition.
    ///
    /// @param provider the provider exposing the returned file system
    /// @param source the repeatable source whose ownership is transferred
    /// @param archiveUri the path-backed URI, or `null` for an explicit source
    /// @param options the read and partition-selection options
    /// @param closeAction the provider unregister callback
    /// @return a parsed read-only file system
    /// @throws IOException if the image, partition table, or HFS Plus metadata is invalid or unsupported
    public static DMGArkivoFileSystemImpl open(
            DMGArkivoFileSystemProvider provider,
            ArkivoSeekableChannelSource source,
            @Nullable URI archiveUri,
            DMGArchiveOptions.Read options,
            Runnable closeAction
    ) throws IOException {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(closeAction, "closeAction");
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(options.common().limits());
        UDIFImage image = UDIFImage.open(source, options.common(), tracker);
        try {
            DMGPartition partition = selectPartition(image, options.partitionIndex(), tracker);
            HFSPlusVolume volume = HFSPlusVolume.open(image, partition, tracker);
            return new DMGArkivoFileSystemImpl(provider, archiveUri, image, volume, options, closeAction);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                image.close();
            } catch (IOException | RuntimeException | Error closeException) {
                if (closeException != exception) {
                    exception.addSuppressed(closeException);
                }
            }
            throw exception;
        }
    }

    /// Selects the requested partition or the first direct HFS Plus partition.
    private static DMGPartition selectPartition(
            UDIFImage image,
            int requestedIndex,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        List<DMGPartition> partitions = image.partitions();
        if (requestedIndex != DMGArchiveOptions.AUTOMATIC_PARTITION_INDEX) {
            if (requestedIndex >= partitions.size()) {
                throw new IOException("DMG partition index is outside the discovered partition list: " + requestedIndex);
            }
            DMGPartition partition = partitions.get(requestedIndex);
            if (!HFSPlusVolume.matches(image, partition, tracker)) {
                throw new IOException("Selected DMG partition does not contain a direct HFS Plus or HFSX volume");
            }
            return partition;
        }
        for (DMGPartition partition : partitions) {
            if (HFSPlusVolume.matches(image, partition, tracker)) {
                return partition;
            }
        }
        for (DMGPartition partition : partitions) {
            if (partition.type() != null && (partition.type().equalsIgnoreCase("Apple_APFS")
                    || partition.type().equalsIgnoreCase("7C3457EF-0000-11AA-AA11-00306543ECAC"))) {
                throw new IOException("DMG contains APFS, which is not supported");
            }
        }
        throw new IOException("DMG contains no supported direct HFS Plus or HFSX partition");
    }

    /// Returns the provider that owns this file system.
    @Override
    public DMGArkivoFileSystemProvider provider() {
        return provider;
    }

    /// Closes the owned image and unregisters this file system.
    @Override
    public void close() throws IOException {
        try (CloseOperation ignored = beginCloseOperation()) {
            if (!open) {
                return;
            }
            open = false;
            @Nullable Throwable failure = null;
            try {
                image.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            try {
                closeAction.run();
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
            throwFailure(failure);
        }
    }

    /// Returns whether the file system and image remain open.
    @Override
    public boolean isOpen() {
        return open && image.isOpen();
    }

    /// Returns `true` because DMG file systems are read-only.
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /// Returns the slash path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the single root directory.
    @Override
    public Iterable<Path> getRootDirectories() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return List.of(rootPath);
        }
    }

    /// Returns the single HFS Plus file store.
    @Override
    public Iterable<FileStore> getFileStores() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return List.of(fileStore);
        }
    }

    /// Returns the supported standard attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        return SUPPORTED_ATTRIBUTE_VIEWS;
    }

    /// Creates an immutable path in this file system.
    @Override
    public Path getPath(String first, String... more) {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return DMGArkivoPath.of(this, first, more);
        }
    }

    /// Creates a slash-aware glob or regular-expression path matcher.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return ArkivoPathMatchers.create(syntaxAndPattern);
        }
    }

    /// Returns an archive-local principal lookup service preserving requested names.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return PreservingUserPrincipalLookupService.instance();
    }

    /// Rejects watch-service creation.
    @Override
    public java.nio.file.WatchService newWatchService() {
        throw new UnsupportedOperationException("DMG watch services are not supported");
    }

    /// Returns the selected HFS Plus partition descriptor.
    @Override
    public DMGPartition partition() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return volume.partition();
        }
    }

    /// Returns the path-backed archive URI, or `null` for an explicit source.
    @Nullable URI archiveUri() {
        return archiveUri;
    }

    /// Opens a read-only input stream for a regular file.
    ///
    /// @param path the entry path to open
    /// @param options the supported read and link options
    /// @return a new managed input stream positioned at the beginning of the data fork
    /// @throws IOException if the path cannot be resolved or its data fork cannot be opened
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        Objects.requireNonNull(options, "options");
        Set<OpenOption> optionSet = Set.copyOf(List.of(options));
        return Channels.newInputStream(newByteChannel(path, optionSet));
    }

    /// Opens a read-only seekable channel for a regular file.
    ///
    /// @param path the entry path to open
    /// @param options the supported read and link options
    /// @param attributes initial attributes, which must be empty for a read-only open
    /// @return a new managed channel positioned at the beginning of the data fork
    /// @throws IOException if the path cannot be resolved or its data fork cannot be opened
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(attributes, "attributes");
            if (attributes.length != 0) {
                throw new UnsupportedOperationException("Initial attributes are not supported by read-only DMG channels");
            }
            for (OpenOption option : options) {
                if (option != StandardOpenOption.READ && option != LinkOption.NOFOLLOW_LINKS) {
                    throw new ReadOnlyFileSystemException();
                }
            }
            HFSPlusNode node = requireNode(path);
            if (node.isDirectory()) {
                throw new FileSystemException(path.toString(), null, "Is a directory");
            }
            if (node.isSymbolicLink()) {
                throw new FileSystemException(path.toString(), null, "Symbolic link was not followed");
            }
            if (!node.isRegularFile()) {
                throw new FileSystemException(path.toString(), null, "Unsupported HFS Plus special file");
            }
            return manageReadChannel(volume.openDataFork(node));
        }
    }

    /// Opens a filtered immutable snapshot of a directory's immediate children.
    ///
    /// @param directory the directory path to enumerate
    /// @param filter the filter applied when the returned stream is iterated
    /// @return a new managed directory stream over an immutable child snapshot
    /// @throws IOException if the path cannot be resolved or is not a directory
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(filter, "filter");
            HFSPlusNode node = requireNode(directory);
            if (!node.isDirectory()) {
                throw new NotDirectoryException(directory.toString());
            }
            ArrayList<Path> entries = new ArrayList<>(node.childPaths().size());
            for (String child : node.childPaths()) {
                entries.add(getPath("/" + child));
            }
            return manageDirectoryStream(new FixedDirectoryStream<>(entries, filter));
        }
    }

    /// Checks entry existence and rejects write or execute access.
    ///
    /// @param path the entry path to check
    /// @param modes the requested access modes
    /// @throws IOException if the entry does not exist
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            requireNode(path);
            Objects.requireNonNull(modes, "modes");
            for (AccessMode mode : modes) {
                Objects.requireNonNull(mode, "mode");
                if (mode != AccessMode.READ) {
                    throw new ReadOnlyFileSystemException();
                }
            }
        }
    }

    /// Returns this archive's single file store after validating the path.
    ///
    /// @param path an existing entry path in this file system
    /// @return the HFS Plus file store
    /// @throws IOException if the entry does not exist
    public FileStore fileStore(Path path) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            requireNode(path);
            return fileStore;
        }
    }

    /// Reads a symbolic link's UTF-8 data-fork target.
    ///
    /// @param link the symbolic-link path
    /// @return the stored target as a path in this file system
    /// @throws IOException if the path is not a symbolic link or its target cannot be decoded
    public Path readSymbolicLink(Path link) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            HFSPlusNode node = requireNode(link);
            if (!node.isSymbolicLink()) {
                throw new NotLinkException(link.toString());
            }
            if (node.size() > Integer.MAX_VALUE) {
                throw new IOException("HFS Plus symbolic-link target is too large");
            }
            byte[] bytes = new byte[Math.toIntExact(node.size())];
            try (SeekableByteChannel channel = volume.openDataFork(node)) {
                ChannelIO.readFully(channel, 0L, ByteBuffer.wrap(bytes));
            }
            try {
                String target = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
                return getPath(target);
            } catch (CharacterCodingException exception) {
                throw new IOException("Invalid UTF-8 HFS Plus symbolic-link target", exception);
            }
        }
    }

    /// Returns a standard read-only attribute view bound to a path.
    ///
    /// @param <V> the requested attribute-view type
    /// @param path the entry path
    /// @param type the attribute-view class
    /// @param options the symbolic-link traversal options
    /// @return a bound basic, owner, or POSIX view, or {@code null} when `type` is unsupported
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
            return null;
        }
    }

    /// Reads a basic or POSIX attribute snapshot.
    ///
    /// @param <A> the requested attribute snapshot type
    /// @param path the resolved entry path
    /// @param type the basic or POSIX attribute class
    /// @param options accepted link options; the provider resolves them before dispatch
    /// @return the immutable stored attribute snapshot
    /// @throws IOException if the entry does not exist
    public <A extends BasicFileAttributes> A readAttributes(
            Path path,
            Class<A> type,
            LinkOption... options
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(options, "options");
            HFSPlusNode node = requireNode(path);
            if (type == BasicFileAttributes.class || type == PosixFileAttributes.class) {
                return type.cast(node);
            }
            throw new UnsupportedOperationException("Unsupported DMG attribute type: " + type.getName());
        }
    }

    /// Reads a named basic, owner, or POSIX attribute selection.
    ///
    /// @param path the resolved entry path
    /// @param attributes the view-qualified comma-separated attribute selection
    /// @param options accepted link options; the provider resolves them before dispatch
    /// @return an immutable map containing the selected attributes
    /// @throws IOException if the entry does not exist
    public Map<String, Object> readAttributes(
            Path path,
            String attributes,
            LinkOption... options
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(attributes, "attributes");
            Objects.requireNonNull(options, "options");
            HFSPlusNode node = requireNode(path);
            int separator = attributes.indexOf(':');
            String view = separator >= 0 ? attributes.substring(0, separator) : "basic";
            String namesText = separator >= 0 ? attributes.substring(separator + 1) : attributes;
            if (!SUPPORTED_ATTRIBUTE_VIEWS.contains(view) || namesText.isEmpty()) {
                throw new UnsupportedOperationException("Unsupported DMG attribute selection: " + attributes);
            }
            Set<String> names = "*".equals(namesText) ? Set.of("*") : Set.of(namesText.split(","));
            boolean all = names.contains("*");
            HashMap<String, Object> values = new HashMap<>();
            if ("owner".equals(view)) {
                put(values, names, all, "owner", node.owner());
            } else if ("posix".equals(view)) {
                putBasic(values, names, all, node);
                put(values, names, all, "owner", node.owner());
                put(values, names, all, "group", node.group());
                put(values, names, all, "permissions", node.permissions());
            } else {
                putBasic(values, names, all, node);
            }
            return Collections.unmodifiableMap(values);
        }
    }

    /// Adds requested basic attributes to a result map.
    private static void putBasic(Map<String, Object> values, Set<String> names, boolean all, HFSPlusNode node) {
        put(values, names, all, "lastModifiedTime", node.lastModifiedTime());
        put(values, names, all, "lastAccessTime", node.lastAccessTime());
        put(values, names, all, "creationTime", node.creationTime());
        put(values, names, all, "size", node.size());
        put(values, names, all, "isRegularFile", node.isRegularFile());
        put(values, names, all, "isDirectory", node.isDirectory());
        put(values, names, all, "isSymbolicLink", node.isSymbolicLink());
        put(values, names, all, "isOther", node.isOther());
        put(values, names, all, "fileKey", node.fileKey());
    }

    /// Adds one attribute when selected explicitly or by wildcard.
    private static void put(
            Map<String, Object> values,
            Set<String> names,
            boolean all,
            String name,
            Object value
    ) {
        if (all || names.contains(name)) {
            values.put(name, value);
        }
    }

    /// Resolves and validates one path as a normalized indexed node.
    private HFSPlusNode requireNode(Path path) throws IOException {
        DMGArkivoPath dmgPath = DMGArkivoPath.require(path, this);
        DMGArkivoPath normalized = (DMGArkivoPath) dmgPath.toAbsolutePath().normalize();
        String archivePath = normalized.archivePath();
        HFSPlusNode node = volume.node(archivePath);
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        return node;
    }

    /// Rejects operations after close.
    private void ensureOpen() {
        if (!isOpen()) {
            throw new java.nio.file.ClosedFileSystemException();
        }
    }

    /// Rethrows an accumulated close failure in its original category.
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

    /// Implements a read-only basic attribute view.
    @NotNullByDefault
    private final class BasicView implements BasicFileAttributeView {
        /// The lazily resolved view path.
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates a basic view.
        private BasicView(ArkivoFileSystemProviderSupport.AttributeViewPath path) {
            this.path = path;
        }

        /// Returns the basic view name.
        @Override
        public String name() {
            return "basic";
        }

        /// Reads the current basic attribute snapshot.
        @Override
        public BasicFileAttributes readAttributes() throws IOException {
            return DMGArkivoFileSystemImpl.this.readAttributes(path.resolve(), BasicFileAttributes.class);
        }

        /// Rejects timestamp mutation.
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
        /// The lazily resolved view path.
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates an owner view.
        private OwnerView(ArkivoFileSystemProviderSupport.AttributeViewPath path) {
            this.path = path;
        }

        /// Returns the owner view name.
        @Override
        public String name() {
            return "owner";
        }

        /// Reads the current owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return DMGArkivoFileSystemImpl.this.readAttributes(path.resolve(), PosixFileAttributes.class).owner();
        }

        /// Rejects owner mutation.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a read-only POSIX attribute view.
    @NotNullByDefault
    private final class PosixView implements PosixFileAttributeView {
        /// The lazily resolved view path.
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates a POSIX view.
        private PosixView(ArkivoFileSystemProviderSupport.AttributeViewPath path) {
            this.path = path;
        }

        /// Returns the POSIX view name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads the current POSIX attribute snapshot.
        @Override
        public PosixFileAttributes readAttributes() throws IOException {
            return DMGArkivoFileSystemImpl.this.readAttributes(path.resolve(), PosixFileAttributes.class);
        }

        /// Reads the current owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        /// Rejects timestamp mutation.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }

        /// Rejects owner mutation.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new ReadOnlyFileSystemException();
        }

        /// Rejects group mutation.
        @Override
        public void setGroup(GroupPrincipal group) {
            Objects.requireNonNull(group, "group");
            throw new ReadOnlyFileSystemException();
        }

        /// Rejects permission mutation.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements HFS Plus capacity reporting for the selected partition.
    @NotNullByDefault
    private final class DMGFileStore extends FileStore {
        /// Returns the selected partition's display name.
        @Override
        public String name() {
            String name = volume.partition().name();
            return name != null ? name : "dmg";
        }

        /// Returns the HFS Plus store type.
        @Override
        public String type() {
            return "hfsplus";
        }

        /// Returns `true` because the store is read-only.
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /// Returns the HFS Plus volume capacity.
        @Override
        public long getTotalSpace() {
            return volume.capacity();
        }

        /// Returns the recorded unallocated space.
        @Override
        public long getUnallocatedSpace() {
            return volume.unallocatedSpace();
        }

        /// Returns zero because no space is writable through this read-only provider.
        @Override
        public long getUsableSpace() {
            return 0L;
        }

        /// Returns whether a standard attribute view is supported.
        @Override
        public boolean supportsFileAttributeView(Class<? extends java.nio.file.attribute.FileAttributeView> type) {
            return type == BasicFileAttributeView.class
                    || type == FileOwnerAttributeView.class
                    || type == PosixFileAttributeView.class;
        }

        /// Returns whether a named standard attribute view is supported.
        @Override
        public boolean supportsFileAttributeView(String name) {
            return SUPPORTED_ATTRIBUTE_VIEWS.contains(name);
        }

        /// Returns `null` because no file-store-specific view is defined.
        @Override
        public <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(Class<V> type) {
            Objects.requireNonNull(type, "type");
            return null;
        }

        /// Reads a common file-store attribute.
        @Override
        public Object getAttribute(String attribute) throws IOException {
            return ArkivoFileStoreAttributes.get(this, attribute);
        }
    }
}

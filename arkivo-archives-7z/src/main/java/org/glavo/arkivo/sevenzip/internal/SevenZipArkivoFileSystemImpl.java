// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import java.util.Iterator;
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
        this.signatureHeader = readSignatureHeader();
        this.root = SevenZipArkivoPath.root(this);
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
        return Set.of("basic");
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
        checkAccess(path, AccessMode.READ);
        throw new UnsupportedOperationException("7z entry data reading is not implemented yet");
    }

    /// Opens an input stream for an entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        Objects.requireNonNull(options, "options");
        checkAccess(path, AccessMode.READ);
        throw new UnsupportedOperationException("7z entry data reading is not implemented yet");
    }

    /// Opens a directory stream.
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        Objects.requireNonNull(filter, "filter");
        requireRoot(directory);
        return new EmptyDirectoryStream();
    }

    /// Checks access to a path.
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        requireRoot(path);
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
            requireRoot(path);
        } catch (IOException exception) {
            return null;
        }
        if (type == BasicFileAttributeView.class) {
            return type.cast(new RootAttributeView());
        }
        return null;
    }

    /// Reads file attributes for a path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException {
        Objects.requireNonNull(type, "type");
        requireRoot(path);
        if (type == BasicFileAttributes.class) {
            return type.cast(SevenZipRootAttributes.INSTANCE);
        }
        throw new UnsupportedOperationException("Unsupported 7z attribute type: " + type.getName());
    }

    /// Reads named file attributes for a path.
    public Map<String, Object> readAttributes(Path path, String attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireRoot(path);
        if (!attributes.startsWith("basic:")) {
            throw new UnsupportedOperationException("Unsupported 7z attribute view: " + attributes);
        }
        return Map.of(
                "isDirectory", true,
                "isRegularFile", false,
                "isSymbolicLink", false,
                "isOther", false,
                "size", 0L
        );
    }

    /// Requires this file system to be open.
    private void ensureOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Requires a path to be the root path of this file system.
    private void requireRoot(Path path) throws IOException {
        ensureOpen();
        if (!(path instanceof SevenZipArkivoPath sevenZipPath) || sevenZipPath.getFileSystem() != this) {
            throw new java.nio.file.ProviderMismatchException();
        }
        if (!"/".equals(sevenZipPath.toAbsolutePath().normalize().toString())) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /// Reads the fixed 7z signature header from the archive storage.
    private SevenZipSignatureHeader readSignatureHeader() throws IOException {
        if (archivePath != null) {
            try (SeekableByteChannel channel = Files.newByteChannel(archivePath, config.openOptions())) {
                return SevenZipHeaderReader.readSignatureHeader(channel);
            }
        }
        if (volumes != null) {
            SeekableByteChannel channel = volumes.openVolume(0);
            if (channel == null) {
                throw new IOException("7z volume source did not provide the first volume");
            }
            try (channel) {
                return SevenZipHeaderReader.readSignatureHeader(channel);
            }
        }
        throw new IOException("7z archive storage is not available");
    }

    /// Empty directory stream used until 7z entry indexing is implemented.
    @NotNullByDefault
    private static final class EmptyDirectoryStream implements DirectoryStream<Path> {
        /// Whether this stream is open.
        private boolean open = true;

        /// Returns an empty iterator.
        @Override
        public Iterator<Path> iterator() {
            if (!open) {
                throw new IllegalStateException("Directory stream is closed");
            }
            open = false;
            return List.<Path>of().iterator();
        }

        /// Closes this stream.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Basic attribute view for the synthetic root directory.
    @NotNullByDefault
    private static final class RootAttributeView implements BasicFileAttributeView {
        /// Returns the attribute view name.
        @Override
        public String name() {
            return "basic";
        }

        /// Reads root attributes.
        @Override
        public BasicFileAttributes readAttributes() {
            return SevenZipRootAttributes.INSTANCE;
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

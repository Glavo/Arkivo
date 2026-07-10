// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.sevenzip.SevenZipArkivoEntryAttributeView;
import org.glavo.arkivo.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements a 7z archive file system backed by either a read index or a forward-only writer.
@NotNullByDefault
public final class SevenZipArkivoFileSystemImpl extends SevenZipArkivoFileSystem {
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS =
            Set.of("basic", "owner", "posix", "7z");

    /// The placeholder signature header exposed while a 7z write file system is open.
    private static final SevenZipSignatureHeader WRITE_MODE_SIGNATURE_HEADER =
            new SevenZipSignatureHeader(0, 4, 0L, 0L, 0L);

    /// The Unix symlink mode encoded in the high 16 bits of 7z Windows attributes.
    private static final int SYMBOLIC_LINK_WINDOWS_ATTRIBUTES = 0120777 << 16;

    /// The 7z provider that created this file system.
    private final SevenZipArkivoFileSystemProvider provider;

    /// The archive path, or `null` when backed by an explicit volume source or output target.
    private final @Nullable Path archivePath;

    /// The volume source, or `null` when backed by an archive path or output target.
    private final @Nullable ArkivoVolumeSource volumes;

    /// The explicit split output target, or `null` for path-backed or read-only file systems.
    private final @Nullable ArkivoVolumeTarget outputTarget;

    /// The explicit output target split size, or `NO_SPLIT_SIZE` when the configuration determines output behavior.
    private final long outputSplitSize;

    /// The parsed file system configuration.
    private final SevenZipArkivoFileSystemConfig config;

    /// The fixed 7z signature header.
    private final SevenZipSignatureHeader signatureHeader;

    /// The file store exposed by this file system.
    private final SevenZipFileStore fileStore;

    /// Parsed entries by absolute path text.
    private final Map<String, SevenZipEntryMetadata> entries;

    /// Child paths by absolute parent path text.
    private final Map<String, List<Path>> children;

    /// The writer used by forward-only write mode, or `null` in read mode.
    private final @Nullable SevenZOutputFile writer;

    /// The staged split output published after the writer closes, or `null` for direct path writes and read mode.
    private final @Nullable SevenZipSplitArchiveOutput splitOutput;

    /// Absolute archive paths emitted by forward-only write mode.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// Absolute archive directory paths emitted or implied by forward-only write mode.
    private final HashSet<String> writtenDirectories = new HashSet<>();

    /// The action invoked after this file system closes.
    private final @Nullable Runnable closeAction;

    /// The root path.
    private final SevenZipArkivoPath root;

    /// Whether this file system is open.
    private boolean open = true;

    /// Whether the owned volume source has been closed.
    private boolean volumesClosed;

    /// Whether the writer has been closed.
    private boolean writerClosed;

    /// Whether split output publication or rollback has completed.
    private boolean splitOutputClosed;

    /// Whether the close action has completed.
    private boolean closeActionCompleted;

    /// Whether a forward-only 7z entry body is currently open.
    private boolean entryOpen;

    /// Creates a 7z file system implementation.
    public SevenZipArkivoFileSystemImpl(
            SevenZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        this(
                provider,
                archivePath,
                volumes,
                null,
                SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE,
                config,
                null
        );
    }

    /// Creates a 7z file system implementation with a close action.
    public SevenZipArkivoFileSystemImpl(
            SevenZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            SevenZipArkivoFileSystemConfig config,
            @Nullable Runnable closeAction
    ) throws IOException {
        this(
                provider,
                archivePath,
                volumes,
                null,
                SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE,
                config,
                closeAction
        );
    }

    /// Creates a forward-only 7z file system over an explicit transactional volume target.
    public SevenZipArkivoFileSystemImpl(
            SevenZipArkivoFileSystemProvider provider,
            ArkivoVolumeTarget outputTarget,
            long splitSize,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        this(provider, null, null, outputTarget, splitSize, config, null);
    }

    /// Creates a 7z file system implementation with one input or output backing.
    private SevenZipArkivoFileSystemImpl(
            SevenZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            @Nullable ArkivoVolumeTarget outputTarget,
            long outputSplitSize,
            SevenZipArkivoFileSystemConfig config,
            @Nullable Runnable closeAction
    ) throws IOException {
        super(Objects.requireNonNull(config, "config").threadSafety());
        int backingCount = (archivePath != null ? 1 : 0)
                + (volumes != null ? 1 : 0)
                + (outputTarget != null ? 1 : 0);
        if (backingCount != 1) {
            throw new IllegalArgumentException("exactly one archive path, volume source, or volume target must be provided");
        }
        if (outputTarget != null && !config.archiveWritable()) {
            throw new IllegalArgumentException("7z volume targets require write archive options");
        }
        if (!config.archiveWritable()
                && config.splitSize() != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            throw new IllegalArgumentException("7z splitSize requires write archive options");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = archivePath != null ? archivePath.toAbsolutePath().normalize() : null;
        this.volumes = volumes;
        this.outputTarget = outputTarget;
        this.outputSplitSize = outputSplitSize;
        this.config = config;
        this.closeAction = closeAction;
        this.root = SevenZipArkivoPath.root(this);
        this.writtenDirectories.add("/");
        if (config.archiveWritable()) {
            validateWriteFeatures();
            @Nullable SevenZOutputFile openedWriter = null;
            @Nullable SevenZipSplitArchiveOutput openedSplitOutput = null;
            try {
                WriterResources writerResources = openArchiveWriter();
                openedWriter = writerResources.writer();
                openedSplitOutput = writerResources.splitOutput();
                openedWriter.setContentCompression(SevenZMethod.COPY);
            } catch (IOException | RuntimeException | Error exception) {
                closeWriterAfterConstructionFailure(exception, openedWriter);
                closeSplitOutputAfterConstructionFailure(exception, openedSplitOutput);
                closeAfterConstructionFailure(exception);
                throw exception;
            }
            this.writer = openedWriter;
            this.splitOutput = openedSplitOutput;
            this.fileStore = SevenZipFileStore.WRITABLE;
            this.signatureHeader = WRITE_MODE_SIGNATURE_HEADER;
            this.entries = Map.of();
            this.children = Map.of("/", List.of());
        } else {
            this.writer = null;
            this.splitOutput = null;
            this.fileStore = SevenZipFileStore.READ_ONLY;
            SevenZipArchiveMetadata archiveMetadata;
            try {
                archiveMetadata = readArchiveMetadata();
            } catch (IOException | RuntimeException | Error exception) {
                closeAfterConstructionFailure(exception);
                throw exception;
            }
            this.signatureHeader = archiveMetadata.signatureHeader();
            this.entries = entriesByPath(archiveMetadata.entries());
            this.children = childrenByPath(this.entries);
        }
    }

    /// Releases a partially opened 7z writer after construction fails.
    private static void closeWriterAfterConstructionFailure(
            Throwable failure,
            @Nullable SevenZOutputFile openedWriter
    ) {
        if (openedWriter != null) {
            try {
                openedWriter.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Rolls back partially opened split output after construction fails.
    private static void closeSplitOutputAfterConstructionFailure(
            Throwable failure,
            @Nullable SevenZipSplitArchiveOutput openedSplitOutput
    ) {
        if (openedSplitOutput != null) {
            try {
                openedSplitOutput.rollback();
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Releases constructor-owned resources after archive metadata loading fails.
    private void closeAfterConstructionFailure(Throwable failure) {
        if (volumes != null) {
            try {
                volumes.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
        }
        if (closeAction != null) {
            try {
                closeAction.run();
            } catch (RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Returns the archive URI, or `null` when backed by an explicit volume source or output target.
    public @Nullable URI archiveUri() {
        return archivePath != null ? archivePath.toUri().normalize() : null;
    }

    /// Returns the file store exposed by this file system.
    public FileStore fileStore() {
        return fileStore;
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
        if (!open && writerClosed && splitOutputClosed && volumesClosed && closeActionCompleted) {
            return;
        }
        open = false;
        @Nullable Throwable failure = null;
        if (!writerClosed) {
            try {
                if (writer != null) {
                    writer.close();
                }
                writerClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
        }
        if (!splitOutputClosed) {
            try {
                if (splitOutput != null) {
                    if (failure == null) {
                        splitOutput.commit();
                    } else {
                        splitOutput.rollback();
                    }
                }
                splitOutputClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
        if (!volumesClosed) {
            try {
                if (volumes != null) {
                    volumes.close();
                }
                volumesClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
        if (!closeActionCompleted) {
            try {
                if (closeAction != null) {
                    closeAction.run();
                }
                closeActionCompleted = true;
            } catch (RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
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

    /// Returns whether this file system is open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Returns whether this file system is read-only.
    @Override
    public boolean isReadOnly() {
        return writer == null;
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
        return List.of(fileStore);
    }

    /// Returns supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        ensureOpen();
        return SUPPORTED_ATTRIBUTE_VIEWS;
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

    /// Returns the user principal lookup service for synthesized 7z principals.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        ensureOpen();
        return SevenZipPrincipalSupport.userPrincipalLookupService();
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
        Objects.requireNonNull(attributes, "attributes");
        if (!isReadOnly() && requestsWrite(options)) {
            return new WritableEntryByteChannel(newOutputStream(path, options, attributes));
        }

        requireReadableFileSystem();
        requireReadOnlyOptions(options);
        SevenZipEntryMetadata metadata = requireEntry(path);
        if (metadata.directory()) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        if (metadata.dataOffset() == SevenZipEntryMetadata.NO_DATA_OFFSET) {
            return new SevenZipByteChannel(new byte[0]);
        }
        if (metadata.method().isCopyOnly()) {
            SeekableByteChannel channel =
                    new SevenZipFileSliceChannel(openArchiveChannel(), metadata.dataOffset(), metadata.size());
            if (metadata.crc32() != SevenZipEntryMetadata.UNKNOWN_CRC32) {
                return new SevenZipCrc32ByteChannel(channel, metadata.size(), metadata.crc32());
            }
            if (metadata.packedCrc32() != SevenZipEntryMetadata.UNKNOWN_CRC32) {
                return new SevenZipCrc32ByteChannel(
                        channel,
                        metadata.size(),
                        metadata.packedCrc32(),
                        "7z packed stream data does not match CRC-32"
                );
            }
            return channel;
        }
        return new SevenZipByteChannel(readDecodedEntry(metadata));
    }

    /// Opens an output stream for a new forward-only file entry.
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return newOutputStream(path, Set.of(options));
    }

    /// Opens an output stream for a new forward-only file entry.
    private OutputStream newOutputStream(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        validateEntryWriteOptions(options);
        int windowsAttributes = initialWindowsAttributes(false, false, attributes);
        String pathText = prepareWritableEntry(path, false);
        beginWritableEntry(pathText, false, windowsAttributes);
        return new WrittenEntryOutputStream(pathText);
    }

    /// Creates a new forward-only directory entry.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        int windowsAttributes = initialWindowsAttributes(true, false, attributes);
        String pathText = prepareWritableEntry(directory, true);
        beginWritableEntry(pathText, true, windowsAttributes);
        closeWritableEntry(pathText, true);
    }

    /// Creates a new forward-only symbolic link entry.
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        int windowsAttributes = initialWindowsAttributes(false, true, attributes);
        String pathText = prepareWritableEntry(link, false);
        beginWritableEntry(pathText, false, windowsAttributes);
        byte[] targetBytes = archivePathText(target).getBytes(StandardCharsets.UTF_8);
        requireWriter().write(targetBytes, 0, targetBytes.length);
        closeWritableEntry(pathText, false);
    }

    /// Opens an input stream for an entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        requireReadableFileSystem();
        requireReadOnlyOptions(options);
        SevenZipEntryMetadata metadata = requireEntry(path);
        if (metadata.directory()) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        if (metadata.dataOffset() == SevenZipEntryMetadata.NO_DATA_OFFSET) {
            return new ByteArrayInputStream(new byte[0]);
        }
        SeekableByteChannel packedChannel = new SevenZipFileSliceChannel(
                openArchiveChannel(),
                metadata.dataOffset(),
                metadata.packedSize()
        );
        if (metadata.packedCrc32() != SevenZipEntryMetadata.UNKNOWN_CRC32) {
            packedChannel = new SevenZipCrc32ByteChannel(
                    packedChannel,
                    metadata.packedSize(),
                    metadata.packedCrc32(),
                    "7z packed stream data does not match CRC-32"
            );
        }
        InputStream input = Channels.newInputStream(packedChannel);
        InputStream decoded = input;
        boolean completed = false;
        Throwable failure = null;
        try {
            boolean skipDecodedOffset;
            if (metadata.method().isCopyOnly()) {
                decoded = input;
                skipDecodedOffset = false;
            } else {
                decoded = SevenZipLZMADecoder.openFolder(
                        input,
                        metadata.method(),
                        checkedDecodedLimit(metadata),
                        config.passwordProvider()
                );
                skipDecodedOffset = true;
            }
            if (skipDecodedOffset && metadata.decodedOffset() > 0) {
                decoded.skipNBytes(metadata.decodedOffset());
            }
            completed = true;
            return new SevenZipBoundedInputStream(decoded, metadata.size(), metadata.crc32());
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (!completed) {
                try {
                    decoded.close();
                } catch (IOException | RuntimeException | Error exception) {
                    if (failure != null) {
                        failure.addSuppressed(exception);
                    } else {
                        throw exception;
                    }
                }
            }
        }
    }

    /// Opens a directory stream.
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        requireReadableFileSystem();
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
        if (!isReadOnly()) {
            checkWritableAccess(path, modes);
            return;
        }
        requireExistingPath(path);
        for (AccessMode mode : modes) {
            Objects.requireNonNull(mode, "mode");
            if (mode != AccessMode.READ) {
                throw new AccessDeniedException(path.toString());
            }
        }
    }

    /// Reads a symbolic link target.
    public Path readSymbolicLink(Path link) throws IOException {
        requireReadableFileSystem();
        String pathText = requireExistingPath(link);
        SevenZipEntryMetadata metadata = entries.get(pathText);
        if (metadata == null || !SevenZipPosixSupport.isSymbolicLink(metadata.windowsAttributes())) {
            throw new NotLinkException(link.toString());
        }
        return getPath(new String(readDecodedEntry(metadata), StandardCharsets.UTF_8));
    }

    /// Returns a file attribute view for a path.
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type) {
        Objects.requireNonNull(type, "type");
        if (!isReadOnly()) {
            return null;
        }
        String pathText;
        try {
            pathText = requireExistingPath(path);
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
        if (type == FileOwnerAttributeView.class) {
            try {
                return type.cast(new OwnerAttributeView(readAttributes(path, PosixFileAttributes.class)));
            } catch (IOException exception) {
                return null;
            }
        }
        if (type == PosixFileAttributeView.class) {
            try {
                return type.cast(new PosixAttributeView(readAttributes(path, PosixFileAttributes.class)));
            } catch (IOException exception) {
                return null;
            }
        }
        if (type == SevenZipArkivoEntryAttributeView.class && entries.containsKey(pathText)) {
            try {
                return type.cast(new SevenZipAttributeView(readAttributes(path, SevenZipArkivoEntryAttributes.class)));
            } catch (IOException exception) {
                return null;
            }
        }
        return null;
    }

    /// Reads file attributes for a path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException {
        requireReadableFileSystem();
        Objects.requireNonNull(type, "type");
        String pathText = requireExistingPath(path);
        if (type == BasicFileAttributes.class
                || type == SevenZipArkivoEntryAttributes.class
                || type == PosixFileAttributes.class) {
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
        requireReadableFileSystem();
        Objects.requireNonNull(attributes, "attributes");
        String pathText = requireExistingPath(path);
        SevenZipEntryMetadata metadata = entries.get(pathText);
        BasicFileAttributes basicAttributes = metadata != null
                ? new SevenZipEntryAttributes(metadata)
                : SevenZipRootAttributes.INSTANCE;
        PosixFileAttributes posixAttributes = (PosixFileAttributes) basicAttributes;
        RequestedAttributes requestedAttributes = RequestedAttributes.parse(attributes);
        HashMap<String, Object> values = new HashMap<>();
        boolean all = requestedAttributes.contains("*");
        if (requestedAttributes.ownerView()) {
            putOwnerAttributes(values, posixAttributes, requestedAttributes, all);
        } else if (requestedAttributes.posixView()) {
            putPosixAttributes(values, posixAttributes, requestedAttributes, all);
        } else if (requestedAttributes.sevenZipView()) {
            if (metadata == null) {
                throw new UnsupportedOperationException("The synthetic 7z root has no 7z entry attributes");
            }
            putSevenZipAttributes(values, basicAttributes, metadata, requestedAttributes, all);
        } else {
            putBasicAttributes(values, basicAttributes, requestedAttributes, all);
        }
        return Collections.unmodifiableMap(values);
    }

    /// Adds selected basic attributes to the result map.
    private static void putBasicAttributes(
            Map<String, Object> values,
            BasicFileAttributes basicAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        if (all || requestedAttributes.contains("size")) {
            values.put("size", basicAttributes.size());
        }
        if (all || requestedAttributes.contains("creationTime")) {
            values.put("creationTime", basicAttributes.creationTime());
        }
        if (all || requestedAttributes.contains("lastAccessTime")) {
            values.put("lastAccessTime", basicAttributes.lastAccessTime());
        }
        if (all || requestedAttributes.contains("lastModifiedTime")) {
            values.put("lastModifiedTime", basicAttributes.lastModifiedTime());
        }
        if (all || requestedAttributes.contains("isDirectory")) {
            values.put("isDirectory", basicAttributes.isDirectory());
        }
        if (all || requestedAttributes.contains("isRegularFile")) {
            values.put("isRegularFile", basicAttributes.isRegularFile());
        }
        if (all || requestedAttributes.contains("isSymbolicLink")) {
            values.put("isSymbolicLink", basicAttributes.isSymbolicLink());
        }
        if (all || requestedAttributes.contains("isOther")) {
            values.put("isOther", basicAttributes.isOther());
        }
        if (all || requestedAttributes.contains("fileKey")) {
            values.put("fileKey", basicAttributes.fileKey());
        }
    }

    /// Adds selected owner attributes to the result map.
    private static void putOwnerAttributes(
            Map<String, Object> values,
            PosixFileAttributes posixAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        if (all || requestedAttributes.contains("owner")) {
            values.put("owner", posixAttributes.owner());
        }
    }

    /// Adds selected POSIX attributes to the result map.
    private static void putPosixAttributes(
            Map<String, Object> values,
            PosixFileAttributes posixAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        putBasicAttributes(values, posixAttributes, requestedAttributes, all);
        putOwnerAttributes(values, posixAttributes, requestedAttributes, all);
        if (all || requestedAttributes.contains("group")) {
            values.put("group", posixAttributes.group());
        }
        if (all || requestedAttributes.contains("permissions")) {
            values.put("permissions", posixAttributes.permissions());
        }
    }

    /// Adds selected 7z attributes to the result map.
    private static void putSevenZipAttributes(
            Map<String, Object> values,
            BasicFileAttributes basicAttributes,
            SevenZipEntryMetadata metadata,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        putBasicAttributes(values, basicAttributes, requestedAttributes, all);
        if (all || requestedAttributes.contains("path")) {
            values.put("path", metadata.path());
        }
        if (all || requestedAttributes.contains("windowsAttributes")) {
            values.put("windowsAttributes", metadata.windowsAttributes());
        }
        if (all || requestedAttributes.contains("unixMode")) {
            values.put("unixMode", SevenZipPosixSupport.unixMode(metadata.windowsAttributes()));
        }
    }

    /// Requires this file system to be open.
    private void ensureOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Validates 7z write-mode backing and feature combinations.
    private void validateWriteFeatures() {
        if (volumes != null) {
            throw new UnsupportedOperationException("7z volume sources cannot be opened with write archive options");
        }
        if (outputTarget != null) {
            if (outputSplitSize <= 0) {
                throw new IllegalArgumentException("splitSize must be positive");
            }
            if (config.splitSize() != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
                throw new IllegalArgumentException("Explicit 7z volume targets must provide splitSize separately");
            }
        } else if (config.splitSize() != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            SevenZipSplitVolumePaths.requireFirstVolumePath(
                    Objects.requireNonNull(archivePath, "archivePath")
            );
        }
        if (config.passwordProvider() != null) {
            throw new UnsupportedOperationException("7z encrypted archive writes are not supported");
        }
        if (config.encryptHeaders()) {
            throw new UnsupportedOperationException("7z encrypted header writes are not supported");
        }
    }

    /// Opens the underlying archive writer and optional split publisher.
    private WriterResources openArchiveWriter() throws IOException {
        @Nullable ArkivoVolumeTarget target = outputTarget;
        long splitSize = outputSplitSize;
        if (target == null && config.splitSize() != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            Path currentArchivePath = Objects.requireNonNull(archivePath, "archivePath");
            SevenZipPathVolumeTarget pathTarget =
                    new SevenZipPathVolumeTarget(currentArchivePath, config.openOptions());
            pathTarget.validateTargetOptions();
            target = pathTarget;
            splitSize = config.splitSize();
        }
        if (target != null) {
            SevenZipSplitArchiveOutput splitOutput = SevenZipSplitArchiveOutput.open(target, splitSize);
            return new WriterResources(splitOutput.writer(), splitOutput);
        }

        Path currentArchivePath = Objects.requireNonNull(archivePath, "archivePath");
        SeekableByteChannel channel = Files.newByteChannel(currentArchivePath, config.openOptions());
        try {
            return new WriterResources(new SevenZOutputFile(channel), null);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Stores an opened archive writer and its optional split publisher.
    ///
    /// @param writer the seekable 7z archive writer
    /// @param splitOutput the split publisher, or `null` for direct path output
    @NotNullByDefault
    private record WriterResources(
            SevenZOutputFile writer,
            @Nullable SevenZipSplitArchiveOutput splitOutput
    ) {
        /// Creates one writer resource set.
        private WriterResources {
            Objects.requireNonNull(writer, "writer");
        }
    }

    /// Requires this file system to be in read mode.
    private void requireReadableFileSystem() {
        ensureOpen();
        if (!isReadOnly()) {
            throw new UnsupportedOperationException("Forward-only 7z write file systems do not expose reads");
        }
    }

    /// Requires this file system to be in write mode.
    private void requireWritableFileSystem() {
        ensureOpen();
        if (isReadOnly()) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Returns the writer for write mode.
    private SevenZOutputFile requireWriter() {
        SevenZOutputFile currentWriter = writer;
        if (currentWriter == null) {
            throw new ReadOnlyFileSystemException();
        }
        return currentWriter;
    }

    /// Returns whether entry open options request write access.
    private static boolean requestsWrite(Set<? extends OpenOption> options) {
        Objects.requireNonNull(options, "options");
        return options.contains(StandardOpenOption.WRITE)
                || options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW)
                || options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                || options.contains(StandardOpenOption.APPEND);
    }

    /// Validates options for a forward-only entry write.
    private static void validateEntryWriteOptions(Set<? extends OpenOption> options) {
        if (options.contains(StandardOpenOption.READ)) {
            throw new UnsupportedOperationException("7z entry update mode is not supported");
        }
        if (options.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("7z entry append mode is not supported");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported 7z entry write option: " + option);
            }
        }
    }

    /// Returns 7z Windows attributes derived from supported initial file attributes.
    private static int initialWindowsAttributes(
            boolean directory,
            boolean symbolicLink,
            FileAttribute<?>... attributes
    ) {
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        if (permissions == null) {
            return symbolicLink
                    ? SYMBOLIC_LINK_WINDOWS_ATTRIBUTES
                    : SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES;
        }
        if (symbolicLink) {
            return SevenZipPosixSupport.symbolicLinkWindowsAttributes(permissions);
        }
        if (directory) {
            return SevenZipPosixSupport.directoryWindowsAttributes(permissions);
        }
        return SevenZipPosixSupport.regularFileWindowsAttributes(permissions);
    }

    /// Returns POSIX permissions stored by supported initial file attributes.
    private static @Nullable Set<PosixFilePermission> initialPosixPermissions(FileAttribute<?>... attributes) {
        Objects.requireNonNull(attributes, "attributes");
        @Nullable Set<PosixFilePermission> permissions = null;
        for (FileAttribute<?> attribute : attributes) {
            Objects.requireNonNull(attribute, "attribute");
            String name = attribute.name();
            if ("posix:permissions".equals(name)) {
                permissions = posixPermissions(attribute);
            } else {
                throw new UnsupportedOperationException("Unsupported 7z entry initial file attribute: " + name);
            }
        }
        return permissions;
    }

    /// Returns POSIX permissions stored by a file attribute.
    private static Set<PosixFilePermission> posixPermissions(FileAttribute<?> attribute) {
        Object value = attribute.value();
        if (!(value instanceof Set<?> values)) {
            throw new IllegalArgumentException("posix:permissions value must be a Set");
        }

        EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (Object element : values) {
            if (!(element instanceof PosixFilePermission permission)) {
                throw new IllegalArgumentException("posix:permissions contains a non-POSIX permission value");
            }
            permissions.add(permission);
        }
        return permissions;
    }

    /// Prepares a new archive entry path for forward-only writing.
    private String prepareWritableEntry(Path path, boolean directory) throws IOException {
        String pathText = normalizedPathText(path);
        if ("/".equals(pathText)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (writtenEntries.contains(pathText) || !directory && writtenDirectories.contains(pathText)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        markImplicitWritableParents(pathText);
        return pathText;
    }

    /// Records implicit parent directories for a path and rejects file-parent conflicts.
    private void markImplicitWritableParents(String path) throws IOException {
        int separator = path.indexOf('/', 1);
        while (separator >= 0) {
            String parent = path.substring(0, separator);
            if (writtenEntries.contains(parent) && !writtenDirectories.contains(parent)) {
                throw new java.nio.file.FileSystemException(path, parent, "7z parent entry is not a directory");
            }
            writtenDirectories.add(parent);
            separator = path.indexOf('/', separator + 1);
        }
    }

    /// Records a forward-only entry after it has been emitted.
    private void recordWrittenEntry(String path, boolean directory) {
        writtenEntries.add(path);
        if (directory) {
            writtenDirectories.add(path);
        }
    }

    /// Opens a 7z writer entry.
    private void beginWritableEntry(String path, boolean directory, int windowsAttributes) throws IOException {
        if (entryOpen) {
            throw new IOException("A 7z entry is already open");
        }
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(writableEntryName(path));
        entry.setDirectory(directory);
        if (windowsAttributes != SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES) {
            entry.setHasWindowsAttributes(true);
            entry.setWindowsAttributes(windowsAttributes);
        }
        requireWriter().putArchiveEntry(entry);
        entryOpen = true;
    }

    /// Closes the current 7z writer entry.
    private void closeWritableEntry(String path, boolean directory) throws IOException {
        if (!entryOpen) {
            return;
        }
        try {
            requireWriter().closeArchiveEntry();
        } finally {
            entryOpen = false;
        }
        recordWrittenEntry(path, directory);
    }

    /// Checks access to a known forward-only write-mode path.
    private void checkWritableAccess(Path path, AccessMode... modes) throws IOException {
        requireWritableKnownPath(path);
        for (AccessMode mode : modes) {
            Objects.requireNonNull(mode, "mode");
            if (mode == AccessMode.READ || mode == AccessMode.EXECUTE) {
                throw new UnsupportedOperationException("Forward-only 7z write file systems do not expose reads");
            }
        }
    }

    /// Requires a path known to forward-only write mode.
    private void requireWritableKnownPath(Path path) throws IOException {
        String pathText = normalizedPathText(path);
        if (!"/".equals(pathText)
                && !writtenEntries.contains(pathText)
                && !writtenDirectories.contains(pathText)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /// Returns a normalized absolute 7z path text.
    private String normalizedPathText(Path path) {
        ensureOpen();
        if (!(path instanceof SevenZipArkivoPath sevenZipPath) || sevenZipPath.getFileSystem() != this) {
            throw new java.nio.file.ProviderMismatchException();
        }
        return sevenZipPath.toAbsolutePath().normalize().toString();
    }

    /// Converts an absolute file-system path text to a 7z entry name.
    private static String writableEntryName(String path) {
        return path.substring(1);
    }

    /// Returns archive-style path text for a link target.
    private static String archivePathText(Path path) {
        return path.toString().replace('\\', '/');
    }

    /// Requires byte channel options to describe a read-only open.
    private static void requireReadOnlyOptions(Set<? extends OpenOption> options) {
        Objects.requireNonNull(options, "options");
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("7z entry channels are read-only");
            }
        }
    }

    /// Requires input stream options to describe a read-only open.
    private static void requireReadOnlyOptions(OpenOption... options) {
        Objects.requireNonNull(options, "options");
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("7z entry streams are read-only");
            }
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
            return SevenZipHeaderReader.readArchiveMetadata(channel, config.passwordProvider());
        }
    }

    /// Opens the underlying archive channel.
    private SeekableByteChannel openArchiveChannel() throws IOException {
        if (archivePath != null) {
            List<Path> splitVolumePaths = SevenZipSplitVolumePaths.discover(archivePath);
            if (splitVolumePaths != null) {
                return SevenZipVolumeChannel.open(index -> {
                    if (index < 0 || index >= splitVolumePaths.size()) {
                        return null;
                    }
                    return Files.newByteChannel(splitVolumePaths.get((int) index), config.openOptions());
                });
            }
            return Files.newByteChannel(archivePath, config.openOptions());
        }
        if (volumes != null) {
            return SevenZipVolumeChannel.open(volumes);
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

    /// Returns the decoded byte limit needed to reach the end of an entry inside a folder output.
    private static long checkedDecodedLimit(SevenZipEntryMetadata metadata) throws IOException {
        try {
            return Math.addExact(metadata.decodedOffset(), metadata.size());
        } catch (ArithmeticException exception) {
            throw new IOException("7z decoded entry limit is too large", exception);
        }
    }

    /// Returns parsed entries keyed by normalized absolute path text.
    private Map<String, SevenZipEntryMetadata> entriesByPath(List<SevenZipEntryMetadata> parsedEntries)
            throws IOException {
        LinkedHashMap<String, SevenZipEntryMetadata> result = new LinkedHashMap<>();
        for (SevenZipEntryMetadata metadata : parsedEntries) {
            String pathText = entryPathText(metadata.path());
            if (result.put(pathText, metadata) != null) {
                throw new IOException("Duplicate 7z entry path: " + metadata.path());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /// Returns the normalized absolute file system path text for a decoded 7z entry path.
    private static String entryPathText(String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\")
                || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException("7z entry path must be relative");
        }
        ArrayList<String> names = new ArrayList<>();
        int start = 0;
        while (start <= path.length()) {
            int end = nextEntryPathSeparator(path, start);

            String name = path.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IOException("7z entry path must not contain ..");
                }
                names.add(name);
            }
            start = end + 1;
        }
        if (names.isEmpty()) {
            throw new IOException("7z entry is missing a path");
        }
        return "/" + String.join("/", names);
    }

    /// Returns the index of the next entry path separator, or the path length.
    private static int nextEntryPathSeparator(String path, int start) {
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

    /// Returns directory children keyed by normalized absolute parent path text.
    private Map<String, List<Path>> childrenByPath(Map<String, SevenZipEntryMetadata> entries) throws IOException {
        LinkedHashMap<String, ArrayList<Path>> result = new LinkedHashMap<>();
        result.put("/", new ArrayList<>());
        for (String pathText : entries.keySet()) {
            Path path = getPath(pathText);
            Path parent = path.getParent();
            String parentText = parent != null ? parent.toString() : "/";
            SevenZipEntryMetadata parentMetadata = entries.get(parentText);
            if (parentMetadata != null && !parentMetadata.directory()) {
                throw new IOException("7z entry path conflicts with directory: " + parentMetadata.path());
            }
            result.computeIfAbsent(parentText, ignored -> new ArrayList<>()).add(path);
        }

        LinkedHashMap<String, List<Path>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayList<Path>> entry : result.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
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
            if (!SUPPORTED_ATTRIBUTE_VIEWS.contains(view)) {
                throw new UnsupportedOperationException("Unsupported 7z attribute view: " + view);
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("7z attribute names must not be empty");
            }
            if ("*".equals(names)) {
                return new RequestedAttributes(view, Set.of("*"));
            }
            return new RequestedAttributes(view, Set.of(names.split(",")));
        }

        /// Returns whether this request targets the owner attribute view.
        private boolean ownerView() {
            return "owner".equals(view);
        }

        /// Returns whether this request targets the POSIX attribute view.
        private boolean posixView() {
            return "posix".equals(view);
        }

        /// Returns whether this request targets the 7z attribute view.
        private boolean sevenZipView() {
            return "7z".equals(view);
        }

        /// Returns whether the given attribute was requested.
        private boolean contains(String name) {
            return names.contains(name);
        }
    }

    /// Records a written file entry when its body stream is closed successfully.
    @NotNullByDefault
    private final class WrittenEntryOutputStream extends OutputStream {
        /// The normalized path to record after close.
        private final String path;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a recording output stream.
        private WrittenEntryOutputStream(String path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Writes one byte to the entry body.
        @Override
        public void write(int value) throws IOException {
            ensureStreamOpen();
            requireWriter().write(new byte[]{(byte) value}, 0, 1);
        }

        /// Writes bytes to the entry body.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            ensureStreamOpen();
            requireWriter().write(buffer, offset, length);
        }

        /// Flushes this stream.
        @Override
        public void flush() throws IOException {
            ensureStreamOpen();
        }

        /// Closes the entry body and records the emitted file path.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closeWritableEntry(path, false);
            closed = true;
        }

        /// Ensures this stream is open.
        private void ensureStreamOpen() throws ClosedChannelException {
            if (closed) {
                throw new ClosedChannelException();
            }
            SevenZipArkivoFileSystemImpl.this.ensureOpen();
        }
    }

    /// Exposes a forward-only output stream as a writable byte channel.
    @NotNullByDefault
    private static final class WritableEntryByteChannel implements SeekableByteChannel {
        /// The wrapped output stream.
        private final OutputStream output;

        /// The current sequential write position.
        private long position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a writable byte channel.
        private WritableEntryByteChannel(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        /// Reads are not supported for forward-only output entries.
        @Override
        public int read(ByteBuffer destination) {
            throw new NonReadableChannelException();
        }

        /// Writes bytes at the current forward-only position.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            int length = source.remaining();
            if (source.hasArray()) {
                int offset = source.arrayOffset() + source.position();
                output.write(source.array(), offset, length);
                source.position(source.limit());
            } else {
                byte[] buffer = new byte[Math.min(length, 8192)];
                int remaining = length;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, buffer.length);
                    source.get(buffer, 0, chunk);
                    output.write(buffer, 0, chunk);
                    remaining -= chunk;
                }
            }
            position += length;
            return length;
        }

        /// Returns the current write position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Allows only retaining the current forward-only position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition != position) {
                throw new UnsupportedOperationException("7z output entry channels are forward-only");
            }
            return this;
        }

        /// Returns the current written size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return position;
        }

        /// Truncation is not supported for forward-only output entries.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            if (size != position) {
                throw new UnsupportedOperationException("7z output entry channels cannot be truncated");
            }
            return this;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the wrapped output stream.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            open = false;
            output.close();
        }

        /// Ensures this channel is open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
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

    /// Owner attribute view for one 7z path.
    @NotNullByDefault
    private static final class OwnerAttributeView implements FileOwnerAttributeView {
        /// The attributes returned by this view.
        private final PosixFileAttributes attributes;

        /// Creates an owner attribute view.
        private OwnerAttributeView(PosixFileAttributes attributes) {
            this.attributes = Objects.requireNonNull(attributes, "attributes");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "owner";
        }

        /// Returns the synthesized owner principal.
        @Override
        public UserPrincipal getOwner() {
            return attributes.owner();
        }

        /// Rejects owner mutation.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new ReadOnlyFileSystemException();
        }
    }

    /// POSIX attribute view for one 7z path.
    @NotNullByDefault
    private static final class PosixAttributeView implements PosixFileAttributeView {
        /// The attributes returned by this view.
        private final PosixFileAttributes attributes;

        /// Creates a POSIX attribute view.
        private PosixAttributeView(PosixFileAttributes attributes) {
            this.attributes = Objects.requireNonNull(attributes, "attributes");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads POSIX attributes.
        @Override
        public PosixFileAttributes readAttributes() {
            return attributes;
        }

        /// Returns the synthesized owner principal.
        @Override
        public UserPrincipal getOwner() {
            return attributes.owner();
        }

        /// Rejects POSIX time mutation.
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

        /// Rejects permissions mutation.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            throw new ReadOnlyFileSystemException();
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

    /// 7z attribute view for one archive entry path.
    @NotNullByDefault
    private static final class SevenZipAttributeView implements SevenZipArkivoEntryAttributeView {
        /// The attributes returned by this view.
        private final SevenZipArkivoEntryAttributes attributes;

        /// Creates a 7z attribute view.
        private SevenZipAttributeView(SevenZipArkivoEntryAttributes attributes) {
            this.attributes = Objects.requireNonNull(attributes, "attributes");
        }

        /// Reads attributes.
        @Override
        public SevenZipArkivoEntryAttributes readAttributes() {
            return attributes;
        }

        /// Rejects entry time mutation.
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

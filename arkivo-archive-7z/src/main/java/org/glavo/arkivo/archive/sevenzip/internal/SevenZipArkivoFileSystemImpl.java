// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipPackedStream;

import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ArkivoVolumeChannel;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.internal.ArkivoPathMatchers;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.archive.internal.PosixPermissions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
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
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements a 7z archive file system backed by a read/update index or a forward-only writer.
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

    /// Decoded bodies staged by update mode, keyed by absolute path text.
    private final Map<String, ArkivoStoredContent> stagedContents;

    /// The storage that owns decoded update bodies, or `null` outside update mode.
    private final @Nullable ArkivoEditStorage editStorage;

    /// Stored bodies whose first cleanup attempt failed and must be retried while closing.
    private final List<ArkivoStoredContent> retiredStoredContents;

    /// Entry-specific output settings changed by update mode.
    private final Map<String, UpdateOutputSettings> updateOutputSettings;

    /// The writer used by forward-only write mode, or `null` in read and update modes.
    private final @Nullable SevenZipArchiveWriter writer;

    /// The staged split output published after the writer closes, or `null` for direct path writes and read/update modes.
    private final @Nullable SevenZipSplitArchiveOutput splitOutput;

    /// Whether this file system performs a complete archive rewrite on update.
    private final boolean updateMode;

    /// The split size used by update output, or `NO_SPLIT_SIZE` for single-volume output.
    private final long updateSplitSize;

    /// The active random-access update channel, or `null` when no body is being changed.
    private @Nullable UpdateEntryByteChannel activeUpdateChannel;

    /// Whether update mode has changed the archive index or content.
    private boolean dirty;

    /// The pending encoded-header encryption state, or `null` when header encryption is disabled.
    private final @Nullable SevenZipHeaderEncryption headerEncryption;

    /// Absolute archive paths emitted by forward-only write mode.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// Absolute archive directory paths emitted or implied by forward-only write mode.
    private final HashSet<String> writtenDirectories = new HashSet<>();

    /// The action invoked after this file system closes.
    private final @Nullable Runnable closeAction;

    /// The root path.
    private final SevenZipArkivoPath root;

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Whether the owned volume source has been closed.
    private boolean volumesClosed;

    /// Whether the writer has been closed.
    private boolean writerClosed;

    /// Whether encoded-header encryption has completed or its key has been cleared.
    private boolean headerEncryptionClosed;

    /// Whether split output publication or rollback has completed.
    private boolean splitOutputClosed;

    /// Whether update staging storage has closed successfully.
    private boolean editStorageClosed;

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

    /// Creates a complete-rewrite 7z update file system over explicit volume input and output.
    public SevenZipArkivoFileSystemImpl(
            SevenZipArkivoFileSystemProvider provider,
            ArkivoVolumeSource volumes,
            ArkivoVolumeTarget outputTarget,
            long splitSize,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        this(provider, null, volumes, outputTarget, splitSize, config, null);
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
        boolean updateMode = config.archiveUpdate();
        int sourceCount = (archivePath != null ? 1 : 0) + (volumes != null ? 1 : 0);
        if (updateMode) {
            if (sourceCount != 1) {
                throw new IllegalArgumentException("7z update mode requires exactly one archive path or volume source");
            }
            if (volumes != null && outputTarget == null && config.commitTarget() == null) {
                throw new IllegalArgumentException(
                        "7z volume-source updates require a commit target or transactional volume target"
                );
            }
        } else {
            int backingCount = sourceCount + (outputTarget != null ? 1 : 0);
            if (backingCount != 1) {
                throw new IllegalArgumentException(
                        "exactly one archive path, volume source, or volume target must be provided"
                );
            }
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
        this.updateMode = updateMode;
        this.root = SevenZipArkivoPath.root(this);
        this.writtenDirectories.add("/");

        if (updateMode) {
            this.writer = null;
            this.splitOutput = null;
            this.headerEncryption = null;
            this.headerEncryptionClosed = true;
            this.fileStore = SevenZipFileStore.WRITABLE;
            ArkivoEditStorage openedEditStorage = config.editStorage();
            if (openedEditStorage == null) {
                openedEditStorage = ArkivoEditStorage.temporaryFiles(defaultEditStorageDirectory(this.archivePath));
            }
            boolean newArchive = this.archivePath != null && !Files.exists(this.archivePath);
            SevenZipArchiveMetadata archiveMetadata;
            Map<String, SevenZipEntryMetadata> parsedEntries;
            Map<String, List<Path>> parsedChildren;
            long resolvedSplitSize;
            try {
                validateUpdateFeatures();
                if (newArchive) {
                    if (!config.openOptions().contains(StandardOpenOption.CREATE)) {
                        throw new NoSuchFileException(this.archivePath.toString());
                    }
                    archiveMetadata = new SevenZipArchiveMetadata(WRITE_MODE_SIGNATURE_HEADER, List.of());
                } else {
                    archiveMetadata = readArchiveMetadata();
                }
                resolvedSplitSize = resolveUpdateSplitSize();
                if (resolvedSplitSize != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE
                        && config.commitTarget() != null) {
                    throw new IllegalArgumentException(
                            "7z split updates do not support single-file commit targets"
                    );
                }
                parsedEntries = entriesByPath(archiveMetadata.entries());
                parsedChildren = childrenByPath(parsedEntries);
            } catch (IOException | RuntimeException | Error exception) {
                try {
                    openedEditStorage.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                closeAfterConstructionFailure(exception);
                throw exception;
            }
            this.updateSplitSize = resolvedSplitSize;
            this.signatureHeader = archiveMetadata.signatureHeader();
            this.entries = new LinkedHashMap<>(parsedEntries);
            this.children = new LinkedHashMap<>(parsedChildren);
            this.stagedContents = new LinkedHashMap<>();
            this.editStorage = openedEditStorage;
            this.retiredStoredContents = new ArrayList<>();
            this.updateOutputSettings = new LinkedHashMap<>();
            this.editStorageClosed = false;
            this.dirty = newArchive;
        } else if (config.archiveWritable()) {
            validateWriteFeatures();
            @Nullable SevenZipArchiveWriter openedWriter = null;
            @Nullable SevenZipSplitArchiveOutput openedSplitOutput = null;
            @Nullable SevenZipHeaderEncryption openedHeaderEncryption = null;
            try (SevenZipWritePassword password = SevenZipWritePassword.open(config.passwordProvider())) {
                if (config.encryptHeaders()) {
                    byte @Nullable [] passwordBytes = password.bytes();
                    if (passwordBytes == null) {
                        throw new IOException("7z encrypted header write requires a password");
                    }
                    openedHeaderEncryption = SevenZipHeaderEncryption.create(passwordBytes);
                }
                WriterResources writerResources = openArchiveWriter(password.bytes());
                openedWriter = writerResources.writer();
                openedSplitOutput = writerResources.splitOutput();
            } catch (IOException | RuntimeException | Error exception) {
                closeWriterAfterConstructionFailure(exception, openedWriter);
                closeSplitOutputAfterConstructionFailure(exception, openedSplitOutput);
                if (openedHeaderEncryption != null) {
                    openedHeaderEncryption.close();
                }
                closeAfterConstructionFailure(exception);
                throw exception;
            }
            this.writer = openedWriter;
            this.splitOutput = openedSplitOutput;
            this.headerEncryption = openedHeaderEncryption;
            this.headerEncryptionClosed = openedHeaderEncryption == null;
            this.fileStore = SevenZipFileStore.WRITABLE;
            this.signatureHeader = WRITE_MODE_SIGNATURE_HEADER;
            this.entries = Map.of();
            this.children = Map.of("/", List.of());
            this.stagedContents = Map.of();
            this.editStorage = null;
            this.retiredStoredContents = List.of();
            this.updateOutputSettings = Map.of();
            this.updateSplitSize = SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
            this.editStorageClosed = true;
        } else {
            this.writer = null;
            this.splitOutput = null;
            this.headerEncryption = null;
            this.headerEncryptionClosed = true;
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
            this.stagedContents = Map.of();
            this.editStorage = null;
            this.retiredStoredContents = List.of();
            this.updateOutputSettings = Map.of();
            this.updateSplitSize = SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
            this.editStorageClosed = true;
        }
    }

    /// Releases a partially opened 7z writer after construction fails.
    private static void closeWriterAfterConstructionFailure(
            Throwable failure,
            @Nullable SevenZipArchiveWriter openedWriter
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
        try (CloseOperation ignored = beginCloseOperation()) {
            if (updateMode) {
                closeUpdate();
                return;
            }
            if (!open
                    && writerClosed
                    && headerEncryptionClosed
                    && splitOutputClosed
                    && volumesClosed
                    && closeActionCompleted) {
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
            if (!headerEncryptionClosed && writerClosed) {
                SevenZipHeaderEncryption encryption = Objects.requireNonNull(
                        headerEncryption,
                        "headerEncryption"
                );
                try {
                    if (failure == null) {
                        if (splitOutput != null) {
                            splitOutput.encryptHeader(encryption);
                        } else {
                            encryption.applyTo(Objects.requireNonNull(archivePath, "archivePath"));
                        }
                    }
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                } finally {
                    encryption.close();
                    headerEncryptionClosed = true;
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
                    failure = appendFailure(failure, exception);
                }
            }
            if (!volumesClosed) {
                try {
                    if (volumes != null) {
                        volumes.close();
                    }
                    volumesClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            if (!closeActionCompleted) {
                try {
                    if (closeAction != null) {
                        closeAction.run();
                    }
                    closeActionCompleted = true;
                } catch (RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            throwFailure(failure);
        }
    }

    /// Closes update mode after publishing changed content transactionally.
    private void closeUpdate() throws IOException {
        if (!open
                && editStorageClosed
                && stagedContents.isEmpty()
                && retiredStoredContents.isEmpty()
                && volumesClosed
                && closeActionCompleted) {
            return;
        }

        @Nullable Throwable failure = null;
        if (open) {
            @Nullable UpdateEntryByteChannel channel = activeUpdateChannel;
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            if (failure == null && dirty) {
                try {
                    commitUpdate();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            open = false;
            writerClosed = true;
            headerEncryptionClosed = true;
            splitOutputClosed = true;
        }
        failure = closeUpdateStorage(failure);
        if (!volumesClosed) {
            try {
                if (volumes != null) {
                    volumes.close();
                }
                volumesClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (!closeActionCompleted) {
            try {
                if (closeAction != null) {
                    closeAction.run();
                }
                closeActionCompleted = true;
            } catch (RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        throwFailure(failure);
    }

    /// Closes all update entry bodies and their owning storage, retaining failed items for a later close retry.
    private @Nullable Throwable closeUpdateStorage(@Nullable Throwable failure) {
        Iterator<ArkivoStoredContent> stagedIterator = stagedContents.values().iterator();
        while (stagedIterator.hasNext()) {
            ArkivoStoredContent content = stagedIterator.next();
            try {
                content.close();
                stagedIterator.remove();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        Iterator<ArkivoStoredContent> retiredIterator = retiredStoredContents.iterator();
        while (retiredIterator.hasNext()) {
            ArkivoStoredContent content = retiredIterator.next();
            try {
                content.close();
                retiredIterator.remove();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (!editStorageClosed) {
            try {
                Objects.requireNonNull(editStorage, "editStorage").close();
                editStorageClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
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
        return writer == null && !updateMode;
    }

    /// Returns the path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the root directories.
    @Override
    public @Unmodifiable Iterable<Path> getRootDirectories() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return List.of(root);
        }
    }

    /// Returns the file stores.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return List.of(fileStore);
        }
    }

    /// Returns supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return SUPPORTED_ATTRIBUTE_VIEWS;
        }
    }

    /// Returns a path in this file system.
    @Override
    public Path getPath(String first, String... more) {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return SevenZipArkivoPath.of(this, first, more);
        }
    }

    /// Returns a path matcher for this file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return ArkivoPathMatchers.create(syntaxAndPattern);
        }
    }

    /// Returns the user principal lookup service for synthesized 7z principals.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return SevenZipPrincipalSupport.userPrincipalLookupService();
        }
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
        boolean writable = requestsWrite(options);
        try (Operation ignored = writable ? beginWriteOperation() : beginReadOperation()) {
            SeekableByteChannel channel = newByteChannelLocked(path, options, attributes);
            return writable ? manageWriteChannel(channel) : manageReadChannel(channel);
        }
    }

    /// Opens an entry byte channel while the caller holds the matching operation lock.
    private SeekableByteChannel newByteChannelLocked(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        if (updateMode && requestsWrite(options)) {
            return newUpdateByteChannel(path, options, attributes);
        }
        if (!isReadOnly() && requestsWrite(options)) {
            return new WritableEntryByteChannel(newOutputStream(path, options, attributes));
        }

        requireReadableFileSystem();
        requireReadOnlyOptions(options);
        SevenZipEntryMetadata metadata = requireEntry(path);
        if (metadata.directory()) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        String pathText = normalizedPathText(path);
        @Nullable ArkivoStoredContent stagedContent = updateMode ? stagedContents.get(pathText) : null;
        if (stagedContent != null) {
            return stagedContent.openChannel(Set.of(StandardOpenOption.READ));
        }
        if (metadata.dataOffset() == SevenZipEntryMetadata.NO_DATA_OFFSET) {
            return new SevenZipByteChannel(new byte[0]);
        }
        if (metadata.method().isCopyOnly()) {
            SeekableByteChannel channel =
                    new SevenZipFileSliceChannel(openArchiveChannel(), metadata.dataOffset(), metadata.size());
            if (metadata.crc32() != SevenZipEntryMetadata.UNKNOWN_CRC32) {
                return new SevenZipCRC32ByteChannel(channel, metadata.size(), metadata.crc32());
            }
            if (metadata.packedCrc32() != SevenZipEntryMetadata.UNKNOWN_CRC32) {
                return new SevenZipCRC32ByteChannel(
                        channel,
                        metadata.size(),
                        metadata.packedCrc32(),
                        "7z packed stream data does not match CRC-32"
                );
            }
            return channel;
        }
        if (updateMode) {
            return newStoredReadChannel(path);
        }
        return new SevenZipByteChannel(readDecodedEntry(metadata));
    }

    /// Opens an output stream for a writable file entry.
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            return manageOutputStream(newOutputStream(path, Set.of(options)));
        }
    }

    /// Opens an output stream for a new forward-only file entry with explicit metadata.
    OutputStream newOutputStream(Path path, SevenZipEntryWriteMetadata metadata) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            Objects.requireNonNull(metadata, "metadata");
            requireWritableFileSystem();
            String pathText = prepareWritableEntry(path, false);
            beginWritableEntry(pathText, false, metadata);
            return manageOutputStream(new WrittenEntryOutputStream(pathText));
        }
    }

    /// Opens an output stream for a writable file entry.
    private OutputStream newOutputStream(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        if (updateMode) {
            if (options.contains(StandardOpenOption.READ)) {
                throw new UnsupportedOperationException("7z output streams do not support READ");
            }
            LinkedHashSet<OpenOption> channelOptions = new LinkedHashSet<>(options);
            if (channelOptions.isEmpty()) {
                channelOptions.add(StandardOpenOption.CREATE);
                channelOptions.add(StandardOpenOption.TRUNCATE_EXISTING);
                channelOptions.add(StandardOpenOption.WRITE);
            } else if (!channelOptions.contains(StandardOpenOption.APPEND)) {
                channelOptions.add(StandardOpenOption.WRITE);
            }
            return Channels.newOutputStream(newUpdateByteChannel(path, channelOptions, attributes));
        }
        validateEntryWriteOptions(options);
        SevenZipEntryWriteMetadata metadata = initialEntryMetadata(false, false, attributes);
        String pathText = prepareWritableEntry(path, false);
        beginWritableEntry(pathText, false, metadata);
        return new WrittenEntryOutputStream(pathText);
    }

    /// Creates a new directory entry in a writable archive.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            Objects.requireNonNull(attributes, "attributes");
            requireWritableFileSystem();
            SevenZipEntryWriteMetadata metadata = initialEntryMetadata(true, false, attributes);
            if (updateMode) {
                String pathText = prepareUpdateEntry(directory);
                addUpdateEntry(pathText, newMetadata(pathText, true, 0L, metadata), null);
                return;
            }
            String pathText = prepareWritableEntry(directory, true);
            beginWritableEntry(pathText, true, metadata);
            closeWritableEntry(pathText, true);
        }
    }

    /// Creates a new forward-only directory entry with explicit metadata.
    void createDirectory(Path directory, SevenZipEntryWriteMetadata metadata) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            Objects.requireNonNull(metadata, "metadata");
            requireWritableFileSystem();
            String pathText = prepareWritableEntry(directory, true);
            beginWritableEntry(pathText, true, metadata);
            closeWritableEntry(pathText, true);
        }
    }

    /// Creates a new symbolic link entry in a writable archive.
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(attributes, "attributes");
            requireWritableFileSystem();
            SevenZipEntryWriteMetadata metadata = initialEntryMetadata(false, true, attributes);
            byte[] targetBytes = archivePathText(target).getBytes(StandardCharsets.UTF_8);
            if (updateMode) {
                String pathText = prepareUpdateEntry(link);
                ArkivoStoredContent stagedTarget = storeBytes(pathText, targetBytes);
                addUpdateEntry(
                        pathText,
                        newMetadata(pathText, false, targetBytes.length, metadata),
                        stagedTarget
                );
                return;
            }
            String pathText = prepareWritableEntry(link, false);
            beginWritableEntry(pathText, false, metadata);
            requireWriter().write(targetBytes, 0, targetBytes.length);
            closeWritableEntry(pathText, false);
        }
    }

    /// Creates a new forward-only symbolic link entry with explicit metadata.
    void createSymbolicLink(Path link, Path target, SevenZipEntryWriteMetadata metadata) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(metadata, "metadata");
            requireWritableFileSystem();
            String pathText = prepareWritableEntry(link, false);
            beginWritableEntry(pathText, false, metadata);
            byte[] targetBytes = archivePathText(target).getBytes(StandardCharsets.UTF_8);
            requireWriter().write(targetBytes, 0, targetBytes.length);
            closeWritableEntry(pathText, false);
        }
    }

    /// Deletes one entry from an update-mode archive.
    public void delete(Path path) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            deleteLocked(path);
        }
    }

    /// Deletes one entry while the caller holds the exclusive operation lock.
    private void deleteLocked(Path path) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        String pathText = requireExistingPath(path);
        if ("/".equals(pathText)) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "The 7z root cannot be deleted");
        }
        @Nullable SevenZipEntryMetadata metadata = entries.get(pathText);
        boolean directory = metadata == null || metadata.directory();
        if (directory && !children.getOrDefault(pathText, List.of()).isEmpty()) {
            throw new DirectoryNotEmptyException(path.toString());
        }
        if (metadata == null) {
            throw new NoSuchFileException(path.toString());
        }

        entries.remove(pathText);
        @Nullable ArkivoStoredContent removedContent = stagedContents.remove(pathText);
        if (removedContent != null) {
            releaseStoredContent(removedContent);
        }
        updateOutputSettings.remove(pathText);
        rebuildUpdateChildren();
        dirty = true;
    }

    /// Moves one entry and any descendants inside an update-mode archive.
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            moveLocked(source, target, options);
        }
    }

    /// Moves one entry while the caller holds the exclusive operation lock.
    private void moveLocked(Path source, Path target, CopyOption... options) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(source);
        boolean replaceExisting = false;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else if (option != StandardCopyOption.ATOMIC_MOVE) {
                throw new UnsupportedOperationException("Unsupported 7z move option: " + option);
            }
        }

        String sourcePath = requireExistingPath(source);
        String targetPath = normalizedPathText(target);
        if ("/".equals(sourcePath) || "/".equals(targetPath)) {
            throw new java.nio.file.FileSystemException(
                    source.toString(),
                    target.toString(),
                    "The 7z root cannot be moved"
            );
        }
        if (sourcePath.equals(targetPath)) {
            return;
        }

        @Nullable SevenZipEntryMetadata sourceMetadata = entries.get(sourcePath);
        boolean sourceDirectory = sourceMetadata == null || sourceMetadata.directory();
        if (sourceDirectory && targetPath.startsWith(sourcePath + "/")) {
            throw new java.nio.file.FileSystemException(
                    source.toString(),
                    target.toString(),
                    "A directory cannot be moved below itself"
            );
        }
        ensureExistingParentDirectory(targetPath);

        boolean targetExists = entries.containsKey(targetPath) || children.containsKey(targetPath);
        @Nullable SevenZipEntryMetadata targetMetadata = entries.get(targetPath);
        boolean targetDirectory = targetExists && (targetMetadata == null || targetMetadata.directory());
        if (targetExists) {
            if (!replaceExisting) {
                throw new FileAlreadyExistsException(target.toString());
            }
            if (sourceDirectory != targetDirectory) {
                throw new java.nio.file.FileSystemException(
                        source.toString(),
                        target.toString(),
                        "Source and target entry types differ"
                );
            }
            if (targetDirectory && !children.getOrDefault(targetPath, List.of()).isEmpty()) {
                throw new DirectoryNotEmptyException(target.toString());
            }
        }

        LinkedHashMap<String, String> movedPaths = new LinkedHashMap<>();
        for (String pathText : entries.keySet()) {
            if (pathText.equals(sourcePath) || pathText.startsWith(sourcePath + "/")) {
                movedPaths.put(pathText, targetPath + pathText.substring(sourcePath.length()));
            }
        }
        if (movedPaths.isEmpty()) {
            throw new NoSuchFileException(source.toString());
        }
        for (String movedPath : movedPaths.values()) {
            if (entries.containsKey(movedPath)
                    && !movedPaths.containsKey(movedPath)
                    && !movedPath.equals(targetPath)) {
                throw new FileAlreadyExistsException(movedPath);
            }
        }

        LinkedHashMap<String, SevenZipEntryMetadata> rebuiltEntries = new LinkedHashMap<>();
        for (Map.Entry<String, SevenZipEntryMetadata> entry : entries.entrySet()) {
            String oldPath = entry.getKey();
            if (targetExists && oldPath.equals(targetPath) && !movedPaths.containsKey(oldPath)) {
                continue;
            }
            @Nullable String movedPath = movedPaths.get(oldPath);
            String newPath = movedPath != null ? movedPath : oldPath;
            rebuiltEntries.put(newPath, copyMetadata(entry.getValue(), newPath, entry.getValue().size()));
        }

        @Nullable ArkivoStoredContent replacedContent = targetExists && !movedPaths.containsKey(targetPath)
                ? stagedContents.remove(targetPath)
                : null;
        remapUpdateMap(stagedContents, movedPaths, targetPath, targetExists);
        if (replacedContent != null) {
            releaseStoredContent(replacedContent);
        }
        remapUpdateMap(updateOutputSettings, movedPaths, targetPath, targetExists);
        entries.clear();
        entries.putAll(rebuiltEntries);
        rebuildUpdateChildren();
        dirty = true;
    }

    /// Updates one named entry attribute in update mode.
    public void setAttribute(Path path, String attribute, @Nullable Object value, LinkOption... options)
            throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            setAttributeLocked(path, attribute, value, options);
        }
    }

    /// Updates one named entry attribute while the caller holds the exclusive operation lock.
    private void setAttributeLocked(Path path, String attribute, @Nullable Object value, LinkOption... options)
            throws IOException {
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(options, "options");
        requireUpdateMode();
        int separator = attribute.indexOf(':');
        String view = separator >= 0 ? attribute.substring(0, separator) : "basic";
        String name = separator >= 0 ? attribute.substring(separator + 1) : attribute;
        switch (view) {
            case "basic" -> setBasicAttribute(path, name, value);
            case "owner" -> throw new UnsupportedOperationException("7z does not store owner principals");
            case "posix" -> setPosixAttribute(path, name, value);
            case "7z" -> setSevenZipAttribute(path, name, value);
            default -> throw new UnsupportedOperationException("Unsupported 7z attribute view: " + view);
        }
    }

    /// Opens an input stream for an entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return manageInputStream(newInputStreamLocked(path, options));
        }
    }

    /// Opens an entry input stream while the caller holds the shared operation lock.
    private InputStream newInputStreamLocked(Path path, OpenOption... options) throws IOException {
        requireReadableFileSystem();
        requireReadOnlyOptions(options);
        SevenZipEntryMetadata metadata = requireEntry(path);
        if (metadata.directory()) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        String pathText = normalizedPathText(path);
        @Nullable ArkivoStoredContent stagedContent = updateMode ? stagedContents.get(pathText) : null;
        if (stagedContent != null) {
            return Channels.newInputStream(stagedContent.openChannel(Set.of(StandardOpenOption.READ)));
        }
        if (metadata.dataOffset() == SevenZipEntryMetadata.NO_DATA_OFFSET) {
            return new ByteArrayInputStream(new byte[0]);
        }
        List<InputStream> packedInputs = openPackedInputs(metadata);
        if (metadata.method().isCopyOnly()) {
            if (packedInputs.size() != 1) {
                closeInputStreams(packedInputs);
                throw new IOException("7z Copy folder must consume exactly one packed stream");
            }
            return new SevenZipBoundedInputStream(packedInputs.get(0), metadata.size(), metadata.crc32());
        }

        InputStream decoded = SevenZipLZMADecoder.openFolder(
                packedInputs,
                metadata.packedStreams().stream().mapToLong(SevenZipPackedStream::size).toArray(),
                metadata.method(),
                checkedDecodedLimit(metadata),
                config.passwordProvider()
        );
        boolean completed = false;
        Throwable failure = null;
        try {
            if (metadata.decodedOffset() > 0) {
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

    /// Opens and validates every physical packed stream consumed by an entry folder.
    private List<InputStream> openPackedInputs(SevenZipEntryMetadata metadata) throws IOException {
        ArrayList<InputStream> inputs = new ArrayList<>(metadata.packedStreams().size());
        Throwable failure = null;
        try {
            for (SevenZipPackedStream packedStream : metadata.packedStreams()) {
                SeekableByteChannel channel = new SevenZipFileSliceChannel(
                        openArchiveChannel(),
                        packedStream.offset(),
                        packedStream.size()
                );
                if (packedStream.crc32() != SevenZipEntryMetadata.UNKNOWN_CRC32) {
                    channel = new SevenZipCRC32ByteChannel(
                            channel,
                            packedStream.size(),
                            packedStream.crc32(),
                            "7z packed stream data does not match CRC-32"
                    );
                }
                inputs.add(Channels.newInputStream(channel));
            }
            return List.copyOf(inputs);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure != null) {
                try {
                    closeInputStreams(inputs);
                } catch (IOException | RuntimeException | Error exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
    }

    /// Closes input streams in declaration order while preserving every close failure.
    private static void closeInputStreams(List<? extends InputStream> inputs) throws IOException {
        Throwable failure = null;
        for (InputStream input : inputs) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
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

    /// Opens a directory stream.
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            requireReadableFileSystem();
            Objects.requireNonNull(filter, "filter");
            String pathText = requireExistingPath(directory);
            SevenZipEntryMetadata metadata = entries.get(pathText);
            if (metadata != null && !metadata.directory()) {
                throw new java.nio.file.NotDirectoryException(directory.toString());
            }
            return manageDirectoryStream(
                    new EntryDirectoryStream(children.getOrDefault(pathText, List.of()), filter)
            );
        }
    }

    /// Checks access to a path.
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            if (updateMode) {
                requireExistingPath(path);
                for (AccessMode mode : modes) {
                    Objects.requireNonNull(mode, "mode");
                    if (mode == AccessMode.EXECUTE) {
                        throw new AccessDeniedException(path.toString());
                    }
                }
                return;
            }
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
    }

    /// Reads a symbolic link target.
    public Path readSymbolicLink(Path link) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            requireReadableFileSystem();
            String pathText = requireExistingPath(link);
            SevenZipEntryMetadata metadata = entries.get(pathText);
            if (metadata == null || !SevenZipPosixSupport.isSymbolicLink(metadata.windowsAttributes())) {
                throw new NotLinkException(link.toString());
            }
            return getPath(new String(readDecodedEntry(metadata), StandardCharsets.UTF_8));
        }
    }

    /// Returns a file attribute view for a path.
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(Path path, Class<V> type) {
        try (Operation ignored = beginReadOperation()) {
            Objects.requireNonNull(type, "type");
            if (!isReadOnly() && !updateMode) {
                return null;
            }
            String pathText;
            try {
                pathText = requireExistingPath(path);
            } catch (IOException exception) {
                return null;
            }
            if (type == BasicFileAttributeView.class) {
                return type.cast(new BasicAttributeView(path));
            }
            if (type == FileOwnerAttributeView.class) {
                return type.cast(new OwnerAttributeView(path));
            }
            if (type == PosixFileAttributeView.class) {
                return type.cast(new PosixAttributeView(path));
            }
            if (type == SevenZipArkivoEntryAttributeView.class && entries.containsKey(pathText)) {
                return type.cast(new SevenZipAttributeView(path));
            }
            return null;
        }
    }

    /// Reads file attributes for a path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException {
        try (Operation ignored = beginReadOperation()) {
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
    }

    /// Reads named file attributes for a path.
    public Map<String, Object> readAttributes(Path path, String attributes) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readAttributesLocked(path, attributes);
        }
    }

    /// Reads named file attributes while the caller holds the shared operation lock.
    private Map<String, Object> readAttributesLocked(Path path, String attributes) throws IOException {
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
        if (all || requestedAttributes.contains("coderGraph")) {
            values.put("coderGraph", metadata.coderGraph());
        }
        if (all || requestedAttributes.contains("solid")) {
            values.put("solid", metadata.solid());
        }
        if (all || requestedAttributes.contains("substreamIndex")) {
            values.put("substreamIndex", metadata.substreamIndex());
        }
        if (all || requestedAttributes.contains("substreamCount")) {
            values.put("substreamCount", metadata.substreamCount());
        }
        if (all || requestedAttributes.contains("dataOffset")) {
            values.put("dataOffset", metadata.dataOffset());
        }
        if (all || requestedAttributes.contains("decodedOffset")) {
            values.put("decodedOffset", metadata.decodedOffset());
        }
        if (all || requestedAttributes.contains("packedSize")) {
            values.put("packedSize", metadata.packedSize());
        }
        if (all || requestedAttributes.contains("packedCrc32")) {
            values.put("packedCrc32", metadata.packedCrc32());
        }
        if (all || requestedAttributes.contains("packedStreams")) {
            values.put("packedStreams", metadata.packedStreams());
        }
        if (all || requestedAttributes.contains("crc32")) {
            values.put("crc32", metadata.crc32());
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

    /// Validates 7z update-mode backing and output combinations.
    private void validateUpdateFeatures() {
        if (outputTarget != null) {
            if (outputSplitSize <= 0) {
                throw new IllegalArgumentException("splitSize must be positive");
            }
            if (config.splitSize() != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
                throw new IllegalArgumentException("Explicit 7z volume targets must provide splitSize separately");
            }
            if (config.commitTarget() != null) {
                throw new IllegalArgumentException("7z volume updates do not support single-file commit targets");
            }
        } else if (config.splitSize() != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            SevenZipSplitVolumePaths.requireFirstVolumePath(
                    Objects.requireNonNull(archivePath, "archivePath")
            );
        }
    }

    /// Resolves the split size for update output, preserving an existing path-backed split layout by default.
    private long resolveUpdateSplitSize() throws IOException {
        if (outputTarget != null) {
            return outputSplitSize;
        }
        if (config.commitTarget() != null) {
            return SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
        }
        if (config.splitSizeConfigured()
                && config.splitSize() != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            return config.splitSize();
        }
        @Nullable Path currentArchivePath = archivePath;
        @Nullable List<Path> splitVolumePaths = currentArchivePath != null
                ? SevenZipSplitVolumePaths.discover(currentArchivePath)
                : null;
        if (config.splitSizeConfigured()) {
            if (splitVolumePaths != null && config.commitTarget() == null) {
                return Long.MAX_VALUE;
            }
            return SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
        }
        if (splitVolumePaths != null) {
            long splitSize = Files.size(splitVolumePaths.get(0));
            if (splitSize <= 0L) {
                throw new IOException("Existing 7z first volume is empty");
            }
            return splitSize;
        }
        return SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
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
    }

    /// Opens the underlying archive writer and optional split publisher.
    private WriterResources openArchiveWriter(byte @Nullable [] password) throws IOException {
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
            SevenZipSplitArchiveOutput splitOutput = SevenZipSplitArchiveOutput.open(
                    target,
                    splitSize,
                    password,
                    config.compression(),
                    config.filters(),
                    config.solidFileCount()
            );
            return new WriterResources(splitOutput.writer(), splitOutput);
        }

        Path currentArchivePath = Objects.requireNonNull(archivePath, "archivePath");
        SeekableByteChannel channel = Files.newByteChannel(currentArchivePath, config.openOptions());
        try {
            SevenZipArchiveWriter writer = new SevenZipArchiveWriter(
                    channel,
                    password,
                    config.compression(),
                    config.filters(),
                    config.solidFileCount()
            );
            return new WriterResources(writer, null);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Publishes all surviving update entries through a complete 7z rewrite.
    private void commitUpdate() throws IOException {
        if (updateSplitSize != SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            commitSplitUpdate();
        } else {
            commitSingleVolumeUpdate();
        }
    }

    /// Publishes a single-volume updated archive through an Arkivo commit transaction.
    private void commitSingleVolumeUpdate() throws IOException {
        @Nullable Path sourcePath = archivePath;
        @Nullable ArkivoCommitTarget configuredTarget = config.commitTarget();
        ArkivoCommitTarget target = configuredTarget != null
                ? configuredTarget
                : ArkivoCommitTarget.atomicReplace(defaultCommitDirectory(
                        Objects.requireNonNull(sourcePath, "archivePath")
                ));
        ArkivoCommitOutput output = target.openOutput(sourcePath);
        @Nullable Throwable failure = null;
        @Nullable SevenZipHeaderEncryption encryption = null;
        try (SevenZipWritePassword password = SevenZipWritePassword.open(config.passwordProvider())) {
            if (config.encryptHeaders()) {
                byte @Nullable [] passwordBytes = password.bytes();
                if (passwordBytes == null) {
                    throw new IOException("7z encrypted header write requires a password");
                }
                encryption = SevenZipHeaderEncryption.create(passwordBytes);
            }
            try (SeekableByteChannel channel = output.openChannel(Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ));
                 SevenZipArchiveWriter archiveWriter = new SevenZipArchiveWriter(
                         channel,
                         password.bytes(),
                         config.compression(),
                         config.filters(),
                         config.solidFileCount()
                 )) {
                writeUpdatedArchive(archiveWriter);
            }
            if (encryption != null) {
                encryption.applyTo(output.path());
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        } finally {
            if (encryption != null) {
                encryption.close();
            }
        }

        try {
            if (failure == null) {
                output.commit();
            } else {
                output.rollback();
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            output.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        throwFailure(failure);
    }

    /// Publishes a split updated archive through a transactional volume target.
    private void commitSplitUpdate() throws IOException {
        ArkivoVolumeTarget target;
        if (outputTarget != null) {
            target = outputTarget;
        } else {
            target = new SevenZipPathVolumeTarget(
                    Objects.requireNonNull(archivePath, "archivePath"),
                    config.openOptions()
            );
        }

        @Nullable Throwable failure = null;
        @Nullable SevenZipSplitArchiveOutput output = null;
        @Nullable SevenZipHeaderEncryption encryption = null;
        try (SevenZipWritePassword password = SevenZipWritePassword.open(config.passwordProvider())) {
            if (config.encryptHeaders()) {
                byte @Nullable [] passwordBytes = password.bytes();
                if (passwordBytes == null) {
                    throw new IOException("7z encrypted header write requires a password");
                }
                encryption = SevenZipHeaderEncryption.create(passwordBytes);
            }
            output = SevenZipSplitArchiveOutput.open(
                    target,
                    updateSplitSize,
                    password.bytes(),
                    config.compression(),
                    config.filters(),
                    config.solidFileCount()
            );
            SevenZipArchiveWriter archiveWriter = output.writer();
            @Nullable Throwable writerFailure = null;
            try {
                writeUpdatedArchive(archiveWriter);
            } catch (IOException | RuntimeException | Error exception) {
                writerFailure = exception;
            }
            try {
                archiveWriter.close();
            } catch (IOException | RuntimeException | Error exception) {
                writerFailure = appendFailure(writerFailure, exception);
            }
            throwFailure(writerFailure);
            if (encryption != null) {
                output.encryptHeader(encryption);
            }
            output.commit();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        } finally {
            if (encryption != null) {
                encryption.close();
            }
        }

        if (failure != null && output != null) {
            try {
                output.rollback();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        throwFailure(failure);
    }

    /// Writes every indexed entry to a newly opened 7z writer.
    private void writeUpdatedArchive(SevenZipArchiveWriter archiveWriter) throws IOException {
        byte[] transferBuffer = new byte[64 * 1024];
        for (Map.Entry<String, SevenZipEntryMetadata> indexedEntry : entries.entrySet()) {
            String pathText = indexedEntry.getKey();
            SevenZipEntryMetadata metadata = indexedEntry.getValue();
            UpdateOutputSettings settings =
                    updateOutputSettings.getOrDefault(pathText, UpdateOutputSettings.DEFAULTS);
            SevenZipEntryWriteMetadata writeMetadata = new SevenZipEntryWriteMetadata(
                    metadata.lastModifiedTime(),
                    metadata.lastAccessTime(),
                    metadata.creationTime(),
                    metadata.windowsAttributes(),
                    settings.compression(),
                    settings.filtersConfigured(),
                    settings.filters()
            );

            archiveWriter.putArchiveEntry(
                    writableEntryName(pathText),
                    metadata.directory(),
                    writeMetadata
            );

            @Nullable Throwable entryFailure = null;
            try {
                if (!metadata.directory()) {
                    @Nullable ArkivoStoredContent stagedContent = stagedContents.get(pathText);
                    if (stagedContent != null) {
                        try (SeekableByteChannel channel = stagedContent.openChannel(Set.of(StandardOpenOption.READ));
                             InputStream input = Channels.newInputStream(channel)) {
                            copyToArchive(input, archiveWriter, transferBuffer);
                        }
                    } else if (metadata.size() > 0L) {
                        try (InputStream input = newInputStream(getPath(pathText))) {
                            copyToArchive(input, archiveWriter, transferBuffer);
                        }
                    }
                }
            } catch (IOException | RuntimeException | Error exception) {
                entryFailure = exception;
            }
            try {
                archiveWriter.closeArchiveEntry();
            } catch (IOException | RuntimeException | Error exception) {
                entryFailure = appendFailure(entryFailure, exception);
            }
            throwFailure(entryFailure);
        }
    }

    /// Streams one decoded entry body into an open 7z archive entry.
    private static void copyToArchive(
            InputStream input,
            SevenZipArchiveWriter archiveWriter,
            byte[] transferBuffer
    ) throws IOException {
        while (true) {
            int read = input.read(transferBuffer);
            if (read < 0) {
                return;
            }
            if (read > 0) {
                archiveWriter.write(transferBuffer, 0, read);
            }
        }
    }

    /// Returns the directory used by default temporary update-entry storage.
    private static Path defaultEditStorageDirectory(@Nullable Path archivePath) {
        return archivePath != null
                ? defaultCommitDirectory(archivePath)
                : Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
    }

    /// Returns the directory used for default atomic update output.
    private static Path defaultCommitDirectory(Path archivePath) {
        Path absolutePath = archivePath.toAbsolutePath();
        @Nullable Path parent = absolutePath.getParent();
        return parent != null ? parent : absolutePath.getFileSystem().getPath(".").toAbsolutePath();
    }

    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure == null) {
            return exception;
        }
        if (failure != exception) {
            failure.addSuppressed(exception);
        }
        return failure;
    }

    /// Throws an accumulated failure with its original category.
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

    /// Stores an opened archive writer and its optional split publisher.
    ///
    /// @param writer      the seekable 7z archive writer
    /// @param splitOutput the split publisher, or `null` for direct path output
    @NotNullByDefault
    private record WriterResources(
            SevenZipArchiveWriter writer,
            @Nullable SevenZipSplitArchiveOutput splitOutput
    ) {
        /// Creates one writer resource set.
        private WriterResources {
            Objects.requireNonNull(writer, "writer");
        }
    }

    /// Requires this file system to be in read or update mode.
    private void requireReadableFileSystem() {
        ensureOpen();
        if (!isReadOnly() && !updateMode) {
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

    /// Returns the writer for forward-only write mode.
    private SevenZipArchiveWriter requireWriter() {
        @Nullable SevenZipArchiveWriter currentWriter = writer;
        if (currentWriter == null) {
            throw new ReadOnlyFileSystemException();
        }
        return currentWriter;
    }

    /// Requires this file system to be in complete-rewrite update mode.
    private void requireUpdateMode() {
        ensureOpen();
        if (!updateMode) {
            if (isReadOnly()) {
                throw new ReadOnlyFileSystemException();
            }
            throw new UnsupportedOperationException("Forward-only 7z write file systems do not support mutations");
        }
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

    /// Validates options for a random-access update entry channel.
    private static void validateUpdateEntryOptions(Set<? extends OpenOption> options) {
        if (options.contains(StandardOpenOption.APPEND) && options.contains(StandardOpenOption.READ)) {
            throw new IllegalArgumentException("7z entry APPEND cannot be combined with READ");
        }
        if (options.contains(StandardOpenOption.APPEND)
                && options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            throw new IllegalArgumentException("7z entry APPEND cannot be combined with TRUNCATE_EXISTING");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ
                    && option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.APPEND
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported 7z entry update option: " + option);
            }
        }
    }

    /// Returns 7z entry metadata derived from supported initial file attributes.
    private static SevenZipEntryWriteMetadata initialEntryMetadata(
            boolean directory,
            boolean symbolicLink,
            FileAttribute<?>... attributes
    ) {
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        int windowsAttributes;
        if (permissions == null) {
            windowsAttributes = symbolicLink
                    ? SYMBOLIC_LINK_WINDOWS_ATTRIBUTES
                    : SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES;
        } else if (symbolicLink) {
            windowsAttributes = SevenZipPosixSupport.symbolicLinkWindowsAttributes(permissions);
        } else if (directory) {
            windowsAttributes = SevenZipPosixSupport.directoryWindowsAttributes(permissions);
        } else {
            windowsAttributes = SevenZipPosixSupport.regularFileWindowsAttributes(permissions);
        }
        return SevenZipEntryWriteMetadata.withWindowsAttributes(windowsAttributes);
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
        return PosixPermissions.copyOf(attribute.value(), "posix:permissions");
    }

    /// Opens a seekable channel that stages one update-mode entry body.
    private SeekableByteChannel newUpdateByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        requireUpdateMode();
        validateUpdateEntryOptions(options);
        if (activeUpdateChannel != null) {
            throw new java.nio.file.FileSystemException(
                    path.toString(),
                    null,
                    "Another 7z entry update channel is already open"
            );
        }

        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean writable = options.contains(StandardOpenOption.WRITE) || append;
        boolean readable = options.contains(StandardOpenOption.READ);
        if (!readable && !writable) {
            throw new IllegalArgumentException("7z entry update channel requires READ, WRITE, or APPEND");
        }

        String pathText = normalizedPathText(path);
        if ("/".equals(pathText)) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "The 7z root is a directory");
        }
        @Nullable SevenZipEntryMetadata existing = entries.get(pathText);
        boolean syntheticDirectory = existing == null && children.containsKey(pathText);
        boolean create = writable
                && (options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW));
        boolean createNew = options.contains(StandardOpenOption.CREATE_NEW);
        if (existing == null && !syntheticDirectory && !create) {
            throw new NoSuchFileException(path.toString());
        }
        if ((existing != null || syntheticDirectory) && createNew) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (syntheticDirectory || existing != null && existing.directory()) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "Is a directory");
        }
        if (existing == null) {
            ensureExistingParentDirectory(pathText);
        }

        SevenZipEntryWriteMetadata initialMetadata = initialEntryMetadata(false, false, attributes);
        boolean truncate = writable && options.contains(StandardOpenOption.TRUNCATE_EXISTING);
        ArkivoStoredContent pendingContent = requireEditStorage().createContent(
                pathText,
                ArkivoEditStorage.UNKNOWN_SIZE
        );
        @Nullable SeekableByteChannel storageChannel = null;
        try {
            if (existing != null && !truncate) {
                copyEntryToStoredContent(path, pendingContent);
            }
            LinkedHashSet<OpenOption> channelOptions = new LinkedHashSet<>();
            if (readable) {
                channelOptions.add(StandardOpenOption.READ);
            }
            if (writable) {
                channelOptions.add(StandardOpenOption.WRITE);
            }
            if (existing == null || truncate) {
                channelOptions.add(StandardOpenOption.TRUNCATE_EXISTING);
            }
            storageChannel = pendingContent.openChannel(Set.copyOf(channelOptions));
            UpdateEntryByteChannel channel = new UpdateEntryByteChannel(
                    pathText,
                    existing,
                    initialMetadata,
                    pendingContent,
                    storageChannel,
                    readable,
                    writable,
                    append,
                    writable && (existing == null || truncate)
            );
            activeUpdateChannel = channel;
            return channel;
        } catch (IOException | RuntimeException | Error exception) {
            if (storageChannel != null) {
                try {
                    storageChannel.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            releaseStoredContent(pendingContent);
            throw exception;
        }
    }

    /// Commits one staged entry body into the update index.
    private void commitUpdatedEntry(UpdateEntryByteChannel channel, ArkivoStoredContent content) throws IOException {
        if (activeUpdateChannel == channel) {
            activeUpdateChannel = null;
        }
        @Nullable SevenZipEntryMetadata existing = entries.get(channel.path());
        if (existing != channel.originalMetadata()) {
            throw new java.nio.file.FileSystemException(
                    channel.path(),
                    null,
                    "7z entry changed while its update channel was open"
            );
        }

        long size = content.size();
        SevenZipEntryMetadata metadata = existing != null
                ? copyMetadata(existing, channel.path(), size)
                : newMetadata(channel.path(), false, size, channel.initialMetadata());
        @Nullable Map<String, List<Path>> rebuiltChildren = null;
        if (existing == null) {
            LinkedHashMap<String, SevenZipEntryMetadata> candidateEntries = new LinkedHashMap<>(entries);
            candidateEntries.put(channel.path(), metadata);
            rebuiltChildren = childrenByPath(candidateEntries);
        }
        entries.put(channel.path(), metadata);
        @Nullable ArkivoStoredContent previousContent = stagedContents.put(channel.path(), content);
        if (previousContent != null) {
            releaseStoredContent(previousContent);
        }
        if (rebuiltChildren != null) {
            children.clear();
            children.putAll(rebuiltChildren);
        }
        dirty = true;
    }

    /// Prepares a previously absent path for an update-mode entry.
    private String prepareUpdateEntry(Path path) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        String pathText = normalizedPathText(path);
        if ("/".equals(pathText) || entries.containsKey(pathText) || children.containsKey(pathText)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        ensureExistingParentDirectory(pathText);
        return pathText;
    }

    /// Adds one new update entry and marks the archive dirty.
    private void addUpdateEntry(
            String path,
            SevenZipEntryMetadata metadata,
            @Nullable ArkivoStoredContent content
    ) throws IOException {
        LinkedHashMap<String, SevenZipEntryMetadata> candidateEntries = new LinkedHashMap<>(entries);
        candidateEntries.put(path, metadata);
        Map<String, List<Path>> rebuiltChildren;
        try {
            rebuiltChildren = childrenByPath(candidateEntries);
        } catch (IOException | RuntimeException | Error exception) {
            if (content != null) {
                releaseStoredContent(content);
            }
            throw exception;
        }
        entries.put(path, metadata);
        if (content != null) {
            stagedContents.put(path, content);
        }
        children.clear();
        children.putAll(rebuiltChildren);
        dirty = true;
    }

    /// Returns the update entry storage owned by this file system.
    private ArkivoEditStorage requireEditStorage() {
        return Objects.requireNonNull(editStorage, "editStorage");
    }

    /// Streams the current decoded entry body into newly allocated stored content.
    private void copyEntryToStoredContent(Path path, ArkivoStoredContent content) throws IOException {
        try (InputStream input = newInputStream(path);
             SeekableByteChannel output = content.openChannel(Set.of(
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE
             ))) {
            byte[] buffer = new byte[64 * 1024];
            ByteBuffer bytes = ByteBuffer.wrap(buffer);
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    return;
                }
                bytes.clear();
                bytes.limit(read);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
        }
    }

    /// Decodes one source-backed entry into temporary seekable storage for random reads in update mode.
    private SeekableByteChannel newStoredReadChannel(Path path) throws IOException {
        String pathText = normalizedPathText(path);
        long expectedSize = requireEntry(path).size();
        ArkivoStoredContent content = requireEditStorage().createContent(
                pathText,
                expectedSize
        );
        @Nullable SeekableByteChannel channel = null;
        try {
            copyEntryToStoredContent(path, content);
            channel = content.openChannel(Set.of(StandardOpenOption.READ));
            return new StoredContentReadByteChannel(content, channel);
        } catch (IOException | RuntimeException | Error exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            releaseStoredContent(content);
            throw exception;
        }
    }

    /// Stores one small generated body in update storage.
    private ArkivoStoredContent storeBytes(String path, byte @Unmodifiable [] bytes) throws IOException {
        ArkivoStoredContent content = requireEditStorage().createContent(path, bytes.length);
        try {
            try (SeekableByteChannel output = content.openChannel(Set.of(
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ))) {
                ByteBuffer source = ByteBuffer.wrap(bytes);
                while (source.hasRemaining()) {
                    output.write(source);
                }
            }
            return content;
        } catch (IOException | RuntimeException | Error exception) {
            releaseStoredContent(content);
            throw exception;
        }
    }

    /// Releases stored content now or retains it for a close-time cleanup retry.
    private void releaseStoredContent(ArkivoStoredContent content) {
        try {
            content.close();
        } catch (IOException | RuntimeException | Error exception) {
            retiredStoredContents.add(content);
        }
    }

    /// Rejects a structural or metadata mutation while an update channel remains open.
    private void requireNoActiveUpdateChannel(Path path) throws IOException {
        if (activeUpdateChannel != null) {
            throw new java.nio.file.FileSystemException(
                    path.toString(),
                    null,
                    "A 7z entry update channel is still open"
            );
        }
    }

    /// Requires a path's parent to exist as a directory.
    private void ensureExistingParentDirectory(String path) throws IOException {
        int separator = path.lastIndexOf('/');
        String parentPath = separator > 0 ? path.substring(0, separator) : "/";
        if (!"/".equals(parentPath)
                && !entries.containsKey(parentPath)
                && !children.containsKey(parentPath)) {
            throw new NoSuchFileException(parentPath);
        }
        @Nullable SevenZipEntryMetadata parent = entries.get(parentPath);
        if (parent != null && !parent.directory()) {
            throw new java.nio.file.FileSystemException(path, parentPath, "7z parent entry is not a directory");
        }
    }

    /// Rebuilds update-mode directory child indexes.
    private void rebuildUpdateChildren() throws IOException {
        Map<String, List<Path>> rebuilt = childrenByPath(entries);
        children.clear();
        children.putAll(rebuilt);
    }

    /// Remaps path-keyed update state after a move.
    private static <T> void remapUpdateMap(
            Map<String, T> values,
            Map<String, String> movedPaths,
            String targetPath,
            boolean targetExists
    ) {
        LinkedHashMap<String, T> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            String oldPath = entry.getKey();
            if (targetExists && oldPath.equals(targetPath) && !movedPaths.containsKey(oldPath)) {
                continue;
            }
            @Nullable String movedPath = movedPaths.get(oldPath);
            rebuilt.put(movedPath != null ? movedPath : oldPath, entry.getValue());
        }
        values.clear();
        values.putAll(rebuilt);
    }

    /// Returns metadata for a newly created update entry.
    private static SevenZipEntryMetadata newMetadata(
            String path,
            boolean directory,
            long size,
            SevenZipEntryWriteMetadata metadata
    ) {
        return new SevenZipEntryMetadata(
                writableEntryName(path),
                directory,
                size,
                SevenZipEntryMetadata.NO_DATA_OFFSET,
                0L,
                0L,
                SevenZipLZMADecoder.COPY_METHOD_ID,
                new byte[0],
                metadata.creationTime(),
                metadata.lastAccessTime(),
                metadata.lastModifiedTime(),
                metadata.windowsAttributes()
        );
    }

    /// Returns copied parsed metadata with a changed path and decoded size.
    private static SevenZipEntryMetadata copyMetadata(
            SevenZipEntryMetadata metadata,
            String path,
            long size
    ) {
        return new SevenZipEntryMetadata(
                writableEntryName(path),
                metadata.directory(),
                size,
                metadata.decodedOffset(),
                metadata.substreamIndex(),
                metadata.substreamCount(),
                metadata.packedStreams(),
                metadata.crc32(),
                metadata.method(),
                metadata.creationTime(),
                metadata.lastAccessTime(),
                metadata.lastModifiedTime(),
                metadata.windowsAttributes()
        );
    }

    /// Sets one basic timestamp attribute.
    private void setBasicAttribute(Path path, String name, @Nullable Object value) throws IOException {
        if (!(value instanceof FileTime time)) {
            throw new IllegalArgumentException("7z basic timestamp value must be FileTime: " + name);
        }
        switch (name) {
            case "lastModifiedTime" -> setTimes(path, time, null, null);
            case "lastAccessTime" -> setTimes(path, null, time, null);
            case "creationTime" -> setTimes(path, null, null, time);
            default -> throw new UnsupportedOperationException("Unsupported writable 7z basic attribute: " + name);
        }
    }

    /// Sets one POSIX attribute.
    private void setPosixAttribute(Path path, String name, @Nullable Object value) throws IOException {
        switch (name) {
            case "lastModifiedTime", "lastAccessTime", "creationTime" -> setBasicAttribute(path, name, value);
            case "owner" -> {
                if (!(value instanceof UserPrincipal owner)) {
                    throw new IllegalArgumentException("7z POSIX owner value must be UserPrincipal");
                }
                setOwner(path, owner);
            }
            case "group" -> {
                if (!(value instanceof GroupPrincipal group)) {
                    throw new IllegalArgumentException("7z POSIX group value must be GroupPrincipal");
                }
                setGroup(path, group);
            }
            case "permissions" -> {
                setPermissions(path, PosixPermissions.copyOf(value, "7z POSIX permissions"));
            }
            default -> throw new UnsupportedOperationException("Unsupported writable 7z POSIX attribute: " + name);
        }
    }

    /// Sets one 7z-specific entry property.
    private void setSevenZipAttribute(Path path, String name, @Nullable Object value) throws IOException {
        switch (name) {
            case "lastModifiedTime", "lastAccessTime", "creationTime" -> setBasicAttribute(path, name, value);
            case "windowsAttributes" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("7z windowsAttributes value must be numeric");
                }
                setWindowsAttributes(path, number.intValue());
            }
            case "unixMode" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("7z unixMode value must be numeric");
                }
                setUnixMode(path, number.intValue());
            }
            case "compression" -> {
                if (!(value instanceof SevenZipCompression compression)) {
                    throw new IllegalArgumentException("7z compression value must be SevenZipCompression");
                }
                setCompression(path, compression);
            }
            case "filter" -> {
                if (value == null) {
                    clearFilter(path);
                } else if (value instanceof SevenZipFilter filter) {
                    setFilter(path, filter);
                } else {
                    throw new IllegalArgumentException("7z filter value must be SevenZipFilter or null");
                }
            }
            case "filters" -> {
                if (!(value instanceof SevenZipFilterChain filters)) {
                    throw new IllegalArgumentException("7z filters value must be SevenZipFilterChain");
                }
                setFilters(path, filters);
            }
            default -> throw new UnsupportedOperationException("Unsupported writable 7z attribute: " + name);
        }
    }

    /// Sets entry timestamps in update mode.
    private void setTimes(
            Path path,
            @Nullable FileTime lastModifiedTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime creationTime
    ) throws IOException {
        mutateMetadata(path, metadata -> copyMetadataValues(
                metadata,
                creationTime != null ? creationTime : metadata.creationTime(),
                lastAccessTime != null ? lastAccessTime : metadata.lastAccessTime(),
                lastModifiedTime != null ? lastModifiedTime : metadata.lastModifiedTime(),
                metadata.windowsAttributes()
        ));
    }

    /// Rejects owner changes because 7z has no owner principal property.
    private void setOwner(Path path, UserPrincipal owner) {
        Objects.requireNonNull(owner, "owner");
        requireUpdateMode();
        throw new UnsupportedOperationException("7z does not store owner principals");
    }

    /// Rejects group changes because 7z has no group principal property.
    private void setGroup(Path path, GroupPrincipal group) {
        Objects.requireNonNull(group, "group");
        requireUpdateMode();
        throw new UnsupportedOperationException("7z does not store group principals");
    }

    /// Sets POSIX permissions encoded in the 7z Windows attributes property.
    private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        Objects.requireNonNull(permissions, "permissions");
        mutateMetadata(path, metadata -> copyMetadataValues(
                metadata,
                metadata.creationTime(),
                metadata.lastAccessTime(),
                metadata.lastModifiedTime(),
                SevenZipPosixSupport.withPermissions(
                        metadata.directory(),
                        metadata.windowsAttributes(),
                        permissions
                )
        ));
    }

    /// Sets the raw Windows attributes property.
    private void setWindowsAttributes(Path path, int windowsAttributes) throws IOException {
        mutateMetadata(path, metadata -> copyMetadataValues(
                metadata,
                metadata.creationTime(),
                metadata.lastAccessTime(),
                metadata.lastModifiedTime(),
                windowsAttributes
        ));
    }

    /// Sets Unix mode bits in the high half of the Windows attributes property.
    private void setUnixMode(Path path, int unixMode) throws IOException {
        if (unixMode < SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE || unixMode > 0xffff) {
            throw new IllegalArgumentException("unixMode must be an unsigned 16-bit value or UNKNOWN_UNIX_MODE");
        }
        mutateMetadata(path, metadata -> {
            int current = metadata.windowsAttributes();
            int windowsAttributes;
            if (unixMode == SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE) {
                windowsAttributes = current == SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
                        ? current
                        : current & 0xffff;
            } else {
                int lowAttributes = current == SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
                        ? 0
                        : current & 0xffff;
                windowsAttributes = lowAttributes | unixMode << 16;
            }
            return copyMetadataValues(
                    metadata,
                    metadata.creationTime(),
                    metadata.lastAccessTime(),
                    metadata.lastModifiedTime(),
                    windowsAttributes
            );
        });
    }

    /// Sets entry-specific output compression.
    private void setCompression(Path path, SevenZipCompression compression) throws IOException {
        Objects.requireNonNull(compression, "compression");
        mutateOutputSettings(path, settings -> new UpdateOutputSettings(
                compression,
                settings.filtersConfigured(),
                settings.filters()
        ));
    }

    /// Sets an entry-specific preprocessing filter.
    private void setFilter(Path path, SevenZipFilter filter) throws IOException {
        Objects.requireNonNull(filter, "filter");
        setFilters(path, SevenZipFilterChain.of(filter));
    }

    /// Sets entry-specific preprocessing filters.
    private void setFilters(Path path, SevenZipFilterChain filters) throws IOException {
        Objects.requireNonNull(filters, "filters");
        mutateOutputSettings(path, settings -> new UpdateOutputSettings(
                settings.compression(),
                true,
                filters
        ));
    }

    /// Disables preprocessing for one updated entry.
    private void clearFilter(Path path) throws IOException {
        setFilters(path, SevenZipFilterChain.EMPTY);
    }

    /// Replaces one entry's parsed metadata through an update function.
    private void mutateMetadata(Path path, MetadataMutation mutation) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        String pathText = requireExistingPath(path);
        @Nullable SevenZipEntryMetadata metadata = entries.get(pathText);
        if (metadata == null) {
            throw new java.nio.file.FileSystemException(
                    path.toString(),
                    null,
                    "Synthetic 7z directory metadata cannot be changed"
            );
        }
        entries.put(pathText, mutation.apply(metadata));
        dirty = true;
    }

    /// Replaces one entry's output settings through an update function.
    private void mutateOutputSettings(Path path, OutputSettingsMutation mutation) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        String pathText = requireExistingPath(path);
        if (!entries.containsKey(pathText)) {
            throw new java.nio.file.FileSystemException(
                    path.toString(),
                    null,
                    "Synthetic 7z directory output settings cannot be changed"
            );
        }
        UpdateOutputSettings settings =
                updateOutputSettings.getOrDefault(pathText, UpdateOutputSettings.DEFAULTS);
        updateOutputSettings.put(pathText, mutation.apply(settings));
        dirty = true;
    }

    /// Returns copied metadata with selected timestamp and attribute values.
    private static SevenZipEntryMetadata copyMetadataValues(
            SevenZipEntryMetadata metadata,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes
    ) {
        return new SevenZipEntryMetadata(
                metadata.path(),
                metadata.directory(),
                metadata.size(),
                metadata.decodedOffset(),
                metadata.substreamIndex(),
                metadata.substreamCount(),
                metadata.packedStreams(),
                metadata.crc32(),
                metadata.method(),
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                windowsAttributes
        );
    }

    /// Changes an immutable parsed metadata snapshot.
    @FunctionalInterface
    @NotNullByDefault
    private interface MetadataMutation {
        /// Returns replacement metadata.
        SevenZipEntryMetadata apply(SevenZipEntryMetadata metadata);
    }

    /// Changes immutable per-entry output settings.
    @FunctionalInterface
    @NotNullByDefault
    private interface OutputSettingsMutation {
        /// Returns replacement output settings.
        UpdateOutputSettings apply(UpdateOutputSettings settings);
    }

    /// Stores per-entry output method overrides for update rewriting.
    ///
    /// @param compression       the entry compression override, or null for the archive default
    /// @param filtersConfigured whether the filter chain overrides the archive default
    /// @param filters           the filter-chain override, or null when inherited
    @NotNullByDefault
    private record UpdateOutputSettings(
            @Nullable SevenZipCompression compression,
            boolean filtersConfigured,
            @Nullable SevenZipFilterChain filters
    ) {
        /// Validates one per-entry output override.
        private UpdateOutputSettings {
            if (filtersConfigured && filters == null) {
                throw new IllegalArgumentException("Configured update filters must not be null");
            }
            if (!filtersConfigured && filters != null) {
                throw new IllegalArgumentException("Inherited update filters must be null");
            }
        }

        /// The default settings that inherit archive output methods.
        private static final UpdateOutputSettings DEFAULTS =
                new UpdateOutputSettings(null, false, null);
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
    private void beginWritableEntry(
            String path,
            boolean directory,
            SevenZipEntryWriteMetadata metadata
    ) throws IOException {
        if (entryOpen) {
            throw new IOException("A 7z entry is already open");
        }
        requireWriter().putArchiveEntry(writableEntryName(path), directory, metadata);
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
        if ("/".equals(pathText) || entries.containsKey(pathText) || children.containsKey(pathText)) {
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
            ArkivoReadLimitTracker readLimits = ArkivoReadLimitTracker.fromLimits(
                    config.maximumEntryCount(),
                    config.maximumEntrySize(),
                    config.maximumTotalEntrySize(),
                    config.maximumMetadataSize()
            );
            SevenZipArchiveMetadata metadata = SevenZipHeaderReader.readArchiveMetadata(
                    channel,
                    config.passwordProvider(),
                    readLimits
            );
            for (SevenZipEntryMetadata entry : metadata.entries()) {
                readLimits.acceptEntry(entry.path(), entry.size());
            }
            return metadata;
        }
    }

    /// Opens the underlying archive channel.
    private SeekableByteChannel openArchiveChannel() throws IOException {
        if (archivePath != null) {
            List<Path> splitVolumePaths = SevenZipSplitVolumePaths.discover(archivePath);
            if (splitVolumePaths != null) {
                return ArkivoVolumeChannel.open(index -> {
                    if (index < 0 || index >= splitVolumePaths.size()) {
                        return null;
                    }
                    return Files.newByteChannel(splitVolumePaths.get((int) index), sourceOpenOptions());
                });
            }
            return Files.newByteChannel(archivePath, sourceOpenOptions());
        }
        if (volumes != null) {
            return ArkivoVolumeChannel.open(volumes);
        }
        throw new IOException("7z archive storage is not available");
    }

    /// Returns archive source options that never mutate update-mode input storage.
    private @Unmodifiable Set<? extends OpenOption> sourceOpenOptions() {
        return updateMode ? Set.of(StandardOpenOption.READ) : config.openOptions();
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
        LinkedHashMap<String, LinkedHashMap<String, Path>> result = new LinkedHashMap<>();
        result.put("/", new LinkedHashMap<>());
        for (String pathText : entries.keySet()) {
            Path child = getPath(pathText);
            while (true) {
                @Nullable Path parent = child.getParent();
                String parentText = parent != null ? parent.toString() : "/";
                @Nullable SevenZipEntryMetadata parentMetadata = entries.get(parentText);
                if (parentMetadata != null && !parentMetadata.directory()) {
                    throw new IOException("7z entry path conflicts with directory: " + parentMetadata.path());
                }
                result.computeIfAbsent(parentText, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(child.toString(), child);
                if (parent == null || "/".equals(parentText)) {
                    break;
                }
                child = parent;
            }
        }

        LinkedHashMap<String, List<Path>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Path>> entry : result.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue().values()));
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

    /// Exposes one transient decoded entry body as a seekable read-only channel.
    @NotNullByDefault
    private final class StoredContentReadByteChannel implements SeekableByteChannel {
        /// The transient stored body released when this channel closes.
        private final ArkivoStoredContent content;

        /// The read channel opened over the transient stored body.
        private final SeekableByteChannel channel;

        /// Whether this wrapper remains open.
        private boolean channelOpen = true;

        /// Creates a read-only channel over transient stored content.
        private StoredContentReadByteChannel(ArkivoStoredContent content, SeekableByteChannel channel) {
            this.content = Objects.requireNonNull(content, "content");
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        /// Reads decoded entry bytes.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureChannelOpen();
            return channel.read(destination);
        }

        /// Rejects writes to a read-only entry channel.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureChannelOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current decoded entry position.
        @Override
        public long position() throws IOException {
            ensureChannelOpen();
            return channel.position();
        }

        /// Changes the current decoded entry position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureChannelOpen();
            channel.position(newPosition);
            return this;
        }

        /// Returns the decoded entry size.
        @Override
        public long size() throws IOException {
            ensureChannelOpen();
            return channel.size();
        }

        /// Rejects truncation of a read-only entry channel.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureChannelOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether this wrapper remains open.
        @Override
        public boolean isOpen() {
            return channelOpen;
        }

        /// Closes the read channel and releases its transient decoded body.
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
            releaseStoredContent(content);
            throwFailure(failure);
        }

        /// Requires this wrapper to remain open.
        private void ensureChannelOpen() throws ClosedChannelException {
            if (!channelOpen) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Implements a staged random-access channel for one update-mode entry.
    @NotNullByDefault
    private final class UpdateEntryByteChannel implements SeekableByteChannel {
        /// The normalized absolute entry path.
        private final String path;

        /// The metadata present when this channel opened, or `null` for a new entry.
        private final @Nullable SevenZipEntryMetadata originalMetadata;

        /// Initial metadata applied when a new entry is committed.
        private final SevenZipEntryWriteMetadata initialMetadata;

        /// The pending stored body transferred to the archive index after a successful close.
        private final ArkivoStoredContent content;

        /// The seekable channel opened over the pending stored body.
        private final SeekableByteChannel channel;

        /// Whether reads are allowed.
        private final boolean readable;

        /// Whether writes are allowed.
        private final boolean writable;

        /// Whether every write is forced to the current end.
        private final boolean append;

        /// Whether closing this channel must update the archive index.
        private boolean changed;

        /// Whether this channel is open.
        private boolean channelOpen = true;

        /// Creates a staged update entry channel.
        private UpdateEntryByteChannel(
                String path,
                @Nullable SevenZipEntryMetadata originalMetadata,
                SevenZipEntryWriteMetadata initialMetadata,
                ArkivoStoredContent content,
                SeekableByteChannel channel,
                boolean readable,
                boolean writable,
                boolean append,
                boolean forceCommit
        ) throws IOException {
            this.path = Objects.requireNonNull(path, "path");
            this.originalMetadata = originalMetadata;
            this.initialMetadata = Objects.requireNonNull(initialMetadata, "initialMetadata");
            this.content = Objects.requireNonNull(content, "content");
            this.channel = Objects.requireNonNull(channel, "channel");
            this.readable = readable;
            this.writable = writable;
            this.append = append;
            if (append) {
                channel.position(channel.size());
            }
            this.changed = forceCommit;
        }

        /// Returns the normalized entry path.
        private String path() {
            return path;
        }

        /// Returns metadata present when this channel opened.
        private @Nullable SevenZipEntryMetadata originalMetadata() {
            return originalMetadata;
        }

        /// Returns initial metadata for a new entry.
        private SevenZipEntryWriteMetadata initialMetadata() {
            return initialMetadata;
        }

        /// Reads staged bytes from the current position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureChannelOpen();
            if (!readable) {
                throw new NonReadableChannelException();
            }
            return channel.read(destination);
        }

        /// Writes staged bytes at the current position or end in append mode.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureChannelOpen();
            if (!writable) {
                throw new NonWritableChannelException();
            }
            if (append) {
                channel.position(channel.size());
            }
            int count = channel.write(source);
            changed |= count != 0;
            return count;
        }

        /// Returns the current staged position.
        @Override
        public long position() throws IOException {
            ensureChannelOpen();
            return channel.position();
        }

        /// Changes the current staged position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureChannelOpen();
            channel.position(newPosition);
            return this;
        }

        /// Returns the current staged size.
        @Override
        public long size() throws IOException {
            ensureChannelOpen();
            return channel.size();
        }

        /// Truncates staged content.
        @Override
        public SeekableByteChannel truncate(long newSize) throws IOException {
            ensureChannelOpen();
            if (!writable) {
                throw new NonWritableChannelException();
            }
            long previousSize = channel.size();
            channel.truncate(newSize);
            if (newSize < previousSize) {
                changed = true;
            }
            return this;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return channelOpen;
        }

        /// Closes this channel and commits changed staged bytes to the update index.
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
            if (activeUpdateChannel == this) {
                activeUpdateChannel = null;
            }
            boolean transferred = false;
            if (failure == null && writable && changed) {
                try {
                    commitUpdatedEntry(this, content);
                    transferred = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            if (!transferred) {
                releaseStoredContent(content);
            }
            throwFailure(failure);
        }

        /// Ensures this channel is open.
        private void ensureChannelOpen() throws ClosedChannelException {
            if (!channelOpen) {
                throw new ClosedChannelException();
            }
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
    private final class OwnerAttributeView implements FileOwnerAttributeView {
        /// The path exposed by this view.
        private final Path path;

        /// Creates an owner attribute view.
        private OwnerAttributeView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "owner";
        }

        /// Returns the synthesized owner principal.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return SevenZipArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class).owner();
        }

        /// Rejects owner mutation because 7z stores no owner principal.
        @Override
        public void setOwner(UserPrincipal owner) {
            SevenZipArkivoFileSystemImpl.this.setOwner(path, owner);
        }
    }

    /// POSIX attribute view for one 7z path.
    @NotNullByDefault
    private final class PosixAttributeView implements PosixFileAttributeView {
        /// The path exposed by this view.
        private final Path path;

        /// Creates a POSIX attribute view.
        private PosixAttributeView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads POSIX attributes.
        @Override
        public PosixFileAttributes readAttributes() throws IOException {
            return SevenZipArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class);
        }

        /// Returns the synthesized owner principal.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        /// Sets entry timestamps in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setTimes(
                    path,
                    lastModifiedTime,
                    lastAccessTime,
                    createTime
            );
        }

        /// Rejects owner mutation because 7z stores no owner principal.
        @Override
        public void setOwner(UserPrincipal owner) {
            SevenZipArkivoFileSystemImpl.this.setOwner(path, owner);
        }

        /// Rejects group mutation because 7z stores no group principal.
        @Override
        public void setGroup(GroupPrincipal group) {
            SevenZipArkivoFileSystemImpl.this.setGroup(path, group);
        }

        /// Sets permissions encoded by Unix mode metadata.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setPermissions(path, permissions);
        }
    }

    /// Basic attribute view for one 7z path.
    @NotNullByDefault
    private final class BasicAttributeView implements BasicFileAttributeView {
        /// The path exposed by this view.
        private final Path path;

        /// Creates a basic attribute view.
        private BasicAttributeView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "basic";
        }

        /// Reads attributes.
        @Override
        public BasicFileAttributes readAttributes() throws IOException {
            return SevenZipArkivoFileSystemImpl.this.readAttributes(path, BasicFileAttributes.class);
        }

        /// Sets entry timestamps in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setTimes(
                    path,
                    lastModifiedTime,
                    lastAccessTime,
                    createTime
            );
        }
    }

    /// 7z attribute view for one archive entry path.
    @NotNullByDefault
    private final class SevenZipAttributeView implements SevenZipArkivoEntryAttributeView {
        /// The path exposed by this view.
        private final Path path;

        /// Creates a 7z attribute view.
        private SevenZipAttributeView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Reads attributes.
        @Override
        public SevenZipArkivoEntryAttributes readAttributes() throws IOException {
            return SevenZipArkivoFileSystemImpl.this.readAttributes(
                    path,
                    SevenZipArkivoEntryAttributes.class
            );
        }

        /// Sets entry timestamps in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setTimes(
                    path,
                    lastModifiedTime,
                    lastAccessTime,
                    createTime
            );
        }

        /// Sets raw Windows attributes in update mode.
        @Override
        public void setWindowsAttributes(int windowsAttributes) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setWindowsAttributes(path, windowsAttributes);
        }

        /// Sets entry-specific compression in update mode.
        @Override
        public void setCompression(SevenZipCompression compression) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setCompression(path, compression);
        }

        /// Sets an entry-specific filter in update mode.
        @Override
        public void setFilter(SevenZipFilter filter) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setFilter(path, filter);
        }

        /// Sets an entry-specific filter chain in update mode.
        @Override
        public void setFilters(SevenZipFilterChain filters) throws IOException {
            SevenZipArkivoFileSystemImpl.this.setFilters(path, filters);
        }

        /// Disables filtering for this entry in update mode.
        @Override
        public void clearFilter() throws IOException {
            SevenZipArkivoFileSystemImpl.this.clearFilter(path);
        }
    }
}

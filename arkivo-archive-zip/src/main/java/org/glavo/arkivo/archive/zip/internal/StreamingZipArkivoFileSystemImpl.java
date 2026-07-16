// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;

import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoPathVolumeTarget;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumePathLayout;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.internal.ArkivoPathMatchers;
import org.glavo.arkivo.archive.internal.ArkivoFileSystemProviderSupport;
import org.glavo.arkivo.archive.internal.FixedDirectoryStream;
import org.glavo.arkivo.archive.internal.ForwardOnlyOutputChannel;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.internal.PosixPermissions;
import org.glavo.arkivo.archive.internal.StagedSeekableByteChannel;


import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipLegacyCharsetDetector;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotLinkException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
import java.nio.file.spi.FileSystemProvider;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.zip.internal.ZipConstants.BZIP2_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DATA_DESCRIPTOR_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DATA_DESCRIPTOR_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DEFLATED_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DEFLATE64_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DEFLATE64_VERSION_NEEDED;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ENCRYPTED_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_EOS_MARKER_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_PROPERTY_SIZE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_VERSION_NEEDED;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LOCAL_FILE_HEADER_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.STORED_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT16_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT32_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UTF8_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.VERSION_NEEDED;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.WINZIP_AES_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.XZ_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_VERSION_NEEDED;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.isZstandardMethod;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.requireUInt16;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.requireUInt32;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.requireUInt64;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readInt;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readUnsignedShort;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.writeInt;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.writeLong;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.writeShort;

/// Implements forward-only ZIP creation and complete-rewrite editing of existing archives.
///
/// Complete-rewrite sessions expose existing entries and completed staged entries for reading. Direct streaming
/// creation remains write-only because emitted entry bytes cannot be recovered from the output endpoint.
@NotNullByDefault
public final class StreamingZipArkivoFileSystemImpl extends ZipArkivoFileSystem {
    /// The LZMA SDK major version stored in ZIP LZMA property headers.
    private static final int LZMA_SDK_MAJOR_VERSION = 9;

    /// The LZMA SDK minor version stored in ZIP LZMA property headers.
    private static final int LZMA_SDK_MINOR_VERSION = 20;

    /// The dictionary size written for ZIP LZMA entries.
    private static final int LZMA_DICTIONARY_SIZE = 8 * 1024 * 1024;

    /// The fixed payload size of the ZIP64 end of central directory record.
    private static final long ZIP64_END_OF_CENTRAL_DIRECTORY_PAYLOAD_SIZE = 44L;

    /// The fixed size of the ZIP64 end of central directory record.
    private static final long ZIP64_END_OF_CENTRAL_DIRECTORY_SIZE =
            Integer.BYTES + Long.BYTES + ZIP64_END_OF_CENTRAL_DIRECTORY_PAYLOAD_SIZE;

    /// The fixed size of the ZIP64 end of central directory locator.
    private static final long ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE =
            Integer.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES;

    /// The fixed size of an end of central directory record without a comment.
    private static final long END_OF_CENTRAL_DIRECTORY_SIZE =
            Integer.BYTES + Short.BYTES * 4L + Integer.BYTES * 2L + Short.BYTES;

    /// The fixed size of a local file header before variable data.
    private static final int ZIP_LOCAL_FILE_HEADER_MIN_SIZE = 30;

    /// The fixed size of a central directory file header before variable data.
    private static final int ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE = 46;

    /// The buffer size used while copying staged and existing ZIP record bytes.
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /// The staged content path used for local records written during an update.
    private static final String STAGED_RECORDS_PATH = "zip-update-records";

    /// The staged content path used for the fully assembled replacement archive.
    private static final String ASSEMBLED_ARCHIVE_PATH = "zip-assembled-archive";

    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The archive path backing this file system, or `null` for non-path input or output.
    private final @Nullable Path archivePath;

    /// The repeatable archive source used by a non-path update, or `null` otherwise.
    private final @Nullable ArkivoVolumeSource archiveSource;

    /// The transactional volume target used by an explicit multi-volume rewrite, or `null` otherwise.
    private final @Nullable ArkivoVolumeTarget rewriteVolumeTarget;

    /// The maximum output volume size for an explicit multi-volume rewrite, or `NO_SPLIT_SIZE` otherwise.
    private final long rewriteSplitSize;

    /// Whether closing the repeatable archive source has completed.
    private boolean archiveSourceClosed;

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The output stream that receives current ZIP bytes.
    private CountingOutputStream output;

    /// The commit output that receives assembled ZIP bytes before publication.
    private final @Nullable ArkivoCommitOutput commitOutput;

    /// The edit storage used by an existing-archive rewrite, or `null` for direct archive creation.
    private final @Nullable ArkivoEditStorage editStorage;

    /// The staged local records written during an existing-archive rewrite, or `null` otherwise.
    private final @Nullable ArkivoStoredContent stagedRecords;

    /// The existing archive snapshot that requires a full rewrite, or `null` for a new archive.
    private final @Nullable ZipArkivoFileSystemImpl.CentralDirectorySnapshot rewriteSnapshot;

    /// The borrowed read-only view of an archive being updated, or `null` outside complete-rewrite mode.
    private final @Nullable ZipArkivoFileSystemImpl existingArchiveReader;

    /// Uncompressed staged bodies for entries written during complete-rewrite mode.
    private final HashMap<String, ArkivoStoredContent> stagedEntryContents = new HashMap<>();

    /// The next unique storage suffix used for an uncompressed staged entry body.
    private long stagedEntrySequence;

    /// The active random-access update channel, or `null` when no entry body is being edited.
    private @Nullable StagedSeekableByteChannel activeUpdateChannel;

    /// The relocated local header locations for surviving existing entries.
    private final HashMap<String, RelocatedLocalHeader> relocatedExistingEntryOffsets = new HashMap<>();

    /// The raw archive comment written to the final end of central directory record.
    private final byte @Unmodifiable [] archiveComment;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// The entry names already written in this output session.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// The central directory entries to write when the file system closes.
    private final ArrayList<CentralEntry> centralEntries = new ArrayList<>();

    /// The symbolic link targets written during this output session, keyed by normalized entry name.
    private final HashMap<String, String> writtenSymbolicLinkTargets = new HashMap<>();

    /// The existing central directory entries copied when rewriting an archive.
    private final ArrayList<ExistingEntry> existingCentralDirectoryEntries;

    /// The current names of existing entries available in this output session.
    private final HashSet<String> existingEntryNames;

    /// The existing entry names replaced by this output session.
    private final HashSet<String> replacedEntries = new HashSet<>();

    /// The entry names deleted by this output session.
    private final HashSet<String> deletedEntries = new HashSet<>();

    /// Whether this file system may replace existing entries in append mode.
    private final boolean replaceExistingEntries;

    /// The currently open entry output stream, or `null` when no entry is active.
    private @Nullable EntryOutputStream currentEntryOutput;

    /// Whether this file system is open.
    private boolean open = true;

    /// Whether closure of the current output stream has completed.
    private boolean outputClosed;

    /// The action to run after this file system is closed, or `null` when no action is needed.
    private final @Nullable Runnable closeAction;

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            Path archivePath,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        this(provider, archivePath, config, null, false);
    }

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            Path archivePath,
            ZipArkivoFileSystemConfig config,
            @Nullable Runnable closeAction
    ) throws IOException {
        this(provider, archivePath, config, closeAction, false);
    }

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            Path archivePath,
            ZipArkivoFileSystemConfig config,
            @Nullable Runnable closeAction,
            boolean replaceExistingEntries
    ) throws IOException {
        super(config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.archiveSource = null;
        this.rewriteVolumeTarget = null;
        this.rewriteSplitSize = ZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
        this.archiveSourceClosed = true;
        this.config = Objects.requireNonNull(config, "config");
        this.lock = ZipLocks.create(config.threadSafety());
        this.closeAction = closeAction;
        this.replaceExistingEntries = replaceExistingEntries;
        boolean append = config.openOptions().contains(StandardOpenOption.APPEND);
        ArkivoCommitTarget commitTarget = config.commitTarget();
        long splitSize = config.splitSize();
        if (append && splitSize != ZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            throw new UnsupportedOperationException("ZIP append mode does not support split output");
        }
        ZipArkivoFileSystemImpl.CentralDirectorySnapshot appendSnapshot =
                append ? readAppendSnapshot(archivePath, config) : null;
        if (appendSnapshot != null) {
            requireCommitTargetSourceOptions(archivePath, config.openOptions());
            ArkivoEditStorage openedEditStorage = config.editStorage();
            if (openedEditStorage == null) {
                openedEditStorage = ArkivoEditStorage.temporaryFiles(defaultEditStorageDirectory(archivePath));
            }
            @Nullable ArkivoStoredContent openedStagedRecords = null;
            @Nullable SeekableByteChannel outputChannel = null;
            @Nullable OutputStream outputStream = null;
            try {
                openedStagedRecords = openedEditStorage.createContent(
                        STAGED_RECORDS_PATH,
                        ArkivoEditStorage.UNKNOWN_SIZE
                );
                outputChannel = openedStagedRecords.openChannel(Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ));
                outputStream = Channels.newOutputStream(outputChannel);
                this.output = new CountingOutputStream(outputStream, 0L);
                outputStream = null;
                outputChannel = null;
            } catch (IOException | RuntimeException | Error exception) {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                } else if (outputChannel != null) {
                    try {
                        outputChannel.close();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                if (openedStagedRecords != null) {
                    try {
                        openedStagedRecords.close();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    openedEditStorage.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
            this.commitOutput = null;
            this.editStorage = openedEditStorage;
            this.stagedRecords = openedStagedRecords;
            this.rewriteSnapshot = appendSnapshot;
            this.archiveComment = appendSnapshot.archiveComment();
        } else if (commitTarget != null) {
            if (splitSize != ZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
                throw new UnsupportedOperationException("ZIP split streaming writes do not support commit targets");
            }
            requireCommitTargetSourceOptions(archivePath, config.openOptions());
            ArkivoCommitOutput openedCommitOutput = commitTarget.openOutput(archivePath);
            @Nullable SeekableByteChannel outputChannel = null;
            @Nullable OutputStream outputStream = null;
            try {
                outputChannel = openedCommitOutput.openChannel(Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ));
                outputStream = Channels.newOutputStream(outputChannel);
                this.output = new CountingOutputStream(outputStream, 0L);
                outputStream = null;
                outputChannel = null;
            } catch (IOException | RuntimeException | Error exception) {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                } else if (outputChannel != null) {
                    try {
                        outputChannel.close();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    openedCommitOutput.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
            this.commitOutput = openedCommitOutput;
            this.editStorage = null;
            this.stagedRecords = null;
            this.rewriteSnapshot = null;
            this.archiveComment = new byte[0];
        } else {
            this.commitOutput = null;
            this.editStorage = null;
            this.stagedRecords = null;
            this.rewriteSnapshot = null;
            this.archiveComment = new byte[0];
            if (splitSize != ZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
                this.output = SplitCountingOutputStream.open(
                        new PathVolumeTarget(archivePath, config.openOptions()),
                        splitSize
                );
            } else {
                this.output = new CountingOutputStream(
                        Files.newOutputStream(archivePath, config.openOptions().toArray(OpenOption[]::new)),
                        0L
                );
            }
        }
        if (appendSnapshot != null) {
            this.existingCentralDirectoryEntries = existingEntries(appendSnapshot.entries());
            this.existingEntryNames = new HashSet<>(appendSnapshot.entryNames());
            if (!replaceExistingEntries) {
                this.writtenEntries.addAll(appendSnapshot.entryNames());
            }
        } else {
            this.existingCentralDirectoryEntries = new ArrayList<>();
            this.existingEntryNames = new HashSet<>();
        }
        this.existingArchiveReader = appendSnapshot != null
                ? ZipArkivoFileSystemImpl.openUpdateReader(provider, archivePath, config)
                : null;
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Reads the existing central directory snapshot required by append mode.
    private static @Nullable ZipArkivoFileSystemImpl.CentralDirectorySnapshot readAppendSnapshot(
            Path archivePath,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        Set<OpenOption> options = config.openOptions();
        if (options.contains(StandardOpenOption.CREATE_NEW)) {
            return null;
        }
        if (!Files.exists(archivePath)) {
            if (options.contains(StandardOpenOption.CREATE)) {
                return null;
            }
            throw new NoSuchFileException(archivePath.toString());
        }
        return ZipArkivoFileSystemImpl.readCentralDirectorySnapshot(archivePath, config);
    }

    /// Wraps immutable source snapshots with mutable-session entry names.
    private static ArrayList<ExistingEntry> existingEntries(
            List<ZipArkivoFileSystemImpl.CentralDirectoryEntrySnapshot> snapshots
    ) {
        ArrayList<ExistingEntry> entries = new ArrayList<>(snapshots.size());
        for (ZipArkivoFileSystemImpl.CentralDirectoryEntrySnapshot snapshot : snapshots) {
            entries.add(ExistingEntry.original(snapshot));
        }
        return entries;
    }

    /// Returns the directory used by default temporary edit and commit storage.
    private static Path defaultEditStorageDirectory(Path archivePath) {
        Path absolutePath = archivePath.toAbsolutePath();
        Path parent = absolutePath.getParent();
        return parent != null ? parent : absolutePath;
    }

    /// Opens a complete-rewrite ZIP update over an owned repeatable single-volume source.
    public static StreamingZipArkivoFileSystemImpl openUpdate(
            ZipArkivoFileSystemProvider provider,
            ArkivoSeekableChannelSource source,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(config, "config");
        try {
            if (!config.archiveWritable() || !config.openOptions().contains(StandardOpenOption.APPEND)) {
                throw new UnsupportedOperationException(
                        "ZIP channel-source updates require READ and WRITE archive options"
                );
            }
            if (config.commitTarget() == null) {
                throw new IllegalArgumentException(
                        "ZIP channel-source update mode requires ArkivoFileSystem.COMMIT_TARGET"
                );
            }
            if (config.splitSize() != ZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
                throw new UnsupportedOperationException("ZIP channel-source updates do not support split output");
            }
            if (config.sourceMutationPolicy() != null) {
                throw new UnsupportedOperationException(
                        "ZIP channel-source update mode always performs a complete archive rewrite"
                );
            }
            return new StreamingZipArkivoFileSystemImpl(
                    provider,
                    source,
                    null,
                    ZipArkivoFileSystemConfig.NO_SPLIT_SIZE,
                    config
            );
        } catch (IOException | RuntimeException | Error exception) {
            try {
                source.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    /// Opens a complete-rewrite ZIP update over an owned multi-volume source.
    public static StreamingZipArkivoFileSystemImpl openUpdate(
            ZipArkivoFileSystemProvider provider,
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(config, "config");
        try {
            if (!config.archiveWritable() || !config.openOptions().contains(StandardOpenOption.APPEND)) {
                throw new UnsupportedOperationException(
                        "ZIP volume updates require read/write archive options"
                );
            }
            if (splitSize < ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
                    || splitSize > ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE) {
                throw new IllegalArgumentException(
                        "splitSize must be between MINIMUM_SPLIT_SIZE and MAXIMUM_SPLIT_SIZE"
                );
            }
            if (config.commitTarget() != null) {
                throw new UnsupportedOperationException("ZIP volume updates use an ArkivoVolumeTarget");
            }
            if (config.splitSize() != ZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
                throw new UnsupportedOperationException(
                        "ZIP volume update splitSize must be provided as the factory argument"
                );
            }
            if (config.sourceMutationPolicy() != null) {
                throw new UnsupportedOperationException(
                        "ZIP volume updates always perform a complete archive rewrite"
                );
            }
            return new StreamingZipArkivoFileSystemImpl(provider, source, target, splitSize, config);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                source.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    /// Creates a complete-rewrite ZIP editor over a repeatable volume source.
    private StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            ArkivoVolumeSource source,
            @Nullable ArkivoVolumeTarget rewriteVolumeTarget,
            long rewriteSplitSize,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        super(config.threadSafety());
        this.provider = provider;
        this.archivePath = null;
        this.archiveSource = source;
        this.rewriteVolumeTarget = rewriteVolumeTarget;
        this.rewriteSplitSize = rewriteSplitSize;
        this.archiveSourceClosed = false;
        this.config = config;
        this.lock = ZipLocks.create(config.threadSafety());
        this.closeAction = null;
        this.replaceExistingEntries = true;

        ZipArkivoFileSystemImpl.CentralDirectorySnapshot snapshot =
                ZipArkivoFileSystemImpl.readCentralDirectorySnapshot(source, config);
        ArkivoEditStorage openedEditStorage = config.editStorage();
        if (openedEditStorage == null) {
            openedEditStorage = ArkivoEditStorage.temporaryFiles(defaultEditStorageDirectory());
        }
        @Nullable ArkivoStoredContent openedStagedRecords = null;
        @Nullable SeekableByteChannel outputChannel = null;
        @Nullable OutputStream outputStream = null;
        try {
            openedStagedRecords = openedEditStorage.createContent(
                    STAGED_RECORDS_PATH,
                    ArkivoEditStorage.UNKNOWN_SIZE
            );
            outputChannel = openedStagedRecords.openChannel(Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ));
            outputStream = Channels.newOutputStream(outputChannel);
            this.output = new CountingOutputStream(outputStream, 0L);
            outputStream = null;
            outputChannel = null;
        } catch (IOException | RuntimeException | Error exception) {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            } else if (outputChannel != null) {
                try {
                    outputChannel.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            if (openedStagedRecords != null) {
                try {
                    openedStagedRecords.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            try {
                openedEditStorage.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }

        this.commitOutput = null;
        this.editStorage = openedEditStorage;
        this.stagedRecords = openedStagedRecords;
        this.rewriteSnapshot = snapshot;
        this.archiveComment = snapshot.archiveComment();
        this.existingCentralDirectoryEntries = existingEntries(snapshot.entries());
        this.existingEntryNames = new HashSet<>(snapshot.entryNames());
        this.existingArchiveReader = ZipArkivoFileSystemImpl.openUpdateReader(provider, source, config);
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Returns the system temporary directory used for non-path update staging.
    private static Path defaultEditStorageDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
    }

    /// Creates a streaming ZIP archive file system over a writable channel.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            WritableByteChannel output,
            ZipArkivoFileSystemConfig config
    ) {
        super(config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = null;
        this.archiveSource = null;
        this.rewriteVolumeTarget = null;
        this.rewriteSplitSize = ZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
        this.archiveSourceClosed = true;
        this.config = Objects.requireNonNull(config, "config");
        this.lock = ZipLocks.create(config.threadSafety());
        this.closeAction = null;
        this.commitOutput = null;
        this.editStorage = null;
        this.stagedRecords = null;
        this.rewriteSnapshot = null;
        this.archiveComment = new byte[0];
        this.replaceExistingEntries = false;
        if (config.splitSize() != ZipArkivoFileSystemConfig.NO_SPLIT_SIZE) {
            throw new UnsupportedOperationException("ZIP split streaming writes require an archive path");
        }
        this.output = new CountingOutputStream(
                StreamChannelAdapters.outputStream(Objects.requireNonNull(output, "output")),
                0L
        );
        this.existingCentralDirectoryEntries = new ArrayList<>();
        this.existingEntryNames = new HashSet<>();
        this.existingArchiveReader = null;
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Creates a streaming ZIP archive file system over an output stream.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            OutputStream output,
            ZipArkivoFileSystemConfig config
    ) {
        this(
                provider,
                StreamChannelAdapters.writableChannel(Objects.requireNonNull(output, "output")),
                config
        );
    }

    /// Creates a streaming ZIP archive file system over a transactional volume target.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        super(config.threadSafety());
        if (splitSize < ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
                || splitSize > ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE) {
            throw new IllegalArgumentException(
                    "splitSize must be between MINIMUM_SPLIT_SIZE and MAXIMUM_SPLIT_SIZE"
            );
        }
        if (config.commitTarget() != null) {
            throw new UnsupportedOperationException("ZIP volume target output does not support commit targets");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = null;
        this.archiveSource = null;
        this.rewriteVolumeTarget = null;
        this.rewriteSplitSize = ZipArkivoFileSystemConfig.NO_SPLIT_SIZE;
        this.archiveSourceClosed = true;
        this.config = Objects.requireNonNull(config, "config");
        this.lock = ZipLocks.create(config.threadSafety());
        this.closeAction = null;
        this.commitOutput = null;
        this.editStorage = null;
        this.stagedRecords = null;
        this.rewriteSnapshot = null;
        this.archiveComment = new byte[0];
        this.replaceExistingEntries = false;
        this.output = SplitCountingOutputStream.open(Objects.requireNonNull(target, "target"), splitSize);
        this.existingCentralDirectoryEntries = new ArrayList<>();
        this.existingEntryNames = new HashSet<>();
        this.existingArchiveReader = null;
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Returns the archive URI used by ZIP path URI conversion.
    public URI archiveUri() {
        Path path = archivePath;
        if (path == null) {
            throw new UnsupportedOperationException("Streaming ZIP output paths backed by non-path output cannot be converted to URIs");
        }
        return path.toUri().normalize();
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and finishes the output archive.
    @Override
    public void close() throws IOException {
        try (CloseOperation ignored = beginCloseOperation()) {
            lock();
            try {
                if (!open) {
                    @Nullable Throwable failure = null;
                    if (!outputClosed) {
                        try {
                            output.close();
                            outputClosed = true;
                        } catch (IOException | RuntimeException | Error exception) {
                            failure = exception;
                        }
                    }
                    failure = closeExistingArchiveReader(failure);
                    failure = closeArchiveSource(failure);
                    throwFailure(failure);
                    return;
                }
                open = false;
                Throwable failure = null;
                boolean rewriteExistingArchive = rewriteSnapshot != null;
                try {
                    EntryOutputStream entryOutput = currentEntryOutput;
                    if (entryOutput != null) {
                        entryOutput.close();
                    }
                    if (!rewriteExistingArchive) {
                        writeCentralDirectory();
                    }
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                    abortSplitOutput(output);
                } finally {
                    try {
                        output.close();
                        outputClosed = true;
                    } catch (IOException | RuntimeException | Error exception) {
                        failure = appendFailure(failure, exception);
                    }
                }
                if (rewriteExistingArchive) {
                    if (failure == null) {
                        try {
                            rewriteExistingArchive();
                        } catch (IOException | RuntimeException | Error exception) {
                            failure = exception;
                        }
                    }
                    failure = closeRewriteStorage(failure);
                } else {
                    failure = finishCommitOutput(failure);
                }
                failure = closeExistingArchiveReader(failure);
                failure = closeArchiveSource(failure);
                Runnable action = closeAction;
                try {
                    if (action != null) {
                        action.run();
                    }
                } catch (RuntimeException | Error exception) {
                    failure = appendFailure(failure, exception);
                }
                throwFailure(failure);
            } finally {
                unlock();
            }
        }
    }

    /// Reassembles an existing archive from surviving local records and publishes the complete result.
    private void rewriteExistingArchive() throws IOException {
        ArkivoVolumeTarget volumeTarget = rewriteVolumeTarget;
        if (volumeTarget != null) {
            try (SplitCountingOutputStream splitOutput =
                         SplitCountingOutputStream.open(volumeTarget, rewriteSplitSize)) {
                output = splitOutput;
                try {
                    copySurvivingLocalRecords();
                    writeCentralDirectory();
                } catch (IOException | RuntimeException | Error exception) {
                    splitOutput.abort();
                    throw exception;
                }
            }
            return;
        }

        ArkivoEditStorage storage = Objects.requireNonNull(editStorage, "editStorage");
        try (ArkivoStoredContent assembledArchive = storage.createContent(
                ASSEMBLED_ARCHIVE_PATH,
                ArkivoEditStorage.UNKNOWN_SIZE
        )) {
            try (SeekableByteChannel assembledChannel = assembledArchive.openChannel(Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )); CountingOutputStream assembledOutput = new CountingOutputStream(
                    Channels.newOutputStream(assembledChannel),
                    0L
            )) {
                output = assembledOutput;
                copySurvivingLocalRecords();
                writeCentralDirectory();
            }
            publishAssembledArchive(assembledArchive);
        }
    }

    /// Marks a transactional split output for rollback when finalization fails outside a write operation.
    private static void abortSplitOutput(CountingOutputStream output) {
        if (output instanceof SplitCountingOutputStream splitOutput) {
            splitOutput.abort();
        }
    }

    /// Copies the preamble and every surviving local record into the assembled archive.
    private void copySurvivingLocalRecords() throws IOException {
        ArkivoStoredContent records = Objects.requireNonNull(stagedRecords, "stagedRecords");
        ZipArkivoFileSystemImpl.CentralDirectorySnapshot snapshot =
                Objects.requireNonNull(rewriteSnapshot, "rewriteSnapshot");
        relocatedExistingEntryOffsets.clear();

        try (SeekableByteChannel source = openExistingArchiveChannel();
             SeekableByteChannel staged = records.openChannel(Set.of(StandardOpenOption.READ))) {
            copyRange(source, snapshot.preambleOffset(), snapshot.preambleSize(), output);

            ArrayList<ExistingEntry> survivingEntries = new ArrayList<>();
            for (ExistingEntry entry : existingCentralDirectoryEntries) {
                if (!replacedEntries.contains(entry.entryName()) && !deletedEntries.contains(entry.entryName())) {
                    survivingEntries.add(entry);
                }
            }
            survivingEntries.sort(Comparator.comparingLong(
                    entry -> entry.source().localHeaderOffset()
            ));
            for (ExistingEntry entry : survivingEntries) {
                LocalRecordRewrite rewrite = localRecordRewrite(
                        source,
                        entry.source().localHeaderOffset(),
                        entry.source().localHeaderSize(),
                        entry.renamed() ? entry.rawName() : null,
                        entry.localTimestampChanged() ? entry.dosTime() : -1,
                        entry.localTimestampChanged() ? entry.dosDate() : -1
                );
                output.startRecord(rewrite.finalHeaderSize());
                relocatedExistingEntryOffsets.put(
                        entry.entryName(),
                        new RelocatedLocalHeader(output.diskNumber(), output.diskPosition())
                );
                copyLocalRecord(
                        source,
                        entry.source().localHeaderOffset(),
                        entry.source().localHeaderSize(),
                        entry.source().localRecordSize(),
                        rewrite
                );
            }

            for (CentralEntry entry : centralEntries) {
                LocalRecordRewrite rewrite = localRecordRewrite(
                        staged,
                        entry.localHeaderOffset,
                        entry.localHeaderSize,
                        entry.renamed() ? entry.rawName : null,
                        entry.localTimestampChanged ? entry.dosTime : -1,
                        entry.localTimestampChanged ? entry.dosDate : -1
                );
                output.startRecord(rewrite.finalHeaderSize());
                entry.relocatedLocalHeaderDiskNumber = output.diskNumber();
                entry.relocatedLocalHeaderOffset = output.diskPosition();
                copyLocalRecord(
                        staged,
                        entry.localHeaderOffset,
                        entry.localHeaderSize,
                        entry.localRecordSize,
                        rewrite
                );
            }
        }
    }

    /// Opens the original archive used by a complete-rewrite update.
    private SeekableByteChannel openExistingArchiveChannel() throws IOException {
        @Nullable Path path = archivePath;
        if (path != null) {
            return Files.newByteChannel(path, StandardOpenOption.READ);
        }
        return ZipArkivoFileSystemImpl.openLogicalArchiveChannel(
                Objects.requireNonNull(archiveSource, "archiveSource")
        );
    }

    /// Publishes an assembled archive through the configured commit target.
    private void publishAssembledArchive(ArkivoStoredContent assembledArchive) throws IOException {
        @Nullable Path sourcePath = archivePath;
        ArkivoCommitTarget target = config.commitTarget();
        if (target == null) {
            Path path = Objects.requireNonNull(sourcePath, "archivePath");
            target = ArkivoCommitTarget.atomicReplace(defaultEditStorageDirectory(path));
        }

        ArkivoCommitOutput openedOutput = target.openOutput(sourcePath);
        Throwable failure = null;
        try {
            try (SeekableByteChannel source = assembledArchive.openChannel(Set.of(StandardOpenOption.READ));
                 SeekableByteChannel destination = openedOutput.openChannel(Set.of(
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE
                 )); OutputStream destinationOutput = Channels.newOutputStream(destination)) {
                copyRange(source, 0L, assembledArchive.size(), destinationOutput);
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            if (failure == null) {
                openedOutput.commit();
            } else {
                openedOutput.rollback();
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            openedOutput.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        throwFailure(failure);
    }

    /// Copies an exact byte range from a seekable source to an output stream.
    private static void copyRange(
            SeekableByteChannel source,
            long offset,
            long length,
            OutputStream destination
    ) throws IOException {
        if (offset < 0 || length < 0) {
            throw new IOException("ZIP copy range must not be negative");
        }
        source.position(offset);
        ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
        long remaining = length;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(remaining, buffer.capacity()));
            int count = source.read(buffer);
            if (count < 0) {
                throw new IOException("Unexpected end of ZIP storage while copying a local record");
            }
            if (count == 0) {
                continue;
            }
            destination.write(buffer.array(), 0, count);
            remaining -= count;
        }
    }

    /// Prepares a local record header for name or timestamp changes.
    private static LocalRecordRewrite localRecordRewrite(
            SeekableByteChannel source,
            long localHeaderOffset,
            long sourceHeaderSize,
            byte @Nullable [] renamedRawName,
            int replacementDosTime,
            int replacementDosDate
    ) throws IOException {
        boolean timestampChanged = replacementDosTime >= 0 || replacementDosDate >= 0;
        if (renamedRawName == null && !timestampChanged) {
            return LocalRecordRewrite.unchanged(sourceHeaderSize);
        }
        if ((replacementDosTime < 0) != (replacementDosDate < 0)) {
            throw new IllegalArgumentException("DOS time and date replacements must be provided together");
        }
        if (sourceHeaderSize < ZIP_LOCAL_FILE_HEADER_MIN_SIZE || sourceHeaderSize > Integer.MAX_VALUE) {
            throw new IOException("Invalid ZIP local file header size");
        }

        byte[] fixedHeader = readExactRange(source, localHeaderOffset, ZIP_LOCAL_FILE_HEADER_MIN_SIZE);
        ByteBuffer header = ByteBuffer.wrap(fixedHeader).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP local file header");
        }
        int sourceNameLength = Short.toUnsignedInt(header.getShort(26));
        int sourceExtraLength = Short.toUnsignedInt(header.getShort(28));
        if (ZIP_LOCAL_FILE_HEADER_MIN_SIZE + (long) sourceNameLength + sourceExtraLength != sourceHeaderSize) {
            throw new IOException("ZIP local file header size does not match its variable data");
        }

        byte[] rawName = renamedRawName != null
                ? renamedRawName
                : readExactRange(source, localHeaderOffset + ZIP_LOCAL_FILE_HEADER_MIN_SIZE, sourceNameLength);
        long extraOffset = localHeaderOffset + ZIP_LOCAL_FILE_HEADER_MIN_SIZE + sourceNameLength;
        byte[] sourceExtraData = readExactRange(source, extraOffset, sourceExtraLength);
        byte[] localExtraData = renamedRawName != null
                ? removeUnicodePathExtraField(sourceExtraData)
                : sourceExtraData;
        requireUInt16(rawName.length, "entry name length");
        requireUInt16(localExtraData.length, "local extra data length");
        if (renamedRawName != null) {
            header.putShort(6, (short) (Short.toUnsignedInt(header.getShort(6)) | UTF8_FLAG));
        }
        if (timestampChanged) {
            header.putShort(10, (short) replacementDosTime);
            header.putShort(12, (short) replacementDosDate);
        }
        header.putShort(26, (short) rawName.length);
        header.putShort(28, (short) localExtraData.length);
        return new LocalRecordRewrite(fixedHeader, rawName, localExtraData);
    }

    /// Copies one local record, replacing only its header when the entry was renamed.
    private void copyLocalRecord(
            SeekableByteChannel source,
            long localHeaderOffset,
            long sourceHeaderSize,
            long sourceRecordSize,
            LocalRecordRewrite rewrite
    ) throws IOException {
        if (!rewrite.changed()) {
            copyRange(source, localHeaderOffset, sourceRecordSize, output);
            return;
        }
        output.write(rewrite.fixedHeader());
        output.write(rewrite.rawName());
        output.write(rewrite.localExtraData());
        copyRange(
                source,
                localHeaderOffset + sourceHeaderSize,
                sourceRecordSize - sourceHeaderSize,
                output
        );
    }

    /// Reads an exact byte range from seekable storage.
    private static byte[] readExactRange(SeekableByteChannel source, long offset, int length) throws IOException {
        byte[] bytes = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        source.position(offset);
        while (buffer.hasRemaining()) {
            int count = source.read(buffer);
            if (count < 0) {
                throw new IOException("Unexpected end of ZIP storage while reading a local record header");
            }
        }
        return bytes;
    }

    /// Closes the borrowed read-only archive view and returns the accumulated failure.
    private @Nullable Throwable closeExistingArchiveReader(@Nullable Throwable failure) {
        ZipArkivoFileSystemImpl reader = existingArchiveReader;
        if (reader != null && reader.isOpen()) {
            try {
                reader.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        return failure;
    }

    /// Closes the owned repeatable archive source and returns the accumulated failure.
    private @Nullable Throwable closeArchiveSource(@Nullable Throwable failure) {
        if (!archiveSourceClosed) {
            try {
                Objects.requireNonNull(archiveSource, "archiveSource").close();
                archiveSourceClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        return failure;
    }

    /// Closes rewrite staging resources and returns the accumulated failure.
    private @Nullable Throwable closeRewriteStorage(@Nullable Throwable failure) {
        Iterator<ArkivoStoredContent> entryContents = stagedEntryContents.values().iterator();
        while (entryContents.hasNext()) {
            ArkivoStoredContent content = entryContents.next();
            try {
                content.close();
                entryContents.remove();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }

        ArkivoStoredContent records = stagedRecords;
        if (records != null) {
            try {
                records.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        ArkivoEditStorage storage = editStorage;
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        return failure;
    }

    /// Applies source-path open option checks when the actual bytes are staged through a commit target.
    private static void requireCommitTargetSourceOptions(Path archivePath, Set<? extends OpenOption> options)
            throws IOException {
        if (options.contains(StandardOpenOption.CREATE_NEW) && Files.exists(archivePath)) {
            throw new FileAlreadyExistsException(archivePath.toString());
        }
        if (!options.contains(StandardOpenOption.CREATE)
                && !options.contains(StandardOpenOption.CREATE_NEW)
                && !Files.exists(archivePath)) {
            throw new NoSuchFileException(archivePath.toString());
        }
    }

    /// Applies final-path open option checks before writing split ZIP volumes.
    private static void requireSplitTargetOptions(Path archivePath, Set<? extends OpenOption> options)
            throws IOException {
        if (options.contains(StandardOpenOption.CREATE_NEW) && Files.exists(archivePath)) {
            throw new FileAlreadyExistsException(archivePath.toString());
        }
        if (!options.contains(StandardOpenOption.CREATE)
                && !options.contains(StandardOpenOption.CREATE_NEW)
                && !Files.exists(archivePath)) {
            throw new NoSuchFileException(archivePath.toString());
        }
    }

    /// Commits or rolls back the staged commit output and returns the accumulated failure.
    private @Nullable Throwable finishCommitOutput(@Nullable Throwable failure) {
        ArkivoCommitOutput output = commitOutput;
        if (output == null) {
            return failure;
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
        return failure;
    }

    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure != null) {
            failure.addSuppressed(exception);
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

    /// Returns whether this ZIP file system is open.
    @Override
    public boolean isOpen() {
        lock();
        try {
            return open;
        } finally {
            unlock();
        }
    }

    /// Returns whether this ZIP file system rejects write operations.
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /// Returns the ZIP entry path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the root directory path.
    @Override
    public @Unmodifiable Iterable<Path> getRootDirectories() {
        try (Operation ignored = beginReadOperation()) {
            return List.of(rootPath);
        }
    }

    /// Returns the file stores exposed by this ZIP file system.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        try (Operation ignored = beginReadOperation()) {
            return List.of(StreamingZipFileStore.INSTANCE);
        }
    }

    /// Returns the supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        try (Operation ignored = beginReadOperation()) {
            return Set.of("basic", "zip", "owner", "posix");
        }
    }

    /// Returns a path inside this ZIP file system.
    @Override
    public Path getPath(String first, String... more) {
        try (Operation ignored = beginReadOperation()) {
            checkOpen();
            return ZipArkivoPath.of(this, first, more);
        }
    }

    /// Returns a path matcher for paths in this ZIP file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        try (Operation ignored = beginReadOperation()) {
            checkOpen();
            return ArkivoPathMatchers.create(syntaxAndPattern, '/');
        }
    }

    /// Always rejects watch services because ZIP watch services are not supported.
    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("ZIP watch services are not supported");
    }

    /// Checks whether the given path can be accessed in streaming creation or complete-rewrite mode.
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            checkAccessLocked(path, modes);
        }
    }

    /// Checks path access while the caller holds the shared operation lock.
    private void checkAccessLocked(Path path, AccessMode... modes) throws IOException {
        lock();
        try {
            checkOpen();
            String entryName = entryName(path);
            if (!entryName.isEmpty() && !visibleEntry(entryName) && !visibleDirectory(entryName)) {
                throw new NoSuchFileException(path.toString());
            }
            for (AccessMode mode : modes) {
                Objects.requireNonNull(mode, "mode");
                if (mode == AccessMode.READ && rewriteSnapshot == null) {
                    throw new UnsupportedOperationException(
                            "Direct streaming ZIP creation does not expose readable entry bodies"
                    );
                }
                if (mode == AccessMode.EXECUTE) {
                    throw new UnsupportedOperationException("ZIP entries do not support execute access checks");
                }
            }
        } finally {
            unlock();
        }
    }

    /// Opens a directory stream over entries visible in the current output session.
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return manageDirectoryStream(newDirectoryStreamLocked(directory, filter));
        }
    }

    /// Opens a directory stream while the caller holds the shared operation lock.
    private DirectoryStream<Path> newDirectoryStreamLocked(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        Objects.requireNonNull(filter, "filter");
        ArrayList<Path> paths = new ArrayList<>();
        lock();
        try {
            checkOpen();
            String directoryName = entryName(directory);
            if (!readZipAttributesLocked(directory, directoryName).isDirectory()) {
                throw new NoSuchFileException(directory.toString());
            }

            for (String child : visibleChildNames(directoryName)) {
                Path childPath = getPath("/" + child);
                try {
                    if (filter.accept(childPath)) {
                        paths.add(childPath);
                    }
                } catch (IOException exception) {
                    throw new DirectoryIteratorException(exception);
                }
            }
        } finally {
            unlock();
        }
        return new FixedDirectoryStream<>(paths);
    }

    /// Returns an attribute view for an entry path visible in the current output session.
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        try (Operation ignored = beginReadOperation()) {
            return getFileAttributeViewLocked(path, type, options);
        }
    }

    /// Returns an attribute view while the caller holds the shared operation lock.
    private <V extends FileAttributeView> @Nullable V getFileAttributeViewLocked(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        Objects.requireNonNull(type, "type");
        ArkivoFileSystemProviderSupport.AttributeViewPath viewPath =
                ArkivoFileSystemProviderSupport.attributeViewPath(path, options);
        if (type == BasicFileAttributeView.class || type == ZipArkivoEntryAttributeView.class) {
            return type.cast(new EntryAttributeView(this, viewPath));
        }
        if (type == FileOwnerAttributeView.class) {
            return type.cast(new OwnerEntryAttributeView(this, viewPath));
        }
        if (type == PosixFileAttributeView.class) {
            return type.cast(new PosixEntryAttributeView(this, viewPath));
        }
        return null;
    }

    /// Reads typed attributes for an entry path visible in the current output session.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readAttributesLocked(path, type);
        }
    }

    /// Reads typed attributes while the caller holds the shared operation lock.
    private <A extends BasicFileAttributes> A readAttributesLocked(Path path, Class<A> type) throws IOException {
        Objects.requireNonNull(type, "type");
        if (type == BasicFileAttributes.class || type == ZipArkivoEntryAttributes.class) {
            return type.cast(readZipAttributes(path));
        }
        if (type == PosixFileAttributes.class) {
            return type.cast(readZipAttributes(path));
        }
        throw new UnsupportedOperationException("Unsupported ZIP attribute type: " + type.getName());
    }

    /// Reads named attributes for an entry path visible in the current output session.
    public Map<String, Object> readAttributes(Path path, String attributes) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readAttributesLocked(path, attributes);
        }
    }

    /// Reads named attributes while the caller holds the shared operation lock.
    private Map<String, Object> readAttributesLocked(Path path, String attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        int separator = attributes.indexOf(':');
        String view = separator >= 0 ? attributes.substring(0, separator) : "basic";
        String names = separator >= 0 ? attributes.substring(separator + 1) : attributes;
        if (!"basic".equals(view) && !"zip".equals(view) && !"owner".equals(view) && !"posix".equals(view)) {
            throw new UnsupportedOperationException("Unsupported ZIP attribute view: " + view);
        }

        ZipArkivoEntryAttributes zipAttributes = readZipAttributes(path);
        PosixFileAttributes posixAttributes = zipAttributes;
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if ("*".equals(names)) {
            if ("owner".equals(view)) {
                addOwnerAttributes(values, posixAttributes);
            } else {
                addBasicAttributes(values, zipAttributes);
            }
            if ("zip".equals(view)) {
                addZipAttributes(values, zipAttributes);
            } else if ("posix".equals(view)) {
                addPosixAttributes(values, posixAttributes);
            }
            return Collections.unmodifiableMap(values);
        }

        for (String name : names.split(",")) {
            addNamedAttribute(values, zipAttributes, posixAttributes, name.trim(), view);
        }
        return Collections.unmodifiableMap(values);
    }

    /// Reads a symbolic link target written in the current output session.
    public Path readSymbolicLink(Path link) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readSymbolicLinkLocked(link);
        }
    }

    /// Reads a symbolic link target while the caller holds the shared operation lock.
    private Path readSymbolicLinkLocked(Path link) throws IOException {
        lock();
        try {
            checkOpen();
            String entryName = entryName(link);
            if (!readZipAttributesLocked(link, entryName).isSymbolicLink()) {
                throw new NotLinkException(link.toString());
            }

            String target = writtenSymbolicLinkTargets.get(entryName);
            if (target == null) {
                ZipArkivoFileSystemImpl reader = Objects.requireNonNull(
                        existingArchiveReader,
                        "existingArchiveReader"
                );
                return getPath(reader.readSymbolicLink(
                        reader.getPath("/" + existingSourceEntryName(entryName))
                ).toString());
            }
            return getPath(target);
        } finally {
            unlock();
        }
    }

    /// Reads ZIP-specific attributes for an entry path visible in the current output session.
    private ZipArkivoEntryAttributes readZipAttributes(Path path) throws IOException {
        lock();
        try {
            checkOpen();
            return readZipAttributesLocked(path, entryName(path));
        } finally {
            unlock();
        }
    }

    /// Reads ZIP-specific attributes while the file system state lock is already held.
    private ZipArkivoEntryAttributes readZipAttributesLocked(Path path, String entryName) throws IOException {
        if (entryName.isEmpty()) {
            return EntryAttributes.syntheticDirectory("");
        }

        CentralEntry writtenEntry = writtenCentralEntry(entryName);
        if (writtenEntry != null) {
            return EntryAttributes.written(writtenEntry, config);
        }

        EntryAttributes existingEntry = existingEntryAttributes(entryName);
        if (existingEntry != null) {
            return existingEntry;
        }

        if (visibleDirectory(entryName)) {
            return EntryAttributes.syntheticDirectory(entryName);
        }

        throw new NoSuchFileException(path.toString());
    }

    /// Returns a written central directory entry by normalized path key.
    private @Nullable CentralEntry writtenCentralEntry(String entryName) {
        for (CentralEntry entry : centralEntries) {
            if (entryNameKey(entry.entryName).equals(entryName)) {
                return entry;
            }
        }
        return null;
    }

    /// Returns attributes parsed from an existing append-mode entry, or `null` when it is not visible.
    private @Nullable EntryAttributes existingEntryAttributes(String entryName) throws IOException {
        if (!visibleExistingEntry(entryName)) {
            return null;
        }
        for (ExistingEntry entry : existingCentralDirectoryEntries) {
            if (entry.entryName().equals(entryName)) {
                return EntryAttributes.existing(entry.attributesSnapshot(), config);
            }
        }
        return null;
    }

    /// Returns the immutable source name backing one visible existing entry.
    private String existingSourceEntryName(String entryName) throws NoSuchFileException {
        for (ExistingEntry entry : existingCentralDirectoryEntries) {
            if (entry.entryName().equals(entryName) && visibleExistingEntry(entryName)) {
                return entry.source().entryName();
            }
        }
        throw new NoSuchFileException(entryName);
    }

    /// Returns direct child entry names visible below a directory.
    private List<String> visibleChildNames(String directoryName) {
        TreeSet<String> children = new TreeSet<>();
        for (ExistingEntry entry : existingCentralDirectoryEntries) {
            if (visibleExistingEntry(entry.entryName())) {
                addDirectChild(children, directoryName, entry.entryName());
            }
        }
        for (CentralEntry entry : centralEntries) {
            addDirectChild(children, directoryName, entryNameKey(entry.entryName));
        }
        return List.copyOf(children);
    }

    /// Adds a direct child of a directory from a visible descendant path.
    private static void addDirectChild(Set<String> children, String directoryName, String descendantName) {
        if (descendantName.isEmpty() || descendantName.equals(directoryName)) {
            return;
        }

        String remainingName;
        if (directoryName.isEmpty()) {
            remainingName = descendantName;
        } else {
            String prefix = directoryName + "/";
            if (!descendantName.startsWith(prefix)) {
                return;
            }
            remainingName = descendantName.substring(prefix.length());
        }

        if (remainingName.isEmpty()) {
            return;
        }
        int separator = remainingName.indexOf('/');
        String directName = separator >= 0 ? remainingName.substring(0, separator) : remainingName;
        children.add(directoryName.isEmpty() ? directName : directoryName + "/" + directName);
    }

    /// Adds all basic attributes to a named attribute map.
    private static void addBasicAttributes(Map<String, Object> values, ZipArkivoEntryAttributes attributes) {
        values.put("lastModifiedTime", attributes.lastModifiedTime());
        values.put("lastAccessTime", attributes.lastAccessTime());
        values.put("creationTime", attributes.creationTime());
        values.put("size", attributes.size());
        values.put("isRegularFile", attributes.isRegularFile());
        values.put("isDirectory", attributes.isDirectory());
        values.put("isSymbolicLink", attributes.isSymbolicLink());
        values.put("isOther", attributes.isOther());
        values.put("fileKey", attributes.fileKey());
    }

    /// Adds all ZIP-specific attributes to a named attribute map.
    private static void addZipAttributes(Map<String, Object> values, ZipArkivoEntryAttributes attributes) {
        values.put("rawPath", attributes.rawPath());
        values.put("path", attributes.path());
        values.put("comment", attributes.comment());
        values.put("compressedSize", attributes.compressedSize());
        values.put("crc32", attributes.crc32());
        values.put("generalPurposeFlags", attributes.generalPurposeFlags());
        values.put("versionMadeBy", attributes.versionMadeBy());
        values.put("versionNeededToExtract", attributes.versionNeededToExtract());
        values.put("internalAttributes", attributes.internalAttributes());
        values.put("externalAttributes", attributes.externalAttributes());
        values.put("method", attributes.method());
        values.put("encryption", attributes.encryption());
        values.put("localExtraData", attributes.localExtraData());
        values.put("centralDirectoryExtraData", attributes.centralDirectoryExtraData());
        values.put("rawComment", attributes.rawComment());
    }

    /// Adds owner attributes to a named attribute map.
    private static void addOwnerAttributes(Map<String, Object> values, PosixFileAttributes attributes) {
        values.put("owner", attributes.owner());
    }

    /// Adds POSIX attributes to a named attribute map.
    private static void addPosixAttributes(Map<String, Object> values, PosixFileAttributes attributes) {
        addOwnerAttributes(values, attributes);
        values.put("group", attributes.group());
        values.put("permissions", attributes.permissions());
    }

    /// Adds one named attribute to a named attribute map.
    private static void addNamedAttribute(
            Map<String, Object> values,
            ZipArkivoEntryAttributes attributes,
            PosixFileAttributes posixAttributes,
            String name,
            String view
    ) {
        boolean zipView = "zip".equals(view);
        switch (name) {
            case "lastModifiedTime" -> requireBasicView(values, name, view, attributes.lastModifiedTime());
            case "lastAccessTime" -> requireBasicView(values, name, view, attributes.lastAccessTime());
            case "creationTime" -> requireBasicView(values, name, view, attributes.creationTime());
            case "size" -> requireBasicView(values, name, view, attributes.size());
            case "isRegularFile" -> requireBasicView(values, name, view, attributes.isRegularFile());
            case "isDirectory" -> requireBasicView(values, name, view, attributes.isDirectory());
            case "isSymbolicLink" -> requireBasicView(values, name, view, attributes.isSymbolicLink());
            case "isOther" -> requireBasicView(values, name, view, attributes.isOther());
            case "fileKey" -> requireBasicView(values, name, view, attributes.fileKey());
            case "rawPath" -> requireZipView(values, name, zipView, attributes.rawPath());
            case "path" -> requireZipView(values, name, zipView, attributes.path());
            case "comment" -> requireZipView(values, name, zipView, attributes.comment());
            case "compressedSize" -> requireZipView(values, name, zipView, attributes.compressedSize());
            case "crc32" -> requireZipView(values, name, zipView, attributes.crc32());
            case "generalPurposeFlags" -> requireZipView(values, name, zipView, attributes.generalPurposeFlags());
            case "versionMadeBy" -> requireZipView(values, name, zipView, attributes.versionMadeBy());
            case "versionNeededToExtract" -> requireZipView(values, name, zipView, attributes.versionNeededToExtract());
            case "internalAttributes" -> requireZipView(values, name, zipView, attributes.internalAttributes());
            case "externalAttributes" -> requireZipView(values, name, zipView, attributes.externalAttributes());
            case "method" -> requireZipView(values, name, zipView, attributes.method());
            case "encryption" -> requireZipView(values, name, zipView, attributes.encryption());
            case "localExtraData" -> requireZipView(values, name, zipView, attributes.localExtraData());
            case "centralDirectoryExtraData" ->
                    requireZipView(values, name, zipView, attributes.centralDirectoryExtraData());
            case "rawComment" -> requireZipView(values, name, zipView, attributes.rawComment());
            case "owner" -> requireOwnerView(values, name, view, posixAttributes.owner());
            case "group" -> requirePosixView(values, name, view, posixAttributes.group());
            case "permissions" -> requirePosixView(values, name, view, posixAttributes.permissions());
            default -> throw new IllegalArgumentException("Unsupported ZIP attribute: " + name);
        }
    }

    /// Adds a basic attribute or rejects it for views that do not expose basic attributes.
    private static void requireBasicView(Map<String, Object> values, String name, String view, @Nullable Object value) {
        if ("owner".equals(view)) {
            throw new IllegalArgumentException("Attribute requires basic, zip, or posix view: " + name);
        }
        values.put(name, value);
    }

    /// Adds a ZIP-view-only attribute or rejects it for the basic view.
    private static void requireZipView(Map<String, Object> values, String name, boolean zipView, @Nullable Object value) {
        if (!zipView) {
            throw new IllegalArgumentException("Attribute requires zip view: " + name);
        }
        values.put(name, value);
    }

    /// Requires the owner or POSIX view before adding an owner attribute.
    private static void requireOwnerView(Map<String, Object> values, String name, String view, Object value) {
        if (!"owner".equals(view) && !"posix".equals(view)) {
            throw new IllegalArgumentException("Attribute requires owner or posix view: " + name);
        }
        values.put(name, value);
    }

    /// Requires the POSIX view before adding a POSIX attribute.
    private static void requirePosixView(Map<String, Object> values, String name, String view, Object value) {
        if (!"posix".equals(view)) {
            throw new IllegalArgumentException("Attribute requires posix view: " + name);
        }
        values.put(name, value);
    }

    /// Returns a user principal lookup service for synthesized ZIP principals.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        try (Operation ignored = beginReadOperation()) {
            return ZipPosixSupport.userPrincipalLookupService();
        }
    }

    /// Opens storage that captures an uncompressed entry body during complete-rewrite mode.
    private @Nullable OutputStream openStagedEntryOutput(String entryName) throws IOException {
        ArkivoEditStorage storage = editStorage;
        if (storage == null) {
            return null;
        }

        ArkivoStoredContent content = storage.createContent(
                "zip-update-entry-" + stagedEntrySequence++,
                ArkivoEditStorage.UNKNOWN_SIZE
        );
        @Nullable SeekableByteChannel channel = null;
        try {
            channel = content.openChannel(Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ));
            OutputStream stagedOutput = Channels.newOutputStream(channel);
            channel = null;
            stagedEntryContents.put(entryName, content);
            return stagedOutput;
        } catch (IOException | RuntimeException | Error exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            try {
                content.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /// Stores one complete uncompressed entry body for reads during complete-rewrite mode.
    private void stageCompleteEntryContent(String entryName, byte[] contentBytes) throws IOException {
        OutputStream stagedOutput = openStagedEntryOutput(entryName);
        if (stagedOutput == null) {
            return;
        }
        try (stagedOutput) {
            stagedOutput.write(contentBytes);
        }
    }

    /// Opens an output stream for the next ZIP entry.
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return newOutputStream(path, EntryMetadata.deflated(config.defaultEncryption()), options);
    }

    /// Opens a readable existing or staged entry channel, or a forward-only writable channel.
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(attributes, "attributes");
        boolean read = options.isEmpty() || options.contains(StandardOpenOption.READ);
        boolean write = requestsEntryWrite(options);
        if (write && rewriteSnapshot != null) {
            try (Operation ignored = beginWriteOperation()) {
                return manageWriteChannel(newUpdateEntryByteChannel(path, options, attributes));
            }
        }
        if (read && write) {
            throw new UnsupportedOperationException("ZIP update entry channels do not support combined read/write access");
        }
        if (!write) {
            try (Operation ignored = beginReadOperation()) {
                return manageReadChannel(newReadByteChannelLocked(path, options, attributes));
            }
        }

        try (Operation ignored = beginWriteOperation()) {
            EntryMetadata metadata = applyInitialAttributes(
                    EntryMetadata.deflated(config.defaultEncryption()),
                    false,
                    attributes
            );
            OutputStream outputStream = newOutputStreamLocked(
                    path,
                    metadata,
                    options.toArray(OpenOption[]::new)
            );
            return manageWriteChannel(new ForwardOnlyOutputChannel(outputStream));
        }
    }

    /// Opens an input stream over an existing or completed staged entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        Objects.requireNonNull(options, "options");
        return Channels.newInputStream(newByteChannel(
                path,
                Set.copyOf(Arrays.asList(options))
        ));
    }

    /// Opens a read channel while the caller holds a shared file-system operation.
    private SeekableByteChannel newReadByteChannelLocked(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        lock();
        try {
            checkOpen();
            requireNoActiveOutput();
            if (attributes.length != 0) {
                throw new UnsupportedOperationException("ZIP read channels do not accept initial attributes");
            }
            for (OpenOption option : options) {
                if (option != StandardOpenOption.READ) {
                    throw new UnsupportedOperationException("Unsupported ZIP entry read option: " + option);
                }
            }

            String entryName = entryName(path);
            ArkivoStoredContent stagedContent = stagedEntryContents.get(entryName);
            if (stagedContent != null && visibleWrittenEntry(entryName)) {
                return stagedContent.openChannel(Set.of(StandardOpenOption.READ));
            }
            if (visibleExistingEntry(entryName)) {
                ZipArkivoFileSystemImpl reader = Objects.requireNonNull(
                        existingArchiveReader,
                        "existingArchiveReader"
                );
                return reader.newByteChannel(
                        reader.getPath("/" + existingSourceEntryName(entryName)),
                        options,
                        attributes
                );
            }
            if (visibleWrittenEntry(entryName)) {
                throw new IOException("ZIP entry does not expose readable file content: " + path);
            }
            throw new NoSuchFileException(path.toString());
        } finally {
            unlock();
        }
    }

    /// Opens a staged random-access channel for one complete-rewrite entry body.
    private SeekableByteChannel newUpdateEntryByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        lock();
        try {
            checkOpen();
            requireNoActiveEntry();
            validateUpdateEntryOptions(options);

            boolean append = options.contains(StandardOpenOption.APPEND);
            boolean writable = options.contains(StandardOpenOption.WRITE) || append;
            boolean readable = options.contains(StandardOpenOption.READ);
            if (!readable && !writable) {
                throw new IllegalArgumentException("ZIP update entry channel requires READ, WRITE, or APPEND");
            }

            String entryName = regularEntryName(path);
            boolean existing = visibleEntry(entryName);
            boolean create = options.contains(StandardOpenOption.CREATE)
                    || options.contains(StandardOpenOption.CREATE_NEW);
            if (!existing && !create) {
                throw new NoSuchFileException(path.toString());
            }
            if (existing && options.contains(StandardOpenOption.CREATE_NEW)) {
                throw new FileAlreadyExistsException(path.toString());
            }
            if (!existing && visibleDirectory(entryName)) {
                throw new FileSystemException(path.toString(), null, "ZIP entry is a directory");
            }

            boolean truncate = writable && options.contains(StandardOpenOption.TRUNCATE_EXISTING);
            EntryMetadata metadata = applyInitialAttributes(
                    EntryMetadata.deflated(config.defaultEncryption()),
                    false,
                    attributes
            );
            ArkivoStoredContent pendingContent = Objects.requireNonNull(editStorage, "editStorage").createContent(
                    "zip-update-random-entry-" + stagedEntrySequence++,
                    ArkivoEditStorage.UNKNOWN_SIZE
            );
            @Nullable SeekableByteChannel storageChannel = null;
            try {
                if (existing && !truncate) {
                    copyVisibleEntryContent(entryName, pendingContent);
                }
                HashSet<OpenOption> storageOptions = new HashSet<>();
                if (readable) {
                    storageOptions.add(StandardOpenOption.READ);
                }
                if (writable) {
                    storageOptions.add(StandardOpenOption.WRITE);
                }
                if (!existing || truncate) {
                    storageOptions.add(StandardOpenOption.TRUNCATE_EXISTING);
                }
                storageChannel = pendingContent.openChannel(Set.copyOf(storageOptions));
                StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                        storageChannel,
                        readable,
                        writable,
                        append,
                        writable && (!existing || truncate),
                        (completedChannel, commit) -> completeUpdateEntryChannel(
                                completedChannel,
                                commit,
                                entryName,
                                metadata,
                                pendingContent
                        )
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
                try {
                    pendingContent.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        } finally {
            unlock();
        }
    }

    /// Validates open options for a complete-rewrite entry channel.
    private static void validateUpdateEntryOptions(Set<? extends OpenOption> options) {
        if (options.contains(StandardOpenOption.APPEND) && options.contains(StandardOpenOption.READ)) {
            throw new IllegalArgumentException("ZIP entry APPEND cannot be combined with READ");
        }
        if (options.contains(StandardOpenOption.APPEND)
                && options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            throw new IllegalArgumentException("ZIP entry APPEND cannot be combined with TRUNCATE_EXISTING");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ
                    && option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.APPEND
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported ZIP entry update option: " + option);
            }
        }
    }

    /// Copies the currently visible uncompressed body into pending random-access storage.
    private void copyVisibleEntryContent(String entryName, ArkivoStoredContent destination) throws IOException {
        try (SeekableByteChannel source = openVisibleEntryContent(entryName);
             SeekableByteChannel target = destination.openChannel(Set.of(
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE
             ))) {
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
            while (source.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    target.write(buffer);
                }
                buffer.clear();
            }
        }
    }

    /// Opens the uncompressed body currently visible for an update entry.
    private SeekableByteChannel openVisibleEntryContent(String entryName) throws IOException {
        ArkivoStoredContent stagedContent = stagedEntryContents.get(entryName);
        if (stagedContent != null && visibleWrittenEntry(entryName)) {
            return stagedContent.openChannel(Set.of(StandardOpenOption.READ));
        }
        if (visibleExistingEntry(entryName)) {
            ZipArkivoFileSystemImpl reader = Objects.requireNonNull(existingArchiveReader, "existingArchiveReader");
            return reader.newByteChannel(
                    reader.getPath("/" + existingSourceEntryName(entryName)),
                    Set.of(StandardOpenOption.READ)
            );
        }
        throw new IOException("ZIP entry does not expose readable file content: " + entryName);
    }

    /// Commits a changed random-access body as a new staged ZIP local record.
    private void commitUpdatedEntry(
            String entryName,
            EntryMetadata metadata,
            ArkivoStoredContent content
    ) throws IOException {
        if (visibleWrittenEntry(entryName)) {
            removeWrittenEntry(entryName);
        }
        OpenOption[] outputOptions = visibleExistingEntry(entryName)
                ? new OpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING}
                : new OpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW};
        try (SeekableByteChannel source = content.openChannel(Set.of(StandardOpenOption.READ));
             OutputStream target = newOutputStreamLocked(
                     getPath("/" + entryName),
                     metadata,
                     outputOptions
             )) {
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
            while (source.read(buffer) >= 0) {
                target.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
        }
    }

    /// Clears one completed update channel, commits changed bytes, and releases its pending body.
    private void completeUpdateEntryChannel(
            StagedSeekableByteChannel channel,
            boolean commit,
            String entryName,
            EntryMetadata metadata,
            ArkivoStoredContent content
    ) throws IOException {
        @Nullable Throwable failure = null;
        lock();
        try {
            if (activeUpdateChannel == channel) {
                activeUpdateChannel = null;
            }
            if (commit) {
                try {
                    commitUpdatedEntry(entryName, metadata, content);
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
        } finally {
            unlock();
        }
        try {
            content.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        throwFailure(failure);
    }

    /// Returns whether entry open options request output or mutation behavior.
    private static boolean requestsEntryWrite(Set<? extends OpenOption> options) {
        return options.contains(StandardOpenOption.WRITE)
                || options.contains(StandardOpenOption.APPEND)
                || options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW)
                || options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                || options.contains(StandardOpenOption.DELETE_ON_CLOSE)
                || options.contains(StandardOpenOption.SPARSE)
                || options.contains(StandardOpenOption.SYNC)
                || options.contains(StandardOpenOption.DSYNC);
    }

    /// Opens an output stream for the next ZIP entry with central directory attributes.
    OutputStream newOutputStream(Path path, EntryMetadata metadata, OpenOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            return manageOutputStream(newOutputStreamLocked(path, metadata, options));
        }
    }

    /// Opens an entry output stream while the caller holds the exclusive operation lock.
    private OutputStream newOutputStreamLocked(Path path, EntryMetadata metadata, OpenOption... options)
            throws IOException {
        lock();
        try {
            checkOpen();
            metadata.validate();
            requireSupportedOutputOptions(options);
            String entryName = regularEntryName(path);
            requireNoActiveEntry();
            boolean replacingExistingEntry = checkWritableFileEntry(entryName, options);
            byte @Nullable [] password = passwordForMetadata(entryName, metadata);
            byte[] rawName = rawEntryName(entryName);
            int flags = metadata.generalPurposeFlags();
            boolean zip64LocalHeader = metadata.zip64LocalHeaderNeeded();
            byte[] localExtraData = metadata.localHeaderExtraData(zip64LocalHeader);
            output.startRecord(localHeaderSize(rawName, localExtraData));
            registerWritableFileEntry(entryName, replacingExistingEntry);

            long localHeaderAbsoluteOffset = output.position();
            int localHeaderDiskNumber = output.diskNumber();
            long localHeaderOffset = output.diskPosition();
            writeLocalHeader(
                    rawName,
                    localExtraData,
                    metadata.versionNeeded(zip64LocalHeader),
                    flags,
                    metadata.headerMethod(),
                    metadata.dosTime,
                    metadata.dosDate,
                    metadata.localHeaderCrc32(),
                    metadata.localHeaderCompressedSize(zip64LocalHeader),
                    metadata.localHeaderUncompressedSize(zip64LocalHeader)
            );
            EntryOutputStream entryOutput = new EntryOutputStream(
                    entryName,
                    rawName,
                    localHeaderDiskNumber,
                    localHeaderOffset,
                    localHeaderAbsoluteOffset,
                    output.position(),
                    flags,
                    metadata,
                    password
            );
            currentEntryOutput = entryOutput;
            return entryOutput;
        } finally {
            unlock();
        }
    }

    /// Creates a directory entry.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        createDirectory(directory, EntryMetadata.directory(), attributes);
    }

    /// Creates a symbolic link entry.
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            createSymbolicLinkLocked(link, target, attributes);
        }
    }

    /// Creates a symbolic link entry while the caller holds the exclusive operation lock.
    private void createSymbolicLinkLocked(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(target, "target");
        EntryMetadata metadata = applyInitialSymbolicLinkAttributes(
                EntryMetadata.symbolicLink(config.defaultEncryption()),
                attributes
        );
        String targetText = linkTargetText(target);
        writeStoredEntry(link, targetText.getBytes(StandardCharsets.UTF_8), metadata, targetText);
    }

    /// Creates a directory entry with central directory attributes.
    void createDirectory(Path directory, EntryMetadata metadata, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            createDirectoryLocked(directory, metadata, attributes);
        }
    }

    /// Creates a directory entry while the caller holds the exclusive operation lock.
    private void createDirectoryLocked(
            Path directory,
            EntryMetadata metadata,
            FileAttribute<?>... attributes
    ) throws IOException {
        lock();
        try {
            checkOpen();
            metadata = applyInitialAttributes(metadata, true, attributes);
            metadata.validate();
            if (metadata.method != STORED_METHOD) {
                throw new UnsupportedOperationException("ZIP directory entries must use the stored method");
            }
            if (metadata.encrypted()) {
                throw new UnsupportedOperationException("ZIP directory entries cannot be encrypted");
            }

            String entryName = directoryEntryName(directory);
            if (entryName.isEmpty()) {
                return;
            }
            requireNoActiveEntry();
            requireNewEntry(entryName);
            byte[] rawName = rawEntryName(entryName);
            int flags = metadata.generalPurposeFlags() & ~DATA_DESCRIPTOR_FLAG;
            boolean zip64LocalHeader = metadata.zip64LocalHeaderNeeded();
            byte[] localExtraData = metadata.localHeaderExtraData(zip64LocalHeader);
            output.startRecord(localHeaderSize(rawName, localExtraData));
            long localHeaderAbsoluteOffset = output.position();
            int localHeaderDiskNumber = output.diskNumber();
            long localHeaderOffset = output.diskPosition();
            writeLocalHeader(
                    rawName,
                    localExtraData,
                    metadata.versionNeeded(zip64LocalHeader),
                    flags,
                    STORED_METHOD,
                    metadata.dosTime,
                    metadata.dosDate,
                    0,
                    0,
                    0
            );
            centralEntries.add(new CentralEntry(
                    entryName,
                    rawName,
                    flags,
                    STORED_METHOD,
                    metadata.dosTime,
                    metadata.dosDate,
                    0,
                    0,
                    0,
                    localHeaderDiskNumber,
                    localHeaderOffset,
                    output.position() - localHeaderAbsoluteOffset,
                    output.position() - localHeaderAbsoluteOffset,
                    metadata
            ));
        } finally {
            unlock();
        }
    }

    /// Writes a complete stored entry with a known byte array body.
    void writeStoredEntry(Path path, byte[] content, EntryMetadata metadata) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            writeStoredEntry(path, content, metadata, null);
        }
    }

    /// Writes a complete stored entry with optional symbolic link target metadata.
    private void writeStoredEntry(
            Path path,
            byte[] content,
            EntryMetadata metadata,
            @Nullable String symbolicLinkTarget
    ) throws IOException {
        lock();
        try {
            checkOpen();
            Objects.requireNonNull(content, "content");
            metadata.validate();
            if (metadata.method != STORED_METHOD) {
                throw new UnsupportedOperationException("In-memory ZIP entries must use the stored method");
            }
            String entryName = regularEntryName(path);
            requireNoActiveEntry();
            checkNewEntry(entryName);

            CRC32 crc32 = new CRC32();
            crc32.update(content);
            long crcValue = crc32.getValue();
            long size = content.length;
            if (metadata.expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                    && metadata.expectedUncompressedSize != size) {
                throw new IOException("ZIP entry size does not match the configured size: " + entryName);
            }
            if (metadata.expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32 && metadata.expectedCrc32 != crcValue) {
                throw new IOException("ZIP entry CRC-32 does not match the configured CRC-32: " + entryName);
            }

            byte @Nullable [] password = passwordForMetadata(entryName, metadata);
            byte[] rawName = rawEntryName(entryName);
            requireNewEntry(entryName);
            stageCompleteEntryContent(entryName, content);
            int flags = metadata.generalPurposeFlags() & ~DATA_DESCRIPTOR_FLAG;
            long compressedSize = metadata.encryptedCompressedSize(size);
            boolean zip64LocalHeader = metadata.zip64LocalHeaderNeeded(compressedSize, size);
            byte[] localExtraData = metadata.localHeaderExtraData(zip64LocalHeader, compressedSize, size);
            output.startRecord(localHeaderSize(rawName, localExtraData));
            long localHeaderAbsoluteOffset = output.position();
            int localHeaderDiskNumber = output.diskNumber();
            long localHeaderOffset = output.diskPosition();
            writeLocalHeader(
                    rawName,
                    localExtraData,
                    metadata.versionNeeded(zip64LocalHeader),
                    flags,
                    metadata.headerMethod(),
                    metadata.dosTime,
                    metadata.dosDate,
                    crcValue,
                    zip32Field(compressedSize, zip64LocalHeader),
                    zip32Field(size, zip64LocalHeader)
            );
            long localHeaderSize = output.position() - localHeaderAbsoluteOffset;
            if (metadata.traditionalEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                OutputStream encrypted = ZipTraditionalCrypto.openEncryptingStream(
                        output,
                        encryptionPassword,
                        metadata.encryptionVerificationByte(crcValue)
                );
                encrypted.write(content);
                encrypted.flush();
            } else if (metadata.aesEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                ZipAesCrypto.EncryptingOutputStream encrypted = ZipAesCrypto.openEncryptingStream(
                        output,
                        metadata.aesExtraField(),
                        encryptionPassword
                );
                encrypted.write(content);
                encrypted.finish();
            } else {
                output.write(content);
            }
            centralEntries.add(new CentralEntry(
                    entryName,
                    rawName,
                    flags,
                    metadata.headerMethod(),
                    metadata.dosTime,
                    metadata.dosDate,
                    crcValue,
                    compressedSize,
                    size,
                    localHeaderDiskNumber,
                    localHeaderOffset,
                    localHeaderSize,
                    output.position() - localHeaderAbsoluteOffset,
                    metadata
            ));
            if (symbolicLinkTarget != null) {
                writtenSymbolicLinkTargets.put(entryName, symbolicLinkTarget);
            }
        } finally {
            unlock();
        }
    }

    /// Deletes an entry from the rewritten ZIP archive.
    public void delete(Path path) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            deleteLocked(path);
        }
    }

    /// Deletes an entry while the caller holds the exclusive operation lock.
    private void deleteLocked(Path path) throws IOException {
        lock();
        try {
            checkOpen();
            requireNoActiveEntry();
            String entryName = entryName(path);
            if (entryName.isEmpty()) {
                throw new DirectoryNotEmptyException(path.toString());
            }
            boolean existingDirectory = visibleDirectory(entryName);
            boolean existingEntry = visibleEntry(entryName);
            if (!existingEntry && !existingDirectory) {
                throw new NoSuchFileException(path.toString());
            }
            if (directoryHasVisibleChildren(entryName)) {
                throw new DirectoryNotEmptyException(path.toString());
            }

            removeWrittenEntry(entryName);
            if (existingEntryNames.contains(entryName)) {
                deletedEntries.add(entryName);
            }
        } finally {
            unlock();
        }
    }

    /// Moves one entry and all visible descendants inside a complete-rewrite update session.
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            moveLocked(source, target, options);
        }
    }

    /// Moves one entry while the caller holds the exclusive operation lock.
    private void moveLocked(Path source, Path target, CopyOption... options) throws IOException {
        lock();
        try {
            checkOpen();
            requireNoActiveEntry();
            if (rewriteSnapshot == null) {
                throw new UnsupportedOperationException("ZIP moves require complete-rewrite update mode");
            }
            Objects.requireNonNull(options, "options");
            boolean replaceExisting = false;
            for (CopyOption option : options) {
                Objects.requireNonNull(option, "option");
                if (option == StandardCopyOption.REPLACE_EXISTING) {
                    replaceExisting = true;
                } else if (option != StandardCopyOption.ATOMIC_MOVE) {
                    throw new UnsupportedOperationException("Unsupported ZIP move option: " + option);
                }
            }

            String sourceName = entryName(source);
            String targetName = entryName(target);
            if (sourceName.isEmpty() || targetName.isEmpty()) {
                throw new FileSystemException(source.toString(), target.toString(), "The ZIP root cannot be moved");
            }
            ZipArkivoEntryAttributes sourceAttributes = readZipAttributesLocked(source, sourceName);
            if (sourceName.equals(targetName)) {
                return;
            }
            boolean sourceDirectory = sourceAttributes.isDirectory();
            if (sourceDirectory && targetName.startsWith(sourceName + "/")) {
                throw new FileSystemException(
                        source.toString(),
                        target.toString(),
                        "A directory cannot be moved below itself"
                );
            }
            requireMoveParentDirectory(target, targetName);

            @Nullable ZipArkivoEntryAttributes targetAttributes = null;
            try {
                targetAttributes = readZipAttributesLocked(target, targetName);
            } catch (NoSuchFileException ignored) {
            }
            if (targetAttributes != null) {
                if (!replaceExisting) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                if (sourceDirectory != targetAttributes.isDirectory()) {
                    throw new FileSystemException(
                            source.toString(),
                            target.toString(),
                            "Source and target entry types differ"
                    );
                }
                if (targetAttributes.isDirectory() && directoryHasVisibleChildren(targetName)) {
                    throw new DirectoryNotEmptyException(target.toString());
                }
            }

            LinkedHashMap<String, String> movedPaths = movedEntryPaths(sourceName, targetName);
            if (movedPaths.isEmpty()) {
                throw new NoSuchFileException(source.toString());
            }
            for (String movedPath : movedPaths.values()) {
                if (movedPaths.containsKey(movedPath)) {
                    continue;
                }
                if (visibleEntry(movedPath)
                        && !(replaceExisting && movedPath.equals(targetName))) {
                    throw new FileAlreadyExistsException(movedPath);
                }
            }

            ArrayList<ExistingEntry> rebuiltExistingEntries = new ArrayList<>(
                    existingCentralDirectoryEntries.size()
            );
            for (ExistingEntry entry : existingCentralDirectoryEntries) {
                String oldName = entry.entryName();
                if (oldName.equals(targetName) && !movedPaths.containsKey(oldName)) {
                    continue;
                }
                @Nullable String newName = movedPaths.get(oldName);
                rebuiltExistingEntries.add(newName != null ? entry.renamed(newName) : entry);
            }

            ArrayList<CentralEntry> rebuiltCentralEntries = new ArrayList<>(centralEntries.size());
            for (CentralEntry entry : centralEntries) {
                String oldName = entryNameKey(entry.entryName);
                if (oldName.equals(targetName) && !movedPaths.containsKey(oldName)) {
                    continue;
                }
                @Nullable String newName = movedPaths.get(oldName);
                rebuiltCentralEntries.add(newName != null ? entry.renamed(newName) : entry);
            }

            if (targetAttributes != null && !movedPaths.containsKey(targetName)) {
                removeWrittenEntry(targetName);
            }
            existingCentralDirectoryEntries.clear();
            existingCentralDirectoryEntries.addAll(rebuiltExistingEntries);
            centralEntries.clear();
            centralEntries.addAll(rebuiltCentralEntries);
            remapWrittenEntries(movedPaths);
            remapEntryMap(stagedEntryContents, movedPaths);
            remapEntryMap(writtenSymbolicLinkTargets, movedPaths);
            existingEntryNames.clear();
            for (ExistingEntry entry : existingCentralDirectoryEntries) {
                existingEntryNames.add(entry.entryName());
            }
            for (String movedPath : movedPaths.values()) {
                deletedEntries.remove(movedPath);
                replacedEntries.remove(movedPath);
            }
        } finally {
            unlock();
        }
    }

    /// Requires the destination parent to identify a visible directory.
    private void requireMoveParentDirectory(Path target, String targetName) throws IOException {
        int separator = targetName.lastIndexOf('/');
        if (separator < 0) {
            return;
        }
        String parentName = targetName.substring(0, separator);
        ZipArkivoEntryAttributes attributes = readZipAttributesLocked(
                getPath("/" + parentName),
                parentName
        );
        if (!attributes.isDirectory()) {
            throw new FileSystemException(target.toString(), null, "ZIP move target parent is not a directory");
        }
    }

    /// Returns the explicit visible entry names moved from one subtree to another.
    private LinkedHashMap<String, String> movedEntryPaths(String sourceName, String targetName) {
        LinkedHashMap<String, String> movedPaths = new LinkedHashMap<>();
        String sourcePrefix = sourceName + "/";
        for (ExistingEntry entry : existingCentralDirectoryEntries) {
            String name = entry.entryName();
            if (visibleExistingEntry(name) && (name.equals(sourceName) || name.startsWith(sourcePrefix))) {
                movedPaths.put(name, targetName + name.substring(sourceName.length()));
            }
        }
        for (CentralEntry entry : centralEntries) {
            String name = entryNameKey(entry.entryName);
            if (name.equals(sourceName) || name.startsWith(sourcePrefix)) {
                movedPaths.put(name, targetName + name.substring(sourceName.length()));
            }
        }
        return movedPaths;
    }

    /// Remaps names tracked by the written-entry membership set.
    private void remapWrittenEntries(Map<String, String> movedPaths) {
        HashSet<String> remapped = new HashSet<>(writtenEntries.size());
        for (String entryName : writtenEntries) {
            String key = entryNameKey(entryName);
            @Nullable String movedName = movedPaths.get(key);
            if (movedName == null) {
                remapped.add(entryName);
            } else {
                remapped.add(entryName.endsWith("/") ? movedName + "/" : movedName);
            }
        }
        writtenEntries.clear();
        writtenEntries.addAll(remapped);
    }

    /// Remaps keys in one per-entry update map without changing its values.
    private static <V> void remapEntryMap(Map<String, V> values, Map<String, String> movedPaths) {
        HashMap<String, V> movedValues = new HashMap<>();
        for (Map.Entry<String, String> movedPath : movedPaths.entrySet()) {
            V value = values.remove(movedPath.getKey());
            if (value != null) {
                movedValues.put(movedPath.getValue(), value);
            }
        }
        values.putAll(movedValues);
    }

    /// Sets entry timestamps in a complete-rewrite update session.
    private void setTimes(
            Path path,
            @Nullable FileTime lastModifiedTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime createTime
    ) throws IOException {
        if (lastAccessTime != null || createTime != null) {
            throw new UnsupportedOperationException(
                    "ZIP update sessions can persist only the last-modified timestamp"
            );
        }
        mutateEntryMetadata(
                path,
                entry -> {
                    if (lastModifiedTime == null) {
                        return entry;
                    }
                    requireTimestampUpdateSupported(
                            EntryAttributes.existing(entry.attributesSnapshot(), config),
                            entry.dosTime(),
                            lastModifiedTime
                    );
                    return entry.withLastModifiedTime(lastModifiedTime);
                },
                entry -> {
                    if (lastModifiedTime == null) {
                        return entry;
                    }
                    requireTimestampUpdateSupported(
                            EntryAttributes.written(entry, config),
                            entry.dosTime,
                            lastModifiedTime
                    );
                    return entry.withLastModifiedTime(lastModifiedTime);
                }
        );
    }

    /// Rejects timestamp changes coupled to a traditional encryption verification byte.
    private static void requireTimestampUpdateSupported(
            ZipArkivoEntryAttributes attributes,
            int currentDosTime,
            FileTime lastModifiedTime
    ) {
        int updatedDosTime = dosTime(lastModifiedTime);
        if (attributes.encryption().equals(ZipEncryption.traditional())
                && (attributes.generalPurposeFlags() & DATA_DESCRIPTOR_FLAG) != 0
                && (currentDosTime >>> 8) != (updatedDosTime >>> 8)) {
            throw new UnsupportedOperationException(
                    "Changing this traditional ZIP entry timestamp requires rewriting its encrypted body"
            );
        }
    }

    /// Sets synthesized POSIX permissions in a complete-rewrite update session.
    private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        Set<PosixFilePermission> copiedPermissions = Set.copyOf(
                Objects.requireNonNull(permissions, "permissions")
        );
        mutateEntryMetadata(
                path,
                entry -> {
                    EntryAttributes attributes = EntryAttributes.existing(entry.attributesSnapshot(), config);
                    return entry.withExternalAttributes(
                            ZipPosixSupport.UNIX_VERSION_MADE_BY,
                            externalAttributes(copiedPermissions, attributes)
                    );
                },
                entry -> {
                    EntryAttributes attributes = EntryAttributes.written(entry, config);
                    return entry.withExternalAttributes(
                            ZipPosixSupport.UNIX_VERSION_MADE_BY,
                            externalAttributes(copiedPermissions, attributes)
                    );
                }
        );
    }

    /// Returns ZIP external file attributes for permissions without changing the entry type.
    private static long externalAttributes(
            Set<PosixFilePermission> permissions,
            ZipArkivoEntryAttributes attributes
    ) {
        if (attributes.isSymbolicLink()) {
            return ZipPosixSupport.symbolicLinkExternalAttributes(permissions);
        }
        return ZipPosixSupport.externalAttributes(permissions, attributes.isDirectory());
    }

    /// Validates a synthesized owner assignment and the target entry.
    private void setOwner(Path path, UserPrincipal owner) throws IOException {
        ZipPosixSupport.requireDefaultOwner(Objects.requireNonNull(owner, "owner"));
        mutateEntryMetadata(path, entry -> entry, entry -> entry);
    }

    /// Validates a synthesized group assignment and the target entry.
    private void setGroup(Path path, GroupPrincipal group) throws IOException {
        ZipPosixSupport.requireDefaultGroup(Objects.requireNonNull(group, "group"));
        mutateEntryMetadata(path, entry -> entry, entry -> entry);
    }

    /// Sets ZIP internal file attributes in a complete-rewrite update session.
    private void setInternalAttributes(Path path, int internalAttributes) throws IOException {
        requireUInt16(internalAttributes, "internal attributes");
        mutateEntryMetadata(
                path,
                entry -> entry.withInternalAttributes(internalAttributes),
                entry -> entry.withInternalAttributes(internalAttributes)
        );
    }

    /// Sets ZIP external file attributes in a complete-rewrite update session.
    private void setExternalAttributes(Path path, long externalAttributes) throws IOException {
        requireUInt32(externalAttributes, "external attributes");
        mutateEntryMetadata(
                path,
                entry -> entry.withExternalAttributes(entry.versionMadeBy(), externalAttributes),
                entry -> entry.withExternalAttributes(entry.versionMadeBy, externalAttributes)
        );
    }

    /// Sets raw ZIP entry comment bytes in a complete-rewrite update session.
    private void setRawComment(Path path, byte @Nullable [] rawComment) throws IOException {
        byte @Nullable [] copiedComment = rawComment != null ? rawComment.clone() : null;
        if (copiedComment != null) {
            requireUInt16(copiedComment.length, "comment length");
        }
        mutateEntryMetadata(
                path,
                entry -> entry.withRawComment(copiedComment),
                entry -> entry.withRawComment(copiedComment)
        );
    }

    /// Sets one named persistent ZIP entry attribute.
    public void setAttribute(Path path, String attribute, Object value) throws IOException {
        Objects.requireNonNull(attribute, "attribute");
        int separator = attribute.indexOf(':');
        String view = separator >= 0 ? attribute.substring(0, separator) : "basic";
        String name = separator >= 0 ? attribute.substring(separator + 1) : attribute;
        switch (view + ":" + name) {
            case "basic:lastModifiedTime", "posix:lastModifiedTime", "zip:lastModifiedTime" ->
                    setTimes(path, (FileTime) value, null, null);
            case "owner:owner", "posix:owner" -> setOwner(path, (UserPrincipal) value);
            case "posix:group" -> setGroup(path, (GroupPrincipal) value);
            case "posix:permissions", "zip:permissions" -> {
                @SuppressWarnings("unchecked")
                Set<PosixFilePermission> permissions = (Set<PosixFilePermission>) value;
                setPermissions(path, permissions);
            }
            case "zip:internalAttributes" -> setInternalAttributes(path, (Integer) value);
            case "zip:externalAttributes" -> setExternalAttributes(path, (Long) value);
            case "zip:rawComment" -> setRawComment(path, (byte[]) value);
            default -> throw new UnsupportedOperationException("Unsupported writable ZIP attribute: " + attribute);
        }
    }

    /// Replaces metadata for one explicit entry after all validation has succeeded.
    private void mutateEntryMetadata(
            Path path,
            ExistingEntryMutation existingMutation,
            CentralEntryMutation writtenMutation
    ) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            lock();
            try {
                checkOpen();
                requireNoActiveEntry();
                if (rewriteSnapshot == null) {
                    throw new UnsupportedOperationException(
                            "ZIP entry metadata updates require complete-rewrite update mode"
                    );
                }
                String entryName = entryName(path);
                for (int index = 0; index < centralEntries.size(); index++) {
                    CentralEntry entry = centralEntries.get(index);
                    if (entryNameKey(entry.entryName).equals(entryName)) {
                        centralEntries.set(index, writtenMutation.apply(entry));
                        return;
                    }
                }
                if (visibleExistingEntry(entryName)) {
                    for (int index = 0; index < existingCentralDirectoryEntries.size(); index++) {
                        ExistingEntry entry = existingCentralDirectoryEntries.get(index);
                        if (entry.entryName().equals(entryName)) {
                            existingCentralDirectoryEntries.set(index, existingMutation.apply(entry));
                            return;
                        }
                    }
                }
                if (visibleDirectory(entryName)) {
                    throw new UnsupportedOperationException(
                            "Synthetic ZIP directories do not have persistent entry metadata"
                    );
                }
                throw new NoSuchFileException(path.toString());
            } finally {
                unlock();
            }
        }
    }

    /// Changes metadata for one existing source entry.
    @FunctionalInterface
    private interface ExistingEntryMutation {
        /// Returns the replacement existing-entry state.
        ExistingEntry apply(ExistingEntry entry) throws IOException;
    }

    /// Changes metadata for one entry written in the current update session.
    @FunctionalInterface
    private interface CentralEntryMutation {
        /// Returns the replacement written-entry state.
        CentralEntry apply(CentralEntry entry) throws IOException;
    }

    /// Returns the number of bytes stored before an archive being updated.
    @Override
    public long preambleSize() throws IOException {
        try (Operation ignored = beginReadOperation()) {
            ZipArkivoFileSystemImpl reader = existingArchiveReader;
            if (reader == null) {
                throw new UnsupportedOperationException("Streaming ZIP creation does not expose a preamble");
            }
            return reader.preambleSize();
        }
    }

    /// Opens a read-only channel over preamble bytes of an archive being updated.
    @Override
    public SeekableByteChannel openPreambleChannel() throws IOException {
        try (Operation ignored = beginReadOperation()) {
            ZipArkivoFileSystemImpl reader = existingArchiveReader;
            if (reader == null) {
                throw new UnsupportedOperationException("Streaming ZIP creation does not expose a preamble");
            }
            return manageReadChannel(reader.openPreambleChannel());
        }
    }

    /// Requires this file system to be open.
    private void checkOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Requires no forward-only entry output stream to be active.
    private void requireNoActiveOutput() throws IOException {
        if (currentEntryOutput != null) {
            throw new IOException("A ZIP entry output stream is already open");
        }
    }

    /// Requires no entry body writer to be active.
    private void requireNoActiveEntry() throws IOException {
        requireNoActiveOutput();
        if (activeUpdateChannel != null) {
            throw new IOException("A ZIP entry update channel is already open");
        }
    }

    /// Checks that the entry name has not already been written.
    private void checkNewEntry(String entryName) throws IOException {
        if (visibleWrittenEntry(entryName) || visibleExistingEntry(entryName)) {
            throw new java.nio.file.FileAlreadyExistsException(entryName);
        }
    }

    /// Checks whether a writable file entry should replace an existing append-mode entry.
    private boolean checkWritableFileEntry(String entryName, OpenOption... options) throws IOException {
        if (visibleWrittenEntry(entryName)) {
            throw new java.nio.file.FileAlreadyExistsException(entryName);
        }
        boolean existingEntry = visibleExistingEntry(entryName);
        if (!existingEntry) {
            return false;
        }
        if (!replaceExistingEntries) {
            throw new java.nio.file.FileAlreadyExistsException(entryName);
        }
        if (containsOpenOption(options, StandardOpenOption.CREATE_NEW)) {
            throw new java.nio.file.FileAlreadyExistsException(entryName);
        }
        if (options.length > 0 && !containsOpenOption(options, StandardOpenOption.TRUNCATE_EXISTING)) {
            throw new UnsupportedOperationException("ZIP append replacement requires TRUNCATE_EXISTING");
        }
        return true;
    }

    /// Returns whether an open option array contains the requested standard option.
    private static boolean containsOpenOption(OpenOption[] options, StandardOpenOption expected) {
        for (OpenOption option : options) {
            if (option == expected) {
                return true;
            }
        }
        return false;
    }

    /// Registers a writable file entry after its local header has been accepted.
    private void registerWritableFileEntry(String entryName, boolean replacingExistingEntry) {
        writtenEntries.add(entryName);
        if (replacingExistingEntry) {
            replacedEntries.add(entryName);
        }
    }

    /// Requires the entry name to be new.
    private void requireNewEntry(String entryName) throws IOException {
        checkNewEntry(entryName);
        writtenEntries.add(entryName);
    }

    /// Returns whether an existing entry remains visible in this output session.
    private boolean visibleExistingEntry(String entryName) {
        return existingEntryNames.contains(entryName)
                && !deletedEntries.contains(entryName)
                && !replacedEntries.contains(entryName);
    }

    /// Returns whether an entry exists exactly in the current output session.
    private boolean visibleEntry(String entryName) {
        return visibleExistingEntry(entryName) || visibleWrittenEntry(entryName);
    }

    /// Returns whether a written entry remains visible in this output session.
    private boolean visibleWrittenEntry(String entryName) {
        for (String writtenEntry : writtenEntries) {
            if (entryNameKey(writtenEntry).equals(entryName)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether a directory exists explicitly or implicitly in the current output session.
    private boolean visibleDirectory(String entryName) {
        String childPrefix = entryName + "/";
        for (String existingEntry : existingEntryNames) {
            if (!deletedEntries.contains(existingEntry)
                    && !replacedEntries.contains(existingEntry)
                    && existingEntry.startsWith(childPrefix)) {
                return true;
            }
        }
        for (CentralEntry entry : centralEntries) {
            String key = entryNameKey(entry.entryName);
            if (key.startsWith(childPrefix)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether a directory has visible children in the current output session.
    private boolean directoryHasVisibleChildren(String entryName) {
        String childPrefix = entryName + "/";
        for (String existingEntry : existingEntryNames) {
            if (!deletedEntries.contains(existingEntry)
                    && !replacedEntries.contains(existingEntry)
                    && existingEntry.startsWith(childPrefix)) {
                return true;
            }
        }
        for (CentralEntry entry : centralEntries) {
            String key = entryNameKey(entry.entryName);
            if (key.startsWith(childPrefix)) {
                return true;
            }
        }
        return false;
    }

    /// Removes a written entry and its staged readable body from the final archive state.
    private void removeWrittenEntry(String entryName) throws IOException {
        writtenEntries.removeIf(writtenEntry -> entryNameKey(writtenEntry).equals(entryName));
        centralEntries.removeIf(entry -> entryNameKey(entry.entryName).equals(entryName));
        writtenSymbolicLinkTargets.remove(entryName);
        ArkivoStoredContent content = stagedEntryContents.remove(entryName);
        if (content != null) {
            content.close();
        }
    }

    /// Returns the normalized key for a written entry name.
    private static String entryNameKey(String entryName) {
        String key = entryName;
        while (key.endsWith("/") && !key.isEmpty()) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    /// Applies supported initial file attributes to entry metadata.
    private static EntryMetadata applyInitialAttributes(
            EntryMetadata metadata,
            boolean directory,
            FileAttribute<?>... attributes
    ) {
        Objects.requireNonNull(metadata, "metadata");
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        @Nullable FileTime lastModifiedTime = initialLastModifiedTime(attributes);

        if (permissions != null) {
            metadata = metadata.withExternalAttributes(
                    ZipPosixSupport.UNIX_VERSION_MADE_BY,
                    ZipPosixSupport.externalAttributes(permissions, directory)
            );
        }
        return lastModifiedTime != null ? metadata.withLastModifiedTime(lastModifiedTime) : metadata;
    }

    /// Applies supported initial symbolic link attributes to entry metadata.
    private static EntryMetadata applyInitialSymbolicLinkAttributes(
            EntryMetadata metadata,
            FileAttribute<?>... attributes
    ) {
        Objects.requireNonNull(metadata, "metadata");
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        @Nullable FileTime lastModifiedTime = initialLastModifiedTime(attributes);

        if (permissions != null) {
            metadata = metadata.withExternalAttributes(
                    ZipPosixSupport.UNIX_VERSION_MADE_BY,
                    ZipPosixSupport.symbolicLinkExternalAttributes(permissions)
            );
        }
        return lastModifiedTime != null ? metadata.withLastModifiedTime(lastModifiedTime) : metadata;
    }

    /// Returns initial POSIX permissions stored by supported file attributes.
    private static @Nullable Set<PosixFilePermission> initialPosixPermissions(FileAttribute<?>... attributes) {
        Objects.requireNonNull(attributes, "attributes");
        @Nullable Set<PosixFilePermission> permissions = null;
        for (FileAttribute<?> attribute : attributes) {
            Objects.requireNonNull(attribute, "attribute");
            String name = attribute.name();
            if ("posix:permissions".equals(name)) {
                permissions = posixPermissions(attribute);
            } else if (!"basic:lastModifiedTime".equals(name)) {
                throw new UnsupportedOperationException("Unsupported ZIP streaming file attribute: " + name);
            }
        }
        return permissions;
    }

    /// Returns the requested initial last-modified time, or `null` when none was supplied.
    private static @Nullable FileTime initialLastModifiedTime(FileAttribute<?>... attributes) {
        @Nullable FileTime lastModifiedTime = null;
        for (FileAttribute<?> attribute : attributes) {
            Objects.requireNonNull(attribute, "attribute");
            if ("basic:lastModifiedTime".equals(attribute.name())) {
                Object value = attribute.value();
                if (!(value instanceof FileTime fileTime)) {
                    throw new IllegalArgumentException("basic:lastModifiedTime must be a FileTime");
                }
                lastModifiedTime = fileTime;
            }
        }
        return lastModifiedTime;
    }

    /// Returns POSIX permissions stored by a file attribute.
    private static Set<PosixFilePermission> posixPermissions(FileAttribute<?> attribute) {
        return PosixPermissions.copyOf(attribute.value(), "posix:permissions");
    }

    /// Returns a normalized symbolic link target path text.
    private static String linkTargetText(Path target) {
        String text = Objects.requireNonNull(target, "target").toString().replace('\\', '/');
        if (text.isEmpty()) {
            throw new IllegalArgumentException("ZIP symbolic link target must not be empty");
        }
        return text;
    }

    /// Returns a regular file entry name for the given path.
    private String regularEntryName(Path path) {
        String entryName = entryName(path);
        if (entryName.isEmpty()) {
            throw new IllegalArgumentException("Cannot open the ZIP root as an entry output stream");
        }
        if (entryName.endsWith("/")) {
            throw new IllegalArgumentException("Regular ZIP entry names must not end with /");
        }
        return entryName;
    }

    /// Returns a directory entry name for the given path.
    private String directoryEntryName(Path path) {
        String entryName = entryName(path);
        return entryName.isEmpty() || entryName.endsWith("/") ? entryName : entryName + "/";
    }

    /// Returns a ZIP entry name for the given path.
    private String entryName(Path path) {
        if (path.getFileSystem() != this) {
            throw new java.nio.file.ProviderMismatchException();
        }

        String text = path.toAbsolutePath().normalize().toString();
        return "/".equals(text) ? "" : text.substring(1);
    }

    /// Requires output options to be compatible with forward-only ZIP writes.
    private static void requireSupportedOutputOptions(OpenOption... options) {
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == StandardOpenOption.WRITE
                    || option == StandardOpenOption.CREATE
                    || option == StandardOpenOption.CREATE_NEW
                    || option == StandardOpenOption.TRUNCATE_EXISTING) {
                continue;
            }
            throw new UnsupportedOperationException("Unsupported ZIP streaming output option: " + option);
        }
    }

    /// Returns the password for an encrypted entry path.
    private byte[] passwordForEntry(String entryName) throws IOException {
        ArkivoPasswordProvider passwordProvider = config.passwordProvider();
        if (passwordProvider == null) {
            throw new IOException("ZIP encrypted entry requires a password: " + entryName);
        }
        byte[] password = passwordProvider.passwordForEntry("/" + entryName);
        if (password == null) {
            throw new IOException("ZIP encrypted entry requires a password: " + entryName);
        }
        return password;
    }

    /// Returns the password for encrypted metadata, or `null` when the entry is not encrypted.
    private byte @Nullable [] passwordForMetadata(String entryName, EntryMetadata metadata) throws IOException {
        return metadata.encrypted() ? passwordForEntry(entryName) : null;
    }

    /// Returns the complete local file header size before entry data.
    private static long localHeaderSize(byte[] rawName, byte[] localExtraData) {
        return ZIP_LOCAL_FILE_HEADER_MIN_SIZE + (long) rawName.length + localExtraData.length;
    }

    /// Writes the local file header for an entry.
    private void writeLocalHeader(
            byte[] rawName,
            byte[] localExtraData,
            int versionNeeded,
            int flags,
            int method,
            int dosTime,
            int dosDate,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) throws IOException {
        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, versionNeeded);
        writeShort(output, flags);
        writeShort(output, method);
        writeShort(output, dosTime);
        writeShort(output, dosDate);
        writeInt(output, crc32);
        writeInt(output, compressedSize);
        writeInt(output, uncompressedSize);
        writeShort(output, rawName.length);
        writeShort(output, localExtraData.length);
        output.write(rawName);
        output.write(localExtraData);
    }

    /// Writes the data descriptor for a streamed entry.
    private void writeDataDescriptor(
            long crc32,
            long compressedSize,
            long uncompressedSize,
            boolean zip64
    ) throws IOException {
        writeInt(output, DATA_DESCRIPTOR_SIGNATURE);
        writeInt(output, crc32);
        if (zip64) {
            writeLong(output, compressedSize);
            writeLong(output, uncompressedSize);
        } else {
            writeInt(output, compressedSize);
            writeInt(output, uncompressedSize);
        }
    }

    /// Writes the central directory and end record.
    private void writeCentralDirectory() throws IOException {
        ArrayList<byte[]> centralDirectoryRecords = new ArrayList<>();
        appendExistingCentralDirectoryEntries(centralDirectoryRecords);
        for (CentralEntry entry : centralEntries) {
            centralDirectoryRecords.add(centralDirectoryEntryBytes(entry));
        }

        if (!centralDirectoryRecords.isEmpty()) {
            output.startRecord(centralDirectoryRecords.get(0).length);
        }
        long centralDirectoryAbsoluteOffset = output.position();
        int centralDirectoryDiskNumber = output.diskNumber();
        long centralDirectoryOffset = output.diskPosition();
        int[] centralEntryDiskNumbers = new int[centralDirectoryRecords.size()];
        for (int index = 0; index < centralDirectoryRecords.size(); index++) {
            byte[] record = centralDirectoryRecords.get(index);
            output.startRecord(record.length);
            centralEntryDiskNumbers[index] = output.diskNumber();
            output.write(record);
        }

        long centralDirectorySize = output.position() - centralDirectoryAbsoluteOffset;
        long entryCount = centralEntryDiskNumbers.length;
        requireUInt16(archiveComment.length, "archive comment length");
        long endRecordSize = END_OF_CENTRAL_DIRECTORY_SIZE + archiveComment.length;
        output.startRecord(endRecordSize);
        boolean zip64EndRecord = entryCount > UINT16_MAX
                || centralDirectorySize > UINT32_MAX
                || centralDirectoryOffset > UINT32_MAX
                || centralDirectoryDiskNumber > UINT16_MAX
                || output.diskNumber() > UINT16_MAX;
        if (zip64EndRecord) {
            Zip64EndLocation zip64End = writeZip64EndRecord(
                    entryCount,
                    centralDirectorySize,
                    centralDirectoryDiskNumber,
                    centralDirectoryOffset,
                    centralEntryDiskNumbers
            );
            long finalRecordsSize = ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE + endRecordSize;
            output.startRecord(finalRecordsSize);
            int totalDisks = output.diskCountAfter(finalRecordsSize);
            writeInt(output, ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE);
            writeInt(output, zip64End.diskNumber());
            writeLong(output, zip64End.offset());
            writeInt(output, totalDisks);
        }

        int endRecordDiskNumber = output.diskNumber();
        long entriesOnEndRecordDisk = centralEntryCountOnDisk(centralEntryDiskNumbers, endRecordDiskNumber);
        writeInt(output, END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        writeShort(output, zip16Field(endRecordDiskNumber, zip64EndRecord));
        writeShort(output, zip16Field(centralDirectoryDiskNumber, zip64EndRecord));
        writeShort(output, zip16Field(entriesOnEndRecordDisk, zip64EndRecord));
        writeShort(output, zip16Field(entryCount, zip64EndRecord));
        writeInt(output, zip32Field(centralDirectorySize, zip64EndRecord));
        writeInt(output, zip32Field(centralDirectoryOffset, zip64EndRecord));
        writeShort(output, archiveComment.length);
        output.write(archiveComment);
    }

    /// Appends existing central directory records that remain visible after replacements.
    private void appendExistingCentralDirectoryEntries(List<byte[]> records) throws IOException {
        for (ExistingEntry entry : existingCentralDirectoryEntries) {
            if (!replacedEntries.contains(entry.entryName()) && !deletedEntries.contains(entry.entryName())) {
                RelocatedLocalHeader relocatedHeader = relocatedExistingEntryOffsets.get(entry.entryName());
                if (relocatedHeader == null) {
                    throw new IOException("ZIP existing entry was not relocated: " + entry.entryName());
                }
                records.add(relocateCentralDirectoryEntry(entry.centralDirectoryBytes(), relocatedHeader));
            }
        }
    }

    /// Returns an existing central directory entry with its local header location relocated.
    private static byte[] relocateCentralDirectoryEntry(
            byte[] rawEntry,
            RelocatedLocalHeader relocatedHeader
    ) throws IOException {
        Objects.requireNonNull(rawEntry, "rawEntry");
        Objects.requireNonNull(relocatedHeader, "relocatedHeader");
        long localHeaderOffset = relocatedHeader.offset();
        long localHeaderDiskNumber = relocatedHeader.diskNumber();
        requireUInt64(localHeaderOffset, "local header offset");
        requireUInt32(localHeaderDiskNumber, "local header disk number");
        if (rawEntry.length < ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE) {
            throw new IOException("Invalid ZIP central directory header");
        }

        ByteBuffer source = ByteBuffer.wrap(rawEntry).order(ByteOrder.LITTLE_ENDIAN);
        if (source.getInt(0) != CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP central directory header");
        }
        int nameLength = Short.toUnsignedInt(source.getShort(28));
        int extraLength = Short.toUnsignedInt(source.getShort(30));
        int commentLength = Short.toUnsignedInt(source.getShort(32));
        int extraOffset = ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE + nameLength;
        if (extraOffset + extraLength + commentLength != rawEntry.length) {
            throw new IOException("Invalid ZIP central directory variable data length");
        }

        boolean zip64UncompressedSize = Integer.toUnsignedLong(source.getInt(24)) == UINT32_MAX;
        boolean zip64CompressedSize = Integer.toUnsignedLong(source.getInt(20)) == UINT32_MAX;
        boolean originalZip64LocalHeaderOffset = Integer.toUnsignedLong(source.getInt(42)) == UINT32_MAX;
        boolean originalZip64DiskNumber = Short.toUnsignedInt(source.getShort(34)) == UINT16_MAX;
        boolean zip64LocalHeaderOffset = originalZip64LocalHeaderOffset || localHeaderOffset > UINT32_MAX;
        boolean zip64DiskNumber = originalZip64DiskNumber || localHeaderDiskNumber > UINT16_MAX;

        byte[] extraData = Arrays.copyOfRange(rawEntry, extraOffset, extraOffset + extraLength);
        ZipExtraFields.validate(extraData);
        ZipExtraFields.Field zip64 = ZipExtraFields.find(
                extraData,
                ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID
        );
        int sizeValueLength = (zip64UncompressedSize ? Long.BYTES : 0)
                + (zip64CompressedSize ? Long.BYTES : 0);
        int originalLocationLength = (originalZip64LocalHeaderOffset ? Long.BYTES : 0)
                + (originalZip64DiskNumber ? Integer.BYTES : 0);
        if (zip64 == null && sizeValueLength + originalLocationLength != 0) {
            throw new IOException("ZIP64 central directory entry is missing extended information");
        }

        byte[] relocatedExtraData = extraData;
        if (zip64 != null || zip64LocalHeaderOffset || zip64DiskNumber) {
            int oldDataOffset = zip64 == null ? extraData.length : zip64.dataOffset();
            int oldDataSize = zip64 == null ? 0 : zip64.dataSize();
            if (oldDataSize < sizeValueLength + originalLocationLength) {
                throw new IOException("Invalid ZIP64 central directory extra field length");
            }
            int trailingOffset = oldDataOffset + sizeValueLength + originalLocationLength;
            int trailingLength = oldDataSize - sizeValueLength - originalLocationLength;
            int newDataSize = sizeValueLength
                    + (zip64LocalHeaderOffset ? Long.BYTES : 0)
                    + (zip64DiskNumber ? Integer.BYTES : 0)
                    + trailingLength;
            byte[] zip64Field = new byte[Integer.BYTES + newDataSize];
            ByteBuffer zip64Buffer = ByteBuffer.wrap(zip64Field).order(ByteOrder.LITTLE_ENDIAN);
            zip64Buffer.putShort((short) ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID);
            zip64Buffer.putShort((short) newDataSize);
            if (sizeValueLength > 0) {
                zip64Buffer.put(extraData, oldDataOffset, sizeValueLength);
            }
            if (zip64LocalHeaderOffset) {
                zip64Buffer.putLong(localHeaderOffset);
            }
            if (zip64DiskNumber) {
                zip64Buffer.putInt((int) localHeaderDiskNumber);
            }
            if (trailingLength > 0) {
                zip64Buffer.put(extraData, trailingOffset, trailingLength);
            }

            int oldFieldOffset = zip64 == null ? extraData.length : zip64.dataOffset() - Integer.BYTES;
            int oldFieldLength = zip64 == null ? 0 : Integer.BYTES + zip64.dataSize();
            relocatedExtraData = new byte[extraData.length - oldFieldLength + zip64Field.length];
            System.arraycopy(extraData, 0, relocatedExtraData, 0, oldFieldOffset);
            System.arraycopy(zip64Field, 0, relocatedExtraData, oldFieldOffset, zip64Field.length);
            System.arraycopy(
                    extraData,
                    oldFieldOffset + oldFieldLength,
                    relocatedExtraData,
                    oldFieldOffset + zip64Field.length,
                    extraData.length - oldFieldOffset - oldFieldLength
            );
        }
        requireUInt16(relocatedExtraData.length, "central directory extra data length");

        byte[] relocated = new byte[
                rawEntry.length - extraLength + relocatedExtraData.length
        ];
        System.arraycopy(rawEntry, 0, relocated, 0, extraOffset);
        System.arraycopy(relocatedExtraData, 0, relocated, extraOffset, relocatedExtraData.length);
        System.arraycopy(
                rawEntry,
                extraOffset + extraLength,
                relocated,
                extraOffset + relocatedExtraData.length,
                commentLength
        );
        ByteBuffer result = ByteBuffer.wrap(relocated).order(ByteOrder.LITTLE_ENDIAN);
        if (zip64LocalHeaderOffset || zip64DiskNumber) {
            result.putShort(6, (short) Math.max(
                    Short.toUnsignedInt(result.getShort(6)),
                    ZIP64_VERSION_NEEDED
            ));
        }
        result.putShort(30, (short) relocatedExtraData.length);
        result.putShort(34, (short) (zip64DiskNumber ? UINT16_MAX : localHeaderDiskNumber));
        result.putInt(42, (int) (zip64LocalHeaderOffset ? UINT32_MAX : localHeaderOffset));
        return relocated;
    }

    /// Replaces the encoded name in one central directory entry with an authoritative UTF-8 name.
    private static byte[] renameCentralDirectoryEntry(byte[] rawEntry, byte[] rawName) throws IOException {
        Objects.requireNonNull(rawEntry, "rawEntry");
        Objects.requireNonNull(rawName, "rawName");
        requireUInt16(rawName.length, "entry name length");
        if (rawEntry.length < ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE) {
            throw new IOException("Invalid ZIP central directory header");
        }

        ByteBuffer source = ByteBuffer.wrap(rawEntry).order(ByteOrder.LITTLE_ENDIAN);
        if (source.getInt(0) != CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP central directory header");
        }
        int oldNameLength = Short.toUnsignedInt(source.getShort(28));
        int oldExtraLength = Short.toUnsignedInt(source.getShort(30));
        int commentLength = Short.toUnsignedInt(source.getShort(32));
        int oldExtraOffset = ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE + oldNameLength;
        int oldCommentOffset = oldExtraOffset + oldExtraLength;
        if (oldCommentOffset + commentLength != rawEntry.length) {
            throw new IOException("Invalid ZIP central directory variable data length");
        }

        byte[] extraData = removeUnicodePathExtraField(
                Arrays.copyOfRange(rawEntry, oldExtraOffset, oldCommentOffset)
        );
        requireUInt16(extraData.length, "central directory extra data length");
        byte[] renamed = new byte[
                ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE + rawName.length + extraData.length + commentLength
        ];
        System.arraycopy(rawEntry, 0, renamed, 0, ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE);
        int extraOffset = ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE + rawName.length;
        int commentOffset = extraOffset + extraData.length;
        System.arraycopy(rawName, 0, renamed, ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE, rawName.length);
        System.arraycopy(extraData, 0, renamed, extraOffset, extraData.length);
        System.arraycopy(rawEntry, oldCommentOffset, renamed, commentOffset, commentLength);

        ByteBuffer result = ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN);
        result.putShort(8, (short) (Short.toUnsignedInt(result.getShort(8)) | UTF8_FLAG));
        result.putShort(28, (short) rawName.length);
        result.putShort(30, (short) extraData.length);
        return renamed;
    }

    /// Removes every extra field with one identifier while preserving all other records byte-for-byte.
    private static byte[] removeUnicodePathExtraField(byte[] extraData) throws IOException {
        ZipExtraFields.validate(extraData);
        int retainedSize = 0;
        int offset = 0;
        while (offset < extraData.length) {
            ZipExtraFields.Field field = ZipExtraFields.read(extraData, offset);
            if (field.id() != ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID) {
                retainedSize += field.nextOffset() - offset;
            }
            offset = field.nextOffset();
        }
        if (retainedSize == extraData.length) {
            return extraData;
        }

        byte[] retained = new byte[retainedSize];
        int targetOffset = 0;
        offset = 0;
        while (offset < extraData.length) {
            ZipExtraFields.Field field = ZipExtraFields.read(extraData, offset);
            int fieldSize = field.nextOffset() - offset;
            if (field.id() != ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID) {
                System.arraycopy(extraData, offset, retained, targetOffset, fieldSize);
                targetOffset += fieldSize;
            }
            offset = field.nextOffset();
        }
        return retained;
    }

    /// Writes a ZIP64 end record and returns its physical location.
    private Zip64EndLocation writeZip64EndRecord(
            long entryCount,
            long centralDirectorySize,
            int centralDirectoryDiskNumber,
            long centralDirectoryOffset,
            int[] centralEntryDiskNumbers
    ) throws IOException {
        output.startRecord(ZIP64_END_OF_CENTRAL_DIRECTORY_SIZE);
        int zip64EndDiskNumber = output.diskNumber();
        long zip64EndOffset = output.diskPosition();
        long entriesOnZip64EndDisk = centralEntryCountOnDisk(centralEntryDiskNumbers, zip64EndDiskNumber);
        writeInt(output, ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        writeLong(output, ZIP64_END_OF_CENTRAL_DIRECTORY_PAYLOAD_SIZE);
        writeShort(output, ZIP64_VERSION_NEEDED);
        writeShort(output, ZIP64_VERSION_NEEDED);
        writeInt(output, zip64EndDiskNumber);
        writeInt(output, centralDirectoryDiskNumber);
        writeLong(output, entriesOnZip64EndDisk);
        writeLong(output, entryCount);
        writeLong(output, centralDirectorySize);
        writeLong(output, centralDirectoryOffset);
        return new Zip64EndLocation(zip64EndDiskNumber, zip64EndOffset);
    }

    /// Serializes one complete central directory entry.
    private static byte[] centralDirectoryEntryBytes(CentralEntry entry) throws IOException {
        requireUInt16(entry.versionMadeBy, "version made by");
        requireUInt16(entry.internalAttributes, "internal attributes");
        requireUInt32(entry.finalLocalHeaderDiskNumber(), "local header disk number");
        requireUInt32(entry.externalAttributes, "external attributes");
        byte[] centralDirectoryExtraData = entry.centralDirectoryExtraData();
        requireUInt16(centralDirectoryExtraData.length, "central directory extra data length");
        requireUInt16(entry.rawComment.length, "comment length");

        int size = Math.addExact(
                ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE + entry.rawName.length,
                centralDirectoryExtraData.length + entry.rawComment.length
        );
        ByteBuffer record = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        record.putShort((short) entry.versionMadeBy);
        record.putShort((short) entry.finalVersionNeeded());
        record.putShort((short) entry.flags);
        record.putShort((short) entry.method);
        record.putShort((short) entry.dosTime);
        record.putShort((short) entry.dosDate);
        record.putInt((int) entry.crc32);
        record.putInt((int) zip32Field(entry.compressedSize, entry.zip64CompressedSize()));
        record.putInt((int) zip32Field(entry.uncompressedSize, entry.zip64UncompressedSize()));
        record.putShort((short) entry.rawName.length);
        record.putShort((short) centralDirectoryExtraData.length);
        record.putShort((short) entry.rawComment.length);
        record.putShort((short) zip16Field(
                entry.finalLocalHeaderDiskNumber(),
                entry.zip64LocalHeaderDiskNumber()
        ));
        record.putShort((short) entry.internalAttributes);
        record.putInt((int) entry.externalAttributes);
        record.putInt((int) zip32Field(entry.finalLocalHeaderOffset(), entry.zip64LocalHeaderOffset()));
        record.put(entry.rawName);
        record.put(centralDirectoryExtraData);
        record.put(entry.rawComment);
        return record.array();
    }

    /// Returns how many central directory entries start on the given disk.
    private static long centralEntryCountOnDisk(int[] centralEntryDiskNumbers, int diskNumber) {
        long count = 0;
        for (int entryDiskNumber : centralEntryDiskNumbers) {
            if (entryDiskNumber == diskNumber) {
                count++;
            }
        }
        return count;
    }

    /// Returns a ZIP16 field value or the ZIP64 marker.
    private static long zip16Field(long value, boolean zip64) {
        if (zip64) {
            return UINT16_MAX;
        }
        requireUInt16(value, "ZIP16 field");
        return value;
    }

    /// Returns a ZIP32 field value or the ZIP64 marker.
    private static long zip32Field(long value, boolean zip64) {
        if (zip64) {
            return UINT32_MAX;
        }
        requireUInt32(value, "ZIP32 field");
        return value;
    }

    /// Returns whether a ZIP field needs ZIP64 metadata.
    private static boolean zip64Field(long value) {
        requireUInt64(value, "ZIP64 field");
        return value > UINT32_MAX;
    }

    /// Appends one ZIP extra field block.
    private static byte[] appendExtraData(byte[] extraData, byte[] field) {
        byte[] combined = new byte[extraData.length + field.length];
        System.arraycopy(extraData, 0, combined, 0, extraData.length);
        System.arraycopy(field, 0, combined, extraData.length, field.length);
        return combined;
    }

    /// Requires user-provided extra data not to contain reserved ZIP64 metadata.
    private static void requireNoZip64ExtraField(byte[] extraData) throws IOException {
        if (ZipExtraFields.find(extraData, ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID) != null) {
            throw new IOException("ZIP64 extended information extra field is reserved for writer-generated metadata");
        }
    }

    /// Returns a ZIP64 extended information extra field without a disk number.
    private static byte[] zip64ExtendedInformationExtra(
            boolean includeUncompressedSize,
            boolean includeCompressedSize,
            boolean includeLocalHeaderOffset,
            long uncompressedSize,
            long compressedSize,
            long localHeaderOffset
    ) {
        return zip64ExtendedInformationExtra(
                includeUncompressedSize,
                includeCompressedSize,
                includeLocalHeaderOffset,
                false,
                uncompressedSize,
                compressedSize,
                localHeaderOffset,
                0
        );
    }

    /// Returns a ZIP64 extended information extra field.
    private static byte[] zip64ExtendedInformationExtra(
            boolean includeUncompressedSize,
            boolean includeCompressedSize,
            boolean includeLocalHeaderOffset,
            boolean includeDiskNumber,
            long uncompressedSize,
            long compressedSize,
            long localHeaderOffset,
            int diskNumber
    ) {
        int payloadSize = 0;
        if (includeUncompressedSize) {
            payloadSize += Long.BYTES;
        }
        if (includeCompressedSize) {
            payloadSize += Long.BYTES;
        }
        if (includeLocalHeaderOffset) {
            payloadSize += Long.BYTES;
        }
        if (includeDiskNumber) {
            payloadSize += Integer.BYTES;
        }

        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + payloadSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID);
        buffer.putShort((short) payloadSize);
        if (includeUncompressedSize) {
            buffer.putLong(uncompressedSize);
        }
        if (includeCompressedSize) {
            buffer.putLong(compressedSize);
        }
        if (includeLocalHeaderOffset) {
            buffer.putLong(localHeaderOffset);
        }
        if (includeDiskNumber) {
            buffer.putInt(diskNumber);
        }
        return buffer.array();
    }

    /// Returns UTF-8 encoded entry name bytes.
    private static byte[] rawEntryName(String entryName) {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        requireUInt16(rawName.length, "entry name length");
        return rawName;
    }

    /// Encodes a file time into a ZIP DOS time field.
    private static int dosTime(@Nullable FileTime time) {
        LocalDateTime dateTime = dosDateTime(time);
        return (dateTime.getHour() << 11) | (dateTime.getMinute() << 5) | (dateTime.getSecond() / 2);
    }

    /// Encodes a file time into a ZIP DOS date field.
    private static int dosDate(@Nullable FileTime time) {
        LocalDateTime dateTime = dosDateTime(time);
        return ((dateTime.getYear() - 1980) << 9) | (dateTime.getMonthValue() << 5) | dateTime.getDayOfMonth();
    }

    /// Converts a file time to a ZIP-representable local date time.
    private static LocalDateTime dosDateTime(@Nullable FileTime time) {
        if (time == null) {
            return LocalDateTime.of(1980, 1, 1, 0, 0, 0);
        }
        try {
            LocalDateTime value = LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault());
            if (value.getYear() < 1980) {
                return LocalDateTime.of(1980, 1, 1, 0, 0, 0);
            }
            if (value.getYear() > 2107) {
                return LocalDateTime.of(2107, 12, 31, 23, 59, 58);
            }
            return value.withNano(0);
        } catch (DateTimeException exception) {
            return LocalDateTime.of(1980, 1, 1, 0, 0, 0);
        }
    }

    /// Converts ZIP DOS date and time fields to a file time.
    private static FileTime fileTimeFromDos(int date, int time) {
        int day = date & 0x1f;
        int month = (date >>> 5) & 0x0f;
        int year = ((date >>> 9) & 0x7f) + 1980;
        int second = (time & 0x1f) * 2;
        int minute = (time >>> 5) & 0x3f;
        int hour = (time >>> 11) & 0x1f;
        try {
            return FileTime.from(LocalDateTime.of(year, month, day, hour, minute, second)
                    .atZone(ZoneId.systemDefault())
                    .toInstant());
        } catch (DateTimeException exception) {
            return FileTime.fromMillis(0);
        }
    }

    /// Acquires the state lock when it is present.
    private void lock() {
        ZipLocks.lock(lock);
    }

    /// Releases the state lock when it is present.
    private void unlock() {
        ZipLocks.unlock(lock);
    }

    /// Writes bytes to the current ZIP entry.
    private final class EntryOutputStream extends OutputStream {
        /// The entry name.
        private final String entryName;

        /// The raw entry name bytes.
        private final byte[] rawName;

        /// The disk where the local header starts.
        private final int localHeaderDiskNumber;

        /// The local header offset relative to its starting disk.
        private final long localHeaderOffset;

        /// The absolute local header offset in the current output stream.
        private final long localHeaderAbsoluteOffset;

        /// The compressed data offset.
        private final long compressedDataOffset;

        /// The general purpose bit flags.
        private final int flags;

        /// The metadata used to write this entry.
        private final EntryMetadata metadata;

        /// The CRC-32 of uncompressed entry data.
        private final CRC32 crc32 = new CRC32();


        /// The stream that receives uncompressed bytes from the caller.
        private final OutputStream entryOutput;

        /// The optional channel-first compression encoder used by this entry.
        private final @Nullable CompressingWritableByteChannel compressionEncoder;

        /// The optional storage stream that preserves uncompressed bytes for update-session reads.
        private final @Nullable OutputStream stagedEntryOutput;

        /// The WinZip AES output stream, or `null` when this entry does not use WinZip AES.
        private final @Nullable ZipAesCrypto.EncryptingOutputStream aesOutput;

        /// The uncompressed entry size.
        private long uncompressedSize;

        /// Whether this entry output stream is open.
        private boolean entryOpen = true;

        /// Creates an entry output stream.
        private EntryOutputStream(
                String entryName,
                byte[] rawName,
                int localHeaderDiskNumber,
                long localHeaderOffset,
                long localHeaderAbsoluteOffset,
                long compressedDataOffset,
                int flags,
                EntryMetadata metadata,
                byte @Nullable [] password
        ) throws IOException {
            this.entryName = entryName;
            this.rawName = rawName;
            this.localHeaderDiskNumber = localHeaderDiskNumber;
            this.localHeaderOffset = localHeaderOffset;
            this.localHeaderAbsoluteOffset = localHeaderAbsoluteOffset;
            this.compressedDataOffset = compressedDataOffset;
            this.flags = flags;
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            OutputStream dataOutput = output;
            ZipAesCrypto.EncryptingOutputStream entryAesOutput = null;
            long localHeaderSize = output.position() - localHeaderAbsoluteOffset;
            if (metadata.traditionalEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                dataOutput = ZipTraditionalCrypto.openEncryptingStream(
                        output,
                        encryptionPassword,
                        metadata.encryptionVerificationByte()
                );
            } else if (metadata.aesEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                entryAesOutput = ZipAesCrypto.openEncryptingStream(
                        output,
                        metadata.aesExtraField(),
                        encryptionPassword
                );
                dataOutput = entryAesOutput;
            }
            this.aesOutput = entryAesOutput;
            CompressingWritableByteChannel entryCompressingWritableByteChannel = null;
            if (metadata.method == DEFLATED_METHOD) {
                entryCompressingWritableByteChannel = ZipCompressionFormats.newWritableByteChannel(
                        "deflate",
                        dataOutput
                );
                this.entryOutput = StreamChannelAdapters.outputStream(entryCompressingWritableByteChannel);
            } else if (metadata.method == DEFLATE64_METHOD) {
                entryCompressingWritableByteChannel = ZipCompressionFormats.newWritableByteChannel(
                        "deflate64",
                        dataOutput
                );
                this.entryOutput = StreamChannelAdapters.outputStream(entryCompressingWritableByteChannel);
            } else if (metadata.method == BZIP2_METHOD) {
                entryCompressingWritableByteChannel = ZipCompressionFormats.newWritableByteChannel(
                        "bzip2",
                        dataOutput
                );
                this.entryOutput = StreamChannelAdapters.outputStream(entryCompressingWritableByteChannel);
            } else if (isZstandardMethod(metadata.method)) {
                entryCompressingWritableByteChannel = ZipCompressionFormats.newWritableByteChannel(
                        "zstd",
                        dataOutput
                );
                this.entryOutput = StreamChannelAdapters.outputStream(entryCompressingWritableByteChannel);
            } else if (metadata.method == LZMA_METHOD) {
                this.entryOutput = new ZipLZMAOutputStream(dataOutput);
            } else if (metadata.method == XZ_METHOD) {
                entryCompressingWritableByteChannel = ZipCompressionFormats.newWritableByteChannel(
                        "xz",
                        dataOutput
                );
                this.entryOutput = StreamChannelAdapters.outputStream(entryCompressingWritableByteChannel);
            } else {
                this.entryOutput = dataOutput;
            }
            this.compressionEncoder = entryCompressingWritableByteChannel;
            this.stagedEntryOutput = openStagedEntryOutput(entryName);
        }

        /// Writes one byte to the current ZIP entry.
        @Override
        public void write(int value) throws IOException {
            lock();
            try {
                ensureEntryOpen();
                crc32.update(value);
                uncompressedSize++;
                OutputStream stagedOutput = stagedEntryOutput;
                if (stagedOutput != null) {
                    stagedOutput.write(value);
                }
                entryOutput.write(value);
            } finally {
                unlock();
            }
        }

        /// Writes bytes to the current ZIP entry.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            lock();
            try {
                ensureEntryOpen();
                crc32.update(bytes, offset, length);
                uncompressedSize += length;
                OutputStream stagedOutput = stagedEntryOutput;
                if (stagedOutput != null) {
                    stagedOutput.write(bytes, offset, length);
                }
                entryOutput.write(bytes, offset, length);
            } finally {
                unlock();
            }
        }

        /// Closes the current ZIP entry.
        @Override
        public void close() throws IOException {
            lock();
            try {
                if (!entryOpen) {
                    return;
                }
                entryOpen = false;
                try {
                    OutputStream stagedOutput = stagedEntryOutput;
                    if (stagedOutput != null) {
                        stagedOutput.close();
                    }
                    CompressingWritableByteChannel entryCompressingWritableByteChannel = compressionEncoder;
                    if (entryCompressingWritableByteChannel != null) {
                        entryCompressingWritableByteChannel.finish();
                    } else if (entryOutput instanceof ZipLZMAOutputStream lzmaOutput) {
                        lzmaOutput.finish();
                    }
                    ZipAesCrypto.EncryptingOutputStream entryAesOutput = aesOutput;
                    if (entryAesOutput != null) {
                        entryAesOutput.finish();
                    }
                    long compressedSize = output.position() - compressedDataOffset;
                    long crcValue = crc32.getValue();
                    if (metadata.expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                            && metadata.expectedUncompressedSize != uncompressedSize) {
                        throw new IOException("ZIP entry size does not match the configured size: " + entryName);
                    }
                    if (metadata.expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32
                            && metadata.expectedCrc32 != crcValue) {
                        throw new IOException("ZIP entry CRC-32 does not match the configured CRC-32: " + entryName);
                    }
                    if (metadata.requiresDataDescriptor()) {
                        boolean zip64DataDescriptor = metadata.zip64DataDescriptor();
                        if (!zip64DataDescriptor
                                && (compressedSize > UINT32_MAX || uncompressedSize > UINT32_MAX)) {
                            throw new IOException("ZIP entry needs ZIP64 sizes before streaming data starts: " + entryName);
                        }
                        writeDataDescriptor(crcValue, compressedSize, uncompressedSize, zip64DataDescriptor);
                    }
                    centralEntries.add(new CentralEntry(
                            entryName,
                            rawName,
                            flags,
                            metadata.headerMethod(),
                            metadata.dosTime,
                            metadata.dosDate,
                            crcValue,
                            compressedSize,
                            uncompressedSize,
                            localHeaderDiskNumber,
                            localHeaderOffset,
                            compressedDataOffset - localHeaderAbsoluteOffset,
                            output.position() - localHeaderAbsoluteOffset,
                            metadata
                    ));
                } catch (IOException | RuntimeException | Error exception) {
                    abortSplitOutput(output);
                    throw exception;
                } finally {
                    currentEntryOutput = null;
                }
            } finally {
                unlock();
            }
        }

        /// Requires this entry stream to be open.
        private void ensureEntryOpen() throws IOException {
            if (!entryOpen || currentEntryOutput != this) {
                throw new IOException("ZIP entry output stream is closed");
            }
            checkOpen();
        }
    }
    /// Counts bytes written to an output stream.
    @NotNullByDefault
    private static class CountingOutputStream extends OutputStream {
        /// The wrapped output stream.
        protected OutputStream output;

        /// The number of bytes written.
        protected long position;

        /// Creates a counting output stream.
        private CountingOutputStream(OutputStream output, long initialPosition) {
            if (initialPosition < 0) {
                throw new IllegalArgumentException("initialPosition must not be negative");
            }
            this.output = Objects.requireNonNull(output, "output");
            this.position = initialPosition;
        }

        /// Returns the number of bytes written.
        private long position() {
            return position;
        }

        /// Returns the disk where the next byte will be written.
        int diskNumber() {
            return 0;
        }

        /// Returns the offset where the next byte will be written within the current disk.
        long diskPosition() {
            return position;
        }

        /// Returns the disk count after writing the given number of additional bytes.
        int diskCountAfter(long additionalBytes) {
            if (additionalBytes < 0) {
                throw new IllegalArgumentException("additionalBytes must not be negative");
            }
            return 1;
        }

        /// Starts a ZIP record that must remain on one physical disk.
        void startRecord(long recordSize) throws IOException {
            if (recordSize < 0L) {
                throw new IllegalArgumentException("recordSize must not be negative");
            }
        }

        /// Writes one byte.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
            position++;
        }

        /// Writes bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
            position += length;
        }

        /// Flushes the wrapped output stream.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Closes the wrapped output stream.
        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    /// Creates path-backed transactional output for conventional ZIP split volumes.
    ///
    /// @param archivePath the final archive path
    /// @param openOptions the requested archive open options
    @NotNullByDefault
    private record PathVolumeTarget(Path archivePath, @Unmodifiable Set<OpenOption> openOptions)
            implements ArkivoVolumeTarget {
        /// Creates a path-backed ZIP volume target.
        private PathVolumeTarget(Path archivePath, Set<OpenOption> openOptions) {
            this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
            this.openOptions = Set.copyOf(openOptions);
        }

        /// Opens staged output through the shared path volume transaction implementation.
        @Override
        public ArkivoVolumeOutput openOutput() throws IOException {
            requireSplitTargetOptions(archivePath, openOptions);
            return new ArkivoPathVolumeTarget(new PathVolumeLayout(archivePath), openOptions).openOutput();
        }
    }

    /// Maps conventional split ZIP disks to their final published paths.
    ///
    /// @param archivePath the normalized final ZIP archive path
    @NotNullByDefault
    private record PathVolumeLayout(Path archivePath) implements ArkivoVolumePathLayout {
        /// Creates one conventional ZIP path layout.
        private PathVolumeLayout(Path archivePath) {
            this.archivePath = Objects.requireNonNull(archivePath, "archivePath")
                    .toAbsolutePath()
                    .normalize();
        }

        /// Returns the common output directory for the ZIP archive and its numbered disks.
        @Override
        public Path outputDirectory() {
            Path parent = archivePath.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("archivePath must have a parent directory");
            }
            return parent;
        }

        /// Returns a numbered path for a preceding disk or the archive path for the final disk.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            if (index < 0L || index > finalVolumeIndex || index > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid ZIP disk index: " + index);
            }
            return index == finalVolumeIndex
                    ? archivePath
                    : ZipSplitVolumePaths.numberedVolumePath(archivePath, (int) index);
        }

        /// Returns all existing final and numbered disk paths owned by this ZIP layout.
        @Override
        public @Unmodifiable List<Path> existingVolumePaths() throws IOException {
            String baseName = outputBaseName(archivePath);
            ArrayList<Path> paths = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDirectory())) {
                for (Path path : stream) {
                    Path fileName = path.getFileName();
                    if (fileName != null && isNumberedVolumeName(fileName.toString(), baseName)) {
                        paths.add(path);
                    }
                }
            }
            if (Files.exists(archivePath)) {
                paths.add(archivePath);
            }
            return List.copyOf(paths);
        }

        /// Returns the base file name used by conventional numbered ZIP disks.
        private static String outputBaseName(Path archivePath) {
            Path fileName = archivePath.getFileName();
            if (fileName == null) {
                throw new IllegalArgumentException("archivePath must have a file name");
            }
            String name = fileName.toString();
            return name.length() >= 4 && name.regionMatches(true, name.length() - 4, ".zip", 0, 4)
                    ? name.substring(0, name.length() - 4)
                    : name;
        }

        /// Returns whether a file name is a conventional numbered disk for the output base name.
        private static boolean isNumberedVolumeName(String fileName, String baseName) {
            String prefix = baseName + ".z";
            if (!fileName.startsWith(prefix) || fileName.length() == prefix.length()) {
                return false;
            }
            for (int index = prefix.length(); index < fileName.length(); index++) {
                if (!Character.isDigit(fileName.charAt(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    /// Writes ZIP bytes to transactional split archive volumes.
    @NotNullByDefault
    private static final class SplitCountingOutputStream extends CountingOutputStream {
        /// The transactional multi-volume output.
        private final ArkivoVolumeOutput volumeOutput;

        /// The maximum number of bytes written to one split volume.
        private final long splitSize;

        /// The zero-based disk number currently receiving bytes.
        private int currentDiskNumber;

        /// The number of bytes written to the current disk.
        private long currentDiskPosition;

        /// The first write or flush failure that prevents publication.
        private @Nullable Throwable writeFailure;

        /// Whether a higher-level archive operation requires rollback.
        private boolean rollbackOnly;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Opens a split ZIP output stream over a transactional volume target.
        private static SplitCountingOutputStream open(ArkivoVolumeTarget target, long splitSize) throws IOException {
            ArkivoVolumeOutput volumeOutput = Objects.requireNonNull(target.openOutput(), "volumeOutput");
            @Nullable WritableByteChannel channel = null;
            @Nullable OutputStream stream = null;
            try {
                channel = Objects.requireNonNull(volumeOutput.openVolume(0L), "volume channel");
                stream = Channels.newOutputStream(channel);
                SplitCountingOutputStream result = new SplitCountingOutputStream(volumeOutput, splitSize, stream);
                writeInt(result, DATA_DESCRIPTOR_SIGNATURE);
                stream = null;
                channel = null;
                return result;
            } catch (IOException | RuntimeException | Error exception) {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                } else if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    volumeOutput.rollback();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                try {
                    volumeOutput.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        }

        /// Creates a split ZIP output stream.
        private SplitCountingOutputStream(ArkivoVolumeOutput volumeOutput, long splitSize, OutputStream output) {
            super(output, 0L);
            this.volumeOutput = Objects.requireNonNull(volumeOutput, "volumeOutput");
            this.splitSize = splitSize;
        }

        /// Returns the disk where the next byte will be written.
        @Override
        int diskNumber() {
            if (currentDiskPosition == splitSize) {
                return Math.addExact(currentDiskNumber, 1);
            }
            return currentDiskNumber;
        }

        /// Returns the offset where the next byte will be written within the current disk.
        @Override
        long diskPosition() {
            return currentDiskPosition == splitSize ? 0L : currentDiskPosition;
        }

        /// Returns the disk count after writing the given number of additional bytes.
        @Override
        int diskCountAfter(long additionalBytes) {
            if (additionalBytes < 0) {
                throw new IllegalArgumentException("additionalBytes must not be negative");
            }
            int diskNumber = diskNumber();
            long diskPosition = diskPosition();
            if (additionalBytes == 0) {
                return diskNumber + 1;
            }
            long remaining = splitSize - diskPosition;
            if (additionalBytes <= remaining) {
                return diskNumber + 1;
            }
            long remainingAfterFirstDisk = additionalBytes - remaining;
            long additionalDisks = (remainingAfterFirstDisk + splitSize - 1L) / splitSize;
            long diskCount = diskNumber + 1L + additionalDisks;
            if (diskCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("ZIP split disk count is too large");
            }
            return (int) diskCount;
        }

        /// Starts a ZIP record on a disk where the complete record fits.
        @Override
        void startRecord(long recordSize) throws IOException {
            if (recordSize < 0L) {
                throw new IllegalArgumentException("recordSize must not be negative");
            }
            if (recordSize > splitSize) {
                throw new IOException("ZIP record exceeds the configured split size");
            }
            try {
                if (currentDiskPosition > 0L && recordSize > splitSize - currentDiskPosition) {
                    openNextDisk();
                }
            } catch (IOException | RuntimeException | Error exception) {
                recordWriteFailure(exception);
                throw exception;
            }
        }

        /// Writes one byte.
        @Override
        public void write(int value) throws IOException {
            try {
                ensureWritableDisk();
                output.write(value);
                position++;
                currentDiskPosition++;
            } catch (IOException | RuntimeException | Error exception) {
                recordWriteFailure(exception);
                throw exception;
            }
        }

        /// Writes bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            try {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                while (length > 0) {
                    ensureWritableDisk();
                    int count = (int) Math.min(length, splitSize - currentDiskPosition);
                    output.write(bytes, offset, count);
                    position += count;
                    currentDiskPosition += count;
                    offset += count;
                    length -= count;
                }
            } catch (IOException | RuntimeException | Error exception) {
                recordWriteFailure(exception);
                throw exception;
            }
        }

        /// Flushes the current volume.
        @Override
        public void flush() throws IOException {
            try {
                output.flush();
            } catch (IOException | RuntimeException | Error exception) {
                recordWriteFailure(exception);
                throw exception;
            }
        }

        /// Closes and either commits or rolls back the transactional volume output.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            @Nullable Throwable failure = null;
            try {
                output.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
            try {
                if (writeFailure == null && failure == null && !rollbackOnly) {
                    volumeOutput.commit(currentDiskNumber);
                } else {
                    volumeOutput.rollback();
                }
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
            try {
                volumeOutput.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
            if (failure == null && writeFailure != null) {
                failure = new IOException("ZIP split output was rolled back after an earlier write failure");
            }
            throwFailure(failure);
        }

        /// Marks this output transaction for rollback without replacing the higher-level failure.
        private void abort() {
            rollbackOnly = true;
        }

        /// Records a failed volume output operation.
        private void recordWriteFailure(Throwable exception) {
            writeFailure = appendFailure(writeFailure, exception);
        }

        /// Opens the next volume when the current one is full.
        private void ensureWritableDisk() throws IOException {
            if (currentDiskPosition < splitSize) {
                return;
            }
            openNextDisk();
        }

        /// Closes the current disk and opens the next physical output disk.
        private void openNextDisk() throws IOException {
            output.close();
            int nextDiskNumber = Math.addExact(currentDiskNumber, 1);
            WritableByteChannel nextChannel = Objects.requireNonNull(
                    volumeOutput.openVolume(nextDiskNumber),
                    "volume channel"
            );
            currentDiskNumber = nextDiskNumber;
            currentDiskPosition = 0;
            output = Channels.newOutputStream(nextChannel);
        }
    }

    /// Forwards writes without closing the wrapped stream.
    @NotNullByDefault
    private static final class NonClosingOutputStream extends OutputStream {
        /// The wrapped output stream.
        private final OutputStream output;

        /// Creates a non-closing output stream wrapper.
        private NonClosingOutputStream(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        /// Writes one byte to the wrapped stream.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        /// Writes bytes to the wrapped stream.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
        }

        /// Flushes the wrapped stream.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Does not close the wrapped stream.
        @Override
        public void close() {
        }
    }

    /// Writes a ZIP LZMA segment backed by a raw LZMA encoder.
    private static final class ZipLZMAOutputStream extends OutputStream {
        /// The raw LZMA output stream.
        private final OutputStream output;

        /// Creates a ZIP LZMA output stream and writes the ZIP LZMA property header.
        private ZipLZMAOutputStream(OutputStream output) throws IOException {
            OutputStream lzmaOutput = ZipCompressionFormats.openRawLZMAEncoder(
                    LZMA_DICTIONARY_SIZE,
                    true,
                    new NonClosingOutputStream(output)
            );
            try {
                writeLzmaPropertyHeader(output, 0x5d, LZMA_DICTIONARY_SIZE);
            } catch (IOException | RuntimeException | Error exception) {
                try {
                    lzmaOutput.close();
                } catch (IOException | RuntimeException | Error closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
            this.output = lzmaOutput;
        }

        /// Writes one byte to the LZMA encoder.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        /// Writes bytes to the LZMA encoder.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
        }

        /// Flushes the LZMA encoder.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Finishes the raw LZMA stream.
        private void finish() throws IOException {
            output.close();
        }

        /// Writes the ZIP LZMA property header.
        private static void writeLzmaPropertyHeader(
                OutputStream output,
                int properties,
                int dictionarySize
        ) throws IOException {
            writeShort(output, LZMA_SDK_MAJOR_VERSION | (LZMA_SDK_MINOR_VERSION << 8));
            writeShort(output, LZMA_PROPERTY_SIZE);
            output.write(properties & 0xff);
            writeInt(output, Integer.toUnsignedLong(dictionarySize));
        }
    }

    /// Implements ZIP entry file attributes visible during streaming output.
    @NotNullByDefault
    private static final class EntryAttributes implements ZipArkivoEntryAttributes {
        /// The normalized path key.
        private final String key;

        /// The raw encoded ZIP entry path bytes.
        private final byte[] rawPath;

        /// The decoded ZIP entry path text.
        private final String path;

        /// The decoded ZIP entry comment text, or `null` when no comment is present.
        private final @Nullable String comment;

        /// The compressed entry size.
        private final long compressedSize;

        /// The uncompressed entry size.
        private final long uncompressedSize;

        /// The CRC-32 value.
        private final long crc32;

        /// The general purpose bit flags.
        private final int generalPurposeFlags;

        /// The ZIP version made by field.
        private final int versionMadeBy;

        /// The ZIP version needed to extract field.
        private final int versionNeededToExtract;

        /// The ZIP internal file attributes.
        private final int internalAttributes;

        /// The ZIP external file attributes.
        private final long externalAttributes;

        /// The ZIP compression method field stored in the header.
        private final int method;

        /// The raw local file header extra data.
        private final byte[] localExtraData;

        /// The raw central directory extra data.
        private final byte[] centralDirectoryExtraData;

        /// The raw ZIP entry comment bytes, or `null` when no comment is present.
        private final byte @Nullable [] rawComment;

        /// The last modified time.
        private final FileTime lastModifiedTime;

        /// Whether this path is a directory.
        private final boolean directory;

        /// Creates immutable ZIP entry attributes.
        private EntryAttributes(
                String key,
                byte[] rawPath,
                String path,
                @Nullable String comment,
                long compressedSize,
                long uncompressedSize,
                long crc32,
                int generalPurposeFlags,
                int versionMadeBy,
                int versionNeededToExtract,
                int internalAttributes,
                long externalAttributes,
                int method,
                byte[] localExtraData,
                byte[] centralDirectoryExtraData,
                byte @Nullable [] rawComment,
                FileTime lastModifiedTime,
                boolean directory
        ) {
            this.key = Objects.requireNonNull(key, "key");
            this.rawPath = Objects.requireNonNull(rawPath, "rawPath").clone();
            this.path = Objects.requireNonNull(path, "path");
            this.comment = comment;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.crc32 = crc32;
            this.generalPurposeFlags = generalPurposeFlags;
            this.versionMadeBy = versionMadeBy;
            this.versionNeededToExtract = versionNeededToExtract;
            this.internalAttributes = internalAttributes;
            this.externalAttributes = externalAttributes;
            this.method = method;
            this.localExtraData = Objects.requireNonNull(localExtraData, "localExtraData").clone();
            this.centralDirectoryExtraData =
                    Objects.requireNonNull(centralDirectoryExtraData, "centralDirectoryExtraData").clone();
            this.rawComment = rawComment != null ? rawComment.clone() : null;
            this.lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
            this.directory = directory;
        }

        /// Returns attributes for a synthetic directory.
        private static EntryAttributes syntheticDirectory(String key) {
            return new EntryAttributes(
                    key,
                    new byte[0],
                    key,
                    null,
                    UNKNOWN_SIZE,
                    0L,
                    UNKNOWN_CRC32,
                    0,
                    0,
                    0,
                    0,
                    0L,
                    STORED_METHOD,
                    new byte[0],
                    new byte[0],
                    null,
                    FileTime.fromMillis(0),
                    true
            );
        }

        /// Returns attributes for a newly written central directory entry.
        private static EntryAttributes written(CentralEntry entry, ZipArkivoFileSystemConfig config) throws IOException {
            byte[] centralDirectoryExtraData = entry.centralDirectoryExtraData();
            byte @Nullable [] rawComment = entry.rawComment.length > 0 ? entry.rawComment : null;
            ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(config.legacyCharsetDetector());
            return new EntryAttributes(
                    entryNameKey(entry.entryName),
                    entry.rawName,
                    entry.entryName,
                    decoder.decodeComment(
                            rawComment,
                            entry.flags,
                            centralDirectoryExtraData,
                            ZipLegacyCharsetDetector.HeaderSource.CENTRAL_DIRECTORY,
                            entry.versionNeeded,
                            entry.versionMadeBy
                    ),
                    entry.compressedSize,
                    entry.uncompressedSize,
                    entry.crc32,
                    entry.flags,
                    entry.versionMadeBy,
                    entry.versionNeeded,
                    entry.internalAttributes,
                    entry.externalAttributes,
                    entry.method,
                    entry.localExtraData,
                    centralDirectoryExtraData,
                    rawComment,
                    fileTimeFromDos(entry.dosDate, entry.dosTime),
                    entry.directory()
            );
        }

        /// Returns attributes parsed from an append-mode central directory snapshot.
        private static EntryAttributes existing(
                ZipArkivoFileSystemImpl.CentralDirectoryEntrySnapshot snapshot,
                ZipArkivoFileSystemConfig config
        ) throws IOException {
            byte[] bytes = snapshot.bytes();
            if (bytes.length < ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE
                    || readInt(bytes, 0) != CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
                throw new IOException("Invalid ZIP central directory header");
            }

            int versionMadeBy = readUnsignedShort(bytes, 4);
            int versionNeeded = readUnsignedShort(bytes, 6);
            int flags = readUnsignedShort(bytes, 8);
            int method = readUnsignedShort(bytes, 10);
            int lastModifiedTime = readUnsignedShort(bytes, 12);
            int lastModifiedDate = readUnsignedShort(bytes, 14);
            long crc32 = Integer.toUnsignedLong(readInt(bytes, 16));
            long compressedSize = Integer.toUnsignedLong(readInt(bytes, 20));
            long uncompressedSize = Integer.toUnsignedLong(readInt(bytes, 24));
            int nameLength = readUnsignedShort(bytes, 28);
            int extraLength = readUnsignedShort(bytes, 30);
            int commentLength = readUnsignedShort(bytes, 32);
            int internalAttributes = readUnsignedShort(bytes, 36);
            long externalAttributes = Integer.toUnsignedLong(readInt(bytes, 38));
            long localHeaderOffset = Integer.toUnsignedLong(readInt(bytes, 42));
            int variableOffset = ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE;
            int nextOffset = variableOffset + nameLength + extraLength + commentLength;
            if (nextOffset > bytes.length) {
                throw new IOException("Invalid ZIP central directory variable data length");
            }

            byte[] rawPath = Arrays.copyOfRange(bytes, variableOffset, variableOffset + nameLength);
            byte[] extraData = Arrays.copyOfRange(
                    bytes,
                    variableOffset + nameLength,
                    variableOffset + nameLength + extraLength
            );
            ZipExtraFields.validate(extraData);
            byte[] rawCommentBytes = Arrays.copyOfRange(
                    bytes,
                    variableOffset + nameLength + extraLength,
                    nextOffset
            );
            Zip64Values zip64 = Zip64Values.read(extraData, uncompressedSize, compressedSize, localHeaderOffset);
            ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(config.legacyCharsetDetector());
            String decodedPath = decoder.decodePath(
                    rawPath,
                    flags,
                    extraData,
                    ZipLegacyCharsetDetector.HeaderSource.CENTRAL_DIRECTORY,
                    versionNeeded,
                    versionMadeBy
            );
            byte @Nullable [] rawComment = rawCommentBytes.length > 0 ? rawCommentBytes : null;
            return new EntryAttributes(
                    snapshot.entryName(),
                    rawPath,
                    decodedPath,
                    decoder.decodeComment(
                            rawComment,
                            flags,
                            extraData,
                            ZipLegacyCharsetDetector.HeaderSource.CENTRAL_DIRECTORY,
                            versionNeeded,
                            versionMadeBy
                    ),
                    zip64.compressedSize,
                    zip64.uncompressedSize,
                    crc32,
                    flags,
                    versionMadeBy,
                    versionNeeded,
                    internalAttributes,
                    externalAttributes,
                    method,
                    new byte[0],
                    extraData,
                    rawComment,
                    fileTimeFromDos(lastModifiedDate, lastModifiedTime),
                    decodedPath.endsWith("/")
            );
        }

        /// Returns a copy of the raw encoded ZIP entry path bytes.
        @Override
        public byte[] rawPath() {
            return rawPath.clone();
        }

        /// Returns the decoded ZIP entry path text.
        @Override
        public String path() {
            return path;
        }

        /// Returns the decoded ZIP entry comment text, or `null` when no comment is present.
        @Override
        public @Nullable String comment() {
            return comment;
        }

        /// Returns the compressed size stored in the ZIP metadata, or `UNKNOWN_SIZE` when it is not known.
        @Override
        public long compressedSize() {
            return directory ? UNKNOWN_SIZE : compressedSize;
        }

        /// Returns the CRC-32 value stored in the ZIP metadata, or `UNKNOWN_CRC32` when it is not known.
        @Override
        public long crc32() {
            return directory ? UNKNOWN_CRC32 : crc32;
        }

        /// Returns the general purpose bit flags stored for the ZIP entry.
        @Override
        public int generalPurposeFlags() {
            return generalPurposeFlags;
        }

        /// Returns the ZIP version made by field.
        @Override
        public int versionMadeBy() {
            return versionMadeBy;
        }

        /// Returns the ZIP version needed to extract field.
        @Override
        public int versionNeededToExtract() {
            return versionNeededToExtract;
        }

        /// Returns the ZIP internal file attributes.
        @Override
        public int internalAttributes() {
            return internalAttributes;
        }

        /// Returns the ZIP external file attributes.
        @Override
        public long externalAttributes() {
            return externalAttributes;
        }

        /// Returns the ZIP compression method.
        @Override
        public ZipMethod method() {
            return ZipMethod.of(ZipAesExtraField.compressionMethod(
                    generalPurposeFlags,
                    method,
                    centralDirectoryExtraData
            ));
        }

        /// Returns the ZIP encryption method.
        @Override
        public ZipEncryption encryption() {
            return ZipAesExtraField.encryption(generalPurposeFlags, method, centralDirectoryExtraData);
        }

        /// Returns a copy of the raw local file header extra data bytes.
        @Override
        public byte[] localExtraData() {
            return localExtraData.clone();
        }

        /// Returns a copy of the raw central directory extra data bytes.
        @Override
        public byte[] centralDirectoryExtraData() {
            return centralDirectoryExtraData.clone();
        }

        /// Returns a copy of the raw ZIP entry comment bytes, or `null` when no comment is present.
        @Override
        public byte @Nullable [] rawComment() {
            return rawComment != null ? rawComment.clone() : null;
        }

        /// Returns the last modified time.
        @Override
        public FileTime lastModifiedTime() {
            return lastModifiedTime;
        }

        /// Returns the last access time.
        @Override
        public FileTime lastAccessTime() {
            return lastModifiedTime;
        }

        /// Returns the creation time.
        @Override
        public FileTime creationTime() {
            return lastModifiedTime;
        }

        /// Returns whether this path is a regular file.
        @Override
        public boolean isRegularFile() {
            return !directory && !isSymbolicLink();
        }

        /// Returns whether this path is a directory.
        @Override
        public boolean isDirectory() {
            return directory;
        }

        /// Returns whether this path is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return !directory && ZipPosixSupport.isSymbolicLink(versionMadeBy, externalAttributes);
        }

        /// Returns whether this path is another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the uncompressed entry size.
        @Override
        public long size() {
            return directory ? 0L : uncompressedSize;
        }

        /// Returns an implementation-specific file key.
        @Override
        public Object fileKey() {
            return key;
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal owner() {
            return ZipPosixSupport.DEFAULT_OWNER;
        }

        /// Returns the synthesized group.
        @Override
        public GroupPrincipal group() {
            return ZipPosixSupport.DEFAULT_GROUP;
        }

        /// Returns synthesized POSIX permissions.
        @Override
        public @Unmodifiable Set<PosixFilePermission> permissions() {
            return ZipPosixSupport.permissions(versionMadeBy, externalAttributes, directory);
        }
    }

    /// Stores ZIP64 values decoded from an extended information extra field.
    ///
    /// @param uncompressedSize  the uncompressed size
    /// @param compressedSize    the compressed size
    /// @param localHeaderOffset the local file header offset
    private record Zip64Values(long uncompressedSize, long compressedSize, long localHeaderOffset) {
        /// Reads ZIP64 values from central directory extra data when required.
        private static Zip64Values read(
                byte[] extraData,
                long uncompressedSize,
                long compressedSize,
                long localHeaderOffset
        ) throws IOException {
            boolean needsUncompressedSize = uncompressedSize == UINT32_MAX;
            boolean needsCompressedSize = compressedSize == UINT32_MAX;
            boolean needsLocalHeaderOffset = localHeaderOffset == UINT32_MAX;
            if (!needsUncompressedSize && !needsCompressedSize && !needsLocalHeaderOffset) {
                return new Zip64Values(uncompressedSize, compressedSize, localHeaderOffset);
            }

            ZipExtraFields.Field field = ZipExtraFields.find(extraData, ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID);
            if (field == null) {
                throw new IOException("Required ZIP64 extended information extra field is missing");
            }

            ByteBuffer data = ByteBuffer.wrap(extraData, field.dataOffset(), field.dataSize())
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (needsUncompressedSize) {
                uncompressedSize = readZip64Long(data);
            }
            if (needsCompressedSize) {
                compressedSize = readZip64Long(data);
            }
            if (needsLocalHeaderOffset) {
                localHeaderOffset = readZip64Long(data);
            }
            return new Zip64Values(uncompressedSize, compressedSize, localHeaderOffset);
        }

        /// Reads one little-endian ZIP64 long value.
        private static long readZip64Long(ByteBuffer data) throws IOException {
            if (data.remaining() < Long.BYTES) {
                throw new IOException("Invalid ZIP64 extended information extra field");
            }
            long value = data.getLong();
            if (value < 0) {
                throw new IOException("ZIP64 extended information value is too large");
            }
            return value;
        }
    }

    /// Implements the synthesized POSIX entry attribute view.
    @NotNullByDefault
    private static final class PosixEntryAttributeView implements PosixFileAttributeView {
        /// The file system used to read attributes.
        private final StreamingZipArkivoFileSystemImpl fileSystem;

        /// The path whose attributes are exposed.
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates a synthesized POSIX attribute view.
        private PosixEntryAttributeView(
                StreamingZipArkivoFileSystemImpl fileSystem,
                ArkivoFileSystemProviderSupport.AttributeViewPath path
        ) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads synthesized POSIX attributes.
        @Override
        public PosixFileAttributes readAttributes() throws IOException {
            return fileSystem.readZipAttributes(path.resolve());
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return fileSystem.readZipAttributes(path.resolve()).owner();
        }

        /// Sets entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            fileSystem.setTimes(path.resolve(), lastModifiedTime, lastAccessTime, createTime);
        }

        /// Sets the file owner.
        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            fileSystem.setOwner(path.resolve(), owner);
        }

        /// Sets the file group.
        @Override
        public void setGroup(GroupPrincipal group) throws IOException {
            fileSystem.setGroup(path.resolve(), group);
        }

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) throws IOException {
            fileSystem.setPermissions(path.resolve(), permissions);
        }
    }

    /// Implements the synthesized owner entry attribute view.
    @NotNullByDefault
    private static final class OwnerEntryAttributeView implements FileOwnerAttributeView {
        /// The file system used to read attributes.
        private final StreamingZipArkivoFileSystemImpl fileSystem;

        /// The path whose owner is exposed.
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates a synthesized owner attribute view.
        private OwnerEntryAttributeView(
                StreamingZipArkivoFileSystemImpl fileSystem,
                ArkivoFileSystemProviderSupport.AttributeViewPath path
        ) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "owner";
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return fileSystem.readZipAttributes(path.resolve()).owner();
        }

        /// Sets the file owner.
        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            fileSystem.setOwner(path.resolve(), owner);
        }
    }

    /// Implements the ZIP entry attribute view.
    @NotNullByDefault
    private static final class EntryAttributeView implements ZipArkivoEntryAttributeView {
        /// The file system used to read attributes.
        private final StreamingZipArkivoFileSystemImpl fileSystem;

        /// The path whose attributes are exposed.
        private final ArkivoFileSystemProviderSupport.AttributeViewPath path;

        /// Creates an entry attribute view.
        private EntryAttributeView(
                StreamingZipArkivoFileSystemImpl fileSystem,
                ArkivoFileSystemProviderSupport.AttributeViewPath path
        ) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Reads the ZIP-specific entry attributes.
        @Override
        public ZipArkivoEntryAttributes readAttributes() throws IOException {
            return fileSystem.readZipAttributes(path.resolve());
        }

        /// Sets entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            fileSystem.setTimes(path.resolve(), lastModifiedTime, lastAccessTime, createTime);
        }

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) throws IOException {
            fileSystem.setPermissions(path.resolve(), permissions);
        }

        /// Sets the ZIP compression method requested for the entry.
        @Override
        public void setMethod(ZipMethod method) {
            Objects.requireNonNull(method, "method");
            throw new UnsupportedOperationException(
                    "Changing a completed ZIP entry method requires recompressing its body"
            );
        }

        /// Sets the ZIP encryption method requested for the entry.
        @Override
        public void setEncryption(ZipEncryption encryption) {
            Objects.requireNonNull(encryption, "encryption");
            throw new UnsupportedOperationException(
                    "Changing completed ZIP entry encryption requires rewriting its body"
            );
        }

        /// Sets the expected uncompressed size and CRC-32 value for entries that require them before writing.
        @Override
        public void setUncompressedSizeAndCrc32(long uncompressedSize, long crc32) {
            throw new UnsupportedOperationException(
                    "Completed ZIP entry sizes and checksums are derived from the encoded body"
            );
        }

        /// Sets the ZIP internal file attributes.
        @Override
        public void setInternalAttributes(int internalAttributes) throws IOException {
            fileSystem.setInternalAttributes(path.resolve(), internalAttributes);
        }

        /// Sets the ZIP external file attributes.
        @Override
        public void setExternalAttributes(long externalAttributes) throws IOException {
            fileSystem.setExternalAttributes(path.resolve(), externalAttributes);
        }

        /// Sets raw local file header extra data bytes.
        @Override
        public void setLocalExtraData(byte[] extraData) {
            Objects.requireNonNull(extraData, "extraData");
            throw new UnsupportedOperationException(
                    "Changing completed ZIP local extra data can invalidate encoded entry metadata"
            );
        }

        /// Sets raw central directory extra data bytes.
        @Override
        public void setCentralDirectoryExtraData(byte[] extraData) {
            Objects.requireNonNull(extraData, "extraData");
            throw new UnsupportedOperationException(
                    "Changing completed ZIP central extra data can invalidate generated ZIP metadata"
            );
        }

        /// Sets the raw ZIP entry comment bytes.
        @Override
        public void setRawComment(byte @Nullable [] rawComment) throws IOException {
            fileSystem.setRawComment(path.resolve(), rawComment);
        }
    }

    /// Stores write metadata for one streaming ZIP entry.
    @NotNullByDefault
    static final class EntryMetadata {
        /// The ZIP compression method.
        private final int method;

        /// The ZIP encryption method.
        private final ZipEncryption encryption;

        /// The DOS time field.
        private final int dosTime;

        /// The DOS date field.
        private final int dosDate;

        /// The ZIP version made by field.
        private final int versionMadeBy;

        /// The ZIP internal file attributes.
        private final int internalAttributes;

        /// The ZIP external file attributes.
        private final long externalAttributes;

        /// The expected uncompressed size, or `UNKNOWN_SIZE` when not configured.
        private final long expectedUncompressedSize;

        /// The expected CRC-32 value, or `UNKNOWN_CRC32` when not configured.
        private final long expectedCrc32;

        /// The raw local file header extra data.
        private final byte[] localExtraData;

        /// The raw central directory extra data.
        private final byte[] centralDirectoryExtraData;

        /// The raw ZIP entry comment bytes.
        private final byte[] rawComment;

        /// Creates write metadata.
        EntryMetadata(
                int method,
                ZipEncryption encryption,
                @Nullable FileTime lastModifiedTime,
                int versionMadeBy,
                int internalAttributes,
                long externalAttributes,
                long expectedUncompressedSize,
                long expectedCrc32,
                byte[] localExtraData,
                byte[] centralDirectoryExtraData,
                byte @Nullable [] rawComment
        ) {
            this(
                    method,
                    encryption,
                    dosTime(lastModifiedTime),
                    dosDate(lastModifiedTime),
                    versionMadeBy,
                    internalAttributes,
                    externalAttributes,
                    expectedUncompressedSize,
                    expectedCrc32,
                    localExtraData,
                    centralDirectoryExtraData,
                    rawComment
            );
        }

        /// Creates write metadata with precomputed DOS timestamp fields.
        private EntryMetadata(
                int method,
                ZipEncryption encryption,
                int dosTime,
                int dosDate,
                int versionMadeBy,
                int internalAttributes,
                long externalAttributes,
                long expectedUncompressedSize,
                long expectedCrc32,
                byte[] localExtraData,
                byte[] centralDirectoryExtraData,
                byte @Nullable [] rawComment
        ) {
            this.method = method;
            this.encryption = Objects.requireNonNull(encryption, "encryption");
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.versionMadeBy = versionMadeBy;
            this.internalAttributes = internalAttributes;
            this.externalAttributes = externalAttributes;
            this.expectedUncompressedSize = expectedUncompressedSize;
            this.expectedCrc32 = expectedCrc32;
            byte[] requestedLocalExtraData = Objects.requireNonNull(localExtraData, "localExtraData").clone();
            byte[] requestedCentralDirectoryExtraData =
                    Objects.requireNonNull(centralDirectoryExtraData, "centralDirectoryExtraData").clone();
            if (ZipAesExtraField.isAesEncryption(this.encryption)) {
                ZipAesExtraField aes = ZipAesExtraField.forEncryption(this.encryption, method);
                this.localExtraData = aes.appendTo(requestedLocalExtraData);
                this.centralDirectoryExtraData = aes.appendTo(requestedCentralDirectoryExtraData);
            } else {
                this.localExtraData = requestedLocalExtraData;
                this.centralDirectoryExtraData = requestedCentralDirectoryExtraData;
            }
            this.rawComment = rawComment != null ? rawComment.clone() : new byte[0];
        }

        /// Returns a copy with ZIP version and external attributes replaced.
        private EntryMetadata withExternalAttributes(int versionMadeBy, long externalAttributes) {
            return new EntryMetadata(
                    method,
                    encryption,
                    dosTime,
                    dosDate,
                    versionMadeBy,
                    internalAttributes,
                    externalAttributes,
                    expectedUncompressedSize,
                    expectedCrc32,
                    localExtraData,
                    centralDirectoryExtraData,
                    rawComment
            );
        }

        /// Returns a copy with the DOS last-modified timestamp replaced.
        private EntryMetadata withLastModifiedTime(FileTime lastModifiedTime) {
            Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
            return new EntryMetadata(
                    method,
                    encryption,
                    dosTime(lastModifiedTime),
                    dosDate(lastModifiedTime),
                    versionMadeBy,
                    internalAttributes,
                    externalAttributes,
                    expectedUncompressedSize,
                    expectedCrc32,
                    localExtraData,
                    centralDirectoryExtraData,
                    rawComment
            );
        }

        /// Returns default deflated file metadata.
        private static EntryMetadata deflated(ZipEncryption encryption) {
            return new EntryMetadata(
                    DEFLATED_METHOD,
                    encryption,
                    null,
                    VERSION_NEEDED,
                    0,
                    0,
                    ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                    ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                    new byte[0],
                    new byte[0],
                    null
            );
        }

        /// Returns default directory metadata.
        private static EntryMetadata directory() {
            return new EntryMetadata(
                    STORED_METHOD,
                    ZipEncryption.none(),
                    null,
                    VERSION_NEEDED,
                    0,
                    0x10L,
                    0,
                    0,
                    new byte[0],
                    new byte[0],
                    null
            );
        }

        /// Returns default symbolic link metadata.
        private static EntryMetadata symbolicLink(ZipEncryption encryption) {
            return new EntryMetadata(
                    STORED_METHOD,
                    encryption,
                    null,
                    ZipPosixSupport.UNIX_VERSION_MADE_BY,
                    0,
                    ZipPosixSupport.symbolicLinkExternalAttributes(null),
                    ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                    ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                    new byte[0],
                    new byte[0],
                    null
            );
        }

        /// Returns whether this entry requires a data descriptor.
        private boolean requiresDataDescriptor() {
            return method != STORED_METHOD
                    || expectedUncompressedSize == ZipArkivoEntryAttributes.UNKNOWN_SIZE
                    || expectedCrc32 == ZipArkivoEntryAttributes.UNKNOWN_CRC32;
        }

        /// Returns whether this entry requires a ZIP64 data descriptor.
        private boolean zip64DataDescriptor() {
            if (!requiresDataDescriptor() || expectedUncompressedSize == ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
                return false;
            }
            return expectedUncompressedSize > UINT32_MAX
                    || (method == STORED_METHOD && encryptedCompressedSize(expectedUncompressedSize) > UINT32_MAX);
        }

        /// Returns whether this entry's local header needs ZIP64 size metadata.
        private boolean zip64LocalHeaderNeeded() {
            if (requiresDataDescriptor()) {
                return zip64DataDescriptor();
            }
            return zip64LocalHeaderNeeded(encryptedCompressedSize(expectedUncompressedSize), expectedUncompressedSize);
        }

        /// Returns whether this entry's local header needs ZIP64 size metadata for the given sizes.
        private boolean zip64LocalHeaderNeeded(long compressedSize, long uncompressedSize) {
            return zip64Field(compressedSize) || zip64Field(uncompressedSize);
        }

        /// Returns whether this entry is encrypted.
        private boolean encrypted() {
            return !encryption.equals(ZipEncryption.none());
        }

        /// Returns whether this entry uses traditional ZIP encryption.
        private boolean traditionalEncrypted() {
            return encryption.equals(ZipEncryption.traditional());
        }

        /// Returns whether this entry uses WinZip AES encryption.
        private boolean aesEncrypted() {
            return ZipAesExtraField.isAesEncryption(encryption);
        }

        /// Returns the WinZip AES extra field for this entry.
        private ZipAesExtraField aesExtraField() {
            return ZipAesExtraField.forEncryption(encryption, method);
        }

        /// Returns the compression method identifier to store in ZIP headers.
        private int headerMethod() {
            return aesEncrypted() ? WINZIP_AES_METHOD : method;
        }

        /// Returns the ZIP version needed to extract field for this entry.
        private int versionNeeded(boolean zip64) {
            int version;
            if (method == LZMA_METHOD) {
                version = LZMA_VERSION_NEEDED;
            } else if (method == DEFLATE64_METHOD) {
                version = DEFLATE64_VERSION_NEEDED;
            } else {
                version = VERSION_NEEDED;
            }
            return zip64 ? Math.max(version, ZIP64_VERSION_NEEDED) : version;
        }

        /// Returns the ZIP general purpose flags for this entry.
        private int generalPurposeFlags() {
            int flags = UTF8_FLAG;
            if (method == LZMA_METHOD) {
                flags |= LZMA_EOS_MARKER_FLAG;
            }
            if (requiresDataDescriptor()) {
                flags |= DATA_DESCRIPTOR_FLAG;
            }
            if (encrypted()) {
                flags |= ENCRYPTED_FLAG;
            }
            return flags;
        }

        /// Returns the CRC-32 value to store in the local header.
        private long localHeaderCrc32() {
            return requiresDataDescriptor() ? 0 : expectedCrc32;
        }

        /// Returns the compressed size to store in the local header.
        private long localHeaderCompressedSize(boolean zip64) {
            if (requiresDataDescriptor()) {
                return zip64 ? UINT32_MAX : 0;
            }
            return zip32Field(encryptedCompressedSize(expectedUncompressedSize), zip64);
        }

        /// Returns the uncompressed size to store in the local header.
        private long localHeaderUncompressedSize(boolean zip64) {
            if (requiresDataDescriptor()) {
                return zip64 ? UINT32_MAX : 0;
            }
            return zip32Field(expectedUncompressedSize, zip64);
        }

        /// Returns the compressed size with the encryption header included when needed.
        private long encryptedCompressedSize(long compressedSize) {
            int overhead = 0;
            if (traditionalEncrypted()) {
                overhead = ZipTraditionalCrypto.HEADER_SIZE;
            } else if (aesEncrypted()) {
                overhead = aesExtraField().overheadSize();
            }
            try {
                return Math.addExact(compressedSize, overhead);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("compressed size is out of ZIP64 range", exception);
            }
        }

        /// Returns the traditional ZIP verification byte when CRC-32 is not known before entry data.
        private int encryptionVerificationByte() {
            return requiresDataDescriptor() ? dosTime >>> 8 : (int) (expectedCrc32 >>> 24);
        }

        /// Returns the traditional ZIP verification byte when CRC-32 is already known.
        private int encryptionVerificationByte(long crc32) {
            return (int) (crc32 >>> 24);
        }

        /// Validates that this metadata can be stored in ZIP records.
        private void validate() {
            if (method != STORED_METHOD
                    && method != DEFLATED_METHOD
                    && method != DEFLATE64_METHOD
                    && method != BZIP2_METHOD
                    && method != LZMA_METHOD
                    && method != XZ_METHOD
                    && !isZstandardMethod(method)) {
                throw new UnsupportedOperationException("Unsupported ZIP compression method: " + method);
            }
            if (!encryption.equals(ZipEncryption.none())
                    && !encryption.equals(ZipEncryption.traditional())
                    && !ZipAesExtraField.isAesEncryption(encryption)) {
                throw new UnsupportedOperationException("Unsupported ZIP encryption method: " + encryption);
            }
            requireUInt16(versionMadeBy, "version made by");
            requireUInt16(internalAttributes, "internal attributes");
            requireUInt32(externalAttributes, "external attributes");
            requireUInt16(localExtraData.length, "local extra data length");
            requireUInt16(centralDirectoryExtraData.length, "central directory extra data length");
            requireUInt16(rawComment.length, "comment length");
            if (expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
                requireUInt64(expectedUncompressedSize, "uncompressed size");
                requireUInt64(encryptedCompressedSize(expectedUncompressedSize), "compressed size");
            }
            if (expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32) {
                requireUInt32(expectedCrc32, "CRC-32");
            }
        }

        /// Returns the local header extra data bytes to write.
        private byte[] localHeaderExtraData(boolean zip64) throws IOException {
            return localHeaderExtraData(zip64, 0, 0);
        }

        /// Returns the local header extra data bytes to write for the given sizes.
        private byte[] localHeaderExtraData(boolean zip64, long compressedSize, long uncompressedSize) throws IOException {
            if (!zip64) {
                return localExtraData;
            }
            requireNoZip64ExtraField(localExtraData);
            return appendExtraData(
                    localExtraData,
                    zip64ExtendedInformationExtra(
                            true,
                            true,
                            false,
                            uncompressedSize,
                            compressedSize,
                            0
                    )
            );
        }
    }

    /// Stores the physical location of a ZIP64 end record.
    ///
    /// @param diskNumber the zero-based output disk number
    /// @param offset     the record offset relative to the output disk
    private record Zip64EndLocation(int diskNumber, long offset) {
        /// Validates a ZIP64 end record location.
        private Zip64EndLocation {
            if (diskNumber < 0 || offset < 0L) {
                throw new IllegalArgumentException("ZIP64 end record location must not be negative");
            }
        }
    }

    /// Stores either an unchanged local record header size or replacement header bytes.
    ///
    /// @param fixedHeader the replacement fixed header, or an empty array for an unchanged record
    /// @param rawName the replacement encoded name, or an empty array for an unchanged record
    /// @param localExtraData the replacement local extra data, or an empty array for an unchanged record
    /// @param finalHeaderSize the final local header size
    private record LocalRecordRewrite(
            byte @Unmodifiable [] fixedHeader,
            byte @Unmodifiable [] rawName,
            byte @Unmodifiable [] localExtraData,
            long finalHeaderSize
    ) {
        /// Creates a validated local record rewrite.
        private LocalRecordRewrite {
            fixedHeader = Objects.requireNonNull(fixedHeader, "fixedHeader").clone();
            rawName = Objects.requireNonNull(rawName, "rawName").clone();
            localExtraData = Objects.requireNonNull(localExtraData, "localExtraData").clone();
            if (finalHeaderSize < ZIP_LOCAL_FILE_HEADER_MIN_SIZE) {
                throw new IllegalArgumentException("finalHeaderSize is too small");
            }
        }

        /// Creates an unchanged local record description.
        private static LocalRecordRewrite unchanged(long headerSize) {
            return new LocalRecordRewrite(new byte[0], new byte[0], new byte[0], headerSize);
        }

        /// Creates a changed local record description.
        private LocalRecordRewrite(byte[] fixedHeader, byte[] rawName, byte[] localExtraData) {
            this(
                    fixedHeader,
                    rawName,
                    localExtraData,
                    ZIP_LOCAL_FILE_HEADER_MIN_SIZE + (long) rawName.length + localExtraData.length
            );
        }

        /// Returns whether this record needs a replacement header.
        private boolean changed() {
            return fixedHeader.length != 0;
        }

        /// Returns a copy of the replacement fixed header.
        @Override
        public byte @Unmodifiable [] fixedHeader() {
            return fixedHeader.clone();
        }

        /// Returns a copy of the replacement encoded name.
        @Override
        public byte @Unmodifiable [] rawName() {
            return rawName.clone();
        }

        /// Returns a copy of the replacement local extra data.
        @Override
        public byte @Unmodifiable [] localExtraData() {
            return localExtraData.clone();
        }
    }

    /// Tracks one immutable source entry under its current update-session name.
    ///
    /// @param source the immutable central directory and local record source
    /// @param entryName the current normalized entry name
    /// @param rawName the current encoded central and local header name
    /// @param centralDirectoryBytes the current central directory entry bytes
    /// @param localTimestampChanged whether the local header timestamp differs from the source
    private record ExistingEntry(
            ZipArkivoFileSystemImpl.CentralDirectoryEntrySnapshot source,
            String entryName,
            byte @Unmodifiable [] rawName,
            byte @Unmodifiable [] centralDirectoryBytes,
            boolean localTimestampChanged
    ) {
        /// Creates a validated existing entry state.
        private ExistingEntry {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(entryName, "entryName");
            rawName = Objects.requireNonNull(rawName, "rawName").clone();
            centralDirectoryBytes = Objects.requireNonNull(
                    centralDirectoryBytes,
                    "centralDirectoryBytes"
            ).clone();
        }

        /// Creates an unchanged state from one source snapshot.
        private static ExistingEntry original(
                ZipArkivoFileSystemImpl.CentralDirectoryEntrySnapshot source
        ) {
            byte[] centralDirectoryBytes = source.bytes();
            int nameLength = readUnsignedShort(centralDirectoryBytes, 28);
            byte[] rawName = Arrays.copyOfRange(
                    centralDirectoryBytes,
                    ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE,
                    ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE + nameLength
            );
            return new ExistingEntry(source, source.entryName(), rawName, centralDirectoryBytes, false);
        }

        /// Returns a state with its current name replaced.
        private ExistingEntry renamed(String newEntryName) throws IOException {
            String storedName = directoryEntry() ? newEntryName + "/" : newEntryName;
            byte[] renamedRawName = rawEntryName(storedName);
            return new ExistingEntry(
                    source,
                    newEntryName,
                    renamedRawName,
                    renameCentralDirectoryEntry(centralDirectoryBytes, renamedRawName),
                    localTimestampChanged
            );
        }

        /// Returns a state with a new last-modified timestamp.
        private ExistingEntry withLastModifiedTime(FileTime lastModifiedTime) {
            byte[] updated = centralDirectoryBytes.clone();
            ByteBuffer header = ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN);
            header.putShort(12, (short) StreamingZipArkivoFileSystemImpl.dosTime(lastModifiedTime));
            header.putShort(14, (short) StreamingZipArkivoFileSystemImpl.dosDate(lastModifiedTime));
            return new ExistingEntry(source, entryName, rawName, updated, true);
        }

        /// Returns a state with new ZIP internal file attributes.
        private ExistingEntry withInternalAttributes(int internalAttributes) {
            requireUInt16(internalAttributes, "internal attributes");
            byte[] updated = centralDirectoryBytes.clone();
            ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN).putShort(36, (short) internalAttributes);
            return new ExistingEntry(source, entryName, rawName, updated, localTimestampChanged);
        }

        /// Returns a state with new ZIP external file attributes and version-made-by value.
        private ExistingEntry withExternalAttributes(int versionMadeBy, long externalAttributes) {
            requireUInt16(versionMadeBy, "version made by");
            requireUInt32(externalAttributes, "external attributes");
            byte[] updated = centralDirectoryBytes.clone();
            ByteBuffer header = ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN);
            header.putShort(4, (short) versionMadeBy);
            header.putInt(38, (int) externalAttributes);
            return new ExistingEntry(source, entryName, rawName, updated, localTimestampChanged);
        }

        /// Returns a state with new raw entry comment bytes.
        private ExistingEntry withRawComment(byte @Nullable [] rawComment) {
            byte[] comment = rawComment != null ? rawComment.clone() : new byte[0];
            requireUInt16(comment.length, "comment length");
            int nameLength = readUnsignedShort(centralDirectoryBytes, 28);
            int extraLength = readUnsignedShort(centralDirectoryBytes, 30);
            int commentOffset = ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE + nameLength + extraLength;
            byte[] updated = Arrays.copyOf(centralDirectoryBytes, commentOffset + comment.length);
            System.arraycopy(comment, 0, updated, commentOffset, comment.length);
            ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN).putShort(32, (short) comment.length);
            return new ExistingEntry(source, entryName, rawName, updated, localTimestampChanged);
        }

        /// Returns the current central-directory DOS time field.
        private int dosTime() {
            return readUnsignedShort(centralDirectoryBytes, 12);
        }

        /// Returns the current central-directory DOS date field.
        private int dosDate() {
            return readUnsignedShort(centralDirectoryBytes, 14);
        }

        /// Returns the current ZIP version-made-by field.
        private int versionMadeBy() {
            return readUnsignedShort(centralDirectoryBytes, 4);
        }

        /// Returns whether the source local record must receive a replacement header.
        private boolean renamed() {
            return !entryName.equals(source.entryName());
        }

        /// Returns whether this is an explicit directory entry.
        private boolean directoryEntry() {
            return rawName.length > 0 && rawName[rawName.length - 1] == '/';
        }

        /// Returns a snapshot containing the current central directory metadata for attribute parsing.
        private ZipArkivoFileSystemImpl.CentralDirectoryEntrySnapshot attributesSnapshot() {
            return new ZipArkivoFileSystemImpl.CentralDirectoryEntrySnapshot(
                    entryName,
                    centralDirectoryBytes,
                    source.localHeaderOffset(),
                    source.localHeaderSize(),
                    source.localRecordSize()
            );
        }

        /// Returns a copy of the current encoded name.
        @Override
        public byte @Unmodifiable [] rawName() {
            return rawName.clone();
        }

        /// Returns a copy of the current central directory entry bytes.
        @Override
        public byte @Unmodifiable [] centralDirectoryBytes() {
            return centralDirectoryBytes.clone();
        }
    }

    /// Describes a relocated local header location.
    ///
    /// @param diskNumber the zero-based output disk number
    /// @param offset the local header offset relative to the output disk
    @NotNullByDefault
    private record RelocatedLocalHeader(int diskNumber, long offset) {
        /// Creates a relocated local header location.
        private RelocatedLocalHeader {
            if (diskNumber < 0) {
                throw new IllegalArgumentException("diskNumber must not be negative");
            }
            if (offset < 0L) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }

    /// Stores central directory metadata for one entry.
    @NotNullByDefault
    private static final class CentralEntry {
        /// The decoded entry name.
        private final String entryName;

        /// The decoded entry name serialized in the staged local record.
        private final String serializedEntryName;

        /// The raw entry name bytes.
        private final byte[] rawName;

        /// The general purpose bit flags.
        private final int flags;

        /// The ZIP compression method.
        private final int method;

        /// The DOS time field.
        private final int dosTime;

        /// The DOS date field.
        private final int dosDate;

        /// The CRC-32 value.
        private final long crc32;

        /// The compressed entry size.
        private final long compressedSize;

        /// The uncompressed entry size.
        private final long uncompressedSize;

        /// The disk where the local header starts.
        private final int localHeaderDiskNumber;

        /// The local header offset relative to its starting disk.
        private final long localHeaderOffset;

        /// The exact size of the local header before compressed data.
        private final long localHeaderSize;

        /// The exact size of the local header, compressed data, and optional descriptor.
        private final long localRecordSize;

        /// The local header disk number in a rewritten archive, or `-1` before relocation.
        private int relocatedLocalHeaderDiskNumber = -1;

        /// The local header offset in a rewritten archive, or `-1` before relocation.
        private long relocatedLocalHeaderOffset = -1L;

        /// The ZIP version needed to extract field.
        private final int versionNeeded;

        /// The ZIP version made by field.
        private final int versionMadeBy;

        /// The ZIP internal file attributes.
        private final int internalAttributes;

        /// The ZIP external file attributes.
        private final long externalAttributes;

        /// The raw local file header extra data.
        private final byte[] localExtraData;

        /// The raw central directory extra data.
        private final byte[] centralDirectoryExtraData;

        /// The raw ZIP entry comment bytes.
        private final byte[] rawComment;

        /// Whether the staged local header timestamp must be replaced.
        private final boolean localTimestampChanged;

        /// Creates central directory metadata.
        private CentralEntry(
                String entryName,
                byte[] rawName,
                int flags,
                int method,
                int dosTime,
                int dosDate,
                long crc32,
                long compressedSize,
                long uncompressedSize,
                int localHeaderDiskNumber,
                long localHeaderOffset,
                long localHeaderSize,
                long localRecordSize,
                EntryMetadata metadata
        ) {
            this.entryName = Objects.requireNonNull(entryName, "entryName");
            this.serializedEntryName = entryName;
            this.rawName = Objects.requireNonNull(rawName, "rawName");
            this.flags = flags;
            this.method = method;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc32 = crc32;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localHeaderDiskNumber = localHeaderDiskNumber;
            this.localHeaderOffset = localHeaderOffset;
            this.localHeaderSize = localHeaderSize;
            this.localRecordSize = localRecordSize;
            this.versionNeeded = metadata.versionNeeded(zip64Needed());
            this.versionMadeBy = metadata.versionMadeBy;
            this.internalAttributes = metadata.internalAttributes;
            this.externalAttributes = metadata.externalAttributes;
            this.localExtraData = metadata.localExtraData.clone();
            this.centralDirectoryExtraData = metadata.centralDirectoryExtraData.clone();
            this.rawComment = metadata.rawComment.clone();
            this.localTimestampChanged = false;
        }

        /// Creates changed central metadata over the same staged local record.
        private CentralEntry(
                CentralEntry source,
                String entryName,
                byte[] rawName,
                int flags,
                int dosTime,
                int dosDate,
                int versionMadeBy,
                int internalAttributes,
                long externalAttributes,
                byte[] localExtraData,
                byte[] centralDirectoryExtraData,
                byte[] rawComment,
                boolean localTimestampChanged
        ) {
            this.entryName = Objects.requireNonNull(entryName, "entryName");
            this.serializedEntryName = source.serializedEntryName;
            this.rawName = Objects.requireNonNull(rawName, "rawName");
            this.flags = flags;
            this.method = source.method;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc32 = source.crc32;
            this.compressedSize = source.compressedSize;
            this.uncompressedSize = source.uncompressedSize;
            this.localHeaderDiskNumber = source.localHeaderDiskNumber;
            this.localHeaderOffset = source.localHeaderOffset;
            this.localHeaderSize = source.localHeaderSize;
            this.localRecordSize = source.localRecordSize;
            this.relocatedLocalHeaderDiskNumber = source.relocatedLocalHeaderDiskNumber;
            this.relocatedLocalHeaderOffset = source.relocatedLocalHeaderOffset;
            this.versionNeeded = source.versionNeeded;
            this.versionMadeBy = versionMadeBy;
            this.internalAttributes = internalAttributes;
            this.externalAttributes = externalAttributes;
            this.localExtraData = Objects.requireNonNull(localExtraData, "localExtraData").clone();
            this.centralDirectoryExtraData = Objects.requireNonNull(
                    centralDirectoryExtraData,
                    "centralDirectoryExtraData"
            ).clone();
            this.rawComment = Objects.requireNonNull(rawComment, "rawComment").clone();
            this.localTimestampChanged = source.localTimestampChanged || localTimestampChanged;
        }

        /// Returns central metadata with a new normalized entry name.
        private CentralEntry renamed(String newEntryName) throws IOException {
            String storedName = directory() ? newEntryName + "/" : newEntryName;
            byte[] renamedRawName = rawEntryName(storedName);
            return new CentralEntry(
                    this,
                    storedName,
                    renamedRawName,
                    flags | UTF8_FLAG,
                    dosTime,
                    dosDate,
                    versionMadeBy,
                    internalAttributes,
                    externalAttributes,
                    removeUnicodePathExtraField(localExtraData),
                    removeUnicodePathExtraField(centralDirectoryExtraData),
                    rawComment,
                    false
            );
        }

        /// Returns central metadata with a new last-modified timestamp.
        private CentralEntry withLastModifiedTime(FileTime lastModifiedTime) {
            return new CentralEntry(
                    this,
                    entryName,
                    rawName,
                    flags,
                    StreamingZipArkivoFileSystemImpl.dosTime(lastModifiedTime),
                    StreamingZipArkivoFileSystemImpl.dosDate(lastModifiedTime),
                    versionMadeBy,
                    internalAttributes,
                    externalAttributes,
                    localExtraData,
                    centralDirectoryExtraData,
                    rawComment,
                    true
            );
        }

        /// Returns central metadata with new ZIP internal file attributes.
        private CentralEntry withInternalAttributes(int newInternalAttributes) {
            requireUInt16(newInternalAttributes, "internal attributes");
            return new CentralEntry(
                    this,
                    entryName,
                    rawName,
                    flags,
                    dosTime,
                    dosDate,
                    versionMadeBy,
                    newInternalAttributes,
                    externalAttributes,
                    localExtraData,
                    centralDirectoryExtraData,
                    rawComment,
                    false
            );
        }

        /// Returns central metadata with new ZIP external file attributes and version-made-by value.
        private CentralEntry withExternalAttributes(int newVersionMadeBy, long newExternalAttributes) {
            requireUInt16(newVersionMadeBy, "version made by");
            requireUInt32(newExternalAttributes, "external attributes");
            return new CentralEntry(
                    this,
                    entryName,
                    rawName,
                    flags,
                    dosTime,
                    dosDate,
                    newVersionMadeBy,
                    internalAttributes,
                    newExternalAttributes,
                    localExtraData,
                    centralDirectoryExtraData,
                    rawComment,
                    false
            );
        }

        /// Returns central metadata with new raw entry comment bytes.
        private CentralEntry withRawComment(byte @Nullable [] newRawComment) {
            byte[] comment = newRawComment != null ? newRawComment.clone() : new byte[0];
            requireUInt16(comment.length, "comment length");
            return new CentralEntry(
                    this,
                    entryName,
                    rawName,
                    flags,
                    dosTime,
                    dosDate,
                    versionMadeBy,
                    internalAttributes,
                    externalAttributes,
                    localExtraData,
                    centralDirectoryExtraData,
                    comment,
                    false
            );
        }

        /// Returns whether this entry needs a replacement staged local header.
        private boolean renamed() {
            return !entryName.equals(serializedEntryName);
        }

        /// Returns whether the central directory entry needs ZIP64 extra metadata.
        private boolean zip64Needed() {
            return zip64UncompressedSize()
                    || zip64CompressedSize()
                    || zip64LocalHeaderOffset()
                    || zip64LocalHeaderDiskNumber();
        }

        /// Returns whether the uncompressed size needs ZIP64 metadata.
        private boolean zip64UncompressedSize() {
            return zip64Field(uncompressedSize);
        }

        /// Returns whether the compressed size needs ZIP64 metadata.
        private boolean zip64CompressedSize() {
            return zip64Field(compressedSize);
        }

        /// Returns whether the local header offset needs ZIP64 metadata.
        private boolean zip64LocalHeaderOffset() {
            return zip64Field(finalLocalHeaderOffset());
        }

        /// Returns whether the local header disk number needs ZIP64 metadata.
        private boolean zip64LocalHeaderDiskNumber() {
            return Integer.toUnsignedLong(finalLocalHeaderDiskNumber()) > UINT16_MAX;
        }

        /// Returns the local header disk number to store in the final central directory.
        private int finalLocalHeaderDiskNumber() {
            return relocatedLocalHeaderDiskNumber >= 0
                    ? relocatedLocalHeaderDiskNumber
                    : localHeaderDiskNumber;
        }

        /// Returns the local header offset to store in the final central directory.
        private long finalLocalHeaderOffset() {
            return relocatedLocalHeaderOffset >= 0 ? relocatedLocalHeaderOffset : localHeaderOffset;
        }

        /// Returns the extraction version required by the final relocated metadata.
        private int finalVersionNeeded() {
            return zip64Needed() ? Math.max(versionNeeded, ZIP64_VERSION_NEEDED) : versionNeeded;
        }

        /// Returns whether this entry is a directory entry.
        private boolean directory() {
            return entryName.endsWith("/");
        }

        /// Returns central directory extra data including generated ZIP64 metadata when needed.
        private byte[] centralDirectoryExtraData() throws IOException {
            if (!zip64Needed()) {
                return centralDirectoryExtraData;
            }
            requireNoZip64ExtraField(centralDirectoryExtraData);
            return appendExtraData(
                    centralDirectoryExtraData,
                    zip64ExtendedInformationExtra(
                            zip64UncompressedSize(),
                            zip64CompressedSize(),
                            zip64LocalHeaderOffset(),
                            zip64LocalHeaderDiskNumber(),
                            uncompressedSize,
                            compressedSize,
                            finalLocalHeaderOffset(),
                            finalLocalHeaderDiskNumber()
                    )
            );
        }
    }

}

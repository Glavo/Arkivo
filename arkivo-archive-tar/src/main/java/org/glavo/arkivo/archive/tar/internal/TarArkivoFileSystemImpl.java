// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.archive.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributeView;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystemProvider;
import org.glavo.arkivo.archive.tar.TarArkivoFormat;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

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
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
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
import java.nio.file.StandardCopyOption;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.glavo.arkivo.archive.tar.internal.TarCompressionStreams.openArchiveInput;
import static org.glavo.arkivo.archive.tar.internal.TarCompressionStreams.openArchiveOutput;
import static org.glavo.arkivo.archive.tar.internal.TarCompressionStreams.requireCompression;
import static org.glavo.arkivo.archive.tar.internal.TarCompressionStreams.requireDecompression;

/// Implements a TAR archive file system backed by an in-memory index or a forward-only writer.
@NotNullByDefault
public final class TarArkivoFileSystemImpl extends TarArkivoFileSystem {
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS = Set.of("basic", "owner", "posix", "tar");

    /// The Unix epoch timestamp used for new update-mode entries.
    private static final FileTime UNIX_EPOCH = FileTime.fromMillis(0L);

    /// The sentinel used when the source archive size is path-backed or unknown.
    private static final long UNKNOWN_ARCHIVE_SIZE = -1L;

    /// The file system provider that owns this file system.
    private final TarArkivoFileSystemProvider provider;

    /// The source archive path.
    private final @Nullable Path archivePath;

    /// The archive URI used by generated entry URIs.
    private final @Nullable URI archiveUri;

    /// The owned channel source, or `null` for path-backed file systems.
    private final @Nullable ArkivoSeekableChannelSource channelSource;

    /// The captured channel-source size, or `UNKNOWN_ARCHIVE_SIZE` for path-backed file systems.
    private final long sourceArchiveSize;

    /// The action invoked when this file system closes.
    private final Runnable closeAction;

    /// The synthetic root path.
    private final TarArkivoPath rootPath;

    /// The file store view for this archive.
    private final TarFileStore fileStore = new TarFileStore();

    /// Entry nodes by normalized archive path.
    private final Map<String, Node> nodes;

    /// The storage that owns indexed entry bodies, or `null` in forward-only write mode.
    private final @Nullable ArkivoEditStorage editStorage;

    /// Indexed entry bodies owned by this file system, tracked by identity for shared hard-link content.
    private final Set<ArkivoStoredContent> ownedContents;

    /// The streaming writer used by forward-only write mode, or `null` in read mode.
    private final @Nullable TarArkivoStreamingWriter writer;

    /// The target used to publish a rewritten update-mode archive, or `null` outside update mode.
    private final @Nullable ArkivoCommitTarget commitTarget;

    /// The compression codec wrapping the TAR stream, or `null` for an uncompressed archive.
    private final @Nullable CompressionCodec compressionCodec;

    /// Whether this file system is read-only.
    private final boolean readOnly;

    /// Whether this file system edits an existing archive through complete rewrite.
    private final boolean updateMode;

    /// The active update-mode entry channel, or `null` when no entry is being changed.
    private @Nullable UpdateEntryByteChannel activeUpdateChannel;

    /// Whether update mode has changed the indexed archive.
    private boolean dirty;

    /// Archive paths already emitted by forward-only write mode.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// Directory paths already emitted or implied by forward-only write mode.
    private final HashSet<String> writtenDirectories = new HashSet<>();

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Whether the indexed content storage has been closed.
    private boolean editStorageClosed;

    /// Whether the provider close action has completed.
    private boolean closeActionCompleted;

    /// Whether the owned channel source has been closed.
    private boolean channelSourceClosed;

    /// Creates a TAR file system instance.
    private TarArkivoFileSystemImpl(
            TarArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable URI archiveUri,
            @Nullable ArkivoSeekableChannelSource channelSource,
            long sourceArchiveSize,
            ArkivoFileSystemThreadSafety threadSafety,
            Map<String, Node> nodes,
            @Nullable ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            @Nullable TarArkivoStreamingWriter writer,
            boolean readOnly,
            boolean updateMode,
            @Nullable ArkivoCommitTarget commitTarget,
            @Nullable CompressionCodec compressionCodec,
            Runnable closeAction
    ) {
        super(threadSafety);
        if ((archivePath == null) != (archiveUri == null)) {
            throw new IllegalArgumentException("archivePath and archiveUri must both be present or absent");
        }
        if ((archivePath == null) == (channelSource == null)) {
            throw new IllegalArgumentException("exactly one archive source must be provided");
        }
        if (sourceArchiveSize < UNKNOWN_ARCHIVE_SIZE) {
            throw new IllegalArgumentException("sourceArchiveSize must be unknown or non-negative");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = archivePath;
        this.archiveUri = archiveUri;
        this.channelSource = channelSource;
        this.sourceArchiveSize = sourceArchiveSize;
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.nodes = updateMode ? new LinkedHashMap<>(nodes) : Map.copyOf(nodes);
        this.editStorage = editStorage;
        this.ownedContents = ownedContents;
        this.writer = writer;
        this.readOnly = readOnly;
        this.updateMode = updateMode;
        this.commitTarget = commitTarget;
        this.compressionCodec = compressionCodec;
        this.rootPath = TarArkivoPath.root(this);
        this.writtenDirectories.add("");
        this.editStorageClosed = editStorage == null;
        this.channelSourceClosed = channelSource == null;
    }

    /// Opens a TAR file system from an archive path.
    public static TarArkivoFileSystemImpl open(
            TarArkivoFileSystemProvider provider,
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
        @Nullable CompressionCodec requestedCompression = TarArkivoFileSystem.COMPRESSION.read(environment);
        if (isArchiveUpdateOpen(openOptions)) {
            validateArchiveUpdateOptions(openOptions);
            if (ArkivoFileSystem.SOURCE_MUTATION_POLICY.isPresent(environment)) {
                throw new UnsupportedOperationException("TAR update mode always performs a complete archive rewrite");
            }
            ArkivoEditStorage editStorage = editStorage(environment);
            Set<ArkivoStoredContent> ownedContents = identityContentSet();
            try {
                Map<String, Node> nodes;
                boolean newArchive = !Files.exists(archivePath);
                @Nullable CompressionCodec compressionCodec = requestedCompression;
                if (!newArchive) {
                    if (compressionCodec == null) {
                        compressionCodec = detectCompression(archivePath);
                    }
                    requireDecompression(compressionCodec);
                    try (InputStream input = openArchiveInput(
                            Files.newInputStream(archivePath, StandardOpenOption.READ),
                            compressionCodec
                    )) {
                        nodes = readNodes(input, editStorage, ownedContents, environment);
                    }
                } else if (openOptions.contains(StandardOpenOption.CREATE)) {
                    nodes = rootNodes();
                } else {
                    throw new NoSuchFileException(archivePath.toString());
                }
                requireCompression(compressionCodec);
                @Nullable ArkivoCommitTarget commitTarget = ArkivoFileSystem.COMMIT_TARGET.read(environment);
                if (commitTarget == null) {
                    commitTarget = ArkivoCommitTarget.atomicReplace(defaultCommitDirectory(archivePath));
                }
                TarArkivoFileSystemImpl fileSystem = new TarArkivoFileSystemImpl(
                        provider,
                        archivePath,
                        archiveUri,
                        null,
                        UNKNOWN_ARCHIVE_SIZE,
                        threadSafety,
                        nodes,
                        editStorage,
                        ownedContents,
                        null,
                        false,
                        true,
                        commitTarget,
                        compressionCodec,
                        closeAction
                );
                fileSystem.dirty = newArchive;
                return fileSystem;
            } catch (IOException | RuntimeException | Error exception) {
                closeOpenedStorage(editStorage, ownedContents, exception);
                throw exception;
            }
        }
        if (isWriteArchiveOpen(openOptions)) {
            validateArchiveWriteOptions(openOptions);
            if (ArkivoFileSystem.COMMIT_TARGET.read(environment) != null) {
                throw new UnsupportedOperationException("TAR archive writes do not support commit targets");
            }
            requireCompression(requestedCompression);
            OutputStream output = openArchiveOutput(
                    Files.newOutputStream(archivePath, openOptions.toArray(OpenOption[]::new)),
                    requestedCompression
            );
            return new TarArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    null,
                    UNKNOWN_ARCHIVE_SIZE,
                    threadSafety,
                    rootNodes(),
                    null,
                    identityContentSet(),
                    TarArkivoStreamingWriter.open(output),
                    false,
                    false,
                    null,
                    requestedCompression,
                    closeAction
            );
        }

        validateArchiveReadOptions(openOptions);
        ArkivoEditStorage editStorage = editStorage(environment);
        Set<ArkivoStoredContent> ownedContents = identityContentSet();
        try {
            @Nullable CompressionCodec compressionCodec = requestedCompression;
            if (compressionCodec == null) {
                compressionCodec = detectCompression(archivePath);
            }
            requireDecompression(compressionCodec);
            Map<String, Node> nodes;
            try (InputStream input = openArchiveInput(
                    Files.newInputStream(archivePath, openOptions.toArray(OpenOption[]::new)),
                    compressionCodec
            )) {
                nodes = readNodes(input, editStorage, ownedContents, environment);
            }
            return new TarArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    null,
                    UNKNOWN_ARCHIVE_SIZE,
                    threadSafety,
                    nodes,
                    editStorage,
                    ownedContents,
                    null,
                    true,
                    false,
                    null,
                    compressionCodec,
                    closeAction
            );
        } catch (IOException | RuntimeException | Error exception) {
            closeOpenedStorage(editStorage, ownedContents, exception);
            throw exception;
        }
    }

    /// Opens a TAR file system from a repeatable seekable channel source.
    public static TarArkivoFileSystemImpl open(
            TarArkivoFileSystemProvider provider,
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");

        ArkivoFileSystemThreadSafety threadSafety;
        ArkivoEditStorage editStorage;
        @Nullable CompressionCodec requestedCompression;
        boolean updateMode;
        @Nullable ArkivoCommitTarget commitTarget;
        try {
            threadSafety = ArkivoFileSystem.THREAD_SAFETY.readOrDefault(
                    environment,
                    ArkivoFileSystemThreadSafety.CONCURRENT_READ
            );
            Set<OpenOption> openOptions = ArkivoFileSystem.OPEN_OPTIONS.readOrDefault(
                    environment,
                    Set.of(StandardOpenOption.READ)
            );
            updateMode = isArchiveUpdateOpen(openOptions);
            if (updateMode) {
                validateArchiveUpdateOptions(openOptions);
                if (ArkivoFileSystem.SOURCE_MUTATION_POLICY.isPresent(environment)) {
                    throw new UnsupportedOperationException("TAR update mode always performs a complete archive rewrite");
                }
                commitTarget = ArkivoFileSystem.COMMIT_TARGET.read(environment);
                if (commitTarget == null) {
                    throw new IllegalArgumentException(
                            "TAR channel-source update mode requires ArkivoFileSystem.COMMIT_TARGET"
                    );
                }
            } else {
                validateArchiveReadOptions(openOptions);
                commitTarget = null;
            }
            requestedCompression = TarArkivoFileSystem.COMPRESSION.read(environment);
            editStorage = editStorage(environment);
        } catch (RuntimeException | Error exception) {
            closeSourceAfterOpenFailure(source, exception);
            throw exception;
        }

        Set<ArkivoStoredContent> ownedContents = identityContentSet();
        try {
            @Nullable CompressionCodec compressionCodec = requestedCompression;
            if (compressionCodec == null) {
                try (SeekableByteChannel probeChannel = source.openChannel()) {
                    compressionCodec = detectCompression(probeChannel);
                }
            }
            requireDecompression(compressionCodec);
            if (updateMode) {
                requireCompression(compressionCodec);
            }
            long archiveSize;
            Map<String, Node> nodes;
            try (SeekableByteChannel channel = source.openChannel()) {
                archiveSize = channel.size();
                channel.position(0L);
                try (InputStream input = openArchiveInput(Channels.newInputStream(channel), compressionCodec)) {
                    nodes = readNodes(input, editStorage, ownedContents, environment);
                }
            }
            return new TarArkivoFileSystemImpl(
                    provider,
                    null,
                    null,
                    source,
                    archiveSize,
                    threadSafety,
                    nodes,
                    editStorage,
                    ownedContents,
                    null,
                    !updateMode,
                    updateMode,
                    commitTarget,
                    compressionCodec,
                    () -> {
                    }
            );
        } catch (IOException | RuntimeException | Error exception) {
            closeOpenedStorage(editStorage, ownedContents, exception);
            closeSourceAfterOpenFailure(source, exception);
            throw exception;
        }
    }

    /// Returns the provider that owns this file system.
    @Override
    public TarArkivoFileSystemProvider provider() {
        return provider;
    }

    /// Closes this file system.
    @Override
    public void close() throws IOException {
        try (CloseOperation ignored = beginCloseOperation()) {
            if (open
                    || !ownedContents.isEmpty()
                    || !editStorageClosed
                    || !channelSourceClosed
                    || !closeActionCompleted) {
                @Nullable Throwable failure = null;
                if (open) {
                    @Nullable UpdateEntryByteChannel updateChannel = activeUpdateChannel;
                    if (updateChannel != null) {
                        try {
                            updateChannel.close();
                        } catch (IOException | RuntimeException | Error exception) {
                            failure = exception;
                        }
                    }
                    @Nullable TarArkivoStreamingWriter currentWriter = writer;
                    if (failure == null && currentWriter != null) {
                        try {
                            currentWriter.close();
                        } catch (IOException | RuntimeException | Error exception) {
                            failure = exception;
                        }
                    }
                    if (failure == null && updateMode && dirty) {
                        try {
                            commitUpdate();
                        } catch (IOException | RuntimeException | Error exception) {
                            failure = exception;
                        }
                    }
                    open = false;
                }
                failure = closeIndexedStorage(failure);
                if (!channelSourceClosed) {
                    try {
                        Objects.requireNonNull(channelSource, "channelSource").close();
                        channelSourceClosed = true;
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
    }

    /// Closes indexed entry bodies and their owning storage, retaining failed resources for a later close retry.
    private @Nullable Throwable closeIndexedStorage(@Nullable Throwable failure) {
        Iterator<ArkivoStoredContent> iterator = ownedContents.iterator();
        while (iterator.hasNext()) {
            ArkivoStoredContent content = iterator.next();
            try {
                content.close();
                iterator.remove();
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
        return readOnly;
    }

    /// Returns the TAR archive path separator.
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

    /// Returns a path inside this TAR file system.
    @Override
    public Path getPath(String first, String... more) {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return TarArkivoPath.of(this, first, more);
        }
    }

    /// Returns a path matcher for TAR paths.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        try (Operation ignored = beginReadOperation()) {
            return getPathMatcherLocked(syntaxAndPattern);
        }
    }

    /// Returns a path matcher while the caller holds the shared operation lock.
    private PathMatcher getPathMatcherLocked(String syntaxAndPattern) {
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

    /// Returns the TAR user principal lookup service.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        try (Operation ignored = beginReadOperation()) {
            return TarPosixSupport.userPrincipalLookupService();
        }
    }

    /// Watch services are not supported by TAR file systems.
    @Override
    public java.nio.file.WatchService newWatchService() {
        throw new UnsupportedOperationException("TAR watch services are not supported");
    }

    /// Returns the archive URI used by generated entry URIs.
    @Nullable URI archiveUri() {
        return archiveUri;
    }

    /// Opens an input stream for an entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            requireReadableFileSystem();
            validateReadOptions(Set.of(options));
            Node node = requireNode(path);
            if (!node.attributes().isRegularFile()) {
                throw new FileSystemException(path.toString(), null, "TAR entry is not a regular file");
            }
            return manageInputStream(openContentInputStream(node.content()));
        }
    }

    /// Opens a read-only byte channel for an entry.
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
        if (!readOnly && requestsWrite(options)) {
            return new WritableEntryByteChannel(newOutputStream(path, options, attributes));
        }

        requireReadableFileSystem();
        validateReadOptions(options);
        Node node = requireNode(path);
        if (!node.attributes().isRegularFile()) {
            throw new FileSystemException(path.toString(), null, "TAR entry is not a regular file");
        }
        return openContentChannel(node.content());
    }

    /// Opens an output stream for a new forward-only file entry.
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            return manageOutputStream(newOutputStream(path, Set.of(options)));
        }
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
        if (updateMode) {
            if (options.contains(StandardOpenOption.READ)) {
                throw new UnsupportedOperationException("TAR output streams do not support READ");
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
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        String entryPath = prepareWritableEntry(path, false);
        TarArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginFile(entryPath);
        applyInitialPermissions(currentWriter, permissions);
        return new WrittenEntryOutputStream(currentWriter.openOutputStream(), entryPath);
    }

    /// Creates a new forward-only directory entry.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            createDirectoryLocked(directory, attributes);
        }
    }

    /// Creates a directory entry while the caller holds the exclusive operation lock.
    private void createDirectoryLocked(Path directory, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        if (updateMode) {
            String entryPath = prepareUpdateEntry(directory);
            TarEntryAttributes entryAttributes = defaultAttributes(
                    entryPath,
                    TarEntryAttributes.DIRECTORY_TYPE,
                    permissions != null ? permissionsMode(permissions) : 0755,
                    null,
                    0L
            );
            addUpdateNode(new Node(entryPath, entryAttributes, true, null, false));
            return;
        }
        String entryPath = prepareWritableEntry(directory, true);
        TarArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginDirectory(entryPath);
        applyInitialPermissions(currentWriter, permissions);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, true);
    }

    /// Creates a new forward-only symbolic link entry.
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            createSymbolicLinkLocked(link, target, attributes);
        }
    }

    /// Creates a symbolic link entry while the caller holds the exclusive operation lock.
    private void createSymbolicLinkLocked(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        if (updateMode) {
            String entryPath = prepareUpdateEntry(link);
            TarEntryAttributes entryAttributes = defaultAttributes(
                    entryPath,
                    TarEntryAttributes.SYMBOLIC_LINK_TYPE,
                    permissions != null ? permissionsMode(permissions) : 0777,
                    archivePathText(target),
                    0L
            );
            addUpdateNode(new Node(entryPath, entryAttributes, false, null, false));
            return;
        }
        String entryPath = prepareWritableEntry(link, false);
        TarArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginSymbolicLink(entryPath, archivePathText(target));
        applyInitialPermissions(currentWriter, permissions);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, false);
    }

    /// Creates a new forward-only hard link entry.
    public void createLink(Path link, Path existing) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            createLinkLocked(link, existing);
        }
    }

    /// Creates a hard link entry while the caller holds the exclusive operation lock.
    private void createLinkLocked(Path link, Path existing) throws IOException {
        Objects.requireNonNull(existing, "existing");
        requireWritableFileSystem();
        if (updateMode) {
            String entryPath = prepareUpdateEntry(link);
            Node target = requireNode(existing);
            if (!target.attributes().isRegularFile() || target.directory()) {
                throw new FileSystemException(existing.toString(), null, "TAR hard link target is not a regular file");
            }
            String targetPath = normalizedNodePath(existing);
            TarEntryAttributes entryAttributes = defaultAttributes(
                    entryPath,
                    TarEntryAttributes.HARD_LINK_TYPE,
                    target.attributes().mode(),
                    targetPath,
                    target.contentSize()
            );
            addUpdateNode(new Node(entryPath, entryAttributes, false, target.content(), false));
            return;
        }
        String entryPath = prepareWritableEntry(link, false);
        String targetPath = normalizedNodePath(existing);
        if (!writtenEntries.contains(targetPath) || writtenDirectories.contains(targetPath)) {
            throw new NoSuchFileException(existing.toString());
        }
        TarArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginHardLink(entryPath, targetPath);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, false);
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
        String entryPath = normalizedNodePath(path);
        if (entryPath.isEmpty()) {
            throw new FileSystemException(path.toString(), null, "The TAR root cannot be deleted");
        }
        Node node = requireNode(path);
        if (node.directory() && !node.children().isEmpty()) {
            throw new DirectoryNotEmptyException(path.toString());
        }

        materializeHardLinksTo(entryPath);
        nodes.remove(entryPath);
        @Nullable Node parent = nodes.get(parentPath(entryPath));
        if (parent != null) {
            parent.children().remove(fileName(entryPath));
        }
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
                throw new UnsupportedOperationException("Unsupported TAR move option: " + option);
            }
        }

        String sourcePath = normalizedNodePath(source);
        String targetPath = normalizedNodePath(target);
        if (sourcePath.isEmpty() || targetPath.isEmpty()) {
            throw new FileSystemException(source.toString(), target.toString(), "The TAR root cannot be moved");
        }
        if (sourcePath.equals(targetPath)) {
            return;
        }
        Node sourceNode = requireNode(source);
        if (sourceNode.directory() && targetPath.startsWith(sourcePath + "/")) {
            throw new FileSystemException(source.toString(), target.toString(), "A directory cannot be moved below itself");
        }
        ensureExistingParentDirectory(targetPath);

        @Nullable Node targetNode = nodes.get(targetPath);
        if (targetNode != null) {
            if (!replaceExisting) {
                throw new FileAlreadyExistsException(target.toString());
            }
            if (sourceNode.directory() != targetNode.directory()) {
                throw new FileSystemException(source.toString(), target.toString(), "Source and target entry types differ");
            }
            if (targetNode.directory() && !targetNode.children().isEmpty()) {
                throw new DirectoryNotEmptyException(target.toString());
            }
        }
        if (targetNode != null && !targetNode.directory()) {
            materializeHardLinksTo(targetPath);
        }

        LinkedHashMap<String, String> movedPaths = new LinkedHashMap<>();
        for (String path : nodes.keySet()) {
            if (path.equals(sourcePath) || path.startsWith(sourcePath + "/")) {
                movedPaths.put(path, targetPath + path.substring(sourcePath.length()));
            }
        }
        for (String movedPath : movedPaths.values()) {
            @Nullable Node collision = nodes.get(movedPath);
            if (collision != null && !movedPaths.containsKey(movedPath) && !movedPath.equals(targetPath)) {
                throw new FileAlreadyExistsException(movedPath);
            }
        }

        LinkedHashMap<String, Node> rebuilt = new LinkedHashMap<>();
        for (Node node : nodes.values()) {
            if (targetNode != null && node.path().equals(targetPath) && !movedPaths.containsKey(node.path())) {
                continue;
            }
            @Nullable String movedPath = movedPaths.get(node.path());
            String newPath = movedPath != null ? movedPath : node.path();
            TarArkivoEntryAttributes attributes = node.attributes();
            @Nullable String linkName = movedHardLinkTarget(attributes, movedPaths);
            TarEntryAttributes newAttributes = copyAttributes(
                    attributes,
                    newPath,
                    attributes.typeFlag(),
                    linkName,
                    node.contentSize()
            );
            Node replacement = new Node(
                    newPath,
                    newAttributes,
                    node.directory(),
                    node.content(),
                    node.syntheticDirectory()
            );
            rebuilt.put(newPath, replacement);
        }
        rebuildChildren(rebuilt);
        nodes.clear();
        nodes.putAll(rebuilt);
        dirty = true;
    }

    /// Updates one named entry attribute in update mode.
    public void setAttribute(Path path, String attribute, @Nullable Object value, LinkOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            setAttributeLocked(path, attribute, value, options);
        }
    }

    /// Updates one named entry attribute while the caller holds the exclusive operation lock.
    private void setAttributeLocked(
            Path path,
            String attribute,
            @Nullable Object value,
            LinkOption... options
    ) throws IOException {
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(options, "options");
        int separator = attribute.indexOf(':');
        String view = separator >= 0 ? attribute.substring(0, separator) : "basic";
        String name = separator >= 0 ? attribute.substring(separator + 1) : attribute;
        switch (view) {
            case "basic" -> setBasicAttribute(path, name, value);
            case "owner" -> {
                if (!"owner".equals(name) || !(value instanceof UserPrincipal owner)) {
                    throw new IllegalArgumentException("Unsupported TAR owner attribute: " + name);
                }
                setOwner(path, owner);
            }
            case "posix" -> setPosixAttribute(path, name, value);
            case "tar" -> setTarAttribute(path, name, value);
            default -> throw new UnsupportedOperationException("Unsupported TAR attribute view: " + view);
        }
    }

    /// Opens a directory stream for an entry.
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return manageDirectoryStream(newDirectoryStreamLocked(directory, filter));
        }
    }

    /// Opens a directory stream while the caller holds the shared operation lock.
    private DirectoryStream<Path> newDirectoryStreamLocked(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        requireReadableFileSystem();
        Objects.requireNonNull(filter, "filter");
        Node node = requireNode(directory);
        if (!node.directory()) {
            throw new FileSystemException(directory.toString(), null, "TAR entry is not a directory");
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
        return new ListDirectoryStream(accepted);
    }

    /// Checks access to an entry.
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            checkAccessLocked(path, modes);
        }
    }

    /// Checks access while the caller holds the shared operation lock.
    private void checkAccessLocked(Path path, AccessMode... modes) throws IOException {
        if (updateMode) {
            requireNode(path);
            return;
        }
        if (!readOnly) {
            checkWritableAccess(path, modes);
            return;
        }
        requireNode(path);
        for (AccessMode mode : modes) {
            if (mode != AccessMode.READ) {
                throw new ReadOnlyFileSystemException();
            }
        }
    }

    /// Returns this archive's file store.
    public FileStore fileStore(Path path) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return fileStoreLocked(path);
        }
    }

    /// Returns the file store while the caller holds the shared operation lock.
    private FileStore fileStoreLocked(Path path) throws IOException {
        if (readOnly || updateMode) {
            requireNode(path);
        } else {
            requireWritableKnownPath(path);
        }
        return fileStore;
    }

    /// Reads a symbolic link target.
    public Path readSymbolicLink(Path link) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readSymbolicLinkLocked(link);
        }
    }

    /// Reads a symbolic link target while the caller holds the shared operation lock.
    private Path readSymbolicLinkLocked(Path link) throws IOException {
        requireReadableFileSystem();
        Node node = requireNode(link);
        @Nullable String linkName = node.attributes().linkName();
        if (!node.attributes().isSymbolicLink() || linkName == null) {
            throw new NotLinkException(link.toString());
        }
        return getPath(linkName);
    }

    /// Returns an attribute view for a path.
    public <V extends java.nio.file.attribute.FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
        try (Operation ignored = beginReadOperation()) {
            return getFileAttributeViewLocked(path, type, options);
        }
    }

    /// Returns an attribute view while the caller holds the shared operation lock.
    private <V extends java.nio.file.attribute.FileAttributeView> @Nullable V getFileAttributeViewLocked(
            Path path,
            Class<V> type,
            LinkOption... options
    ) {
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
        if (type == TarArkivoEntryAttributeView.class) {
            return type.cast(new TarView(path));
        }
        return null;
    }

    /// Reads attributes for a path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readAttributesLocked(path, type, options);
        }
    }

    /// Reads attributes while the caller holds the shared operation lock.
    private <A extends BasicFileAttributes> A readAttributesLocked(
            Path path,
            Class<A> type,
            LinkOption... options
    ) throws IOException {
        requireReadableFileSystem();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        TarArkivoEntryAttributes attributes = requireNode(path).attributes();
        if (type == BasicFileAttributes.class || type == TarArkivoEntryAttributes.class || type == PosixFileAttributes.class) {
            return type.cast(attributes);
        }
        throw new UnsupportedOperationException("Unsupported TAR attribute type: " + type.getName());
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
        requireReadableFileSystem();
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(options, "options");
        TarArkivoEntryAttributes entryAttributes = requireNode(path).attributes();
        HashMap<String, Object> values = new HashMap<>();
        RequestedAttributes requestedAttributes = RequestedAttributes.parse(attributes);
        boolean all = requestedAttributes.contains("*");
        if (requestedAttributes.ownerView()) {
            putOwnerAttributes(values, (PosixFileAttributes) entryAttributes, requestedAttributes, all);
        } else if (requestedAttributes.posixView()) {
            putPosixAttributes(values, (PosixFileAttributes) entryAttributes, requestedAttributes, all);
        } else if (requestedAttributes.tarView()) {
            putTarAttributes(values, entryAttributes, requestedAttributes, all);
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

    /// Adds selected TAR attributes to the result map.
    private static void putTarAttributes(
            Map<String, Object> values,
            TarArkivoEntryAttributes entryAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        putBasicAttributes(values, entryAttributes, requestedAttributes, all);
        if (all || requestedAttributes.contains("path")) {
            values.put("path", entryAttributes.path());
        }
        if (all || requestedAttributes.contains("typeFlag")) {
            values.put("typeFlag", entryAttributes.typeFlag());
        }
        if (all || requestedAttributes.contains("mode")) {
            values.put("mode", entryAttributes.mode());
        }
        if (all || requestedAttributes.contains("userId")) {
            values.put("userId", entryAttributes.userId());
        }
        if (all || requestedAttributes.contains("groupId")) {
            values.put("groupId", entryAttributes.groupId());
        }
        if (all || requestedAttributes.contains("userName")) {
            values.put("userName", entryAttributes.userName());
        }
        if (all || requestedAttributes.contains("groupName")) {
            values.put("groupName", entryAttributes.groupName());
        }
        if (all || requestedAttributes.contains("linkName")) {
            values.put("linkName", entryAttributes.linkName());
        }
        if (all || requestedAttributes.contains("isHardLink")) {
            values.put("isHardLink", entryAttributes.isHardLink());
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

    /// Returns whether the archive open options request write mode.
    private static boolean isWriteArchiveOpen(Set<? extends OpenOption> options) {
        return options.contains(StandardOpenOption.WRITE)
                || options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW)
                || options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                || options.contains(StandardOpenOption.APPEND);
    }

    /// Returns whether archive open options request read/write update mode.
    private static boolean isArchiveUpdateOpen(Set<? extends OpenOption> options) {
        return options.contains(StandardOpenOption.READ)
                && options.contains(StandardOpenOption.WRITE)
                && !options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                && !options.contains(StandardOpenOption.CREATE_NEW)
                && !options.contains(StandardOpenOption.APPEND);
    }

    /// Validates archive open options for read mode.
    private static void validateArchiveReadOptions(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("Unsupported TAR archive read option: " + option);
            }
        }
    }

    /// Validates archive open options for forward-only write mode.
    private static void validateArchiveWriteOptions(Set<? extends OpenOption> options) {
        if (!options.contains(StandardOpenOption.WRITE)) {
            throw new IllegalArgumentException("TAR archive write mode requires WRITE");
        }
        if (options.contains(StandardOpenOption.READ)) {
            throw new UnsupportedOperationException("TAR archive update mode is not supported");
        }
        if (options.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("TAR archive append mode is not supported");
        }
        if (!options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                && !options.contains(StandardOpenOption.CREATE_NEW)) {
            throw new UnsupportedOperationException("TAR archive write mode requires TRUNCATE_EXISTING or CREATE_NEW");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported TAR archive write option: " + option);
            }
        }
    }

    /// Validates archive open options for complete rewrite update mode.
    private static void validateArchiveUpdateOptions(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ
                    && option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.CREATE) {
                throw new UnsupportedOperationException("Unsupported TAR archive update option: " + option);
            }
        }
    }

    /// Returns whether entry open options request write access.
    private static boolean requestsWrite(Set<? extends OpenOption> options) {
        return options.contains(StandardOpenOption.WRITE)
                || options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW)
                || options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                || options.contains(StandardOpenOption.APPEND);
    }

    /// Validates options for a forward-only entry write.
    private static void validateEntryWriteOptions(Set<? extends OpenOption> options) {
        if (options.contains(StandardOpenOption.READ)) {
            throw new UnsupportedOperationException("TAR entry update mode is not supported");
        }
        if (options.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("TAR entry append mode is not supported");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported TAR entry write option: " + option);
            }
        }
    }

    /// Validates options for a random-access update entry channel.
    private static void validateUpdateEntryOptions(Set<? extends OpenOption> options) {
        if (options.contains(StandardOpenOption.APPEND) && options.contains(StandardOpenOption.READ)) {
            throw new IllegalArgumentException("TAR entry APPEND cannot be combined with READ");
        }
        if (options.contains(StandardOpenOption.APPEND) && options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            throw new IllegalArgumentException("TAR entry APPEND cannot be combined with TRUNCATE_EXISTING");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ
                    && option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.APPEND
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported TAR entry update option: " + option);
            }
        }
    }

    /// Applies supported initial POSIX permissions to a pending TAR writer entry.
    private static void applyInitialPermissions(
            TarArkivoStreamingWriter writer,
            @Nullable Set<PosixFilePermission> permissions
    ) throws IOException {
        if (permissions == null) {
            return;
        }
        PosixFileAttributeView view = writer.attributeView(PosixFileAttributeView.class);
        if (view == null) {
            throw new UnsupportedOperationException("TAR writer does not expose POSIX file attributes");
        }
        view.setPermissions(permissions);
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
                throw new UnsupportedOperationException("Unsupported TAR entry initial file attribute: " + name);
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

    /// Requires this file system to be in read mode.
    private void requireReadableFileSystem() {
        ensureOpen();
        if (!readOnly && !updateMode) {
            throw new UnsupportedOperationException("Forward-only TAR write file systems do not expose reads");
        }
    }

    /// Requires this file system to be in write mode.
    private void requireWritableFileSystem() {
        ensureOpen();
        if (readOnly) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Returns the writer for write mode.
    private TarArkivoStreamingWriter requireWriter() {
        @Nullable TarArkivoStreamingWriter currentWriter = writer;
        if (currentWriter == null) {
            throw new ReadOnlyFileSystemException();
        }
        return currentWriter;
    }

    /// Requires this file system to be in complete rewrite update mode.
    private void requireUpdateMode() {
        ensureOpen();
        if (!updateMode) {
            if (readOnly) {
                throw new ReadOnlyFileSystemException();
            }
            throw new UnsupportedOperationException("Forward-only TAR write file systems do not support mutations");
        }
    }

    /// Opens a seekable channel that stages one update-mode regular file body.
    private SeekableByteChannel newUpdateByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        requireUpdateMode();
        validateUpdateEntryOptions(options);
        if (activeUpdateChannel != null) {
            throw new FileSystemException(path.toString(), null, "Another TAR entry update channel is already open");
        }

        String entryPath = normalizedNodePath(path);
        if (entryPath.isEmpty()) {
            throw new FileSystemException(path.toString(), null, "The TAR root cannot be opened as a file");
        }
        @Nullable Node existing = nodes.get(entryPath);
        boolean create = options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW);
        boolean createNew = options.contains(StandardOpenOption.CREATE_NEW);
        if (existing == null && !create) {
            throw new NoSuchFileException(path.toString());
        }
        if (existing != null && createNew) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (existing != null && existing.directory()) {
            throw new FileSystemException(path.toString(), null, "TAR entry is a directory");
        }
        if (existing == null) {
            ensureParents(nodes, entryPath);
        }

        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean writable = options.contains(StandardOpenOption.WRITE) || append;
        boolean readable = options.contains(StandardOpenOption.READ);
        if (!readable && !writable) {
            throw new IllegalArgumentException("TAR entry update channel requires READ, WRITE, or APPEND");
        }
        boolean truncate = writable && options.contains(StandardOpenOption.TRUNCATE_EXISTING);
        long expectedSize = existing != null && !truncate ? existing.contentSize() : 0L;
        ArkivoStoredContent pendingContent = requireEditStorage().createContent(entryPath, expectedSize);
        @Nullable SeekableByteChannel storageChannel = null;
        try {
            if (existing != null && !truncate) {
                copyContent(existing.content(), pendingContent);
            }
            LinkedHashSet<OpenOption> storageOptions = new LinkedHashSet<>();
            if (readable) {
                storageOptions.add(StandardOpenOption.READ);
            }
            if (writable) {
                storageOptions.add(StandardOpenOption.WRITE);
            }
            if (existing == null || truncate) {
                storageOptions.add(StandardOpenOption.TRUNCATE_EXISTING);
            }
            storageChannel = pendingContent.openChannel(Set.copyOf(storageOptions));
            UpdateEntryByteChannel channel = new UpdateEntryByteChannel(
                    entryPath,
                    existing,
                    permissions,
                    pendingContent,
                    storageChannel,
                    readable,
                    writable,
                    append,
                    writable && (existing == null || truncate)
            );
            if (writable) {
                activeUpdateChannel = channel;
            }
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

    /// Prepares a previously absent path for an update-mode entry.
    private String prepareUpdateEntry(Path path) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        String entryPath = normalizedNodePath(path);
        if (entryPath.isEmpty() || nodes.containsKey(entryPath)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        ensureParents(nodes, entryPath);
        return entryPath;
    }

    /// Adds a new update-mode node and marks the archive dirty.
    private void addUpdateNode(Node node) throws IOException {
        putNode(nodes, node);
        dirty = true;
    }

    /// Rejects a mutation while an update entry channel is open.
    private void requireNoActiveUpdateChannel(Path path) throws IOException {
        if (activeUpdateChannel != null) {
            throw new FileSystemException(path.toString(), null, "A TAR entry update channel is still open");
        }
    }

    /// Commits staged regular file content into the update index.
    private void commitUpdatedEntry(UpdateEntryByteChannel channel, ArkivoStoredContent content) throws IOException {
        if (activeUpdateChannel == channel) {
            activeUpdateChannel = null;
        }
        @Nullable Node existing = nodes.get(channel.path());
        if (existing != channel.originalNode()) {
            throw new FileSystemException(channel.path(), null, "TAR entry changed while its update channel was open");
        }

        long size = content.size();
        TarArkivoEntryAttributes base = existing != null
                ? existing.attributes()
                : defaultAttributes(channel.path(), TarEntryAttributes.REGULAR_TYPE, 0644, null, size);
        int mode = existing == null && channel.initialPermissions() != null
                ? permissionsMode(channel.initialPermissions())
                : base.mode();
        TarEntryAttributes attributes = new TarEntryAttributes(
                channel.path(),
                TarEntryAttributes.REGULAR_TYPE,
                mode,
                base.userId(),
                base.groupId(),
                base.userName(),
                base.groupName(),
                null,
                size,
                base.lastModifiedTime(),
                base.lastAccessTime(),
                base.creationTime()
        );
        Node replacement = new Node(channel.path(), attributes, false, content, false);
        if (existing == null) {
            putNode(nodes, replacement);
        } else {
            nodes.put(channel.path(), replacement);
        }
        ownedContents.add(content);
        refreshHardLinkContents(channel.path(), content, size);
        dirty = true;
    }

    /// Refreshes cached content for hard links that transitively depend on a changed entry.
    private void refreshHardLinkContents(
            String changedPath,
            @Nullable ArkivoStoredContent content,
            long contentSize
    ) throws IOException {
        ArrayDeque<String> changedPaths = new ArrayDeque<>();
        changedPaths.add(changedPath);
        while (!changedPaths.isEmpty()) {
            String targetPath = changedPaths.removeFirst();
            for (Node node : List.copyOf(nodes.values())) {
                TarArkivoEntryAttributes attributes = node.attributes();
                @Nullable String linkName = attributes.linkName();
                if (!attributes.isHardLink() || linkName == null) {
                    continue;
                }
                requireArchiveLocalPath(linkName, "TAR hard link target");
                if (!normalizeEntryPath(linkName).equals(targetPath)) {
                    continue;
                }
                Node replacement = replaceNodeAttributes(
                        node,
                        copyAttributes(attributes, node.path(), attributes.typeFlag(), linkName, contentSize),
                        content
                );
                nodes.put(node.path(), replacement);
                changedPaths.addLast(node.path());
            }
        }
    }

    /// Copies indexed content into newly allocated writable storage.
    private static void copyContent(
            @Nullable ArkivoStoredContent source,
            ArkivoStoredContent destination
    ) throws IOException {
        try (SeekableByteChannel output = destination.openChannel(Set.of(
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ))) {
            if (source == null) {
                return;
            }
            try (SeekableByteChannel input = source.openChannel(Set.of(StandardOpenOption.READ))) {
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                while (input.read(buffer) >= 0) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    buffer.clear();
                }
            }
        }
    }

    /// Releases uncommitted stored content or retains it for close-time cleanup retry.
    private void releaseStoredContent(ArkivoStoredContent content) {
        try {
            content.close();
        } catch (IOException | RuntimeException | Error exception) {
            ownedContents.add(content);
        }
    }

    /// Returns default metadata for a newly created update-mode entry.
    private static TarEntryAttributes defaultAttributes(
            String path,
            byte typeFlag,
            int mode,
            @Nullable String linkName,
            long size
    ) {
        return new TarEntryAttributes(
                path,
                typeFlag,
                mode,
                0L,
                0L,
                null,
                null,
                linkName,
                size,
                UNIX_EPOCH,
                UNIX_EPOCH,
                UNIX_EPOCH
        );
    }

    /// Returns copied metadata with changed path, type, link target, and body size.
    private static TarEntryAttributes copyAttributes(
            TarArkivoEntryAttributes attributes,
            String path,
            byte typeFlag,
            @Nullable String linkName,
            long size
    ) {
        return new TarEntryAttributes(
                path,
                typeFlag,
                attributes.mode(),
                attributes.userId(),
                attributes.groupId(),
                attributes.userName(),
                attributes.groupName(),
                linkName,
                size,
                attributes.lastModifiedTime(),
                attributes.lastAccessTime(),
                attributes.creationTime()
        );
    }

    /// Returns a replacement node while preserving its directory index and synthetic marker.
    private static Node replaceNodeAttributes(
            Node node,
            TarArkivoEntryAttributes attributes,
            @Nullable ArkivoStoredContent content
    ) {
        Node replacement = new Node(
                node.path(),
                attributes,
                attributes.isDirectory(),
                content,
                node.syntheticDirectory()
        );
        replacement.children().putAll(node.children());
        return replacement;
    }

    /// Returns POSIX permission bits for the given permission set.
    private static int permissionsMode(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            switch (permission) {
                case OWNER_READ -> mode |= 0400;
                case OWNER_WRITE -> mode |= 0200;
                case OWNER_EXECUTE -> mode |= 0100;
                case GROUP_READ -> mode |= 0040;
                case GROUP_WRITE -> mode |= 0020;
                case GROUP_EXECUTE -> mode |= 0010;
                case OTHERS_READ -> mode |= 0004;
                case OTHERS_WRITE -> mode |= 0002;
                case OTHERS_EXECUTE -> mode |= 0001;
            }
        }
        return mode;
    }

    /// Converts hard links that target a deleted entry into independent regular files.
    private void materializeHardLinksTo(String deletedPath) throws IOException {
        ArrayDeque<String> deletedTargets = new ArrayDeque<>();
        deletedTargets.add(deletedPath);
        while (!deletedTargets.isEmpty()) {
            String targetPath = deletedTargets.removeFirst();
            for (Node node : List.copyOf(nodes.values())) {
                TarArkivoEntryAttributes attributes = node.attributes();
                @Nullable String linkName = attributes.linkName();
                if (!attributes.isHardLink() || linkName == null) {
                    continue;
                }
                requireArchiveLocalPath(linkName, "TAR hard link target");
                if (!normalizeEntryPath(linkName).equals(targetPath)) {
                    continue;
                }
                TarEntryAttributes materialized = copyAttributes(
                        attributes,
                        node.path(),
                        TarEntryAttributes.REGULAR_TYPE,
                        null,
                        node.contentSize()
                );
                nodes.put(node.path(), replaceNodeAttributes(node, materialized, node.content()));
                deletedTargets.addLast(node.path());
            }
        }
    }

    /// Returns a hard link target rewritten for moved archive paths.
    private static @Nullable String movedHardLinkTarget(
            TarArkivoEntryAttributes attributes,
            Map<String, String> movedPaths
    ) throws IOException {
        @Nullable String linkName = attributes.linkName();
        if (!attributes.isHardLink() || linkName == null) {
            return linkName;
        }
        requireArchiveLocalPath(linkName, "TAR hard link target");
        return movedPaths.getOrDefault(normalizeEntryPath(linkName), linkName);
    }

    /// Requires a path's parent to exist as a directory.
    private void ensureExistingParentDirectory(String path) throws IOException {
        @Nullable Node parent = nodes.get(parentPath(path));
        if (parent == null) {
            throw new NoSuchFileException(parentPath(path));
        }
        if (!parent.directory()) {
            throw new FileSystemException(path, parent.path(), "TAR parent entry is not a directory");
        }
    }

    /// Rebuilds child indexes after paths have been moved.
    private static void rebuildChildren(Map<String, Node> rebuilt) throws IOException {
        for (Node node : rebuilt.values()) {
            node.children().clear();
        }
        for (Node node : rebuilt.values()) {
            if (node.path().isEmpty()) {
                continue;
            }
            @Nullable Node parent = rebuilt.get(parentPath(node.path()));
            if (parent == null || !parent.directory()) {
                throw new IOException("TAR parent entry is missing after move: " + node.path());
            }
            parent.children().put(fileName(node.path()), node.path());
        }
    }

    /// Sets one basic timestamp attribute.
    private void setBasicAttribute(Path path, String name, @Nullable Object value) throws IOException {
        if (!(value instanceof FileTime time)) {
            throw new IllegalArgumentException("TAR basic timestamp value must be FileTime: " + name);
        }
        switch (name) {
            case "lastModifiedTime" -> setTimes(path, time, null, null);
            case "lastAccessTime" -> setTimes(path, null, time, null);
            case "creationTime" -> setTimes(path, null, null, time);
            default -> throw new UnsupportedOperationException("Unsupported writable TAR basic attribute: " + name);
        }
    }

    /// Sets one POSIX attribute.
    private void setPosixAttribute(Path path, String name, @Nullable Object value) throws IOException {
        switch (name) {
            case "lastModifiedTime", "lastAccessTime", "creationTime" -> setBasicAttribute(path, name, value);
            case "owner" -> {
                if (!(value instanceof UserPrincipal owner)) {
                    throw new IllegalArgumentException("TAR POSIX owner value must be UserPrincipal");
                }
                setOwner(path, owner);
            }
            case "group" -> {
                if (!(value instanceof GroupPrincipal group)) {
                    throw new IllegalArgumentException("TAR POSIX group value must be GroupPrincipal");
                }
                setGroup(path, group);
            }
            case "permissions" -> {
                if (!(value instanceof Set<?> values)) {
                    throw new IllegalArgumentException("TAR POSIX permissions value must be Set");
                }
                EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
                for (Object item : values) {
                    if (!(item instanceof PosixFilePermission permission)) {
                        throw new IllegalArgumentException("TAR POSIX permissions contain an invalid value");
                    }
                    permissions.add(permission);
                }
                setPermissions(path, permissions);
            }
            default -> throw new UnsupportedOperationException("Unsupported writable TAR POSIX attribute: " + name);
        }
    }

    /// Sets one TAR-specific metadata attribute.
    private void setTarAttribute(Path path, String name, @Nullable Object value) throws IOException {
        switch (name) {
            case "lastModifiedTime", "lastAccessTime", "creationTime" -> setBasicAttribute(path, name, value);
            case "userId" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("TAR userId value must be numeric");
                }
                setUserId(path, number.longValue());
            }
            case "groupId" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("TAR groupId value must be numeric");
                }
                setGroupId(path, number.longValue());
            }
            case "mode" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("TAR mode value must be numeric");
                }
                setMode(path, number.intValue());
            }
            case "userName" -> setUserName(path, nullableString(value, "userName"));
            case "groupName" -> setGroupName(path, nullableString(value, "groupName"));
            default -> throw new UnsupportedOperationException("Unsupported writable TAR attribute: " + name);
        }
    }

    /// Converts a nullable string attribute value.
    private static @Nullable String nullableString(@Nullable Object value, String name) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("TAR " + name + " value must be String or null");
    }


    /// Sets entry timestamps in update mode.
    private void setTimes(
            Path path,
            @Nullable FileTime lastModifiedTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime creationTime
    ) throws IOException {
        mutateAttributes(path, attributes -> new TarEntryAttributes(
                attributes.path(),
                attributes.typeFlag(),
                attributes.mode(),
                attributes.userId(),
                attributes.groupId(),
                attributes.userName(),
                attributes.groupName(),
                attributes.linkName(),
                attributes.size(),
                lastModifiedTime != null ? lastModifiedTime : attributes.lastModifiedTime(),
                lastAccessTime != null ? lastAccessTime : attributes.lastAccessTime(),
                creationTime != null ? creationTime : attributes.creationTime()
        ));
    }

    /// Sets the entry owner name in update mode.
    private void setOwner(Path path, UserPrincipal owner) throws IOException {
        setUserName(path, Objects.requireNonNull(owner, "owner").getName());
    }

    /// Sets the entry group name in update mode.
    private void setGroup(Path path, GroupPrincipal group) throws IOException {
        setGroupName(path, Objects.requireNonNull(group, "group").getName());
    }

    /// Sets entry permission bits in update mode.
    private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        Objects.requireNonNull(permissions, "permissions");
        mutateAttributes(
                path,
                attributes -> copyWithMode(attributes, (attributes.mode() & ~0777) | permissionsMode(permissions))
        );
    }

    /// Sets the numeric TAR user identifier in update mode.
    private void setUserId(Path path, long userId) throws IOException {
        if (userId < 0L) {
            throw new IllegalArgumentException("userId must not be negative");
        }
        mutateAttributes(path, attributes -> new TarEntryAttributes(
                attributes.path(), attributes.typeFlag(), attributes.mode(), userId, attributes.groupId(),
                attributes.userName(), attributes.groupName(), attributes.linkName(), attributes.size(),
                attributes.lastModifiedTime(), attributes.lastAccessTime(), attributes.creationTime()
        ));
    }

    /// Sets the numeric TAR group identifier in update mode.
    private void setGroupId(Path path, long groupId) throws IOException {
        if (groupId < 0L) {
            throw new IllegalArgumentException("groupId must not be negative");
        }
        mutateAttributes(path, attributes -> new TarEntryAttributes(
                attributes.path(), attributes.typeFlag(), attributes.mode(), attributes.userId(), groupId,
                attributes.userName(), attributes.groupName(), attributes.linkName(), attributes.size(),
                attributes.lastModifiedTime(), attributes.lastAccessTime(), attributes.creationTime()
        ));
    }

    /// Sets the raw TAR mode in update mode.
    private void setMode(Path path, int mode) throws IOException {
        if (mode < 0) {
            throw new IllegalArgumentException("mode must not be negative");
        }
        mutateAttributes(path, attributes -> copyWithMode(attributes, mode));
    }

    /// Returns copied metadata with a changed mode.
    private static TarEntryAttributes copyWithMode(TarArkivoEntryAttributes attributes, int mode) {
        return new TarEntryAttributes(
                attributes.path(), attributes.typeFlag(), mode, attributes.userId(), attributes.groupId(),
                attributes.userName(), attributes.groupName(), attributes.linkName(), attributes.size(),
                attributes.lastModifiedTime(), attributes.lastAccessTime(), attributes.creationTime()
        );
    }

    /// Sets the TAR user name in update mode.
    private void setUserName(Path path, @Nullable String userName) throws IOException {
        mutateAttributes(path, attributes -> new TarEntryAttributes(
                attributes.path(), attributes.typeFlag(), attributes.mode(), attributes.userId(), attributes.groupId(),
                userName, attributes.groupName(), attributes.linkName(), attributes.size(),
                attributes.lastModifiedTime(), attributes.lastAccessTime(), attributes.creationTime()
        ));
    }

    /// Sets the TAR group name in update mode.
    private void setGroupName(Path path, @Nullable String groupName) throws IOException {
        mutateAttributes(path, attributes -> new TarEntryAttributes(
                attributes.path(), attributes.typeFlag(), attributes.mode(), attributes.userId(), attributes.groupId(),
                attributes.userName(), groupName, attributes.linkName(), attributes.size(),
                attributes.lastModifiedTime(), attributes.lastAccessTime(), attributes.creationTime()
        ));
    }

    /// Replaces one entry's metadata through an update function.
    private void mutateAttributes(Path path, AttributeMutation mutation) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        Node node = requireNode(path);
        if (node.path().isEmpty() || node.syntheticDirectory()) {
            throw new FileSystemException(path.toString(), null, "Synthetic TAR directory metadata cannot be changed");
        }
        TarArkivoEntryAttributes attributes = mutation.apply(node.attributes());
        nodes.put(node.path(), replaceNodeAttributes(node, attributes, node.content()));
        dirty = true;
    }

    /// Changes an immutable entry metadata snapshot.
    @FunctionalInterface
    @NotNullByDefault
    private interface AttributeMutation {
        /// Returns replacement metadata.
        TarArkivoEntryAttributes apply(TarArkivoEntryAttributes attributes);
    }

    /// Prepares a new archive entry path for forward-only writing.
    private String prepareWritableEntry(Path path, boolean directory) throws IOException {
        ensureOpen();
        String entryPath = normalizedNodePath(path);
        if (entryPath.isEmpty()) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (writtenEntries.contains(entryPath) || !directory && writtenDirectories.contains(entryPath)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        markImplicitWritableParents(entryPath);
        return entryPath;
    }

    /// Records implicit parent directories for a path and rejects file-parent conflicts.
    private void markImplicitWritableParents(String path) throws IOException {
        int separator = path.indexOf('/');
        while (separator >= 0) {
            String parent = path.substring(0, separator);
            if (!parent.isEmpty()) {
                if (writtenEntries.contains(parent) && !writtenDirectories.contains(parent)) {
                    throw new FileSystemException(path, parent, "TAR parent entry is not a directory");
                }
                writtenDirectories.add(parent);
            }
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

    /// Checks access to an entry in forward-only write mode.
    private void checkWritableAccess(Path path, AccessMode... modes) throws IOException {
        requireWritableKnownPath(path);
        for (AccessMode mode : modes) {
            if (mode == AccessMode.EXECUTE || mode == AccessMode.READ) {
                throw new UnsupportedOperationException("Forward-only TAR write file systems do not expose reads");
            }
        }
    }

    /// Requires a path known to forward-only write mode.
    private void requireWritableKnownPath(Path path) throws IOException {
        ensureOpen();
        String entryPath = normalizedNodePath(path);
        if (!entryPath.isEmpty() && !writtenEntries.contains(entryPath) && !writtenDirectories.contains(entryPath)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /// Returns archive-style path text for a link target.
    private static String archivePathText(Path path) {
        return path.toString().replace('\\', '/');
    }

    /// Returns a node for a path.
    private Node requireNode(Path path) throws IOException {
        ensureOpen();
        String normalizedPath = normalizedNodePath(path);
        @Nullable Node node = nodes.get(normalizedPath);
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        return node;
    }

    /// Returns a normalized node path.
    private String normalizedNodePath(Path path) {
        TarArkivoPath tarPath = TarArkivoPath.require(path, this);
        TarArkivoPath normalized = (TarArkivoPath) tarPath.toAbsolutePath().normalize();
        return normalized.archivePath();
    }

    /// Validates that only read options are requested.
    private static void validateReadOptions(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("TAR file systems are read-only");
            }
        }
    }

    /// Returns a node map containing only the synthetic root directory.
    private static Map<String, Node> rootNodes() {
        return Map.of("", new Node("", syntheticDirectoryAttributes("/"), true, null, true));
    }

    /// Returns identity-based content ownership storage for an indexed file system.
    private static Set<ArkivoStoredContent> identityContentSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /// Returns configured indexed-content storage or the default temporary-file storage.
    private static ArkivoEditStorage editStorage(Map<String, ?> environment) {
        @Nullable ArkivoEditStorage configured = ArkivoFileSystem.EDIT_STORAGE.read(environment);
        return configured != null
                ? configured
                : ArkivoEditStorage.temporaryFiles(defaultEditStorageDirectory());
    }

    /// Returns the directory used by default indexed-content storage.
    private static Path defaultEditStorageDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
    }

    /// Closes resources allocated while opening an indexed file system and suppresses cleanup failures.
    private static void closeOpenedStorage(
            ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            Throwable failure
    ) {
        for (ArkivoStoredContent content : ownedContents) {
            try {
                content.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        try {
            editStorage.close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /// Streams one archive entry body into newly allocated indexed content storage.
    private static ArkivoStoredContent storeInput(
            ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            String path,
            long expectedSize,
            InputStream input
    ) throws IOException {
        ArkivoStoredContent content = editStorage.createContent(path, expectedSize);
        try {
            try (SeekableByteChannel output = content.openChannel(Set.of(
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ))) {
                copy(input, output);
            }
            ownedContents.add(content);
            return content;
        } catch (IOException | RuntimeException | Error exception) {
            try {
                content.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /// Copies an input stream to a seekable channel using bounded memory.
    private static void copy(InputStream input, SeekableByteChannel output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        ByteBuffer bytes = ByteBuffer.wrap(buffer);
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return;
            }
            bytes.clear();
            bytes.limit(count);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
        }
    }

    /// Opens a stream over indexed content, or an empty stream when the entry has no body.
    private static InputStream openContentInputStream(@Nullable ArkivoStoredContent content) throws IOException {
        return content != null
                ? Channels.newInputStream(content.openChannel(Set.of(StandardOpenOption.READ)))
                : InputStream.nullInputStream();
    }

    /// Opens a seekable channel over indexed content, or an empty channel when the entry has no body.
    private static SeekableByteChannel openContentChannel(@Nullable ArkivoStoredContent content) throws IOException {
        return content != null
                ? content.openChannel(Set.of(StandardOpenOption.READ))
                : new ByteArraySeekableByteChannel(new byte[0]);
    }

    /// Returns the indexed-content storage required outside forward-only write mode.
    private ArkivoEditStorage requireEditStorage() {
        return Objects.requireNonNull(editStorage, "editStorage");
    }

    /// Detects a compression codec whose decoded path prefix is a valid TAR archive.
    private static @Nullable CompressionCodec detectCompression(Path path) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            return detectCompression(channel);
        }
    }

    /// Detects a compression codec whose decoded channel prefix is a valid TAR archive.
    private static @Nullable CompressionCodec detectCompression(SeekableByteChannel channel) throws IOException {
        @Nullable CompressionCodec candidate = CompressionCodecs.detect(channel);
        if (candidate == null || !candidate.canDecompress()) {
            return null;
        }
        channel.position(0L);
        try (InputStream input = openArchiveInput(Channels.newInputStream(channel), candidate)) {
            byte[] probe = new byte[TarArkivoFormat.instance().probeSize()];
            int size = readPrefix(input, probe);
            return TarArkivoFormat.instance().matches(ByteBuffer.wrap(probe, 0, size)) ? candidate : null;
        } catch (IOException exception) {
            return null;
        }
    }

    /// Reads as many prefix bytes as possible without allowing a zero-progress loop.
    private static int readPrefix(InputStream input, byte[] buffer) throws IOException {
        int size = 0;
        while (size < buffer.length) {
            int count = input.read(buffer, size, buffer.length - size);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                throw new IOException("TAR compression probe made no progress");
            }
            size += count;
        }
        return size;
    }

    /// Publishes all surviving update nodes through a complete TAR rewrite.
    private void commitUpdate() throws IOException {
        ArkivoCommitTarget target = Objects.requireNonNull(commitTarget, "commitTarget");
        ArkivoCommitOutput output = target.openOutput(archivePath);
        Throwable failure = null;
        try {
            try (SeekableByteChannel channel = output.openChannel(Set.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ));
                 TarArkivoStreamingWriterImpl archiveWriter =
                         new TarArkivoStreamingWriterImpl(openArchiveOutput(
                                 Channels.newOutputStream(channel),
                                 compressionCodec
                         ))) {
                for (Node node : nodes.values()) {
                    if (node.path().isEmpty() || node.syntheticDirectory()) {
                        continue;
                    }
                    @Nullable ArkivoStoredContent content = node.content();
                    @Nullable SeekableByteChannel body = content != null
                            ? content.openChannel(Set.of(StandardOpenOption.READ))
                            : null;
                    try (body) {
                        archiveWriter.writeSnapshot(node.attributes(), body, node.contentSize());
                    }
                }
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
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

    /// Returns the directory used for default atomic update output.
    private static Path defaultCommitDirectory(Path archivePath) {
        Path absolutePath = archivePath.toAbsolutePath();
        @Nullable Path parent = absolutePath.getParent();
        return parent != null ? parent : absolutePath.getFileSystem().getPath(".").toAbsolutePath();
    }

    /// Closes a channel source after an open failure and suppresses any cleanup failure.
    private static void closeSourceAfterOpenFailure(ArkivoSeekableChannelSource source, Throwable failure) {
        try {
            source.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure.addSuppressed(exception);
        }
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

    /// Reads all entry nodes from a TAR stream.
    private static Map<String, Node> readNodes(
            InputStream input,
            ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            Map<String, ?> environment
    ) throws IOException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        nodes.put("", new Node("", syntheticDirectoryAttributes("/"), true, null, true));

        try (TarArkivoStreamingReader reader = new TarArkivoStreamingReaderImpl(input, environment)) {
            while (reader.next()) {
                TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
                String path = normalizeEntryPath(attributes.path());
                ensureParents(nodes, path);
                TarArkivoEntryAttributes nodeAttributes = attributes;
                @Nullable ArkivoStoredContent content = null;
                if (attributes.isHardLink()) {
                    String targetPath = normalizeHardLinkTargetPath(attributes);
                    @Nullable Node target = nodes.get(targetPath);
                    if (target == null || !target.attributes().isRegularFile()) {
                        throw new IOException("TAR hard link target is not available: " + targetPath);
                    }
                    content = target.content();
                    nodeAttributes = attributesWithSize(attributes, target.contentSize());
                } else if (attributes instanceof TarEntryAttributes internalAttributes
                        && internalAttributes.bodySize() > 0L) {
                    try (InputStream entryInput = reader.openInputStream()) {
                        content = storeInput(
                                editStorage,
                                ownedContents,
                                path,
                                internalAttributes.bodySize(),
                                entryInput
                        );
                    }
                }
                long contentSize = content != null ? content.size() : 0L;
                nodeAttributes = copyAttributes(
                        nodeAttributes,
                        path,
                        nodeAttributes.typeFlag(),
                        nodeAttributes.linkName(),
                        contentSize
                );
                putNode(nodes, new Node(path, nodeAttributes, nodeAttributes.isDirectory(), content, false));
            }
        }
        return nodes;
    }

    /// Normalizes the archive-local target path for a TAR hard link.
    private static String normalizeHardLinkTargetPath(TarArkivoEntryAttributes attributes) throws IOException {
        @Nullable String linkName = attributes.linkName();
        if (linkName == null) {
            throw new IOException("TAR hard link entry is missing a link target: " + attributes.path());
        }
        requireArchiveLocalPath(linkName, "TAR hard link target");
        return normalizeEntryPath(linkName);
    }

    /// Returns an attribute snapshot with a resolved regular file size.
    private static TarEntryAttributes attributesWithSize(TarArkivoEntryAttributes attributes, long size) {
        return new TarEntryAttributes(
                attributes.path(),
                attributes.typeFlag(),
                attributes.mode(),
                attributes.userId(),
                attributes.groupId(),
                attributes.userName(),
                attributes.groupName(),
                attributes.linkName(),
                size,
                attributes.lastModifiedTime(),
                attributes.lastAccessTime(),
                attributes.creationTime()
        );
    }

    /// Adds implicit parent directory nodes.
    private static void ensureParents(Map<String, Node> nodes, String path) throws IOException {
        int separator = path.indexOf('/');
        while (separator >= 0) {
            String parent = path.substring(0, separator);
            if (!parent.isEmpty()) {
                @Nullable Node existing = nodes.get(parent);
                if (existing == null) {
                    ensureParents(nodes, parent);
                    putNode(nodes, new Node(parent, syntheticDirectoryAttributes(parent), true, null, true));
                } else if (!existing.directory()) {
                    throw new IOException("TAR parent entry is not a directory: " + parent);
                }
            }
            separator = path.indexOf('/', separator + 1);
        }
    }

    /// Adds or replaces one node in the node map.
    private static void putNode(Map<String, Node> nodes, Node node) throws IOException {
        @Nullable Node existing = nodes.get(node.path());
        if (existing != null && !(existing.syntheticDirectory() && node.directory())) {
            throw new IOException("Duplicate TAR entry path: " + node.path());
        }
        @Nullable LinkedHashMap<String, String> existingChildren = existing != null ? existing.children() : null;
        if (!node.path().isEmpty()) {
            @Nullable Node parent = nodes.get(parentPath(node.path()));
            if (parent != null) {
                if (!parent.directory()) {
                    throw new IOException("TAR parent entry is not a directory: " + parent.path());
                }
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
        String normalized = path;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IOException("TAR entry is missing a path");
        }
        return normalized;
    }

    /// Requires an archive-local path without absolute or parent-directory components.
    private static void requireArchiveLocalPath(String path, String description) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\") || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException(description + " must be relative");
        }
        boolean hasName = false;
        int start = 0;
        while (start <= path.length()) {
            int end = nextPathSeparator(path, start);
            String name = path.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IOException(description + " must not contain ..");
                }
                hasName = true;
            }
            start = end + 1;
        }
        if (!hasName) {
            throw new IOException(description + " is missing a path");
        }
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

    /// Returns synthetic directory attributes.
    private static TarEntryAttributes syntheticDirectoryAttributes(String path) {
        FileTime time = FileTime.fromMillis(0L);
        return new TarEntryAttributes(path, TarEntryAttributes.DIRECTORY_TYPE, 040755, 0L, 0L, null, null, null, 0L, time, time, time);
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
            if (!"basic".equals(view) && !"owner".equals(view) && !"posix".equals(view) && !"tar".equals(view)) {
                throw new UnsupportedOperationException("Unsupported TAR attribute view: " + view);
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("TAR attribute names must not be empty");
            }
            if ("*".equals(names)) {
                return new RequestedAttributes(view, Set.of("*"));
            }
            return new RequestedAttributes(view, Set.of(names.split(",")));
        }

        /// Returns whether this request targets the TAR attribute view.
        private boolean tarView() {
            return "tar".equals(view);
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

    /// Stores one TAR file system node.
    @NotNullByDefault
    private static final class Node {
        /// The normalized archive path.
        private final String path;

        /// The node attributes.
        private final TarArkivoEntryAttributes attributes;

        /// Whether this node is a directory.
        private final boolean directory;

        /// The cached file content.
        private final @Nullable ArkivoStoredContent content;

        /// Whether this is an implicit directory.
        private final boolean syntheticDirectory;

        /// Child node paths keyed by child name.
        private final LinkedHashMap<String, String> children = new LinkedHashMap<>();

        /// Creates one node.
        private Node(
                String path,
                TarArkivoEntryAttributes attributes,
                boolean directory,
                @Nullable ArkivoStoredContent content,
                boolean syntheticDirectory
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.attributes = Objects.requireNonNull(attributes, "attributes");
            this.directory = directory;
            this.content = content;
            this.syntheticDirectory = syntheticDirectory;
        }

        /// Returns the normalized archive path.
        private String path() {
            return path;
        }

        /// Returns the node attributes.
        private TarArkivoEntryAttributes attributes() {
            return attributes;
        }

        /// Returns whether this node is a directory.
        private boolean directory() {
            return directory;
        }

        /// Returns the cached file content.
        private @Nullable ArkivoStoredContent content() {
            return content;
        }

        /// Returns the cached file content size.
        private long contentSize() throws IOException {
            return content != null ? content.size() : 0L;
        }

        /// Returns whether this is an implicit directory.
        private boolean syntheticDirectory() {
            return syntheticDirectory;
        }

        /// Returns mutable child path map while the file system index is being built.
        private LinkedHashMap<String, String> children() {
            return children;
        }
    }

    /// Implements a basic attribute view for read and update modes.
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
            return TarArkivoFileSystemImpl.this.readAttributes(path, BasicFileAttributes.class);
        }

        /// Sets this path's timestamps in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            TarArkivoFileSystemImpl.this.setTimes(path, lastModifiedTime, lastAccessTime, createTime);
        }
    }

    /// Implements a TAR attribute view for read and update modes.
    @NotNullByDefault
    private final class TarView implements TarArkivoEntryAttributeView {
        /// The path whose attributes are exposed.
        private final Path path;

        /// Creates a TAR attribute view.
        private TarView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Reads this path's TAR attributes.
        @Override
        public TarArkivoEntryAttributes readAttributes() throws IOException {
            return TarArkivoFileSystemImpl.this.readAttributes(path, TarArkivoEntryAttributes.class);
        }

        /// Sets this path's timestamps in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            TarArkivoFileSystemImpl.this.setTimes(path, lastModifiedTime, lastAccessTime, createTime);
        }

        /// Sets this path's numeric user identifier in update mode.
        @Override
        public void setUserId(long userId) throws IOException {
            TarArkivoFileSystemImpl.this.setUserId(path, userId);
        }

        /// Sets this path's numeric group identifier in update mode.
        @Override
        public void setGroupId(long groupId) throws IOException {
            TarArkivoFileSystemImpl.this.setGroupId(path, groupId);
        }

        /// Sets this path's raw TAR mode in update mode.
        @Override
        public void setMode(int mode) throws IOException {
            TarArkivoFileSystemImpl.this.setMode(path, mode);
        }

        /// Sets this path's TAR user name in update mode.
        @Override
        public void setUserName(@Nullable String userName) throws IOException {
            TarArkivoFileSystemImpl.this.setUserName(path, userName);
        }

        /// Sets this path's TAR group name in update mode.
        @Override
        public void setGroupName(@Nullable String groupName) throws IOException {
            TarArkivoFileSystemImpl.this.setGroupName(path, groupName);
        }
    }

    /// Implements an owner attribute view for read and update modes.
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
            return TarArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class).owner();
        }

        /// Sets this path's owner in update mode.
        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            TarArkivoFileSystemImpl.this.setOwner(path, owner);
        }
    }

    /// Implements a POSIX attribute view for read and update modes.
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
            return TarArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class);
        }

        /// Returns this path's owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        /// Sets this path's timestamps in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            TarArkivoFileSystemImpl.this.setTimes(path, lastModifiedTime, lastAccessTime, createTime);
        }

        /// Sets this path's owner in update mode.
        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            TarArkivoFileSystemImpl.this.setOwner(path, owner);
        }

        /// Sets this path's group in update mode.
        @Override
        public void setGroup(GroupPrincipal group) throws IOException {
            TarArkivoFileSystemImpl.this.setGroup(path, group);
        }

        /// Sets this path's permissions in update mode.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) throws IOException {
            TarArkivoFileSystemImpl.this.setPermissions(path, permissions);
        }
    }

    /// Implements a simple TAR file store.
    @NotNullByDefault
    private final class TarFileStore extends FileStore {
        /// Returns the archive file store name.
        @Override
        public String name() {
            @Nullable Path path = archivePath;
            return path != null ? path.toString() : "tar";
        }

        /// Returns the archive file store type.
        @Override
        public String type() {
            return "tar";
        }

        /// Returns whether this file store is read-only.
        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        /// Returns total space when known.
        @Override
        public long getTotalSpace() throws IOException {
            @Nullable Path path = archivePath;
            return path != null ? Files.size(path) : sourceArchiveSize;
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
                    || type == TarArkivoEntryAttributeView.class;
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

    /// Implements a staged random-access channel for one update-mode regular file.
    @NotNullByDefault
    private final class UpdateEntryByteChannel implements SeekableByteChannel {
        /// The normalized entry path.
        private final String path;

        /// The node present when this channel opened, or `null` for a new entry.
        private final @Nullable Node originalNode;

        /// Initial permissions applied only when a new entry is committed.
        private final @Nullable
        @Unmodifiable Set<PosixFilePermission> initialPermissions;

        /// Whether reads are allowed.
        private final boolean readable;

        /// Whether writes are allowed.
        private final boolean writable;

        /// Whether every write is forced to the current end.
        private final boolean append;

        /// The pending stored body transferred to the archive index after a successful close.
        private final ArkivoStoredContent content;

        /// The seekable channel opened over the pending stored body.
        private final SeekableByteChannel channel;

        /// Whether closing this channel must update the archive index.
        private boolean changed;

        /// Whether this channel is open.
        private boolean channelOpen = true;

        /// Creates a staged update entry channel.
        private UpdateEntryByteChannel(
                String path,
                @Nullable Node originalNode,
                @Nullable Set<PosixFilePermission> initialPermissions,
                ArkivoStoredContent content,
                SeekableByteChannel channel,
                boolean readable,
                boolean writable,
                boolean append,
                boolean forceCommit
        ) throws IOException {
            this.path = Objects.requireNonNull(path, "path");
            this.originalNode = originalNode;
            this.initialPermissions = initialPermissions != null ? Set.copyOf(initialPermissions) : null;
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

        /// Returns the node present when this channel opened.
        private @Nullable Node originalNode() {
            return originalNode;
        }

        /// Returns initial permissions for a new entry.
        private @Nullable @Unmodifiable Set<PosixFilePermission> initialPermissions() {
            return initialPermissions;
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

    /// Records a written entry when its body stream is closed successfully.
    @NotNullByDefault
    private final class WrittenEntryOutputStream extends OutputStream {
        /// The wrapped entry body stream.
        private final OutputStream output;

        /// The normalized path to record after close.
        private final String path;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a recording output stream.
        private WrittenEntryOutputStream(OutputStream output, String path) {
            this.output = Objects.requireNonNull(output, "output");
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Writes one byte to the entry body.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        /// Writes bytes to the entry body.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            output.write(buffer, offset, length);
        }

        /// Flushes the wrapped stream.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Closes the entry body and records the emitted file path.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            output.close();
            closed = true;
            recordWrittenEntry(path, false);
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
                throw new UnsupportedOperationException("TAR output entry channels are forward-only");
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
                throw new UnsupportedOperationException("TAR output entry channels cannot be truncated");
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
                throw new IllegalStateException("TAR directory stream is closed");
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

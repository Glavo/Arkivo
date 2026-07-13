// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.archive.ar.ArArkivoFileSystem;
import org.glavo.arkivo.archive.ar.ArArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.internal.ArkivoFileStoreAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
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
import java.nio.file.NotLinkException;
import java.nio.file.NoSuchFileException;
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

/// Implements an AR archive file system backed by an in-memory index or a forward-only writer.
@NotNullByDefault
public final class ArArkivoFileSystemImpl extends ArArkivoFileSystem {
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS = Set.of("basic", "owner", "posix", "ar");

    /// The marker used when no initial AR mode was requested.
    private static final int UNKNOWN_MODE = -1;

    /// The largest AR member size representable by the fixed-width header field.
    private static final long MAX_MEMBER_SIZE = 9_999_999_999L;

    /// The sentinel used when the source archive size is path-backed or unknown.
    private static final long UNKNOWN_ARCHIVE_SIZE = -1L;

    /// The file system provider that owns this file system.
    private final ArArkivoFileSystemProvider provider;

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
    private final ArArkivoPath rootPath;

    /// The file store view for this archive.
    private final ArFileStore fileStore = new ArFileStore();

    /// Entry nodes by normalized archive path.
    private final Map<String, Node> nodes;

    /// The storage that owns indexed member bodies, or `null` in forward-only write mode.
    private final @Nullable ArkivoEditStorage editStorage;

    /// Indexed member bodies owned by this file system.
    private final Set<ArkivoStoredContent> ownedContents;

    /// The streaming writer used by forward-only write mode, or `null` in read and update modes.
    private final @Nullable ArArkivoStreamingWriter writer;

    /// The target used to publish a rewritten update-mode archive, or `null` outside update mode.
    private final @Nullable ArkivoCommitTarget commitTarget;

    /// Whether this file system is read-only.
    private final boolean readOnly;

    /// Whether this file system edits an existing archive through complete rewrite.
    private final boolean updateMode;

    /// The active update-mode member channel, or `null` when no member is being changed.
    private @Nullable UpdateMemberByteChannel activeUpdateChannel;

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

    /// Creates an AR file system instance.
    private ArArkivoFileSystemImpl(
            ArArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable URI archiveUri,
            @Nullable ArkivoSeekableChannelSource channelSource,
            long sourceArchiveSize,
            ArkivoFileSystemThreadSafety threadSafety,
            Map<String, Node> nodes,
            @Nullable ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            @Nullable ArArkivoStreamingWriter writer,
            boolean readOnly,
            boolean updateMode,
            @Nullable ArkivoCommitTarget commitTarget,
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
        this.commitTarget = commitTarget;
        this.readOnly = readOnly;
        this.updateMode = updateMode;
        this.rootPath = ArArkivoPath.root(this);
        this.writtenDirectories.add("");
        this.editStorageClosed = editStorage == null;
        this.channelSourceClosed = channelSource == null;
    }

    /// Opens an AR file system from an archive path.
    public static ArArkivoFileSystemImpl open(
            ArArkivoFileSystemProvider provider,
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
        if (isArchiveUpdateOpen(openOptions)) {
            validateArchiveUpdateOptions(openOptions);
            if (ArkivoFileSystem.SOURCE_MUTATION_POLICY.isPresent(environment)) {
                throw new UnsupportedOperationException("AR update mode always performs a complete archive rewrite");
            }
            ArkivoEditStorage editStorage = editStorage(environment);
            Set<ArkivoStoredContent> ownedContents = identityContentSet();
            try {
                boolean newArchive = !Files.exists(archivePath);
                Map<String, Node> nodes;
                if (!newArchive) {
                    try (InputStream input = Files.newInputStream(archivePath, StandardOpenOption.READ)) {
                        nodes = readNodes(input, editStorage, ownedContents, environment);
                    }
                } else if (openOptions.contains(StandardOpenOption.CREATE)) {
                    nodes = rootNodes();
                } else {
                    throw new NoSuchFileException(archivePath.toString());
                }
                @Nullable ArkivoCommitTarget commitTarget = ArkivoFileSystem.COMMIT_TARGET.read(environment);
                if (commitTarget == null) {
                    commitTarget = ArkivoCommitTarget.atomicReplace(defaultCommitDirectory(archivePath));
                }
                ArArkivoFileSystemImpl fileSystem = new ArArkivoFileSystemImpl(
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
                throw new UnsupportedOperationException("AR archive writes do not support commit targets");
            }
            OutputStream output = Files.newOutputStream(archivePath, openOptions.toArray(OpenOption[]::new));
            return new ArArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    null,
                    UNKNOWN_ARCHIVE_SIZE,
                    threadSafety,
                    rootNodes(),
                    null,
                    identityContentSet(),
                    ArArkivoStreamingWriter.open(output),
                    false,
                    false,
                    null,
                    closeAction
            );
        }

        validateArchiveReadOptions(openOptions);
        ArkivoEditStorage editStorage = editStorage(environment);
        Set<ArkivoStoredContent> ownedContents = identityContentSet();
        try {
            Map<String, Node> nodes;
            try (InputStream input = Files.newInputStream(archivePath, openOptions.toArray(OpenOption[]::new))) {
                nodes = readNodes(input, editStorage, ownedContents, environment);
            }
            return new ArArkivoFileSystemImpl(
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
                    closeAction
            );
        } catch (IOException | RuntimeException | Error exception) {
            closeOpenedStorage(editStorage, ownedContents, exception);
            throw exception;
        }
    }

    /// Opens an AR file system from a repeatable seekable channel source.
    public static ArArkivoFileSystemImpl open(
            ArArkivoFileSystemProvider provider,
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");

        ArkivoFileSystemThreadSafety threadSafety;
        ArkivoEditStorage editStorage;
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
                    throw new UnsupportedOperationException("AR update mode always performs a complete archive rewrite");
                }
                commitTarget = ArkivoFileSystem.COMMIT_TARGET.read(environment);
                if (commitTarget == null) {
                    throw new IllegalArgumentException(
                            "AR channel-source update mode requires ArkivoFileSystem.COMMIT_TARGET"
                    );
                }
            } else {
                validateArchiveReadOptions(openOptions);
                commitTarget = null;
            }
            editStorage = editStorage(environment);
        } catch (RuntimeException | Error exception) {
            closeSourceAfterOpenFailure(source, exception);
            throw exception;
        }

        Set<ArkivoStoredContent> ownedContents = identityContentSet();
        try {
            long archiveSize;
            Map<String, Node> nodes;
            try (SeekableByteChannel channel = source.openChannel()) {
                archiveSize = channel.size();
                channel.position(0L);
                nodes = readNodes(Channels.newInputStream(channel), editStorage, ownedContents, environment);
            }
            return new ArArkivoFileSystemImpl(
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
    public ArArkivoFileSystemProvider provider() {
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
                    @Nullable UpdateMemberByteChannel updateChannel = activeUpdateChannel;
                    if (updateChannel != null) {
                        try {
                            updateChannel.close();
                        } catch (IOException | RuntimeException | Error exception) {
                            failure = exception;
                        }
                    }
                    @Nullable ArArkivoStreamingWriter currentWriter = writer;
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

    /// Closes indexed member bodies and their owning storage, retaining failed resources for a later close retry.
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

    /// Returns the AR archive path separator.
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

    /// Returns a path inside this AR file system.
    @Override
    public Path getPath(String first, String... more) {
        try (Operation ignored = beginReadOperation()) {
            ensureOpen();
            return ArArkivoPath.of(this, first, more);
        }
    }

    /// Returns a path matcher for AR paths.
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

    /// Returns the AR user principal lookup service.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        try (Operation ignored = beginReadOperation()) {
            return ArPosixSupport.userPrincipalLookupService();
        }
    }

    /// Watch services are not supported by AR file systems.
    @Override
    public java.nio.file.WatchService newWatchService() {
        throw new UnsupportedOperationException("AR watch services are not supported");
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
            if (node.directory()) {
                throw new FileSystemException(path.toString(), null, "AR entry is a directory");
            }
            return manageInputStream(openContentInputStream(node.content()));
        }
    }

    /// Opens a byte channel for a member in the current file system mode.
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

    /// Opens a member byte channel while the caller holds the matching operation lock.
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
        if (node.directory()) {
            throw new FileSystemException(path.toString(), null, "AR entry is a directory");
        }
        return openContentChannel(node.content());
    }

    /// Opens an output stream for a writable member.
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            return manageOutputStream(newOutputStream(path, Set.of(options)));
        }
    }

    /// Opens an output stream for a writable member.
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
                throw new UnsupportedOperationException("AR output streams do not support READ");
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
        int mode = initialMode(false, false, attributes);
        String entryPath = prepareWritableEntry(path, false);
        ArArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginFile(entryPath);
        applyInitialMode(currentWriter, mode);
        return new WrittenEntryOutputStream(currentWriter.openOutputStream(), entryPath);
    }

    /// Creates a new directory member in a writable archive.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            createDirectoryLocked(directory, attributes);
        }
    }

    /// Creates a directory member while the caller holds the exclusive operation lock.
    private void createDirectoryLocked(Path directory, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        int mode = initialMode(true, false, attributes);
        if (updateMode) {
            String entryPath = prepareUpdateMember(directory);
            int effectiveMode = mode != UNKNOWN_MODE ? mode : 040755;
            addUpdateNode(new Node(
                    entryPath,
                    defaultAttributes(entryPath, effectiveMode, 0L),
                    true,
                    null,
                    false
            ));
            return;
        }
        String entryPath = prepareWritableEntry(directory, true);
        ArArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginDirectory(entryPath);
        applyInitialMode(currentWriter, mode);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, true);
    }

    /// Creates a new symbolic link member in a writable archive.
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            createSymbolicLinkLocked(link, target, attributes);
        }
    }

    /// Creates a symbolic link member while the caller holds the exclusive operation lock.
    private void createSymbolicLinkLocked(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        String targetText = archivePathText(target);
        if (targetText.isEmpty()) {
            throw new IllegalArgumentException("AR symbolic link target is empty");
        }
        int mode = initialMode(false, true, attributes);
        if (updateMode) {
            String entryPath = prepareUpdateMember(link);
            byte[] targetBytes = targetText.getBytes(StandardCharsets.UTF_8);
            ArkivoStoredContent content = storeBytes(entryPath, targetBytes);
            int effectiveMode = mode != UNKNOWN_MODE ? mode : 0120777;
            addUpdateNode(new Node(
                    entryPath,
                    defaultAttributes(entryPath, effectiveMode, targetBytes.length),
                    false,
                    content,
                    false
            ));
            return;
        }
        String entryPath = prepareWritableEntry(link, false);
        ArArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginSymbolicLink(entryPath, targetText);
        applyInitialMode(currentWriter, mode);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, false);
    }

    /// Deletes one member from an update-mode archive.
    public void delete(Path path) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            deleteLocked(path);
        }
    }

    /// Deletes one member while the caller holds the exclusive operation lock.
    private void deleteLocked(Path path) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        String entryPath = normalizedNodePath(path);
        if (entryPath.isEmpty()) {
            throw new FileSystemException(path.toString(), null, "The AR root cannot be deleted");
        }
        Node node = requireNode(path);
        if (node.directory() && !node.children().isEmpty()) {
            throw new DirectoryNotEmptyException(path.toString());
        }

        nodes.remove(entryPath);
        @Nullable Node parent = nodes.get(parentPath(entryPath));
        if (parent != null) {
            parent.children().remove(fileName(entryPath));
        }
        dirty = true;
    }

    /// Moves one member and any descendants inside an update-mode archive.
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            moveLocked(source, target, options);
        }
    }

    /// Moves one member while the caller holds the exclusive operation lock.
    private void moveLocked(Path source, Path target, CopyOption... options) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(source);
        boolean replaceExisting = false;
        for (CopyOption option : options) {
            if (option == StandardCopyOption.REPLACE_EXISTING) {
                replaceExisting = true;
            } else if (option != StandardCopyOption.ATOMIC_MOVE) {
                throw new UnsupportedOperationException("Unsupported AR move option: " + option);
            }
        }

        String sourcePath = normalizedNodePath(source);
        String targetPath = normalizedNodePath(target);
        if (sourcePath.isEmpty() || targetPath.isEmpty()) {
            throw new FileSystemException(source.toString(), target.toString(), "The AR root cannot be moved");
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
                throw new FileSystemException(source.toString(), target.toString(), "Source and target member types differ");
            }
            if (targetNode.directory() && !targetNode.children().isEmpty()) {
                throw new DirectoryNotEmptyException(target.toString());
            }
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
            ArArkivoEntryAttributes attributes = copyAttributes(node.attributes(), newPath, node.contentSize());
            rebuilt.put(newPath, new Node(
                    newPath,
                    attributes,
                    node.directory(),
                    node.content(),
                    node.syntheticDirectory()
            ));
        }
        rebuildChildren(rebuilt);
        nodes.clear();
        nodes.putAll(rebuilt);
        dirty = true;
    }

    /// Updates one named member attribute in update mode.
    public void setAttribute(Path path, String attribute, @Nullable Object value, LinkOption... options)
            throws IOException {
        try (Operation ignored = beginWriteOperation()) {
            setAttributeLocked(path, attribute, value, options);
        }
    }

    /// Updates one named member attribute while the caller holds the exclusive operation lock.
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
                    throw new IllegalArgumentException("Unsupported AR owner attribute: " + name);
                }
                setOwner(path, owner);
            }
            case "posix" -> setPosixAttribute(path, name, value);
            case "ar" -> setArAttribute(path, name, value);
            default -> throw new UnsupportedOperationException("Unsupported AR attribute view: " + view);
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
            throw new FileSystemException(directory.toString(), null, "AR entry is not a directory");
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

    /// Reads a symbolic link target from an AR archive path.
    public Path readSymbolicLink(Path link) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readSymbolicLinkLocked(link);
        }
    }

    /// Reads a symbolic link target while the caller holds the shared operation lock.
    private Path readSymbolicLinkLocked(Path link) throws IOException {
        requireReadableFileSystem();
        Node node = requireNode(link);
        if (!node.attributes().isSymbolicLink()) {
            throw new NotLinkException(link.toString());
        }
        return getPath(readUtf8(node.content()));
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
        if (type == ArArkivoEntryAttributeView.class) {
            return type.cast(new ArView(path));
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
        ArArkivoEntryAttributes attributes = requireNode(path).attributes();
        if (type == BasicFileAttributes.class || type == ArArkivoEntryAttributes.class || type == PosixFileAttributes.class) {
            return type.cast(attributes);
        }
        throw new UnsupportedOperationException("Unsupported AR attribute type: " + type.getName());
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
        ArArkivoEntryAttributes entryAttributes = requireNode(path).attributes();
        HashMap<String, Object> values = new HashMap<>();
        RequestedAttributes requestedAttributes = RequestedAttributes.parse(attributes);
        boolean all = requestedAttributes.contains("*");
        if (requestedAttributes.ownerView()) {
            putOwnerAttributes(values, (PosixFileAttributes) entryAttributes, requestedAttributes, all);
        } else if (requestedAttributes.posixView()) {
            putPosixAttributes(values, (PosixFileAttributes) entryAttributes, requestedAttributes, all);
        } else if (requestedAttributes.arView()) {
            putArAttributes(values, entryAttributes, requestedAttributes, all);
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

    /// Adds selected AR attributes to the result map.
    private static void putArAttributes(
            Map<String, Object> values,
            ArArkivoEntryAttributes entryAttributes,
            RequestedAttributes requestedAttributes,
            boolean all
    ) {
        putBasicAttributes(values, entryAttributes, requestedAttributes, all);
        if (all || requestedAttributes.contains("path")) {
            values.put("path", entryAttributes.path());
        }
        if (all || requestedAttributes.contains("identifier")) {
            values.put("identifier", entryAttributes.identifier());
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
                throw new UnsupportedOperationException("Unsupported AR archive read option: " + option);
            }
        }
    }

    /// Validates archive open options for forward-only write mode.
    private static void validateArchiveWriteOptions(Set<? extends OpenOption> options) {
        if (!options.contains(StandardOpenOption.WRITE)) {
            throw new IllegalArgumentException("AR archive write mode requires WRITE");
        }
        if (options.contains(StandardOpenOption.READ)) {
            throw new UnsupportedOperationException("AR archive update mode is not supported");
        }
        if (options.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("AR archive append mode is not supported");
        }
        if (!options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                && !options.contains(StandardOpenOption.CREATE_NEW)) {
            throw new UnsupportedOperationException("AR archive write mode requires TRUNCATE_EXISTING or CREATE_NEW");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported AR archive write option: " + option);
            }
        }
    }

    /// Validates archive open options for complete rewrite update mode.
    private static void validateArchiveUpdateOptions(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ
                    && option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.CREATE) {
                throw new UnsupportedOperationException("Unsupported AR archive update option: " + option);
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
            throw new UnsupportedOperationException("AR member update mode is not supported");
        }
        if (options.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("AR member append mode is not supported");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported AR member write option: " + option);
            }
        }
    }

    /// Validates options for a random-access update member channel.
    private static void validateUpdateMemberOptions(Set<? extends OpenOption> options) {
        if (options.contains(StandardOpenOption.APPEND) && options.contains(StandardOpenOption.READ)) {
            throw new IllegalArgumentException("AR member APPEND cannot be combined with READ");
        }
        if (options.contains(StandardOpenOption.APPEND) && options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            throw new IllegalArgumentException("AR member APPEND cannot be combined with TRUNCATE_EXISTING");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ
                    && option != StandardOpenOption.WRITE
                    && option != StandardOpenOption.APPEND
                    && option != StandardOpenOption.CREATE
                    && option != StandardOpenOption.CREATE_NEW
                    && option != StandardOpenOption.TRUNCATE_EXISTING) {
                throw new UnsupportedOperationException("Unsupported AR member update option: " + option);
            }
        }
    }

    /// Returns an AR mode derived from supported initial file attributes, or `UNKNOWN_MODE`.
    private static int initialMode(boolean directory, boolean symbolicLink, FileAttribute<?>... attributes) {
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        if (permissions == null) {
            return UNKNOWN_MODE;
        }
        if (symbolicLink) {
            return ArPosixSupport.symbolicLinkMode(permissions);
        }
        if (directory) {
            return ArPosixSupport.directoryMode(permissions);
        }
        return ArPosixSupport.regularFileMode(permissions);
    }

    /// Applies a supported initial mode to the current pending AR writer member.
    private static void applyInitialMode(ArArkivoStreamingWriter writer, int mode) throws IOException {
        if (mode == UNKNOWN_MODE) {
            return;
        }
        ArArkivoEntryAttributeView view = writer.attributeView(ArArkivoEntryAttributeView.class);
        if (view == null) {
            throw new UnsupportedOperationException("AR writer does not expose AR member attributes");
        }
        view.setMode(mode);
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
                throw new UnsupportedOperationException("Unsupported AR member initial file attribute: " + name);
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

    /// Requires this file system to be in read or update mode.
    private void requireReadableFileSystem() {
        ensureOpen();
        if (!readOnly && !updateMode) {
            throw new UnsupportedOperationException("Forward-only AR write file systems do not expose reads");
        }
    }

    /// Requires this file system to be in write mode.
    private void requireWritableFileSystem() {
        ensureOpen();
        if (readOnly) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Returns the writer for forward-only write mode.
    private ArArkivoStreamingWriter requireWriter() {
        @Nullable ArArkivoStreamingWriter currentWriter = writer;
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
            throw new UnsupportedOperationException("Forward-only AR write file systems do not support mutations");
        }
    }

    /// Opens a seekable channel that stages one update-mode member body.
    private SeekableByteChannel newUpdateByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        requireUpdateMode();
        validateUpdateMemberOptions(options);
        if (activeUpdateChannel != null) {
            throw new FileSystemException(path.toString(), null, "Another AR member update channel is already open");
        }

        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean writable = options.contains(StandardOpenOption.WRITE) || append;
        boolean readable = options.contains(StandardOpenOption.READ);
        if (!readable && !writable) {
            throw new IllegalArgumentException("AR member update channel requires READ, WRITE, or APPEND");
        }

        String entryPath = normalizedNodePath(path);
        if (entryPath.isEmpty()) {
            throw new FileSystemException(path.toString(), null, "The AR root cannot be opened as a file");
        }
        @Nullable Node existing = nodes.get(entryPath);
        boolean create = writable
                && (options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW));
        boolean createNew = options.contains(StandardOpenOption.CREATE_NEW);
        if (existing == null && !create) {
            throw new NoSuchFileException(path.toString());
        }
        if (existing != null && createNew) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (existing != null && existing.directory()) {
            throw new FileSystemException(path.toString(), null, "AR member is a directory");
        }
        if (existing == null) {
            ensureParents(nodes, entryPath);
        }

        int mode = initialMode(false, false, attributes);
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
            UpdateMemberByteChannel channel = new UpdateMemberByteChannel(
                    entryPath,
                    existing,
                    mode,
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

    /// Prepares a previously absent path for an update-mode member.
    private String prepareUpdateMember(Path path) throws IOException {
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

    /// Rejects a mutation while an update member channel is open.
    private void requireNoActiveUpdateChannel(Path path) throws IOException {
        if (activeUpdateChannel != null) {
            throw new FileSystemException(path.toString(), null, "An AR member update channel is still open");
        }
    }

    /// Commits staged member content into the update index.
    private void commitUpdatedMember(UpdateMemberByteChannel channel, ArkivoStoredContent content)
            throws IOException {
        if (activeUpdateChannel == channel) {
            activeUpdateChannel = null;
        }
        @Nullable Node existing = nodes.get(channel.path());
        if (existing != channel.originalNode()) {
            throw new FileSystemException(channel.path(), null, "AR member changed while its update channel was open");
        }

        long size = content.size();
        ArArkivoEntryAttributes base = existing != null
                ? existing.attributes()
                : defaultAttributes(channel.path(), 0100644, size);
        int mode = existing == null && channel.initialMode() != UNKNOWN_MODE
                ? channel.initialMode()
                : base.mode();
        ArArkivoEntryAttributes attributes = new ArNodeAttributes(
                channel.path(),
                archiveIdentifier(channel.path()),
                base.userId(),
                base.groupId(),
                mode,
                size,
                base.lastModifiedTime()
        );
        Node replacement = new Node(channel.path(), attributes, false, content, false);
        if (existing == null) {
            putNode(nodes, replacement);
        } else {
            nodes.put(channel.path(), replacement);
        }
        ownedContents.add(content);
        dirty = true;
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

    /// Returns default metadata for a newly created update-mode member.
    private static ArNodeAttributes defaultAttributes(String path, int mode, long size) {
        return new ArNodeAttributes(
                path,
                archiveIdentifier(path),
                0L,
                0L,
                mode,
                size,
                FileTime.fromMillis(0L)
        );
    }

    /// Returns copied metadata with a changed path and body size.
    private static ArNodeAttributes copyAttributes(
            ArArkivoEntryAttributes attributes,
            String path,
            long size
    ) {
        return new ArNodeAttributes(
                path,
                archiveIdentifier(path),
                attributes.userId(),
                attributes.groupId(),
                attributes.mode(),
                size,
                attributes.lastModifiedTime()
        );
    }

    /// Returns an AR header identifier for a normalized member path.
    private static String archiveIdentifier(String path) {
        boolean standard = !path.startsWith("#1/")
                && !path.equals("/")
                && !path.equals("//")
                && !path.equals("/SYM64/");
        for (int index = 0; standard && index < path.length(); index++) {
            standard = path.charAt(index) <= 0x7f;
        }
        String identifier = path + "/";
        if (standard && identifier.getBytes(StandardCharsets.US_ASCII).length <= 16) {
            return identifier;
        }
        return "#1/" + path.getBytes(StandardCharsets.UTF_8).length;
    }

    /// Returns a replacement node while preserving its directory index and synthetic marker.
    private static Node replaceNodeAttributes(
            Node node,
            ArArkivoEntryAttributes attributes,
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

    /// Requires a path's parent to exist as a directory.
    private void ensureExistingParentDirectory(String path) throws IOException {
        @Nullable Node parent = nodes.get(parentPath(path));
        if (parent == null) {
            throw new NoSuchFileException(parentPath(path));
        }
        if (!parent.directory()) {
            throw new FileSystemException(path, parent.path(), "AR parent member is not a directory");
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
                throw new IOException("AR parent member is missing after move: " + node.path());
            }
            parent.children().put(fileName(node.path()), node.path());
        }
    }

    /// Sets one basic timestamp attribute.
    private void setBasicAttribute(Path path, String name, @Nullable Object value) throws IOException {
        if (!(value instanceof FileTime time)) {
            throw new IllegalArgumentException("AR basic timestamp value must be FileTime: " + name);
        }
        if (!"lastModifiedTime".equals(name)) {
            throw new UnsupportedOperationException("AR stores only lastModifiedTime");
        }
        setTimes(path, time, null, null);
    }

    /// Sets one POSIX attribute.
    private void setPosixAttribute(Path path, String name, @Nullable Object value) throws IOException {
        switch (name) {
            case "lastModifiedTime" -> setBasicAttribute(path, name, value);
            case "lastAccessTime", "creationTime" ->
                    throw new UnsupportedOperationException("AR stores only lastModifiedTime");
            case "owner" -> {
                if (!(value instanceof UserPrincipal owner)) {
                    throw new IllegalArgumentException("AR POSIX owner value must be UserPrincipal");
                }
                setOwner(path, owner);
            }
            case "group" -> {
                if (!(value instanceof GroupPrincipal group)) {
                    throw new IllegalArgumentException("AR POSIX group value must be GroupPrincipal");
                }
                setGroup(path, group);
            }
            case "permissions" -> {
                if (!(value instanceof Set<?> values)) {
                    throw new IllegalArgumentException("AR POSIX permissions value must be Set");
                }
                EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
                for (Object item : values) {
                    if (!(item instanceof PosixFilePermission permission)) {
                        throw new IllegalArgumentException("AR POSIX permissions contain an invalid value");
                    }
                    permissions.add(permission);
                }
                setPermissions(path, permissions);
            }
            default -> throw new UnsupportedOperationException("Unsupported writable AR POSIX attribute: " + name);
        }
    }

    /// Sets one AR-specific metadata attribute.
    private void setArAttribute(Path path, String name, @Nullable Object value) throws IOException {
        switch (name) {
            case "lastModifiedTime" -> setBasicAttribute(path, name, value);
            case "lastAccessTime", "creationTime" ->
                    throw new UnsupportedOperationException("AR stores only lastModifiedTime");
            case "userId" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("AR userId value must be numeric");
                }
                setUserId(path, number.longValue());
            }
            case "groupId" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("AR groupId value must be numeric");
                }
                setGroupId(path, number.longValue());
            }
            case "mode" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("AR mode value must be numeric");
                }
                setMode(path, number.intValue());
            }
            case "size" -> {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("AR size value must be numeric");
                }
                setSize(path, number.longValue());
            }
            default -> throw new UnsupportedOperationException("Unsupported writable AR attribute: " + name);
        }
    }

    /// Sets the stored AR modification time in update mode.
    private void setTimes(
            Path path,
            @Nullable FileTime lastModifiedTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime creationTime
    ) throws IOException {
        if (lastAccessTime != null || creationTime != null) {
            throw new UnsupportedOperationException("AR stores only lastModifiedTime");
        }
        mutateAttributes(path, attributes -> copyWithMetadata(
                attributes,
                attributes.userId(),
                attributes.groupId(),
                attributes.mode(),
                attributes.size(),
                lastModifiedTime != null ? lastModifiedTime : attributes.lastModifiedTime()
        ));
    }

    /// Sets the numeric owner represented by a user principal.
    private void setOwner(Path path, UserPrincipal owner) throws IOException {
        requireUpdateMode();
        setUserId(path, principalId(Objects.requireNonNull(owner, "owner").getName(), "owner"));
    }

    /// Sets the numeric group represented by a group principal.
    private void setGroup(Path path, GroupPrincipal group) throws IOException {
        requireUpdateMode();
        setGroupId(path, principalId(Objects.requireNonNull(group, "group").getName(), "group"));
    }

    /// Parses a non-negative numeric AR principal identifier.
    private static long principalId(String name, String kind) {
        try {
            long value = Long.parseLong(name);
            if (value < 0L) {
                throw new IllegalArgumentException("AR " + kind + " identifier must not be negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("AR " + kind + " principal must have a numeric name", exception);
        }
    }

    /// Sets member permission bits in update mode.
    private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        Objects.requireNonNull(permissions, "permissions");
        mutateAttributes(path, attributes -> copyWithMetadata(
                attributes,
                attributes.userId(),
                attributes.groupId(),
                (attributes.mode() & ~0777) | permissionsMode(permissions),
                attributes.size(),
                attributes.lastModifiedTime()
        ));
    }

    /// Sets the numeric AR user identifier in update mode.
    private void setUserId(Path path, long userId) throws IOException {
        if (userId < 0L) {
            throw new IllegalArgumentException("userId must not be negative");
        }
        mutateAttributes(path, attributes -> copyWithMetadata(
                attributes,
                userId,
                attributes.groupId(),
                attributes.mode(),
                attributes.size(),
                attributes.lastModifiedTime()
        ));
    }

    /// Sets the numeric AR group identifier in update mode.
    private void setGroupId(Path path, long groupId) throws IOException {
        if (groupId < 0L) {
            throw new IllegalArgumentException("groupId must not be negative");
        }
        mutateAttributes(path, attributes -> copyWithMetadata(
                attributes,
                attributes.userId(),
                groupId,
                attributes.mode(),
                attributes.size(),
                attributes.lastModifiedTime()
        ));
    }

    /// Sets the raw AR mode while preserving the member file type.
    private void setMode(Path path, int mode) throws IOException {
        if (mode < 0) {
            throw new IllegalArgumentException("mode must not be negative");
        }
        mutateAttributes(path, attributes -> {
            if (!sameFileType(attributes.mode(), mode)) {
                throw new IllegalArgumentException("AR mode updates cannot change the member file type");
            }
            return copyWithMetadata(
                    attributes,
                    attributes.userId(),
                    attributes.groupId(),
                    mode,
                    attributes.size(),
                    attributes.lastModifiedTime()
            );
        });
    }

    /// Sets the stored AR member body size.
    private void setSize(Path path, long size) throws IOException {
        if (size < 0L || size > MAX_MEMBER_SIZE) {
            throw new IllegalArgumentException("AR member size must be between 0 and " + MAX_MEMBER_SIZE);
        }
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        Node node = requireNode(path);
        if (node.path().isEmpty() || node.syntheticDirectory()) {
            throw new FileSystemException(path.toString(), null, "Synthetic AR directory metadata cannot be changed");
        }
        if (node.directory()) {
            throw new FileSystemException(path.toString(), null, "AR directory bodies cannot be resized");
        }
        if (node.contentSize() == size) {
            return;
        }
        ArkivoStoredContent content = requireEditStorage().createContent(node.path(), size);
        try {
            copyContent(node.content(), content);
            try (SeekableByteChannel channel = content.openChannel(Set.of(StandardOpenOption.WRITE))) {
                long currentSize = channel.size();
                if (size < currentSize) {
                    channel.truncate(size);
                } else if (size > currentSize) {
                    channel.position(size - 1L);
                    ByteBuffer zero = ByteBuffer.wrap(new byte[]{0});
                    while (zero.hasRemaining()) {
                        channel.write(zero);
                    }
                }
            }
            ArArkivoEntryAttributes attributes = copyWithMetadata(
                    node.attributes(),
                    node.attributes().userId(),
                    node.attributes().groupId(),
                    node.attributes().mode(),
                    size,
                    node.attributes().lastModifiedTime()
            );
            nodes.put(node.path(), replaceNodeAttributes(node, attributes, content));
            ownedContents.add(content);
            dirty = true;
        } catch (IOException | RuntimeException | Error exception) {
            releaseStoredContent(content);
            throw exception;
        }
    }

    /// Returns whether two AR modes describe the same member file type.
    private static boolean sameFileType(int firstMode, int secondMode) {
        return ArPosixSupport.isRegularFile(firstMode) == ArPosixSupport.isRegularFile(secondMode)
                && ArPosixSupport.isDirectory(firstMode) == ArPosixSupport.isDirectory(secondMode)
                && ArPosixSupport.isSymbolicLink(firstMode) == ArPosixSupport.isSymbolicLink(secondMode)
                && ArPosixSupport.isOther(firstMode) == ArPosixSupport.isOther(secondMode);
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

    /// Returns copied metadata with selected AR values changed.
    private static ArNodeAttributes copyWithMetadata(
            ArArkivoEntryAttributes attributes,
            long userId,
            long groupId,
            int mode,
            long size,
            FileTime lastModifiedTime
    ) {
        return new ArNodeAttributes(
                attributes.path(),
                archiveIdentifier(attributes.path()),
                userId,
                groupId,
                mode,
                size,
                lastModifiedTime
        );
    }

    /// Replaces one member's metadata through an update function.
    private void mutateAttributes(Path path, AttributeMutation mutation) throws IOException {
        requireUpdateMode();
        requireNoActiveUpdateChannel(path);
        Node node = requireNode(path);
        if (node.path().isEmpty() || node.syntheticDirectory()) {
            throw new FileSystemException(path.toString(), null, "Synthetic AR directory metadata cannot be changed");
        }
        ArArkivoEntryAttributes attributes = mutation.apply(node.attributes());
        nodes.put(node.path(), replaceNodeAttributes(node, attributes, node.content()));
        dirty = true;
    }

    /// Changes an immutable member metadata snapshot.
    @FunctionalInterface
    @NotNullByDefault
    private interface AttributeMutation {
        /// Returns replacement metadata.
        ArArkivoEntryAttributes apply(ArArkivoEntryAttributes attributes);
    }

    /// Prepares a new archive member path for forward-only writing.
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
                    throw new FileSystemException(path, parent, "AR parent member is not a directory");
                }
                writtenDirectories.add(parent);
            }
            separator = path.indexOf('/', separator + 1);
        }
    }

    /// Records a forward-only member after it has been emitted.
    private void recordWrittenEntry(String path, boolean directory) {
        writtenEntries.add(path);
        if (directory) {
            writtenDirectories.add(path);
        }
    }

    /// Checks access to a member in forward-only write mode.
    private void checkWritableAccess(Path path, AccessMode... modes) throws IOException {
        requireWritableKnownPath(path);
        for (AccessMode mode : modes) {
            if (mode == AccessMode.EXECUTE || mode == AccessMode.READ) {
                throw new UnsupportedOperationException("Forward-only AR write file systems do not expose reads");
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
        Node node = nodes.get(normalizedPath);
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        return node;
    }

    /// Returns a normalized node path.
    private String normalizedNodePath(Path path) {
        ArArkivoPath arPath = ArArkivoPath.require(path, this);
        ArArkivoPath normalized = (ArArkivoPath) arPath.toAbsolutePath().normalize();
        return normalized.archivePath();
    }

    /// Validates that only read options are requested.
    private static void validateReadOptions(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("AR file systems are read-only");
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

    /// Streams one archive member body into newly allocated indexed content storage.
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
                ownedContents.add(content);
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

    /// Opens a stream over indexed content, or an empty stream when the member has no body.
    private static InputStream openContentInputStream(@Nullable ArkivoStoredContent content) throws IOException {
        return content != null
                ? Channels.newInputStream(content.openChannel(Set.of(StandardOpenOption.READ)))
                : InputStream.nullInputStream();
    }

    /// Opens a seekable channel over indexed content, or an empty channel when the member has no body.
    private static SeekableByteChannel openContentChannel(@Nullable ArkivoStoredContent content) throws IOException {
        return content != null
                ? content.openChannel(Set.of(StandardOpenOption.READ))
                : new ByteArraySeekableByteChannel(new byte[0]);
    }

    /// Returns the indexed-content storage required outside forward-only write mode.
    private ArkivoEditStorage requireEditStorage() {
        return Objects.requireNonNull(editStorage, "editStorage");
    }

    /// Stores a generated member body in indexed content storage.
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
            ownedContents.add(content);
            return content;
        } catch (IOException | RuntimeException | Error exception) {
            releaseStoredContent(content);
            throw exception;
        }
    }

    /// Reads a UTF-8 member body as path text without an intermediate whole-body byte array.
    private static String readUtf8(@Nullable ArkivoStoredContent content) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream input = openContentInputStream(content);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            while (true) {
                int count = reader.read(buffer);
                if (count < 0) {
                    return text.toString();
                }
                text.append(buffer, 0, count);
            }
        }
    }

    /// Publishes all surviving update nodes through a complete AR rewrite.
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
                 ArArkivoStreamingWriterImpl archiveWriter =
                         new ArArkivoStreamingWriterImpl(Channels.newOutputStream(channel))) {
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

    /// Reads all entry nodes from an AR stream.
    private static Map<String, Node> readNodes(
            InputStream input,
            ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            Map<String, ?> environment
    ) throws IOException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        Node root = new Node("", syntheticDirectoryAttributes("/"), true, null, true);
        nodes.put("", root);

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(input, environment)) {
            while (reader.next()) {
                ArArkivoEntryAttributes attributes = reader.readAttributes(ArArkivoEntryAttributes.class);
                String path = normalizeEntryPath(attributes.path());
                ensureParents(nodes, path);
                @Nullable ArkivoStoredContent content = null;
                if (attributes.size() > 0L) {
                    try (InputStream entryInput = reader.openInputStream()) {
                        content = storeInput(editStorage, ownedContents, path, attributes.size(), entryInput);
                    }
                }
                long contentSize = content != null ? content.size() : 0L;
                ArArkivoEntryAttributes nodeAttributes = copyAttributes(attributes, path, contentSize);
                putNode(nodes, new Node(path, nodeAttributes, nodeAttributes.isDirectory(), content, false));
            }
        }
        return nodes;
    }

    /// Adds implicit parent directory nodes.
    private static void ensureParents(Map<String, Node> nodes, String path) throws IOException {
        int separator = path.indexOf('/');
        while (separator >= 0) {
            String parent = path.substring(0, separator);
            if (!parent.isEmpty() && !nodes.containsKey(parent)) {
                ensureParents(nodes, parent);
                putNode(nodes, new Node(parent, syntheticDirectoryAttributes(parent), true, null, true));
            }
            separator = path.indexOf('/', separator + 1);
        }
    }

    /// Adds or replaces one node in the node map.
    private static void putNode(Map<String, Node> nodes, Node node) throws IOException {
        Node existing = nodes.get(node.path());
        if (existing != null && !(existing.syntheticDirectory() && node.directory())) {
            throw new IOException("Duplicate AR entry path: " + node.path());
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
        String normalized = path;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IOException("AR entry is missing a path");
        }
        return normalized;
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
    private static ArNodeAttributes syntheticDirectoryAttributes(String path) {
        FileTime time = FileTime.fromMillis(0L);
        return new ArNodeAttributes(path, path, 0L, 0L, 040755, 0L, time);
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
            if (!"basic".equals(view) && !"owner".equals(view) && !"posix".equals(view) && !"ar".equals(view)) {
                throw new UnsupportedOperationException("Unsupported AR attribute view: " + view);
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("AR attribute names must not be empty");
            }
            if ("*".equals(names)) {
                return new RequestedAttributes(view, Set.of("*"));
            }
            return new RequestedAttributes(view, Set.of(names.split(",")));
        }

        /// Returns whether this request targets the AR attribute view.
        private boolean arView() {
            return "ar".equals(view);
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

    /// Stores one AR file system node.
    @NotNullByDefault
    private static final class Node {
        /// The normalized archive path.
        private final String path;

        /// The node attributes.
        private final ArArkivoEntryAttributes attributes;

        /// Whether this node is a directory.
        private final boolean directory;

        /// The cached member content.
        private final @Nullable ArkivoStoredContent content;

        /// Whether this is an implicit directory.
        private final boolean syntheticDirectory;

        /// Child node paths keyed by child name.
        private final LinkedHashMap<String, String> children = new LinkedHashMap<>();

        /// Creates one node.
        private Node(
                String path,
                ArArkivoEntryAttributes attributes,
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
        private ArArkivoEntryAttributes attributes() {
            return attributes;
        }

        /// Returns whether this node is a directory.
        private boolean directory() {
            return directory;
        }

        /// Returns the cached member content.
        private @Nullable ArkivoStoredContent content() {
            return content;
        }

        /// Returns the cached member content size.
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

    /// Stores indexed AR member metadata.
    @NotNullByDefault
    private static final class ArNodeAttributes implements ArArkivoEntryAttributes, PosixFileAttributes {
        /// The decoded archive member path.
        private final String path;

        /// The raw header identifier.
        private final String identifier;

        /// The numeric user identifier.
        private final long userId;

        /// The numeric group identifier.
        private final long groupId;

        /// The POSIX mode bits.
        private final int mode;

        /// The node size.
        private final long size;

        /// The last modified time.
        private final FileTime lastModifiedTime;

        /// Creates node attributes.
        private ArNodeAttributes(
                String path,
                String identifier,
                long userId,
                long groupId,
                int mode,
                long size,
                FileTime lastModifiedTime
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.identifier = Objects.requireNonNull(identifier, "identifier");
            this.userId = userId;
            this.groupId = groupId;
            this.mode = mode;
            this.size = size;
            this.lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        }

        /// Returns the decoded archive member path.
        @Override
        public String path() {
            return path;
        }

        /// Returns the raw AR member identifier before long-name resolution.
        @Override
        public String identifier() {
            return identifier;
        }

        /// Returns the numeric user identifier stored by the AR header.
        @Override
        public long userId() {
            return userId;
        }

        /// Returns the numeric group identifier stored by the AR header.
        @Override
        public long groupId() {
            return groupId;
        }

        /// Returns the POSIX mode bits stored by the AR header.
        @Override
        public int mode() {
            return mode;
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

        /// Returns whether this node is a regular file.
        @Override
        public boolean isRegularFile() {
            return ArPosixSupport.isRegularFile(mode);
        }

        /// Returns whether this node is a directory.
        @Override
        public boolean isDirectory() {
            return ArPosixSupport.isDirectory(mode);
        }

        /// Returns whether this node is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return ArPosixSupport.isSymbolicLink(mode);
        }

        /// Returns whether this node has another file type.
        @Override
        public boolean isOther() {
            return ArPosixSupport.isOther(mode);
        }

        /// Returns the node size.
        @Override
        public long size() {
            return size;
        }

        /// Returns an implementation-specific file key.
        @Override
        public Object fileKey() {
            return path;
        }

        /// Returns the owner principal represented by the stored AR user identifier.
        @Override
        public UserPrincipal owner() {
            return ArPosixSupport.owner(userId);
        }

        /// Returns the group principal represented by the stored AR group identifier.
        @Override
        public GroupPrincipal group() {
            return ArPosixSupport.group(groupId);
        }

        /// Returns the POSIX permissions encoded by the stored AR mode bits.
        @Override
        public @Unmodifiable Set<PosixFilePermission> permissions() {
            return ArPosixSupport.permissions(mode);
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
            return ArArkivoFileSystemImpl.this.readAttributes(path, BasicFileAttributes.class);
        }

        /// Sets this path's supported timestamp in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            ArArkivoFileSystemImpl.this.setTimes(path, lastModifiedTime, lastAccessTime, createTime);
        }
    }

    /// Implements an AR attribute view for read and update modes.
    @NotNullByDefault
    private final class ArView implements ArArkivoEntryAttributeView {
        /// The path whose attributes are exposed.
        private final Path path;

        /// Creates an AR attribute view.
        private ArView(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Reads this path's AR attributes.
        @Override
        public ArArkivoEntryAttributes readAttributes() throws IOException {
            return ArArkivoFileSystemImpl.this.readAttributes(path, ArArkivoEntryAttributes.class);
        }

        /// Sets this path's supported timestamp in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            ArArkivoFileSystemImpl.this.setTimes(path, lastModifiedTime, lastAccessTime, createTime);
        }

        /// Sets this path's numeric user identifier in update mode.
        @Override
        public void setUserId(long userId) throws IOException {
            ArArkivoFileSystemImpl.this.setUserId(path, userId);
        }

        /// Sets this path's numeric group identifier in update mode.
        @Override
        public void setGroupId(long groupId) throws IOException {
            ArArkivoFileSystemImpl.this.setGroupId(path, groupId);
        }

        /// Sets this path's raw mode in update mode.
        @Override
        public void setMode(int mode) throws IOException {
            ArArkivoFileSystemImpl.this.setMode(path, mode);
        }

        /// Sets this path's body size in update mode.
        @Override
        public void setSize(long size) throws IOException {
            ArArkivoFileSystemImpl.this.setSize(path, size);
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
            return ArArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class).owner();
        }

        /// Sets this path's numeric owner in update mode.
        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            ArArkivoFileSystemImpl.this.setOwner(path, owner);
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
            return ArArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class);
        }

        /// Returns this path's owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        /// Sets this path's supported timestamp in update mode.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            ArArkivoFileSystemImpl.this.setTimes(path, lastModifiedTime, lastAccessTime, createTime);
        }

        /// Sets this path's numeric owner in update mode.
        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            ArArkivoFileSystemImpl.this.setOwner(path, owner);
        }

        /// Sets this path's numeric group in update mode.
        @Override
        public void setGroup(GroupPrincipal group) throws IOException {
            ArArkivoFileSystemImpl.this.setGroup(path, group);
        }

        /// Sets this path's permissions in update mode.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) throws IOException {
            ArArkivoFileSystemImpl.this.setPermissions(path, permissions);
        }
    }

    /// Implements a simple AR file store.
    @NotNullByDefault
    private final class ArFileStore extends FileStore {
        /// Returns the archive file store name.
        @Override
        public String name() {
            @Nullable Path path = archivePath;
            return path != null ? path.toString() : "ar";
        }

        /// Returns the archive file store type.
        @Override
        public String type() {
            return "ar";
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
                    || type == ArArkivoEntryAttributeView.class;
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

    /// Implements a staged random-access channel for one update-mode member.
    @NotNullByDefault
    private final class UpdateMemberByteChannel implements SeekableByteChannel {
        /// The normalized member path.
        private final String path;

        /// The node present when this channel opened, or `null` for a new member.
        private final @Nullable Node originalNode;

        /// Initial mode applied only when a new member is committed.
        private final int initialMode;

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

        /// Creates a staged update member channel.
        private UpdateMemberByteChannel(
                String path,
                @Nullable Node originalNode,
                int initialMode,
                ArkivoStoredContent content,
                SeekableByteChannel channel,
                boolean readable,
                boolean writable,
                boolean append,
                boolean forceCommit
        ) throws IOException {
            this.path = Objects.requireNonNull(path, "path");
            this.originalNode = originalNode;
            this.initialMode = initialMode;
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

        /// Returns the normalized member path.
        private String path() {
            return path;
        }

        /// Returns the node present when this channel opened.
        private @Nullable Node originalNode() {
            return originalNode;
        }

        /// Returns the initial mode for a new member.
        private int initialMode() {
            return initialMode;
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
            long position = channel.position();
            if (position > MAX_MEMBER_SIZE - source.remaining()) {
                throw new IOException("AR member exceeds the maximum representable size");
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
                    commitUpdatedMember(this, content);
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

    /// Records a written member when its body stream is closed successfully.
    @NotNullByDefault
    private final class WrittenEntryOutputStream extends OutputStream {
        /// The wrapped member body stream.
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

        /// Writes one byte to the member body.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        /// Writes bytes to the member body.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            output.write(buffer, offset, length);
        }

        /// Flushes the wrapped stream.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Closes the member body and records the emitted file path.
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

        /// Reads are not supported for forward-only output members.
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
                throw new UnsupportedOperationException("AR output member channels are forward-only");
            }
            return this;
        }

        /// Returns the current written size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return position;
        }

        /// Truncation is not supported for forward-only output members.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            if (size != position) {
                throw new UnsupportedOperationException("AR output member channels cannot be truncated");
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
                throw new IllegalStateException("AR directory stream is closed");
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

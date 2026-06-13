// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.ar.ArArkivoFileSystem;
import org.glavo.arkivo.ar.ArArkivoFileSystemProvider;
import org.glavo.arkivo.ar.ArArkivoStreamingReader;
import org.glavo.arkivo.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.internal.ArkivoFileStoreAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements an AR archive file system backed by either an in-memory read index or a forward-only writer.
@NotNullByDefault
public final class ArArkivoFileSystemImpl extends ArArkivoFileSystem {
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS = Set.of("basic", "owner", "posix", "ar");

    /// The marker used when no initial AR mode was requested.
    private static final int UNKNOWN_MODE = -1;

    /// The file system provider that owns this file system.
    private final ArArkivoFileSystemProvider provider;

    /// The source archive path.
    private final Path archivePath;

    /// The archive URI used by generated entry URIs.
    private final URI archiveUri;

    /// The action invoked when this file system closes.
    private final Runnable closeAction;

    /// The synthetic root path.
    private final ArArkivoPath rootPath;

    /// The file store view for this archive.
    private final ArFileStore fileStore = new ArFileStore();

    /// Entry nodes by normalized archive path.
    private final @Unmodifiable Map<String, Node> nodes;

    /// The streaming writer used by forward-only write mode, or `null` in read mode.
    private final @Nullable ArArkivoStreamingWriter writer;

    /// Whether this file system is read-only.
    private final boolean readOnly;

    /// Archive paths already emitted by forward-only write mode.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// Directory paths already emitted or implied by forward-only write mode.
    private final HashSet<String> writtenDirectories = new HashSet<>();

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Creates an AR file system instance.
    private ArArkivoFileSystemImpl(
            ArArkivoFileSystemProvider provider,
            Path archivePath,
            URI archiveUri,
            ArkivoFileSystemThreadSafety threadSafety,
            Map<String, Node> nodes,
            @Nullable ArArkivoStreamingWriter writer,
            boolean readOnly,
            Runnable closeAction
    ) {
        super(threadSafety);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.archiveUri = Objects.requireNonNull(archiveUri, "archiveUri");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.nodes = Map.copyOf(nodes);
        this.writer = writer;
        this.readOnly = readOnly;
        this.rootPath = ArArkivoPath.root(this);
        this.writtenDirectories.add("");
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
                    threadSafety,
                    rootNodes(),
                    ArArkivoStreamingWriter.open(output),
                    false,
                    closeAction
            );
        }

        validateArchiveReadOptions(openOptions);
        try (InputStream input = Files.newInputStream(archivePath, openOptions.toArray(OpenOption[]::new))) {
            return new ArArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    threadSafety,
                    readNodes(input),
                    null,
                    true,
                    closeAction
            );
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
        if (open) {
            open = false;
            Throwable failure = null;
            ArArkivoStreamingWriter currentWriter = writer;
            if (currentWriter != null) {
                try {
                    currentWriter.close();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            try {
                closeAction.run();
            } catch (RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
            throwFailure(failure);
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
        ensureOpen();
        return List.of(rootPath);
    }

    /// Returns file stores for this file system.
    @Override
    public Iterable<FileStore> getFileStores() {
        ensureOpen();
        return List.of(fileStore);
    }

    /// Returns supported attribute view names.
    @Override
    public Set<String> supportedFileAttributeViews() {
        return SUPPORTED_ATTRIBUTE_VIEWS;
    }

    /// Returns a path inside this AR file system.
    @Override
    public Path getPath(String first, String... more) {
        ensureOpen();
        return ArArkivoPath.of(this, first, more);
    }

    /// Returns a path matcher for AR paths.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
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
        return ArPosixSupport.userPrincipalLookupService();
    }

    /// Watch services are not supported by AR file systems.
    @Override
    public java.nio.file.WatchService newWatchService() {
        throw new UnsupportedOperationException("AR watch services are not supported");
    }

    /// Returns the archive URI used by generated entry URIs.
    URI archiveUri() {
        return archiveUri;
    }

    /// Opens an input stream for an entry.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        requireReadableFileSystem();
        validateReadOptions(Set.of(options));
        Node node = requireNode(path);
        if (node.directory()) {
            throw new FileSystemException(path.toString(), null, "AR entry is a directory");
        }
        return new ByteArrayInputStream(node.content());
    }

    /// Opens a read-only byte channel for an entry.
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        if (!readOnly && requestsWrite(options)) {
            return new WritableEntryByteChannel(newOutputStream(path, options, attributes));
        }

        requireReadableFileSystem();
        validateReadOptions(options);
        Node node = requireNode(path);
        if (node.directory()) {
            throw new FileSystemException(path.toString(), null, "AR entry is a directory");
        }
        return new ByteArraySeekableByteChannel(node.content());
    }

    /// Opens an output stream for a new forward-only member.
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return newOutputStream(path, Set.of(options));
    }

    /// Opens an output stream for a new forward-only member.
    private OutputStream newOutputStream(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        validateEntryWriteOptions(options);
        int mode = initialMode(false, false, attributes);
        String entryPath = prepareWritableEntry(path, false);
        ArArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginFile(entryPath);
        applyInitialMode(currentWriter, mode);
        return new WrittenEntryOutputStream(currentWriter.openOutputStream(), entryPath);
    }

    /// Creates a new forward-only directory member.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        int mode = initialMode(true, false, attributes);
        String entryPath = prepareWritableEntry(directory, true);
        ArArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginDirectory(entryPath);
        applyInitialMode(currentWriter, mode);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, true);
    }

    /// Creates a new forward-only symbolic link member.
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        int mode = initialMode(false, true, attributes);
        String entryPath = prepareWritableEntry(link, false);
        ArArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginSymbolicLink(entryPath, archivePathText(target));
        applyInitialMode(currentWriter, mode);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, false);
    }

    /// Opens a directory stream for an entry.
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
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
        if (readOnly) {
            requireNode(path);
        } else {
            requireWritableKnownPath(path);
        }
        return fileStore;
    }

    /// Reads a symbolic link target from an AR archive path.
    public Path readSymbolicLink(Path link) throws IOException {
        requireReadableFileSystem();
        Node node = requireNode(link);
        if (!node.attributes().isSymbolicLink()) {
            throw new NotLinkException(link.toString());
        }
        return getPath(new String(node.content(), StandardCharsets.UTF_8));
    }

    /// Returns an attribute view for a path.
    public <V extends java.nio.file.attribute.FileAttributeView> @Nullable V getFileAttributeView(
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

    /// Requires this file system to be in read mode.
    private void requireReadableFileSystem() {
        if (!readOnly) {
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

    /// Returns the writer for write mode.
    private ArArkivoStreamingWriter requireWriter() {
        ArArkivoStreamingWriter currentWriter = writer;
        if (currentWriter == null) {
            throw new ReadOnlyFileSystemException();
        }
        return currentWriter;
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
        return Map.of("", new Node("", syntheticDirectoryAttributes("/"), true, new byte[0], true));
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
    private static Map<String, Node> readNodes(InputStream input) throws IOException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        Node root = new Node("", syntheticDirectoryAttributes("/"), true, new byte[0], true);
        nodes.put("", root);

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(input)) {
            while (reader.next()) {
                ArArkivoEntryAttributes attributes = reader.readAttributes(ArArkivoEntryAttributes.class);
                String path = normalizeEntryPath(attributes.path());
                ensureParents(nodes, path);
                byte[] content;
                try (InputStream entryInput = reader.openInputStream()) {
                    content = entryInput.readAllBytes();
                }
                putNode(nodes, new Node(path, attributes, attributes.isDirectory(), content, false));
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
                putNode(nodes, new Node(parent, syntheticDirectoryAttributes(parent), true, new byte[0], true));
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
        return new ArNodeAttributes(path, path, 0L, 0L, 040755, 0L, time, true);
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
        private final byte @Unmodifiable [] content;

        /// Whether this is an implicit directory.
        private final boolean syntheticDirectory;

        /// Child node paths keyed by child name.
        private final LinkedHashMap<String, String> children = new LinkedHashMap<>();

        /// Creates one node.
        private Node(
                String path,
                ArArkivoEntryAttributes attributes,
                boolean directory,
                byte @Unmodifiable [] content,
                boolean syntheticDirectory
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.attributes = Objects.requireNonNull(attributes, "attributes");
            this.directory = directory;
            this.content = Objects.requireNonNull(content, "content").clone();
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
        private byte @Unmodifiable [] content() {
            return content;
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

    /// Stores attributes for synthetic AR file system nodes.
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

        /// Whether this node is a directory.
        private final boolean directory;

        /// Creates node attributes.
        private ArNodeAttributes(
                String path,
                String identifier,
                long userId,
                long groupId,
                int mode,
                long size,
                FileTime lastModifiedTime,
                boolean directory
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.identifier = Objects.requireNonNull(identifier, "identifier");
            this.userId = userId;
            this.groupId = groupId;
            this.mode = mode;
            this.size = size;
            this.lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
            this.directory = directory;
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
            return !directory;
        }

        /// Returns whether this node is a directory.
        @Override
        public boolean isDirectory() {
            return directory;
        }

        /// Returns whether this node is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        /// Returns whether this node has another file type.
        @Override
        public boolean isOther() {
            return false;
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
            return ArArkivoFileSystemImpl.this.readAttributes(path, BasicFileAttributes.class);
        }

        /// AR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a read-only AR attribute view.
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

        /// AR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }

        /// AR attributes are read-only.
        @Override
        public void setUserId(long userId) {
            throw new ReadOnlyFileSystemException();
        }

        /// AR attributes are read-only.
        @Override
        public void setGroupId(long groupId) {
            throw new ReadOnlyFileSystemException();
        }

        /// AR attributes are read-only.
        @Override
        public void setMode(int mode) {
            throw new ReadOnlyFileSystemException();
        }

        /// AR attributes are read-only.
        @Override
        public void setSize(long size) {
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
            return ArArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class).owner();
        }

        /// AR attributes are read-only.
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
            return ArArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class);
        }

        /// Returns this path's owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        /// AR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }

        /// AR attributes are read-only.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new ReadOnlyFileSystemException();
        }

        /// AR attributes are read-only.
        @Override
        public void setGroup(GroupPrincipal group) {
            Objects.requireNonNull(group, "group");
            throw new ReadOnlyFileSystemException();
        }

        /// AR attributes are read-only.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a simple AR file store.
    @NotNullByDefault
    private final class ArFileStore extends FileStore {
        /// Returns the archive file store name.
        @Override
        public String name() {
            return archivePath.toString();
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
            return Files.size(archivePath);
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

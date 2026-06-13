// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.tar.TarArkivoEntryAttributeView;
import org.glavo.arkivo.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.tar.TarArkivoFileSystem;
import org.glavo.arkivo.tar.TarArkivoFileSystemProvider;
import org.glavo.arkivo.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.tar.TarArkivoStreamingWriter;
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
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
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

/// Implements a TAR archive file system backed by either an in-memory read index or a forward-only writer.
@NotNullByDefault
public final class TarArkivoFileSystemImpl extends TarArkivoFileSystem {
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS = Set.of("basic", "owner", "posix", "tar");

    /// The file system provider that owns this file system.
    private final TarArkivoFileSystemProvider provider;

    /// The source archive path.
    private final Path archivePath;

    /// The archive URI used by generated entry URIs.
    private final URI archiveUri;

    /// The action invoked when this file system closes.
    private final Runnable closeAction;

    /// The synthetic root path.
    private final TarArkivoPath rootPath;

    /// The file store view for this archive.
    private final TarFileStore fileStore = new TarFileStore();

    /// Entry nodes by normalized archive path.
    private final @Unmodifiable Map<String, Node> nodes;

    /// The streaming writer used by forward-only write mode, or `null` in read mode.
    private final @Nullable TarArkivoStreamingWriter writer;

    /// Whether this file system is read-only.
    private final boolean readOnly;

    /// Archive paths already emitted by forward-only write mode.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// Directory paths already emitted or implied by forward-only write mode.
    private final HashSet<String> writtenDirectories = new HashSet<>();

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Creates a TAR file system instance.
    private TarArkivoFileSystemImpl(
            TarArkivoFileSystemProvider provider,
            Path archivePath,
            URI archiveUri,
            ArkivoFileSystemThreadSafety threadSafety,
            Map<String, Node> nodes,
            @Nullable TarArkivoStreamingWriter writer,
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
        this.rootPath = TarArkivoPath.root(this);
        this.writtenDirectories.add("");
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
        if (isWriteArchiveOpen(openOptions)) {
            validateArchiveWriteOptions(openOptions);
            if (ArkivoFileSystem.COMMIT_TARGET.read(environment) != null) {
                throw new UnsupportedOperationException("TAR archive writes do not support commit targets");
            }
            OutputStream output = Files.newOutputStream(archivePath, openOptions.toArray(OpenOption[]::new));
            return new TarArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    threadSafety,
                    rootNodes(),
                    TarArkivoStreamingWriter.open(output),
                    false,
                    closeAction
            );
        }

        validateArchiveReadOptions(openOptions);
        try (InputStream input = Files.newInputStream(archivePath, openOptions.toArray(OpenOption[]::new))) {
            return new TarArkivoFileSystemImpl(
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
    public TarArkivoFileSystemProvider provider() {
        return provider;
    }

    /// Closes this file system.
    @Override
    public void close() throws IOException {
        if (open) {
            open = false;
            Throwable failure = null;
            TarArkivoStreamingWriter currentWriter = writer;
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

    /// Returns the TAR archive path separator.
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

    /// Returns a path inside this TAR file system.
    @Override
    public Path getPath(String first, String... more) {
        ensureOpen();
        return TarArkivoPath.of(this, first, more);
    }

    /// Returns a path matcher for TAR paths.
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

    /// Returns the TAR user principal lookup service.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return TarPosixSupport.userPrincipalLookupService();
    }

    /// Watch services are not supported by TAR file systems.
    @Override
    public java.nio.file.WatchService newWatchService() {
        throw new UnsupportedOperationException("TAR watch services are not supported");
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
        if (!node.attributes().isRegularFile()) {
            throw new FileSystemException(path.toString(), null, "TAR entry is not a regular file");
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
        if (!node.attributes().isRegularFile()) {
            throw new FileSystemException(path.toString(), null, "TAR entry is not a regular file");
        }
        return new ByteArraySeekableByteChannel(node.content());
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
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        String entryPath = prepareWritableEntry(path, false);
        TarArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginFile(entryPath);
        applyInitialPermissions(currentWriter, permissions);
        return new WrittenEntryOutputStream(currentWriter.openOutputStream(), entryPath);
    }

    /// Creates a new forward-only directory entry.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        String entryPath = prepareWritableEntry(directory, true);
        TarArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginDirectory(entryPath);
        applyInitialPermissions(currentWriter, permissions);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, true);
    }

    /// Creates a new forward-only symbolic link entry.
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attributes) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attributes, "attributes");
        requireWritableFileSystem();
        @Nullable Set<PosixFilePermission> permissions = initialPosixPermissions(attributes);
        String entryPath = prepareWritableEntry(link, false);
        TarArkivoStreamingWriter currentWriter = requireWriter();
        currentWriter.beginSymbolicLink(entryPath, archivePathText(target));
        applyInitialPermissions(currentWriter, permissions);
        currentWriter.endEntry();
        recordWrittenEntry(entryPath, false);
    }

    /// Creates a new forward-only hard link entry.
    public void createLink(Path link, Path existing) throws IOException {
        Objects.requireNonNull(existing, "existing");
        requireWritableFileSystem();
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

    /// Opens a directory stream for an entry.
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
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

    /// Reads a symbolic link target.
    public Path readSymbolicLink(Path link) throws IOException {
        requireReadableFileSystem();
        Node node = requireNode(link);
        String linkName = node.attributes().linkName();
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
        if (!readOnly) {
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
        TarArkivoStreamingWriter currentWriter = writer;
        if (currentWriter == null) {
            throw new ReadOnlyFileSystemException();
        }
        return currentWriter;
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
        Node node = nodes.get(normalizedPath);
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

    /// Reads all entry nodes from a TAR stream.
    private static Map<String, Node> readNodes(InputStream input) throws IOException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        nodes.put("", new Node("", syntheticDirectoryAttributes("/"), true, new byte[0], true));

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(input)) {
            while (reader.next()) {
                TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
                String path = normalizeEntryPath(attributes.path());
                ensureParents(nodes, path);
                TarArkivoEntryAttributes nodeAttributes = attributes;
                byte[] content = new byte[0];
                if (attributes.isHardLink()) {
                    String targetPath = normalizeHardLinkTargetPath(attributes);
                    Node target = nodes.get(targetPath);
                    if (target == null || !target.attributes().isRegularFile()) {
                        throw new IOException("TAR hard link target is not available: " + targetPath);
                    }
                    content = target.content();
                    nodeAttributes = attributesWithSize(attributes, content.length);
                } else if (attributes.isRegularFile()) {
                    try (InputStream entryInput = reader.openInputStream()) {
                        content = entryInput.readAllBytes();
                    }
                }
                putNode(nodes, new Node(path, nodeAttributes, nodeAttributes.isDirectory(), content, false));
            }
        }
        return nodes;
    }

    /// Normalizes the archive-local target path for a TAR hard link.
    private static String normalizeHardLinkTargetPath(TarArkivoEntryAttributes attributes) throws IOException {
        String linkName = attributes.linkName();
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
                Node existing = nodes.get(parent);
                if (existing == null) {
                    ensureParents(nodes, parent);
                    putNode(nodes, new Node(parent, syntheticDirectoryAttributes(parent), true, new byte[0], true));
                } else if (!existing.directory()) {
                    throw new IOException("TAR parent entry is not a directory: " + parent);
                }
            }
            separator = path.indexOf('/', separator + 1);
        }
    }

    /// Adds or replaces one node in the node map.
    private static void putNode(Map<String, Node> nodes, Node node) throws IOException {
        Node existing = nodes.get(node.path());
        if (existing != null && !(existing.syntheticDirectory() && node.directory())) {
            throw new IOException("Duplicate TAR entry path: " + node.path());
        }
        @Nullable LinkedHashMap<String, String> existingChildren = existing != null ? existing.children() : null;
        if (!node.path().isEmpty()) {
            Node parent = nodes.get(parentPath(node.path()));
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
        private final byte @Unmodifiable [] content;

        /// Whether this is an implicit directory.
        private final boolean syntheticDirectory;

        /// Child node paths keyed by child name.
        private final LinkedHashMap<String, String> children = new LinkedHashMap<>();

        /// Creates one node.
        private Node(
                String path,
                TarArkivoEntryAttributes attributes,
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
        private TarArkivoEntryAttributes attributes() {
            return attributes;
        }

        /// Returns whether this node is a directory.
        private boolean directory() {
            return directory;
        }

        /// Returns the cached file content.
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
            return TarArkivoFileSystemImpl.this.readAttributes(path, BasicFileAttributes.class);
        }

        /// TAR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a read-only TAR attribute view.
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

        /// TAR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setUserId(long userId) {
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setGroupId(long groupId) {
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setMode(int mode) {
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setUserName(@Nullable String userName) {
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setGroupName(@Nullable String groupName) {
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
            return TarArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class).owner();
        }

        /// TAR attributes are read-only.
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
            return TarArkivoFileSystemImpl.this.readAttributes(path, PosixFileAttributes.class);
        }

        /// Returns this path's owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        /// TAR attributes are read-only.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setGroup(GroupPrincipal group) {
            Objects.requireNonNull(group, "group");
            throw new ReadOnlyFileSystemException();
        }

        /// TAR attributes are read-only.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            throw new ReadOnlyFileSystemException();
        }
    }

    /// Implements a simple TAR file store.
    @NotNullByDefault
    private final class TarFileStore extends FileStore {
        /// Returns the archive file store name.
        @Override
        public String name() {
            return archivePath.toString();
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

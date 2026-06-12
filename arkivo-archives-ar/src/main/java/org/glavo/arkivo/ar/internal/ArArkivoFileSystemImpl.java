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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements a read-only AR archive file system backed by an in-memory member index.
@NotNullByDefault
public final class ArArkivoFileSystemImpl extends ArArkivoFileSystem {
    /// The supported file attribute view names.
    private static final @Unmodifiable Set<String> SUPPORTED_ATTRIBUTE_VIEWS = Set.of("basic", "ar");

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

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Creates an AR file system instance.
    private ArArkivoFileSystemImpl(
            ArArkivoFileSystemProvider provider,
            Path archivePath,
            URI archiveUri,
            ArkivoFileSystemThreadSafety threadSafety,
            Map<String, Node> nodes,
            Runnable closeAction
    ) {
        super(threadSafety);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.archiveUri = Objects.requireNonNull(archiveUri, "archiveUri");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.nodes = Map.copyOf(nodes);
        this.rootPath = ArArkivoPath.root(this);
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
        for (OpenOption option : openOptions) {
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("AR archive file systems are read-only");
            }
        }

        try (InputStream input = Files.newInputStream(archivePath, openOptions.toArray(OpenOption[]::new))) {
            return new ArArkivoFileSystemImpl(
                    provider,
                    archivePath,
                    archiveUri,
                    threadSafety,
                    readNodes(input),
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
    public void close() {
        if (open) {
            open = false;
            closeAction.run();
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

    /// User principal lookup is not supported by AR file systems.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("AR user principal lookup is not supported");
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
        validateReadOptions(options);
        Node node = requireNode(path);
        if (node.directory()) {
            throw new FileSystemException(path.toString(), null, "AR entry is a directory");
        }
        return new ByteArraySeekableByteChannel(node.content());
    }

    /// Opens a directory stream for an entry.
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
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
        requireNode(path);
        for (AccessMode mode : modes) {
            if (mode != AccessMode.READ) {
                throw new ReadOnlyFileSystemException();
            }
        }
    }

    /// Returns this archive's file store.
    public FileStore fileStore(Path path) throws IOException {
        requireNode(path);
        return fileStore;
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
        if (type == ArArkivoEntryAttributeView.class) {
            return type.cast(new ArView(path));
        }
        return null;
    }

    /// Reads attributes for a path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        ArArkivoEntryAttributes attributes = requireNode(path).attributes();
        if (type == BasicFileAttributes.class || type == ArArkivoEntryAttributes.class) {
            return type.cast(attributes);
        }
        throw new UnsupportedOperationException("Unsupported AR attribute type: " + type.getName());
    }

    /// Reads named attributes for a path.
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(options, "options");
        ArArkivoEntryAttributes entryAttributes = requireNode(path).attributes();
        HashMap<String, Object> values = new HashMap<>();
        RequestedAttributes requestedAttributes = RequestedAttributes.parse(attributes);
        boolean all = requestedAttributes.contains("*");
        if (requestedAttributes.arView()) {
            putArAttributes(values, entryAttributes, requestedAttributes, all);
        } else {
            putBasicAttributes(values, entryAttributes, requestedAttributes, all);
        }
        return Collections.unmodifiableMap(values);
    }

    /// Adds selected basic attributes to the result map.
    private static void putBasicAttributes(
            Map<String, Object> values,
            ArArkivoEntryAttributes entryAttributes,
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

    /// Requires this file system to be open.
    private void ensureOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
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
                putNode(nodes, new Node(path, attributes, false, content, false));
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
            if (!"basic".equals(view) && !"ar".equals(view)) {
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
    private static final class ArNodeAttributes implements ArArkivoEntryAttributes {
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
        public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
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
        public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
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
            return true;
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
            return type == BasicFileAttributeView.class || type == ArArkivoEntryAttributeView.class;
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
        public Object getAttribute(String attribute) {
            throw new UnsupportedOperationException("AR file store attributes are not supported");
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

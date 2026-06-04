// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemEntryStream;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipEncryption;
import org.glavo.arkivo.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/// Implements ZIP archive file system state and operations.
@NotNullByDefault
public final class ZipArkivoFileSystemImpl extends ZipArkivoFileSystem {
    /// The ZIP local file header signature.
    private static final int ZIP_LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;

    /// The minimum ZIP local file header size.
    private static final int ZIP_LOCAL_FILE_HEADER_MIN_SIZE = 30;

    /// The file name length field offset inside a local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET = 26;

    /// The extra field length field offset inside a local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET = 28;

    /// The buffer size used when scanning for the first local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_SCAN_BUFFER_SIZE = 8192;

    /// The marker value used by ZIP records when a 32-bit unsigned field is stored in ZIP64 metadata.
    private static final long ZIP_UINT32_MAX = 0xffff_ffffL;

    /// The ZIP end of central directory signature.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;

    /// The ZIP central directory file header signature.
    private static final int ZIP_CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50;

    /// The ZIP64 end of central directory record signature.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50;

    /// The ZIP64 end of central directory locator signature.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064b50;

    /// The minimum ZIP end of central directory record size.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 22;

    /// The minimum ZIP central directory file header size.
    private static final int ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE = 46;

    /// The ZIP64 end of central directory locator size.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE = 20;

    /// The minimum ZIP64 end of central directory record size.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 56;

    /// The general purpose bit flag that marks encrypted entries.
    private static final int ZIP_ENCRYPTED_FLAG = 1;

    /// The largest ZIP comment size stored in the end of central directory record.
    private static final int ZIP_COMMENT_MAX_SIZE = 0xffff;

    /// The largest byte range that must be scanned to find the end of central directory record.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_MAX_SEARCH =
            ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE + ZIP_COMMENT_MAX_SIZE;

    /// The central directory size field offset inside the end of central directory record.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_SIZE_OFFSET = 12;

    /// The central directory offset field offset inside the end of central directory record.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_OFFSET_OFFSET = 16;

    /// The comment length field offset inside the end of central directory record.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_COMMENT_LENGTH_OFFSET = 20;

    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The archive path backing this file system, or `null` for split volume sources.
    private final @Nullable Path archivePath;

    /// The split volume source backing this file system, or `null` for single archive paths.
    private final @Nullable ArkivoVolumeSource volumes;

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The callback invoked after this file system closes, or `null` when no callback is needed.
    private final @Nullable Runnable closeAction;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// The cached preamble size, or `-1` when it has not been located yet.
    private volatile long preambleSize = -1;

    /// The parsed ZIP central directory index, or `null` when it has not been loaded yet.
    private volatile @Nullable ZipIndex index;

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Creates a ZIP archive file system instance.
    public ZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            ZipArkivoFileSystemConfig config
    ) {
        this(provider, archivePath, volumes, config, null);
    }

    /// Creates a ZIP archive file system instance with a close callback.
    public ZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            ZipArkivoFileSystemConfig config,
            @Nullable Runnable closeAction
    ) {
        super(config.storageAccess());
        if (archivePath == null && volumes == null) {
            throw new IllegalArgumentException("archivePath or volumes must be set");
        }
        if (archivePath != null && volumes != null) {
            throw new IllegalArgumentException("archivePath and volumes cannot both be set");
        }
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = archivePath;
        this.volumes = volumes;
        this.config = Objects.requireNonNull(config, "config");
        this.closeAction = closeAction;
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Returns the number of bytes stored before the ZIP archive body.
    @Override
    public long preambleSize() throws IOException {
        checkOpen();
        long cachedSize = preambleSize;
        if (cachedSize >= 0) {
            return cachedSize;
        }

        try (SeekableByteChannel channel = openArchiveChannel()) {
            long locatedSize = locatePreambleSize(channel);
            preambleSize = locatedSize;
            return locatedSize;
        }
    }

    /// Opens a read-only channel over the bytes stored before the ZIP archive body.
    @Override
    public SeekableByteChannel openPreambleChannel() throws IOException {
        checkOpen();
        SeekableByteChannel channel = openArchiveChannel();
        boolean completed = false;
        try {
            long size = preambleSize;
            if (size < 0) {
                size = locatePreambleSize(channel);
                preambleSize = size;
            }
            completed = true;
            return new BoundedSeekableByteChannel(channel, size);
        } finally {
            if (!completed) {
                channel.close();
            }
        }
    }

    /// Returns the archive URI used by ZIP path URI conversion, or `null` for volume-backed file systems.
    @Nullable URI archiveUri() {
        return archivePath != null ? archivePath.toUri().normalize() : null;
    }

    /// Opens a forward-only stream over ZIP entry paths in storage order.
    @Override
    public ArkivoFileSystemEntryStream openEntryStream() throws IOException {
        ZipIndex loadedIndex = index();
        ArrayList<Path> paths = new ArrayList<>(loadedIndex.storageEntries.size());
        for (ZipEntryRecord entry : loadedIndex.storageEntries) {
            paths.add(getPath("/" + entry.key));
        }
        return new EntryPathStream(List.copyOf(paths));
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and any owned volume source.
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
            Runnable action = closeAction;
            if (action != null) {
                action.run();
            }
        }
    }

    /// Returns whether this ZIP file system is open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Returns whether this ZIP file system rejects write operations.
    @Override
    public boolean isReadOnly() {
        return !config.storageAccess().writable();
    }

    /// Returns the ZIP entry path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the root directory path.
    @Override
    public @Unmodifiable Iterable<Path> getRootDirectories() {
        return List.of(rootPath);
    }

    /// Returns the file stores exposed by this ZIP file system.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        return List.of(fileStore());
    }

    /// Returns the supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "zip");
    }

    /// Returns a path inside this ZIP file system.
    @Override
    public Path getPath(String first, String... more) {
        checkOpen();
        return ZipArkivoPath.of(this, first, more);
    }

    /// Opens a byte channel for an entry path.
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireReadOnlyOptions(options);
        ZipEntryRecord entry = requireReadableEntry(path);
        long dataOffset = dataOffset(entry);
        if (entry.method == ZipMethod.STORED_ID) {
            return new BoundedSeekableByteChannel(openArchiveChannel(), dataOffset, entry.compressedSize);
        }
        if (entry.method == ZipMethod.DEFLATED_ID) {
            return new ByteArraySeekableByteChannel(inflateEntry(entry, dataOffset));
        }
        throw new IOException("Unsupported ZIP compression method: " + entry.method);
    }

    /// Opens an input stream for an entry path.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        requireReadOnlyOptions(options);
        ZipEntryRecord entry = requireReadableEntry(path);
        if (entry.method != ZipMethod.STORED_ID && entry.method != ZipMethod.DEFLATED_ID) {
            throw new IOException("Unsupported ZIP compression method: " + entry.method);
        }

        long dataOffset = dataOffset(entry);
        SeekableByteChannel archive = openArchiveChannel();
        boolean completed = false;
        try {
            SeekableByteChannel compressed = new BoundedSeekableByteChannel(archive, dataOffset, entry.compressedSize);
            if (entry.method == ZipMethod.STORED_ID) {
                InputStream input = Channels.newInputStream(compressed);
                completed = true;
                return input;
            }

            InputStream input = new EntryInflaterInputStream(Channels.newInputStream(compressed));
            completed = true;
            return input;
        } finally {
            if (!completed) {
                archive.close();
            }
        }
    }

    /// Returns a readable entry record for a path.
    private ZipEntryRecord requireReadableEntry(Path path) throws IOException {
        String key = pathKey(path);
        ZipEntryRecord entry = requireEntry(key);
        if (entry.directory) {
            throw new IOException("ZIP entry is a directory: " + path);
        }
        if ((entry.generalPurposeFlags & ZIP_ENCRYPTED_FLAG) != 0) {
            throw new IOException("Encrypted ZIP entries are not supported yet: " + path);
        }
        return entry;
    }

    /// Opens a directory stream for an entry path.
    public DirectoryStream<Path> newDirectoryStream(
            Path directory,
            DirectoryStream.Filter<? super Path> filter
    ) throws IOException {
        Objects.requireNonNull(filter, "filter");
        String key = pathKey(directory);
        ZipIndex loadedIndex = index();
        if (!loadedIndex.directories.contains(key)) {
            throw new NoSuchFileException(directory.toString());
        }

        ArrayList<Path> paths = new ArrayList<>();
        for (String child : loadedIndex.children.getOrDefault(key, List.of())) {
            Path childPath = getPath("/" + child);
            try {
                if (filter.accept(childPath)) {
                    paths.add(childPath);
                }
            } catch (IOException exception) {
                throw new DirectoryIteratorException(exception);
            }
        }
        return new ListDirectoryStream(List.copyOf(paths));
    }

    /// Checks access to an entry path.
    public void checkAccess(Path path, java.nio.file.AccessMode... modes) throws IOException {
        String key = pathKey(path);
        ZipIndex loadedIndex = index();
        if (!loadedIndex.directories.contains(key) && !loadedIndex.entries.containsKey(key)) {
            throw new NoSuchFileException(path.toString());
        }
        for (java.nio.file.AccessMode mode : modes) {
            if (mode != java.nio.file.AccessMode.READ) {
                throw new AccessDeniedException(path.toString());
            }
        }
    }

    /// Returns an attribute view for an entry path.
    public <V extends java.nio.file.attribute.FileAttributeView> @Nullable V getFileAttributeView(
            Path path,
            Class<V> type
    ) {
        Objects.requireNonNull(type, "type");
        if (type == BasicFileAttributeView.class || type == ZipArkivoEntryAttributeView.class) {
            return type.cast(new EntryAttributeView(this, path));
        }
        return null;
    }

    /// Reads typed attributes for an entry path.
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type) throws IOException {
        Objects.requireNonNull(type, "type");
        if (type == BasicFileAttributes.class || type == ZipArkivoEntryAttributes.class) {
            return type.cast(readZipAttributes(path));
        }
        throw new UnsupportedOperationException("Unsupported ZIP attribute type: " + type.getName());
    }

    /// Reads ZIP-specific attributes for an entry path.
    private ZipArkivoEntryAttributes readZipAttributes(Path path) throws IOException {
        String key = pathKey(path);
        ZipIndex loadedIndex = index();
        ZipEntryRecord entry = loadedIndex.entries.get(key);
        if (entry != null) {
            return new EntryAttributes(entry);
        }
        if (loadedIndex.directories.contains(key)) {
            return EntryAttributes.syntheticDirectory(key);
        }
        throw new NoSuchFileException(path.toString());
    }

    /// Returns the file store exposed by this ZIP file system.
    public FileStore fileStore() {
        return ZipFileStore.INSTANCE;
    }

    /// Reads named attributes for an entry path.
    public Map<String, Object> readAttributes(Path path, String attributes) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        int separator = attributes.indexOf(':');
        String view = separator >= 0 ? attributes.substring(0, separator) : "basic";
        String names = separator >= 0 ? attributes.substring(separator + 1) : attributes;
        if (!"basic".equals(view) && !"zip".equals(view)) {
            throw new UnsupportedOperationException("Unsupported ZIP attribute view: " + view);
        }

        ZipArkivoEntryAttributes zipAttributes = readZipAttributes(path);
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if ("*".equals(names)) {
            addBasicAttributes(values, zipAttributes);
            if ("zip".equals(view)) {
                addZipAttributes(values, zipAttributes);
            }
            return Collections.unmodifiableMap(values);
        }

        for (String name : names.split(",")) {
            addNamedAttribute(values, zipAttributes, name.trim(), "zip".equals(view));
        }
        return Collections.unmodifiableMap(values);
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
        values.put("comment", attributes.comment());
    }

    /// Adds one named attribute to a named attribute map.
    private static void addNamedAttribute(
            Map<String, Object> values,
            ZipArkivoEntryAttributes attributes,
            String name,
            boolean zipView
    ) {
        switch (name) {
            case "lastModifiedTime" -> values.put(name, attributes.lastModifiedTime());
            case "lastAccessTime" -> values.put(name, attributes.lastAccessTime());
            case "creationTime" -> values.put(name, attributes.creationTime());
            case "size" -> values.put(name, attributes.size());
            case "isRegularFile" -> values.put(name, attributes.isRegularFile());
            case "isDirectory" -> values.put(name, attributes.isDirectory());
            case "isSymbolicLink" -> values.put(name, attributes.isSymbolicLink());
            case "isOther" -> values.put(name, attributes.isOther());
            case "fileKey" -> values.put(name, attributes.fileKey());
            case "rawPath" -> requireZipView(values, name, zipView, attributes.rawPath());
            case "path" -> requireZipView(values, name, zipView, attributes.path());
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
            case "centralDirectoryExtraData" -> requireZipView(values, name, zipView, attributes.centralDirectoryExtraData());
            case "rawComment" -> requireZipView(values, name, zipView, attributes.rawComment());
            case "comment" -> requireZipView(values, name, zipView, attributes.comment());
            default -> throw new IllegalArgumentException("Unsupported ZIP attribute: " + name);
        }
    }

    /// Adds a ZIP-view-only attribute or rejects it for the basic view.
    private static void requireZipView(Map<String, Object> values, String name, boolean zipView, @Nullable Object value) {
        if (!zipView) {
            throw new IllegalArgumentException("Attribute requires zip view: " + name);
        }
        values.put(name, value);
    }

    /// Returns a path matcher for this ZIP file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        return ArkivoPathMatchers.create(syntaxAndPattern, '/');
    }

    /// Returns a user principal lookup service for this ZIP file system.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("ZIP user principals are not implemented yet");
    }

    /// Opens a watch service for this ZIP file system.
    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException("ZIP watch services are not supported");
    }

    /// Requires this file system to be open.
    private void checkOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Returns the loaded ZIP central directory index.
    private ZipIndex index() throws IOException {
        checkOpen();
        ZipIndex loadedIndex = index;
        if (loadedIndex != null) {
            return loadedIndex;
        }

        synchronized (this) {
            loadedIndex = index;
            if (loadedIndex == null) {
                loadedIndex = readIndex();
                index = loadedIndex;
            }
            return loadedIndex;
        }
    }

    /// Reads the ZIP central directory index from archive storage.
    private ZipIndex readIndex() throws IOException {
        if (archivePath == null) {
            throw new IOException("Split ZIP archive indexing is not implemented yet");
        }

        try (SeekableByteChannel channel = openArchiveChannel()) {
            ZipEndRecord endRecord = readEndRecord(channel);
            ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(config.entryNameEncoding());
            HashMap<String, ZipEntryRecord> entries = new HashMap<>();
            ArrayList<ZipEntryRecord> storageEntries = new ArrayList<>();
            HashSet<String> directories = new HashSet<>();
            HashMap<String, HashSet<String>> children = new HashMap<>();
            directories.add("");

            ByteBuffer centralDirectory = ByteBuffer.allocate(Math.toIntExact(endRecord.centralDirectorySize))
                    .order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, endRecord.actualCentralDirectoryOffset, centralDirectory);
            centralDirectory.flip();

            while (centralDirectory.hasRemaining()) {
                int offset = centralDirectory.position();
                if (centralDirectory.remaining() < ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE
                        || centralDirectory.getInt(offset) != ZIP_CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
                    throw new IOException("Invalid ZIP central directory header");
                }

                int versionMadeBy = Short.toUnsignedInt(centralDirectory.getShort(offset + 4));
                int versionNeeded = Short.toUnsignedInt(centralDirectory.getShort(offset + 6));
                int flags = Short.toUnsignedInt(centralDirectory.getShort(offset + 8));
                int method = Short.toUnsignedInt(centralDirectory.getShort(offset + 10));
                int lastModifiedTime = Short.toUnsignedInt(centralDirectory.getShort(offset + 12));
                int lastModifiedDate = Short.toUnsignedInt(centralDirectory.getShort(offset + 14));
                long crc32 = Integer.toUnsignedLong(centralDirectory.getInt(offset + 16));
                long compressedSize = Integer.toUnsignedLong(centralDirectory.getInt(offset + 20));
                long uncompressedSize = Integer.toUnsignedLong(centralDirectory.getInt(offset + 24));
                int nameLength = Short.toUnsignedInt(centralDirectory.getShort(offset + 28));
                int extraLength = Short.toUnsignedInt(centralDirectory.getShort(offset + 30));
                int commentLength = Short.toUnsignedInt(centralDirectory.getShort(offset + 32));
                int internalAttributes = Short.toUnsignedInt(centralDirectory.getShort(offset + 36));
                long externalAttributes = Integer.toUnsignedLong(centralDirectory.getInt(offset + 38));
                long localHeaderOffset = Integer.toUnsignedLong(centralDirectory.getInt(offset + 42));
                int variableOffset = offset + ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE;
                int nextOffset = variableOffset + nameLength + extraLength + commentLength;
                if (nextOffset > centralDirectory.limit()) {
                    throw new IOException("Invalid ZIP central directory variable data length");
                }

                byte[] rawPath = readBytes(centralDirectory, variableOffset, nameLength);
                byte[] extraData = readBytes(centralDirectory, variableOffset + nameLength, extraLength);
                byte[] rawComment = readBytes(centralDirectory, variableOffset + nameLength + extraLength, commentLength);
                Zip64Values zip64 = Zip64Values.read(
                        extraData,
                        uncompressedSize,
                        compressedSize,
                        localHeaderOffset
                );
                uncompressedSize = zip64.uncompressedSize;
                compressedSize = zip64.compressedSize;
                localHeaderOffset = zip64.localHeaderOffset;

                String decodedPath;
                String decodedComment = null;
                try {
                    decodedPath = decoder.decodePath(rawPath, flags, extraData);
                    if (rawComment.length > 0) {
                        decodedComment = decoder.decodeComment(rawComment, flags, extraData);
                    }
                } catch (java.nio.charset.CharacterCodingException exception) {
                    throw new IOException("Failed to decode ZIP entry name", exception);
                }

                String key = entryKey(decodedPath);
                if (!key.isEmpty()) {
                    boolean directory = decodedPath.endsWith("/");
                    long actualLocalHeaderOffset = localHeaderOffset + endRecord.offsetAdjustment;
                    byte[] localExtraData = readLocalExtraData(channel, actualLocalHeaderOffset);
                    ZipEntryRecord entry = new ZipEntryRecord(
                            key,
                            rawPath,
                            decodedPath,
                            compressedSize,
                            uncompressedSize,
                            crc32,
                            flags,
                            versionMadeBy,
                            versionNeeded,
                            internalAttributes,
                            externalAttributes,
                            method,
                            actualLocalHeaderOffset,
                            localExtraData,
                            extraData,
                            rawComment.length > 0 ? rawComment : null,
                            decodedComment,
                            dosTime(lastModifiedDate, lastModifiedTime),
                            directory
                    );
                    entries.put(key, entry);
                    storageEntries.add(entry);
                    addTreePath(key, directory, directories, children);
                }

                centralDirectory.position(nextOffset);
            }

            return new ZipIndex(
                    Map.copyOf(entries),
                    storageEntriesByOffset(storageEntries),
                    Set.copyOf(directories),
                    freezeChildren(children)
            );
        }
    }

    /// Returns an entry record for a path key or throws when the entry is absent.
    private ZipEntryRecord requireEntry(String key) throws IOException {
        ZipEntryRecord entry = index().entries.get(key);
        if (entry == null) {
            throw new NoSuchFileException(key);
        }
        return entry;
    }

    /// Returns the archive data offset for an entry.
    private long dataOffset(ZipEntryRecord entry) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(ZIP_LOCAL_FILE_HEADER_MIN_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        try (SeekableByteChannel channel = openArchiveChannel()) {
            readFully(channel, entry.localHeaderOffset, header);
        }
        header.flip();
        if (header.getInt(0) != ZIP_LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP local file header");
        }
        int nameLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET));
        int extraLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET));
        return entry.localHeaderOffset + ZIP_LOCAL_FILE_HEADER_MIN_SIZE + nameLength + extraLength;
    }

    /// Inflates a deflated ZIP entry into a byte array.
    private byte[] inflateEntry(ZipEntryRecord entry, long dataOffset) throws IOException {
        try (SeekableByteChannel archive = openArchiveChannel();
             SeekableByteChannel compressed = new BoundedSeekableByteChannel(archive, dataOffset, entry.compressedSize);
             InputStream input = Channels.newInputStream(compressed);
             InflaterInputStream inflater = new InflaterInputStream(input, new Inflater(true))) {
            return inflater.readAllBytes();
        }
    }

    /// Requires byte channel options to describe a read-only open.
    private static void requireReadOnlyOptions(Set<? extends OpenOption> options) {
        Objects.requireNonNull(options, "options");
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("ZIP entry channels are read-only");
            }
        }
    }

    /// Requires input stream options to describe a read-only open.
    private static void requireReadOnlyOptions(OpenOption... options) {
        Objects.requireNonNull(options, "options");
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("ZIP entry streams are read-only");
            }
        }
    }

    /// Returns the normalized ZIP index key for a path.
    private String pathKey(Path path) {
        Objects.requireNonNull(path, "path");
        if (path.getFileSystem() != this) {
            throw new ProviderMismatchException();
        }
        String value = path.toAbsolutePath().normalize().toString();
        if ("/".equals(value)) {
            return "";
        }
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/") && !value.isEmpty()) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /// Returns the normalized ZIP index key for a decoded entry path.
    private static String entryKey(String path) {
        String value = path;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/") && !value.isEmpty()) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /// Adds an entry or directory key to the directory tree.
    private static void addTreePath(
            String key,
            boolean directory,
            HashSet<String> directories,
            HashMap<String, HashSet<String>> children
    ) {
        String current = key;
        if (directory) {
            directories.add(current);
        }

        while (true) {
            int separator = current.lastIndexOf('/');
            String parent = separator >= 0 ? current.substring(0, separator) : "";
            String child = current;
            children.computeIfAbsent(parent, ignored -> new HashSet<>()).add(child);
            if (parent.isEmpty()) {
                break;
            }
            directories.add(parent);
            current = parent;
        }
    }

    /// Returns immutable child path lists sorted by path text.
    private static Map<String, List<String>> freezeChildren(HashMap<String, HashSet<String>> children) {
        HashMap<String, List<String>> frozenChildren = new HashMap<>();
        for (Map.Entry<String, HashSet<String>> entry : children.entrySet()) {
            ArrayList<String> values = new ArrayList<>(entry.getValue());
            values.sort(String::compareTo);
            frozenChildren.put(entry.getKey(), List.copyOf(values));
        }
        return Map.copyOf(frozenChildren);
    }

    /// Returns immutable entry records sorted by physical local header offset.
    private static List<ZipEntryRecord> storageEntriesByOffset(ArrayList<ZipEntryRecord> entries) {
        entries.sort(Comparator.comparingLong(left -> left.localHeaderOffset));
        return List.copyOf(entries);
    }

    /// Reads a byte array range from a buffer without changing its position.
    private static byte[] readBytes(ByteBuffer buffer, int offset, int length) {
        byte[] bytes = new byte[length];
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(offset);
        duplicate.get(bytes);
        return bytes;
    }

    /// Reads raw local file header extra data.
    private static byte[] readLocalExtraData(SeekableByteChannel channel, long offset) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(ZIP_LOCAL_FILE_HEADER_MIN_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, offset, header);
        header.flip();
        if (header.getInt(0) != ZIP_LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP local file header");
        }
        int nameLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET));
        int extraLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET));
        ByteBuffer extra = ByteBuffer.allocate(extraLength);
        readFully(channel, offset + ZIP_LOCAL_FILE_HEADER_MIN_SIZE + nameLength, extra);
        return extra.array();
    }

    /// Converts ZIP DOS date and time fields to a file time.
    private static FileTime dosTime(int date, int time) {
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

    /// Reads the ZIP end of central directory record needed for central directory indexing.
    private static ZipEndRecord readEndRecord(SeekableByteChannel channel) throws IOException {
        long size = channel.size();
        if (size < ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE) {
            throw new IOException("ZIP end of central directory record not found");
        }

        int searchSize = (int) Math.min(size, ZIP_END_OF_CENTRAL_DIRECTORY_MAX_SEARCH);
        long searchOffset = size - searchSize;
        ByteBuffer buffer = ByteBuffer.allocate(searchSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, searchOffset, buffer);
        buffer.flip();

        for (int index = searchSize - ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE; index >= 0; index--) {
            if (buffer.getInt(index) != ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                continue;
            }

            int commentLength = Short.toUnsignedInt(
                    buffer.getShort(index + ZIP_END_OF_CENTRAL_DIRECTORY_COMMENT_LENGTH_OFFSET)
            );
            if (index + ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE + commentLength != searchSize) {
                continue;
            }

            long centralDirectorySize = Integer.toUnsignedLong(
                    buffer.getInt(index + ZIP_END_OF_CENTRAL_DIRECTORY_SIZE_OFFSET)
            );
            long centralDirectoryOffset = Integer.toUnsignedLong(
                    buffer.getInt(index + ZIP_END_OF_CENTRAL_DIRECTORY_OFFSET_OFFSET)
            );
            if (centralDirectorySize == ZIP_UINT32_MAX || centralDirectoryOffset == ZIP_UINT32_MAX) {
                return readZip64EndRecord(channel, searchOffset + index);
            }

            long actualCentralDirectoryOffset = searchOffset + index - centralDirectorySize;
            if (actualCentralDirectoryOffset < centralDirectoryOffset) {
                throw new IOException("ZIP central directory offset is inconsistent");
            }
            return new ZipEndRecord(
                    centralDirectorySize,
                    centralDirectoryOffset,
                    actualCentralDirectoryOffset,
                    actualCentralDirectoryOffset - centralDirectoryOffset
            );
        }

        throw new IOException("ZIP end of central directory record not found");
    }

    /// Reads ZIP64 central directory location from the ZIP64 end records.
    private static ZipEndRecord readZip64EndRecord(SeekableByteChannel channel, long eocdOffset) throws IOException {
        long locatorOffset = eocdOffset - ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE;
        if (locatorOffset < 0) {
            throw new IOException("ZIP64 end of central directory locator not found");
        }

        ByteBuffer locator = ByteBuffer.allocate(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, locatorOffset, locator);
        locator.flip();
        if (locator.getInt(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE) {
            throw new IOException("ZIP64 end of central directory locator not found");
        }

        long storedZip64EndOffset = locator.getLong(8);
        long actualZip64EndOffset = locateZip64EndRecord(channel, locatorOffset, storedZip64EndOffset);
        ByteBuffer fixedRecord = ByteBuffer.allocate(ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, actualZip64EndOffset, fixedRecord);
        fixedRecord.flip();
        if (fixedRecord.getInt(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            throw new IOException("ZIP64 end of central directory record not found");
        }

        long centralDirectorySize = fixedRecord.getLong(40);
        long centralDirectoryOffset = fixedRecord.getLong(48);
        long actualCentralDirectoryOffset = actualZip64EndOffset - centralDirectorySize;
        if (actualCentralDirectoryOffset < centralDirectoryOffset) {
            throw new IOException("ZIP64 central directory offset is inconsistent");
        }
        return new ZipEndRecord(
                centralDirectorySize,
                centralDirectoryOffset,
                actualCentralDirectoryOffset,
                actualCentralDirectoryOffset - centralDirectoryOffset
        );
    }

    /// Locates the actual ZIP64 end record offset in physical storage.
    private static long locateZip64EndRecord(
            SeekableByteChannel channel,
            long locatorOffset,
            long storedZip64EndOffset
    ) throws IOException {
        if (storedZip64EndOffset >= 0 && storedZip64EndOffset + ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE <= locatorOffset) {
            ByteBuffer signature = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, storedZip64EndOffset, signature);
            signature.flip();
            if (signature.getInt(0) == ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                return storedZip64EndOffset;
            }
        }

        int searchSize = (int) Math.min(locatorOffset, ZIP_END_OF_CENTRAL_DIRECTORY_MAX_SEARCH);
        long searchOffset = locatorOffset - searchSize;
        ByteBuffer buffer = ByteBuffer.allocate(searchSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, searchOffset, buffer);
        buffer.flip();
        for (int index = searchSize - ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE; index >= 0; index--) {
            if (buffer.getInt(index) == ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                return searchOffset + index;
            }
        }

        throw new IOException("ZIP64 end of central directory record not found");
    }

    /// Opens a channel for the physical storage that contains the beginning of this ZIP archive.
    private SeekableByteChannel openArchiveChannel() throws IOException {
        if (archivePath != null) {
            return Files.newByteChannel(archivePath, StandardOpenOption.READ);
        }

        assert volumes != null;
        SeekableByteChannel channel = volumes.openVolume(0);
        if (channel == null) {
            throw new IOException("ZIP first volume is not available");
        }
        return channel;
    }

    /// Locates the number of bytes before the ZIP archive body.
    private static long locatePreambleSize(SeekableByteChannel channel) throws IOException {
        long size = channel.size();
        if (size < ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE) {
            throw new IOException("ZIP end of central directory record not found");
        }

        int searchSize = (int) Math.min(size, ZIP_END_OF_CENTRAL_DIRECTORY_MAX_SEARCH);
        long searchOffset = size - searchSize;
        ByteBuffer buffer = ByteBuffer.allocate(searchSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, searchOffset, buffer);
        buffer.flip();

        for (int index = searchSize - ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE; index >= 0; index--) {
            if (buffer.getInt(index) != ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                continue;
            }

            int commentLength = Short.toUnsignedInt(
                    buffer.getShort(index + ZIP_END_OF_CENTRAL_DIRECTORY_COMMENT_LENGTH_OFFSET)
            );
            if (index + ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE + commentLength != searchSize) {
                continue;
            }

            long centralDirectorySize = Integer.toUnsignedLong(
                    buffer.getInt(index + ZIP_END_OF_CENTRAL_DIRECTORY_SIZE_OFFSET)
            );
            long centralDirectoryOffset = Integer.toUnsignedLong(
                    buffer.getInt(index + ZIP_END_OF_CENTRAL_DIRECTORY_OFFSET_OFFSET)
            );

            if (centralDirectorySize == ZIP_UINT32_MAX || centralDirectoryOffset == ZIP_UINT32_MAX) {
                long firstLocalHeaderOffset = locateFirstLocalFileHeaderOffset(channel, size);
                if (firstLocalHeaderOffset >= 0) {
                    return firstLocalHeaderOffset;
                }
                throw new IOException("ZIP64 preamble detection requires a local file header");
            }

            long actualCentralDirectoryOffset = searchOffset + index - centralDirectorySize;
            if (actualCentralDirectoryOffset < centralDirectoryOffset) {
                throw new IOException("ZIP central directory offset is inconsistent");
            }

            long centralDirectoryPreambleSize = actualCentralDirectoryOffset - centralDirectoryOffset;
            if (centralDirectorySize == 0) {
                return centralDirectoryPreambleSize;
            }

            long firstLocalHeaderOffset = locateFirstLocalFileHeaderOffset(channel, size);
            if (firstLocalHeaderOffset >= 0
                    && (centralDirectoryPreambleSize == 0 || firstLocalHeaderOffset >= centralDirectoryPreambleSize)) {
                return firstLocalHeaderOffset;
            }
            return centralDirectoryPreambleSize;
        }

        throw new IOException("ZIP end of central directory record not found");
    }

    /// Locates the first plausible local file header offset.
    private static long locateFirstLocalFileHeaderOffset(SeekableByteChannel channel, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(
                ZIP_LOCAL_FILE_HEADER_SCAN_BUFFER_SIZE + ZIP_LOCAL_FILE_HEADER_MIN_SIZE - 1
        ).order(ByteOrder.LITTLE_ENDIAN);
        byte[] carryBytes = new byte[ZIP_LOCAL_FILE_HEADER_MIN_SIZE - 1];
        int carrySize = 0;
        long offset = 0;

        while (offset < size) {
            buffer.clear();
            buffer.put(carryBytes, 0, carrySize);

            int readSize = (int) Math.min(ZIP_LOCAL_FILE_HEADER_SCAN_BUFFER_SIZE, size - offset);
            buffer.limit(carrySize + readSize);
            readFully(channel, offset, buffer);

            int totalSize = buffer.position();
            buffer.flip();
            long bufferOffset = offset - carrySize;

            for (int index = 0; index <= totalSize - ZIP_LOCAL_FILE_HEADER_MIN_SIZE; index++) {
                if (buffer.getInt(index) != ZIP_LOCAL_FILE_HEADER_SIGNATURE) {
                    continue;
                }

                long candidateOffset = bufferOffset + index;
                if (isPlausibleLocalFileHeader(channel, candidateOffset, size)) {
                    return candidateOffset;
                }
            }

            carrySize = Math.min(ZIP_LOCAL_FILE_HEADER_MIN_SIZE - 1, totalSize);
            buffer.position(totalSize - carrySize);
            buffer.get(carryBytes, 0, carrySize);
            offset += readSize;
        }

        return -1;
    }

    /// Returns whether a local file header candidate has a plausible bounded length.
    private static boolean isPlausibleLocalFileHeader(
            SeekableByteChannel channel,
            long offset,
            long storageSize
    ) throws IOException {
        if (offset < 0 || offset + ZIP_LOCAL_FILE_HEADER_MIN_SIZE > storageSize) {
            return false;
        }

        ByteBuffer header = ByteBuffer.allocate(ZIP_LOCAL_FILE_HEADER_MIN_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, offset, header);
        header.flip();
        if (header.getInt(0) != ZIP_LOCAL_FILE_HEADER_SIGNATURE) {
            return false;
        }

        int nameLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET));
        int extraLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET));
        return nameLength > 0
                && offset + ZIP_LOCAL_FILE_HEADER_MIN_SIZE + (long) nameLength + extraLength <= storageSize;
    }

    /// Reads bytes from a channel until the destination buffer is full.
    private static void readFully(SeekableByteChannel channel, long position, ByteBuffer destination)
            throws IOException {
        channel.position(position);
        while (destination.hasRemaining()) {
            if (channel.read(destination) < 0) {
                throw new IOException("Unexpected end of ZIP storage");
            }
        }
    }

    /// Stores the parsed ZIP end record values needed by the index.
    private static final class ZipEndRecord {
        /// The central directory size in bytes.
        private final long centralDirectorySize;

        /// The central directory offset stored in the ZIP metadata.
        private final long centralDirectoryOffset;

        /// The actual central directory offset in physical storage.
        private final long actualCentralDirectoryOffset;

        /// The offset added to ZIP-relative offsets to produce physical storage offsets.
        private final long offsetAdjustment;

        /// Creates parsed ZIP end record values.
        private ZipEndRecord(
                long centralDirectorySize,
                long centralDirectoryOffset,
                long actualCentralDirectoryOffset,
                long offsetAdjustment
        ) {
            this.centralDirectorySize = centralDirectorySize;
            this.centralDirectoryOffset = centralDirectoryOffset;
            this.actualCentralDirectoryOffset = actualCentralDirectoryOffset;
            this.offsetAdjustment = offsetAdjustment;
        }
    }

    /// Stores the parsed ZIP central directory index.
    private static final class ZipIndex {
        /// The entry records keyed by normalized entry path.
        private final Map<String, ZipEntryRecord> entries;

        /// The entry records in physical local header order.
        private final List<ZipEntryRecord> storageEntries;

        /// The normalized directory paths.
        private final Set<String> directories;

        /// The direct child paths keyed by normalized parent directory path.
        private final Map<String, List<String>> children;

        /// Creates a parsed ZIP central directory index.
        private ZipIndex(
                Map<String, ZipEntryRecord> entries,
                List<ZipEntryRecord> storageEntries,
                Set<String> directories,
                Map<String, List<String>> children
        ) {
            this.entries = entries;
            this.storageEntries = storageEntries;
            this.directories = directories;
            this.children = children;
        }
    }

    /// Stores parsed ZIP central directory metadata for one entry.
    private static final class ZipEntryRecord {
        /// The normalized entry key used by the file system index.
        private final String key;

        /// The raw encoded path bytes.
        private final byte[] rawPath;

        /// The decoded path text.
        private final String path;

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

        /// The ZIP compression method identifier.
        private final int method;

        /// The actual local file header offset in physical storage.
        private final long localHeaderOffset;

        /// The raw local file header extra data.
        private final byte[] localExtraData;

        /// The raw central directory extra data.
        private final byte[] centralDirectoryExtraData;

        /// The raw encoded comment bytes, or `null` when absent.
        private final byte @Nullable [] rawComment;

        /// The decoded comment, or `null` when absent.
        private final @Nullable String comment;

        /// The last modified time.
        private final FileTime lastModifiedTime;

        /// Whether this entry is a directory.
        private final boolean directory;

        /// Creates parsed ZIP central directory metadata for one entry.
        private ZipEntryRecord(
                String key,
                byte[] rawPath,
                String path,
                long compressedSize,
                long uncompressedSize,
                long crc32,
                int generalPurposeFlags,
                int versionMadeBy,
                int versionNeededToExtract,
                int internalAttributes,
                long externalAttributes,
                int method,
                long localHeaderOffset,
                byte[] localExtraData,
                byte[] centralDirectoryExtraData,
                byte @Nullable [] rawComment,
                @Nullable String comment,
                FileTime lastModifiedTime,
                boolean directory
        ) {
            this.key = key;
            this.rawPath = rawPath;
            this.path = path;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.crc32 = crc32;
            this.generalPurposeFlags = generalPurposeFlags;
            this.versionMadeBy = versionMadeBy;
            this.versionNeededToExtract = versionNeededToExtract;
            this.internalAttributes = internalAttributes;
            this.externalAttributes = externalAttributes;
            this.method = method;
            this.localHeaderOffset = localHeaderOffset;
            this.localExtraData = localExtraData;
            this.centralDirectoryExtraData = centralDirectoryExtraData;
            this.rawComment = rawComment;
            this.comment = comment;
            this.lastModifiedTime = lastModifiedTime;
            this.directory = directory;
        }
    }

    /// Stores ZIP64 values decoded from an extended information extra field.
    private static final class Zip64Values {
        /// The uncompressed size.
        private final long uncompressedSize;

        /// The compressed size.
        private final long compressedSize;

        /// The local file header offset.
        private final long localHeaderOffset;

        /// Creates decoded ZIP64 values.
        private Zip64Values(long uncompressedSize, long compressedSize, long localHeaderOffset) {
            this.uncompressedSize = uncompressedSize;
            this.compressedSize = compressedSize;
            this.localHeaderOffset = localHeaderOffset;
        }

        /// Reads ZIP64 values from central directory extra data when required.
        private static Zip64Values read(
                byte[] extraData,
                long uncompressedSize,
                long compressedSize,
                long localHeaderOffset
        ) throws IOException {
            boolean needsUncompressedSize = uncompressedSize == ZIP_UINT32_MAX;
            boolean needsCompressedSize = compressedSize == ZIP_UINT32_MAX;
            boolean needsLocalHeaderOffset = localHeaderOffset == ZIP_UINT32_MAX;
            if (!needsUncompressedSize && !needsCompressedSize && !needsLocalHeaderOffset) {
                return new Zip64Values(uncompressedSize, compressedSize, localHeaderOffset);
            }

            int offset = 0;
            while (offset + 4 <= extraData.length) {
                int fieldId = Short.toUnsignedInt(ByteBuffer.wrap(extraData, offset, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getShort());
                int fieldSize = Short.toUnsignedInt(ByteBuffer.wrap(extraData, offset + 2, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getShort());
                int dataOffset = offset + 4;
                int nextOffset = dataOffset + fieldSize;
                if (nextOffset > extraData.length) {
                    throw new IOException("Invalid ZIP extra field length");
                }
                if (fieldId == 0x0001) {
                    ByteBuffer data = ByteBuffer.wrap(extraData, dataOffset, fieldSize).order(ByteOrder.LITTLE_ENDIAN);
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
                offset = nextOffset;
            }

            throw new IOException("Required ZIP64 extended information extra field is missing");
        }

        /// Reads one little-endian ZIP64 long value.
        private static long readZip64Long(ByteBuffer data) throws IOException {
            if (data.remaining() < Long.BYTES) {
                throw new IOException("Invalid ZIP64 extended information extra field");
            }
            return data.getLong();
        }
    }

    /// Exposes the ZIP archive as a read-only file store.
    private static final class ZipFileStore extends FileStore {
        /// The shared ZIP file store instance.
        private static final ZipFileStore INSTANCE = new ZipFileStore();

        /// Creates a ZIP file store.
        private ZipFileStore() {
        }

        /// Returns the file store name.
        @Override
        public String name() {
            return "zip";
        }

        /// Returns the file store type.
        @Override
        public String type() {
            return "zip";
        }

        /// Returns whether this file store is read-only.
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /// Returns the total space in bytes, or zero when it is not available.
        @Override
        public long getTotalSpace() {
            return 0;
        }

        /// Returns the usable space in bytes.
        @Override
        public long getUsableSpace() {
            return 0;
        }

        /// Returns the unallocated space in bytes.
        @Override
        public long getUnallocatedSpace() {
            return 0;
        }

        /// Returns whether a file attribute view is supported.
        @Override
        public boolean supportsFileAttributeView(Class<? extends java.nio.file.attribute.FileAttributeView> type) {
            return type == BasicFileAttributeView.class || type == ZipArkivoEntryAttributeView.class;
        }

        /// Returns whether a file attribute view is supported.
        @Override
        public boolean supportsFileAttributeView(String name) {
            return "basic".equals(name) || "zip".equals(name);
        }

        /// Returns a file store attribute view.
        @Override
        public <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(Class<V> type) {
            return null;
        }

        /// Reads a file store attribute.
        @Override
        public @Nullable Object getAttribute(String attribute) {
            return null;
        }
    }

    /// Implements ZIP entry file attributes.
    private static final class EntryAttributes implements ZipArkivoEntryAttributes {
        /// The synthetic root or directory key, or `null` for real entries.
        private final @Nullable String syntheticDirectoryKey;

        /// The backing ZIP entry record, or `null` for synthetic directories.
        private final @Nullable ZipEntryRecord entry;

        /// Creates attributes backed by a real ZIP entry record.
        private EntryAttributes(ZipEntryRecord entry) {
            this.syntheticDirectoryKey = null;
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        /// Creates attributes for a synthetic directory.
        private EntryAttributes(String syntheticDirectoryKey) {
            this.syntheticDirectoryKey = syntheticDirectoryKey;
            this.entry = null;
        }

        /// Returns attributes for a synthetic directory.
        private static EntryAttributes syntheticDirectory(String key) {
            return new EntryAttributes(key);
        }

        /// Returns the raw encoded ZIP entry path bytes.
        @Override
        public byte @Unmodifiable [] rawPath() {
            ZipEntryRecord record = entry;
            return record != null ? record.rawPath.clone() : new byte[0];
        }

        /// Returns the decoded ZIP entry path text.
        @Override
        public String path() {
            ZipEntryRecord record = entry;
            return record != null ? record.path : Objects.requireNonNull(syntheticDirectoryKey, "syntheticDirectoryKey");
        }

        /// Returns the compressed size stored in the ZIP metadata, or `UNKNOWN_SIZE` when it is not known.
        @Override
        public long compressedSize() {
            ZipEntryRecord record = entry;
            return record != null && !record.directory ? record.compressedSize : UNKNOWN_SIZE;
        }

        /// Returns the CRC-32 value stored in the ZIP metadata, or `UNKNOWN_CRC32` when it is not known.
        @Override
        public long crc32() {
            ZipEntryRecord record = entry;
            return record != null && !record.directory ? record.crc32 : UNKNOWN_CRC32;
        }

        /// Returns the general purpose bit flags stored for the ZIP entry.
        @Override
        public int generalPurposeFlags() {
            ZipEntryRecord record = entry;
            return record != null ? record.generalPurposeFlags : 0;
        }

        /// Returns the ZIP version made by field.
        @Override
        public int versionMadeBy() {
            ZipEntryRecord record = entry;
            return record != null ? record.versionMadeBy : 0;
        }

        /// Returns the ZIP version needed to extract field.
        @Override
        public int versionNeededToExtract() {
            ZipEntryRecord record = entry;
            return record != null ? record.versionNeededToExtract : 0;
        }

        /// Returns the ZIP internal file attributes.
        @Override
        public int internalAttributes() {
            ZipEntryRecord record = entry;
            return record != null ? record.internalAttributes : 0;
        }

        /// Returns the ZIP external file attributes.
        @Override
        public long externalAttributes() {
            ZipEntryRecord record = entry;
            return record != null ? record.externalAttributes : 0;
        }

        /// Returns the ZIP compression method.
        @Override
        public ZipMethod method() {
            ZipEntryRecord record = entry;
            return record != null ? ZipMethod.of(record.method) : ZipMethod.stored();
        }

        /// Returns the ZIP encryption method.
        @Override
        public ZipEncryption encryption() {
            ZipEntryRecord record = entry;
            return record != null && (record.generalPurposeFlags & ZIP_ENCRYPTED_FLAG) != 0
                    ? ZipEncryption.traditional()
                    : ZipEncryption.none();
        }

        /// Returns the raw local file header extra data bytes.
        @Override
        public byte @Unmodifiable [] localExtraData() {
            ZipEntryRecord record = entry;
            return record != null ? record.localExtraData.clone() : new byte[0];
        }

        /// Returns the raw central directory extra data bytes.
        @Override
        public byte @Unmodifiable [] centralDirectoryExtraData() {
            ZipEntryRecord record = entry;
            return record != null ? record.centralDirectoryExtraData.clone() : new byte[0];
        }

        /// Returns the raw ZIP entry comment bytes, or `null` when no comment is present.
        @Override
        public byte @Nullable @Unmodifiable [] rawComment() {
            ZipEntryRecord record = entry;
            return record != null && record.rawComment != null ? record.rawComment.clone() : null;
        }

        /// Returns the decoded ZIP entry comment, or `null` when no comment is present.
        @Override
        public @Nullable String comment() {
            ZipEntryRecord record = entry;
            return record != null ? record.comment : null;
        }

        /// Returns the last modified time.
        @Override
        public FileTime lastModifiedTime() {
            ZipEntryRecord record = entry;
            return record != null ? record.lastModifiedTime : FileTime.fromMillis(0);
        }

        /// Returns the last access time.
        @Override
        public FileTime lastAccessTime() {
            return lastModifiedTime();
        }

        /// Returns the creation time.
        @Override
        public FileTime creationTime() {
            return lastModifiedTime();
        }

        /// Returns whether this path is a regular file.
        @Override
        public boolean isRegularFile() {
            ZipEntryRecord record = entry;
            return record != null && !record.directory;
        }

        /// Returns whether this path is a directory.
        @Override
        public boolean isDirectory() {
            ZipEntryRecord record = entry;
            return record == null || record.directory;
        }

        /// Returns whether this path is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        /// Returns whether this path is another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the uncompressed entry size.
        @Override
        public long size() {
            ZipEntryRecord record = entry;
            return record != null && !record.directory ? record.uncompressedSize : 0;
        }

        /// Returns an implementation-specific file key.
        @Override
        public @Nullable Object fileKey() {
            ZipEntryRecord record = entry;
            return record != null ? record.key : syntheticDirectoryKey;
        }
    }

    /// Implements the ZIP entry attribute view.
    private static final class EntryAttributeView implements ZipArkivoEntryAttributeView {
        /// The file system used to read attributes.
        private final ZipArkivoFileSystemImpl fileSystem;

        /// The path whose attributes are exposed.
        private final Path path;

        /// Creates an entry attribute view.
        private EntryAttributeView(ZipArkivoFileSystemImpl fileSystem, Path path) {
            this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Reads the ZIP-specific entry attributes.
        @Override
        public ZipArkivoEntryAttributes readAttributes() throws IOException {
            return fileSystem.readZipAttributes(path);
        }

        /// Sets entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the ZIP compression method requested for the entry.
        @Override
        public void setMethod(ZipMethod method) {
            Objects.requireNonNull(method, "method");
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the ZIP encryption method requested for the entry.
        @Override
        public void setEncryption(ZipEncryption encryption) {
            Objects.requireNonNull(encryption, "encryption");
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the expected uncompressed size and CRC-32 value for entries that require them before writing.
        @Override
        public void setUncompressedSizeAndCrc32(long uncompressedSize, long crc32) {
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the ZIP internal file attributes.
        @Override
        public void setInternalAttributes(int internalAttributes) {
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the ZIP external file attributes.
        @Override
        public void setExternalAttributes(long externalAttributes) {
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the raw local file header extra data bytes.
        @Override
        public void setLocalExtraData(byte[] extraData) {
            Objects.requireNonNull(extraData, "extraData");
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the raw central directory extra data bytes.
        @Override
        public void setCentralDirectoryExtraData(byte[] extraData) {
            Objects.requireNonNull(extraData, "extraData");
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the decoded ZIP entry comment.
        @Override
        public void setComment(@Nullable String comment) {
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the raw ZIP entry comment bytes.
        @Override
        public void setRawComment(byte @Nullable [] rawComment) {
            throw new java.nio.file.ReadOnlyFileSystemException();
        }
    }

    /// Implements a forward-only path stream backed by a path list.
    @NotNullByDefault
    private static final class EntryPathStream implements ArkivoFileSystemEntryStream {
        /// The paths returned by this stream.
        private final List<Path> paths;

        /// The next path index.
        private int index;

        /// Whether this stream is open.
        private boolean open = true;

        /// Creates an entry path stream.
        private EntryPathStream(List<Path> paths) {
            this.paths = paths;
        }

        /// Returns the next path or `null` when traversal is complete.
        @Override
        public @Nullable Path next() throws IOException {
            if (!open) {
                throw new IOException("Entry stream is closed");
            }
            if (index >= paths.size()) {
                return null;
            }
            return paths.get(index++);
        }

        /// Closes this stream.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Implements an immutable directory stream backed by a path list.
    private static final class ListDirectoryStream implements DirectoryStream<Path> {
        /// The directory entries.
        private final List<Path> paths;

        /// Whether this directory stream is open.
        private boolean open = true;

        /// Whether the iterator has already been returned.
        private boolean iteratorReturned;

        /// Creates a directory stream backed by a path list.
        private ListDirectoryStream(List<Path> paths) {
            this.paths = paths;
        }

        /// Returns an iterator over directory entries.
        @Override
        public java.util.Iterator<Path> iterator() {
            if (!open) {
                throw new IllegalStateException("Directory stream is closed");
            }
            if (iteratorReturned) {
                throw new IllegalStateException("Directory stream iterator has already been returned");
            }
            iteratorReturned = true;
            return paths.iterator();
        }

        /// Closes this directory stream.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Inflates a ZIP entry stream and releases the native inflater when closed.
    @NotNullByDefault
    private static final class EntryInflaterInputStream extends InflaterInputStream {
        /// The inflater used by this stream.
        private final Inflater inflater;

        /// Whether the inflater has been released.
        private boolean released;

        /// Creates an inflater stream for raw ZIP deflate data.
        private EntryInflaterInputStream(InputStream input) {
            this(input, new Inflater(true));
        }

        /// Creates an inflater stream with the given raw deflate inflater.
        private EntryInflaterInputStream(InputStream input, Inflater inflater) {
            super(input, inflater);
            this.inflater = inflater;
        }

        /// Closes the stream and releases the inflater.
        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!released) {
                    released = true;
                    inflater.end();
                }
            }
        }
    }

    /// Exposes the leading bytes of a seekable channel as a read-only seekable channel.
    @NotNullByDefault
    private static final class BoundedSeekableByteChannel implements SeekableByteChannel {
        /// The wrapped storage channel.
        private final SeekableByteChannel channel;

        /// The absolute storage offset where this bounded channel starts.
        private final long offset;

        /// The visible channel size.
        private final long size;

        /// The current position inside the bounded channel.
        private long position;

        /// Whether this bounded channel is open.
        private boolean open = true;

        /// Creates a bounded channel over the first `size` bytes of the given channel.
        private BoundedSeekableByteChannel(SeekableByteChannel channel, long size) {
            this(channel, 0, size);
        }

        /// Creates a bounded channel over `size` bytes starting at the given storage offset.
        private BoundedSeekableByteChannel(SeekableByteChannel channel, long offset, long size) {
            this.channel = Objects.requireNonNull(channel, "channel");
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            this.offset = offset;
            this.size = size;
        }

        /// Reads bytes from the current bounded channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }

            int originalLimit = destination.limit();
            long remaining = size - position;
            if (destination.remaining() > remaining) {
                destination.limit(destination.position() + (int) remaining);
            }

            try {
                channel.position(offset + position);
                int read = channel.read(destination);
                if (read > 0) {
                    position += read;
                }
                return read;
            } finally {
                destination.limit(originalLimit);
            }
        }

        /// Always rejects writes because preamble channels are read-only.
        @Override
        public int write(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            throw new NonWritableChannelException();
        }

        /// Returns the current bounded channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current bounded channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the visible channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return size;
        }

        /// Always rejects truncation because preamble channels are read-only.
        @Override
        public SeekableByteChannel truncate(long newSize) {
            if (newSize < 0) {
                throw new IllegalArgumentException("newSize must not be negative");
            }
            throw new NonWritableChannelException();
        }

        /// Returns whether this bounded channel is open.
        @Override
        public boolean isOpen() {
            return open && channel.isOpen();
        }

        /// Closes this bounded channel and the wrapped storage channel.
        @Override
        public void close() throws IOException {
            open = false;
            channel.close();
        }

        /// Requires this bounded channel to be open.
        private void ensureOpen() throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Exposes a byte array as a read-only seekable channel.
    private static final class ByteArraySeekableByteChannel implements SeekableByteChannel {
        /// The backing bytes.
        private final byte[] bytes;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a read-only byte array channel.
        private ByteArraySeekableByteChannel(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        /// Reads bytes from the current position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }

            int length = Math.min(destination.remaining(), bytes.length - position);
            destination.put(bytes, position, length);
            position += length;
            return length;
        }

        /// Always rejects writes because ZIP entry channels are read-only.
        @Override
        public int write(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition is out of range");
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the byte array size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Always rejects truncation because ZIP entry channels are read-only.
        @Override
        public SeekableByteChannel truncate(long size) {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}

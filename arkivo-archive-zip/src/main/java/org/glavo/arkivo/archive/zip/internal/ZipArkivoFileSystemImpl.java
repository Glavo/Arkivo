// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import com.github.luben.zstd.ZstdInputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaInputStream;
import org.glavo.arkivo.codec.xz.internal.XzInputStream;
import org.glavo.arkivo.codec.bzip2.internal.BZip2InputStream;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.codec.deflate64.internal.Deflate64InputStream;
import org.glavo.arkivo.archive.internal.ArkivoFileStoreAttributes;
import org.glavo.arkivo.archive.internal.ArkivoPathMatchers;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipEntryNameEncoding;
import org.glavo.arkivo.archive.zip.ZipMethod;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NotLinkException;
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
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
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
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.glavo.arkivo.archive.zip.internal.ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DATA_DESCRIPTOR_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DATA_DESCRIPTOR_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ENCRYPTED_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_EOS_MARKER_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_PROPERTY_SIZE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LOCAL_FILE_HEADER_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.STRONG_ENCRYPTION_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT32_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.WINZIP_AES_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.isZstandardMethod;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readInt;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readRequiredByte;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readUnsignedShort;

/// Implements ZIP archive file system state and operations.
@NotNullByDefault
public final class ZipArkivoFileSystemImpl extends ZipArkivoFileSystem {
    /// The minimum ZIP local file header size.
    private static final int ZIP_LOCAL_FILE_HEADER_MIN_SIZE = 30;

    /// The file name length field offset inside a local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET = 26;

    /// The extra field length field offset inside a local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET = 28;

    /// The CRC-32 field offset inside a local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_CRC32_OFFSET = 14;

    /// The compressed size field offset inside a local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_COMPRESSED_SIZE_OFFSET = 18;

    /// The uncompressed size field offset inside a local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_UNCOMPRESSED_SIZE_OFFSET = 22;

    /// The buffer size used when scanning for the first local file header.
    private static final int ZIP_LOCAL_FILE_HEADER_SCAN_BUFFER_SIZE = 8192;

    /// The largest supported ZIP data descriptor size, including its optional signature.
    private static final int ZIP_DATA_DESCRIPTOR_MAX_SIZE = 24;

    /// The minimum ZIP end of central directory record size.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 22;

    /// The minimum ZIP central directory file header size.
    private static final int ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE = 46;

    /// The ZIP64 end of central directory locator size.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE = 20;

    /// The minimum ZIP64 end of central directory record size.
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 56;

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

    /// The optional lock that protects lazy ZIP index initialization.
    private final @Nullable ReentrantLock indexLock;

    /// The parsed ZIP central directory index, or `null` when it has not been loaded yet.
    private volatile @Nullable ZipIndex index;

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Whether the owned volume source has been closed.
    private boolean volumesClosed;

    /// Whether the close action has completed.
    private boolean closeActionCompleted;

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
        super(config.threadSafety());
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
        this.indexLock = ZipLocks.create(config.threadSafety());
    }

    /// Returns the number of bytes stored before the ZIP archive body.
    @Override
    public long preambleSize() throws IOException {
        try (Operation ignored = beginReadOperation()) {
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
    }

    /// Opens a read-only channel over the bytes stored before the ZIP archive body.
    @Override
    public SeekableByteChannel openPreambleChannel() throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return manageReadChannel(openPreambleChannelLocked());
        }
    }

    /// Opens the preamble channel while the caller holds the shared operation lock.
    private SeekableByteChannel openPreambleChannelLocked() throws IOException {
        checkOpen();
        SeekableByteChannel channel = openArchiveChannel();
        boolean completed = false;
        Throwable failure = null;
        try {
            long size = preambleSize;
            if (size < 0) {
                size = locatePreambleSize(channel);
                preambleSize = size;
            }
            completed = true;
            return new BoundedSeekableByteChannel(channel, size);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (!completed) {
                closeAfterFailedOpen(channel, failure);
            }
        }
    }

    /// Returns the archive URI used by ZIP path URI conversion, or `null` for volume-backed file systems.
    @Nullable URI archiveUri() {
        return archivePath != null ? archivePath.toUri().normalize() : null;
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and any owned volume source.
    @Override
    public void close() throws IOException {
        try (CloseOperation ignored = beginCloseOperation()) {
            if (!open && volumesClosed && closeActionCompleted) {
                return;
            }
            open = false;
            Throwable failure = null;
            if (!volumesClosed) {
                try {
                    if (volumes != null) {
                        volumes.close();
                    }
                    volumesClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }
            Runnable action = closeAction;
            if (!closeActionCompleted) {
                try {
                    if (action != null) {
                        action.run();
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
    }

    /// Returns whether this ZIP file system is open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Returns whether this ZIP file system rejects write operations.
    @Override
    public boolean isReadOnly() {
        return true;
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
            checkOpen();
            return List.of(rootPath);
        }
    }

    /// Returns the file stores exposed by this ZIP file system.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        try (Operation ignored = beginReadOperation()) {
            checkOpen();
            return List.of(fileStore());
        }
    }

    /// Returns the supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        try (Operation ignored = beginReadOperation()) {
            checkOpen();
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

    /// Opens a byte channel for an entry path.
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return manageReadChannel(newByteChannelLocked(path, options, attributes));
        }
    }

    /// Opens an entry byte channel while the caller holds the shared operation lock.
    private SeekableByteChannel newByteChannelLocked(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(attributes, "attributes");
        requireReadOnlyOptions(options);
        ZipEntryRecord entry = requireReadableEntry(path);
        requireSupportedEncryption(path, entry);
        long dataOffset = dataOffset(entry);
        int compressionMethod = entry.compressionMethod();
        if (compressionMethod == ZipMethod.STORED_ID && !entry.encrypted()) {
            return new ValidatingStoredEntryByteChannel(
                    new BoundedSeekableByteChannel(openArchiveChannel(), dataOffset, entry.compressedSize),
                    entry.crc32,
                    entry.uncompressedSize
            );
        }
        if (compressionMethod == ZipMethod.STORED_ID
                || compressionMethod == ZipMethod.DEFLATED_ID
                || compressionMethod == ZipMethod.DEFLATE64_ID
                || compressionMethod == ZipMethod.BZIP2_ID
                || compressionMethod == ZipMethod.LZMA_ID
                || compressionMethod == ZipMethod.XZ_ID
                || isZstandardMethod(compressionMethod)) {
            return new ByteArraySeekableByteChannel(readEntryBytes(path, entry, dataOffset));
        }
        throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
    }

    /// Opens an input stream for an entry path.
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return manageInputStream(newInputStreamLocked(path, options));
        }
    }

    /// Opens an entry input stream while the caller holds the shared operation lock.
    private InputStream newInputStreamLocked(Path path, OpenOption... options) throws IOException {
        requireReadOnlyOptions(options);
        ZipEntryRecord entry = requireReadableEntry(path);
        requireSupportedEncryption(path, entry);
        int compressionMethod = entry.compressionMethod();
        if (compressionMethod != ZipMethod.STORED_ID
                && compressionMethod != ZipMethod.DEFLATED_ID
                && compressionMethod != ZipMethod.DEFLATE64_ID
                && compressionMethod != ZipMethod.BZIP2_ID
                && compressionMethod != ZipMethod.LZMA_ID
                && compressionMethod != ZipMethod.XZ_ID
                && !isZstandardMethod(compressionMethod)) {
            throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
        }

        long dataOffset = dataOffset(entry);
        return entryInputStream(path, entry, dataOffset);
    }

    /// Reads a symbolic link target from a ZIP archive path.
    public Path readSymbolicLink(Path link) throws IOException {
        try (Operation ignored = beginReadOperation()) {
            return readSymbolicLinkLocked(link);
        }
    }

    /// Reads a symbolic link target while the caller holds the shared operation lock.
    private Path readSymbolicLinkLocked(Path link) throws IOException {
        ZipArkivoEntryAttributes attributes = readZipAttributes(link);
        if (!attributes.isSymbolicLink()) {
            throw new NotLinkException(link.toString());
        }
        try (InputStream input = newInputStream(link, StandardOpenOption.READ)) {
            return getPath(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /// Returns a readable entry record for a path.
    private ZipEntryRecord requireReadableEntry(Path path) throws IOException {
        String key = pathKey(path);
        ZipEntryRecord entry = requireEntry(key);
        if (entry.directory) {
            throw new IOException("ZIP entry is a directory: " + path);
        }
        return entry;
    }

    /// Opens a directory stream for an entry path.
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
        try (Operation ignored = beginReadOperation()) {
            checkAccessLocked(path, modes);
        }
    }

    /// Checks access while the caller holds the shared operation lock.
    private void checkAccessLocked(Path path, java.nio.file.AccessMode... modes) throws IOException {
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
        try (Operation ignored = beginReadOperation()) {
            return getFileAttributeViewLocked(path, type);
        }
    }

    /// Returns an attribute view while the caller holds the shared operation lock.
    private <V extends java.nio.file.attribute.FileAttributeView> @Nullable V getFileAttributeViewLocked(
            Path path,
            Class<V> type
    ) {
        Objects.requireNonNull(type, "type");
        if (type == BasicFileAttributeView.class || type == ZipArkivoEntryAttributeView.class) {
            return type.cast(new EntryAttributeView(this, path));
        }
        if (type == FileOwnerAttributeView.class) {
            return type.cast(new OwnerEntryAttributeView(this, path));
        }
        if (type == PosixFileAttributeView.class) {
            return type.cast(new PosixEntryAttributeView(this, path));
        }
        return null;
    }

    /// Reads typed attributes for an entry path.
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
        try (Operation ignored = beginReadOperation()) {
            return ZipFileStore.INSTANCE;
        }
    }

    /// Reads named attributes for an entry path.
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

    /// Returns a path matcher for this ZIP file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        try (Operation ignored = beginReadOperation()) {
            return ArkivoPathMatchers.create(syntaxAndPattern, '/');
        }
    }

    /// Returns a user principal lookup service for this ZIP file system.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        try (Operation ignored = beginReadOperation()) {
            return ZipPosixSupport.userPrincipalLookupService();
        }
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

        ZipLocks.lock(indexLock);
        try {
            loadedIndex = index;
            if (loadedIndex == null) {
                loadedIndex = readIndex();
                index = loadedIndex;
            }
            return loadedIndex;
        } finally {
            ZipLocks.unlock(indexLock);
        }
    }

    /// Reads the ZIP central directory index from archive storage.
    private ZipIndex readIndex() throws IOException {
        try (ArchiveChannel channel = openArchiveChannel()) {
            ZipEndRecord endRecord = readEndRecord(channel);
            ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(config.entryNameEncoding());
            HashMap<String, ZipEntryRecord> entries = new HashMap<>();
            ArrayList<ZipEntryRecord> storageEntries = new ArrayList<>();
            HashSet<String> directories = new HashSet<>();
            HashMap<String, HashSet<String>> children = new HashMap<>();
            directories.add("");

            ByteBuffer centralDirectory = ByteBuffer.allocate(centralDirectoryIndexSize(endRecord.centralDirectorySize))
                    .order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, endRecord.actualCentralDirectoryOffset, centralDirectory);
            centralDirectory.flip();

            while (centralDirectory.hasRemaining()) {
                int offset = centralDirectory.position();
                if (centralDirectory.remaining() < ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE
                        || centralDirectory.getInt(offset) != CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
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
                int localHeaderDiskNumber = Short.toUnsignedInt(centralDirectory.getShort(offset + 34));
                int variableOffset = offset + ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE;
                int nextOffset = variableOffset + nameLength + extraLength + commentLength;
                if (nextOffset > centralDirectory.limit()) {
                    throw new IOException("Invalid ZIP central directory variable data length");
                }

                byte[] rawPath = readBytes(centralDirectory, variableOffset, nameLength);
                byte[] extraData = readBytes(centralDirectory, variableOffset + nameLength, extraLength);
                ZipExtraFields.validate(extraData);
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
                try {
                    decodedPath = decoder.decodePath(rawPath, flags, extraData);
                } catch (java.nio.charset.CharacterCodingException exception) {
                    throw new IOException("Failed to decode ZIP entry name", exception);
                }
                String decodedComment =
                        decoder.decodeComment(rawComment.length > 0 ? rawComment : null, flags, extraData);

                String key = entryKey(decodedPath);
                if (key.isEmpty()) {
                    throw new IOException("ZIP entry is missing a path");
                }
                boolean directory = decodedPath.endsWith("/");
                long actualLocalHeaderOffset = checkedZipOffsetAdd(
                        channel.volumeStartOffset(localHeaderDiskNumber),
                        localHeaderOffset,
                        "local header offset"
                );
                actualLocalHeaderOffset = checkedZipOffsetAdd(
                        actualLocalHeaderOffset,
                        endRecord.offsetAdjustment,
                        "local header offset"
                );
                byte[] localExtraData = readLocalExtraData(
                        channel,
                        actualLocalHeaderOffset,
                        rawPath,
                        flags,
                        method,
                        crc32,
                        compressedSize,
                        uncompressedSize,
                        extraData
                );
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
                        decodedComment,
                        rawComment.length > 0 ? rawComment : null,
                        lastModifiedTime,
                        dosTime(lastModifiedDate, lastModifiedTime),
                        directory
                );
                if (entries.put(key, entry) != null) {
                    throw new IOException("Duplicate ZIP entry path: " + decodedPath);
                }
                storageEntries.add(entry);
                addTreePath(key, directory, directories, children);

                centralDirectory.position(nextOffset);
            }

            validateDirectoryConflicts(entries, directories);
            return new ZipIndex(
                    Map.copyOf(entries),
                    storageEntriesByOffset(storageEntries),
                    Set.copyOf(directories),
                    freezeChildren(children)
            );
        }
    }

    /// Reads the current central directory metadata from one path-backed ZIP archive.
    static CentralDirectorySnapshot readCentralDirectorySnapshot(
            Path archivePath,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        Objects.requireNonNull(archivePath, "archivePath");
        Objects.requireNonNull(config, "config");
        if (ZipSplitVolumePaths.discover(archivePath) != null) {
            throw new UnsupportedOperationException("ZIP append mode does not support split archives");
        }

        try (ZipArkivoFileSystemImpl fileSystem = new ZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archivePath,
                null,
                snapshotReadConfig(config)
        )) {
            return readCentralDirectorySnapshot(fileSystem, config);
        }
    }

    /// Reads current central directory metadata without taking ownership of a repeatable single-volume source.
    static CentralDirectorySnapshot readCentralDirectorySnapshot(
            ArkivoSeekableChannelSource source,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(config, "config");
        try (ZipArkivoFileSystemImpl fileSystem = new ZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                null,
                new BorrowedVolumeSource(source),
                snapshotReadConfig(config)
        )) {
            return readCentralDirectorySnapshot(fileSystem, config);
        }
    }

    /// Returns a read-only configuration used while indexing an archive for an update.
    private static ZipArkivoFileSystemConfig snapshotReadConfig(ZipArkivoFileSystemConfig config) {
        return new ZipArkivoFileSystemConfig(
                Set.of(StandardOpenOption.READ),
                config.passwordProvider(),
                config.defaultEncryption(),
                ZipArkivoFileSystemConfig.NO_SPLIT_SIZE,
                config.entryNameEncoding(),
                config.threadSafety(),
                null,
                null,
                null
        );
    }

    /// Reads central directory metadata through an already opened read-only ZIP file system.
    private static CentralDirectorySnapshot readCentralDirectorySnapshot(
            ZipArkivoFileSystemImpl fileSystem,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        ZipIndex index = fileSystem.index();
        try (ArchiveChannel channel = fileSystem.openArchiveChannel()) {
            ZipEndRecord endRecord = readEndRecord(channel);
            ByteBuffer centralDirectory =
                    ByteBuffer.allocate(centralDirectoryIndexSize(endRecord.centralDirectorySize));
            readFully(channel, endRecord.actualCentralDirectoryOffset, centralDirectory);
            centralDirectory.flip();
            return new CentralDirectorySnapshot(
                    index.storageEntries.size(),
                    index.entries.keySet(),
                    centralDirectoryEntrySnapshots(
                            centralDirectory,
                            config.entryNameEncoding(),
                            index,
                            channel
                    ),
                    fileSystem.preambleSize(),
                    endRecord.archiveComment
            );
        }
    }

    /// Reads raw central directory entries together with their normalized keys.
    private static @Unmodifiable List<CentralDirectoryEntrySnapshot> centralDirectoryEntrySnapshots(
            ByteBuffer centralDirectory,
            ZipEntryNameEncoding entryNameEncoding,
            ZipIndex index,
            SeekableByteChannel channel
    ) throws IOException {
        ByteBuffer buffer = centralDirectory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(entryNameEncoding);
        ArrayList<CentralDirectoryEntrySnapshot> entries = new ArrayList<>();
        while (buffer.hasRemaining()) {
            int offset = buffer.position();
            if (buffer.remaining() < ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE
                    || buffer.getInt(offset) != CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
                throw new IOException("Invalid ZIP central directory header");
            }

            int flags = Short.toUnsignedInt(buffer.getShort(offset + 8));
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            int variableOffset = offset + ZIP_CENTRAL_DIRECTORY_HEADER_MIN_SIZE;
            int nextOffset = variableOffset + nameLength + extraLength + commentLength;
            if (nextOffset > buffer.limit()) {
                throw new IOException("Invalid ZIP central directory variable data length");
            }

            byte[] rawPath = readBytes(buffer, variableOffset, nameLength);
            byte[] extraData = readBytes(buffer, variableOffset + nameLength, extraLength);
            ZipExtraFields.validate(extraData);
            String decodedPath;
            try {
                decodedPath = decoder.decodePath(rawPath, flags, extraData);
            } catch (java.nio.charset.CharacterCodingException exception) {
                throw new IOException("Failed to decode ZIP entry name", exception);
            }

            String key = entryKey(decodedPath);
            if (key.isEmpty()) {
                throw new IOException("ZIP entry is missing a path");
            }
            ZipEntryRecord indexedEntry = index.entries.get(key);
            if (indexedEntry == null) {
                throw new IOException("ZIP central directory entry is missing from the parsed index: " + decodedPath);
            }
            entries.add(new CentralDirectoryEntrySnapshot(
                    key,
                    readBytes(buffer, offset, nextOffset - offset),
                    indexedEntry.localHeaderOffset,
                    localRecordSize(channel, indexedEntry)
            ));
            buffer.position(nextOffset);
        }
        return List.copyOf(entries);
    }

    /// Returns the exact byte size of an entry local header, compressed data, and optional data descriptor.
    private static long localRecordSize(SeekableByteChannel channel, ZipEntryRecord entry) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(ZIP_LOCAL_FILE_HEADER_MIN_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, entry.localHeaderOffset, header);
        header.flip();
        if (header.getInt(0) != LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP local file header");
        }

        int nameLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET));
        int extraLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET));
        long dataOffset = localHeaderVariableOffset(entry.localHeaderOffset, nameLength, "local file data offset");
        dataOffset = checkedZipOffsetAdd(dataOffset, extraLength, "local file data offset");
        long recordEnd = checkedZipOffsetAdd(dataOffset, entry.compressedSize, "local file data end");
        if ((entry.generalPurposeFlags & DATA_DESCRIPTOR_FLAG) != 0) {
            recordEnd = checkedZipOffsetAdd(
                    recordEnd,
                    dataDescriptorSize(channel, recordEnd, entry),
                    "local record end"
            );
        }
        if (recordEnd > channel.size()) {
            throw new IOException("ZIP local record extends beyond archive storage");
        }
        return recordEnd - entry.localHeaderOffset;
    }

    /// Returns the exact size of a ZIP32 or ZIP64 data descriptor at the given offset.
    private static int dataDescriptorSize(
            SeekableByteChannel channel,
            long offset,
            ZipEntryRecord entry
    ) throws IOException {
        long remaining = channel.size() - offset;
        if (remaining < 12) {
            throw new IOException("ZIP data descriptor is truncated");
        }
        int readSize = (int) Math.min(remaining, ZIP_DATA_DESCRIPTOR_MAX_SIZE);
        ByteBuffer descriptor = ByteBuffer.allocate(readSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, offset, descriptor);
        descriptor.flip();

        boolean preferZip64 = entry.compressedSize > UINT32_MAX
                || entry.uncompressedSize > UINT32_MAX
                || ZipExtraFields.find(
                entry.localExtraData,
                ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID
        ) != null;
        int size;
        if (preferZip64) {
            size = matchingDataDescriptorSize(descriptor, entry, true, true);
            if (size < 0) {
                size = matchingDataDescriptorSize(descriptor, entry, false, true);
            }
            if (size < 0) {
                size = matchingDataDescriptorSize(descriptor, entry, true, false);
            }
            if (size < 0) {
                size = matchingDataDescriptorSize(descriptor, entry, false, false);
            }
        } else {
            size = matchingDataDescriptorSize(descriptor, entry, true, false);
            if (size < 0) {
                size = matchingDataDescriptorSize(descriptor, entry, false, false);
            }
            if (size < 0) {
                size = matchingDataDescriptorSize(descriptor, entry, true, true);
            }
            if (size < 0) {
                size = matchingDataDescriptorSize(descriptor, entry, false, true);
            }
        }
        if (size < 0) {
            if (entry.directory && entry.compressedSize == 0 && entry.uncompressedSize == 0) {
                return 0;
            }
            throw new IOException(
                    "ZIP data descriptor does not match central directory metadata: " + entry.path
            );
        }
        return size;
    }

    /// Returns a matching data descriptor size, or `-1` when the selected layout does not match.
    private static int matchingDataDescriptorSize(
            ByteBuffer descriptor,
            ZipEntryRecord entry,
            boolean signature,
            boolean zip64
    ) {
        int size = (signature ? Integer.BYTES : 0)
                + Integer.BYTES
                + (zip64 ? Long.BYTES * 2 : Integer.BYTES * 2);
        if (descriptor.remaining() < size) {
            return -1;
        }

        ByteBuffer candidate = descriptor.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (signature && candidate.getInt() != DATA_DESCRIPTOR_SIGNATURE) {
            return -1;
        }
        long crc32 = Integer.toUnsignedLong(candidate.getInt());
        long compressedSize = zip64 ? candidate.getLong() : Integer.toUnsignedLong(candidate.getInt());
        long uncompressedSize = zip64 ? candidate.getLong() : Integer.toUnsignedLong(candidate.getInt());
        if (compressedSize < 0 || uncompressedSize < 0) {
            return -1;
        }
        return crc32 == entry.crc32
                && compressedSize == entry.compressedSize
                && uncompressedSize == entry.uncompressedSize
                ? size
                : -1;
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
        if (header.getInt(0) != LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP local file header");
        }
        int nameLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET));
        int extraLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET));
        long dataOffset = localHeaderVariableOffset(entry.localHeaderOffset, nameLength, "local file data offset");
        return checkedZipOffsetAdd(dataOffset, extraLength, "local file data offset");
    }

    /// Reads a ZIP entry into a byte array.
    private byte[] readEntryBytes(Path path, ZipEntryRecord entry, long dataOffset) throws IOException {
        if (entry.uncompressedSize > Integer.MAX_VALUE) {
            throw new IOException("ZIP entry is too large for seekable decoded access: " + path);
        }
        try (InputStream input = entryInputStream(path, entry, dataOffset)) {
            return input.readAllBytes();
        }
    }

    /// Opens a ZIP entry data stream.
    private InputStream entryInputStream(Path path, ZipEntryRecord entry, long dataOffset) throws IOException {
        SeekableByteChannel archive = openArchiveChannel();
        boolean completed = false;
        Throwable failure = null;
        try {
            SeekableByteChannel compressed = new BoundedSeekableByteChannel(archive, dataOffset, entry.compressedSize);
            InputStream input = Channels.newInputStream(compressed);
            ZipAesExtraField aes = entry.aesExtraField();
            if (entry.encrypted()) {
                input = aes != null
                        ? openAesDecryptingStream(path, entry, aes, input)
                        : openTraditionalDecryptingStream(path, entry, input);
            }
            if (entry.compressionMethod() == ZipMethod.DEFLATED_ID) {
                input = new EntryInflaterInputStream(input);
            } else if (entry.compressionMethod() == ZipMethod.DEFLATE64_ID) {
                input = openDeflate64InputStream(input);
            } else if (entry.compressionMethod() == ZipMethod.BZIP2_ID) {
                input = openBzip2InputStream(input);
            } else if (isZstandardMethod(entry.compressionMethod())) {
                input = openZstandardInputStream(input);
            } else if (entry.compressionMethod() == ZipMethod.LZMA_ID) {
                input = openLzmaInputStream(input, entry.uncompressedSize, entry.generalPurposeFlags);
            } else if (entry.compressionMethod() == ZipMethod.XZ_ID) {
                input = openXzInputStream(input);
            }
            long expectedCrc32 = aes != null ? ZipArkivoEntryAttributes.UNKNOWN_CRC32 : entry.crc32;
            input = new ValidatingEntryInputStream(input, expectedCrc32, entry.uncompressedSize);
            completed = true;
            return input;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (!completed) {
                closeAfterFailedOpen(archive, failure);
            }
        }
    }

    /// Opens a Deflate64 decoding stream and closes the compressed stream if setup fails.
    private static InputStream openDeflate64InputStream(InputStream input) throws IOException {
        try {
            return new Deflate64InputStream(input);
        } catch (RuntimeException | Error exception) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens a BZIP2 decoding stream and closes the compressed stream if setup fails.
    private static InputStream openBzip2InputStream(InputStream input) throws IOException {
        try {
            return new BZip2InputStream(input);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens a Zstandard decoding stream and closes the compressed stream if setup fails.
    private static InputStream openZstandardInputStream(InputStream input) throws IOException {
        try {
            return new ZstdInputStream(input);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens a ZIP LZMA decoding stream and closes the compressed stream if setup fails.
    private static InputStream openLzmaInputStream(
            InputStream input,
            long uncompressedSize,
            int flags
    ) throws IOException {
        try {
            readUnsignedShort(input);
            int propertySize = readUnsignedShort(input);
            if (propertySize != LZMA_PROPERTY_SIZE) {
                throw new IOException("Unsupported ZIP LZMA property size: " + propertySize);
            }
            int properties = readRequiredByte(input);
            int dictionarySize = readInt(input);
            if (dictionarySize < 0) {
                throw new IOException("Unsupported ZIP LZMA dictionary size: "
                        + Integer.toUnsignedLong(dictionarySize));
            }
            boolean usesEndMarker = (flags & LZMA_EOS_MARKER_FLAG) != 0;
            if (!usesEndMarker && uncompressedSize == ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
                throw new IOException("ZIP LZMA entry without EOS marker requires an uncompressed size");
            }
            long expectedUncompressedSize = usesEndMarker ? -1L : uncompressedSize;
            return new LzmaInputStream(input, expectedUncompressedSize, properties, dictionarySize);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens an XZ decoding stream and closes the compressed stream if setup fails.
    private static InputStream openXzInputStream(InputStream input) throws IOException {
        try {
            return new XzInputStream(input, false);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens a WinZip AES decrypting stream for an entry.
    private InputStream openAesDecryptingStream(
            Path path,
            ZipEntryRecord entry,
            ZipAesExtraField aes,
            InputStream input
    ) throws IOException {
        if ((entry.generalPurposeFlags & STRONG_ENCRYPTION_FLAG) != 0) {
            throw new IOException("Unsupported ZIP encryption method");
        }
        return ZipAesCrypto.openDecryptingStream(input, aes, passwordForEntry(path), entry.compressedSize);
    }

    /// Opens a traditional ZIP decrypting stream for an entry.
    private InputStream openTraditionalDecryptingStream(
            Path path,
            ZipEntryRecord entry,
            InputStream input
    ) throws IOException {
        requireTraditionalEncryption(entry);
        if (entry.compressedSize < ZipTraditionalCrypto.HEADER_SIZE) {
            throw new IOException("Encrypted ZIP entry is missing its encryption header: " + path);
        }
        return ZipTraditionalCrypto.openDecryptingStream(
                input,
                passwordForEntry(path),
                encryptionVerificationByte(entry)
        );
    }

    /// Requires an encrypted entry to use the traditional ZIP encryption method.
    private static void requireTraditionalEncryption(ZipEntryRecord entry) throws IOException {
        if ((entry.generalPurposeFlags & STRONG_ENCRYPTION_FLAG) != 0
                || ZipAesExtraField.isAes(entry.method, entry.centralDirectoryExtraData)) {
            throw new IOException("Unsupported ZIP encryption method");
        }
    }

    /// Requires an encrypted entry to describe a supported ZIP encryption method.
    private static void requireSupportedEncryption(Path path, ZipEntryRecord entry) throws IOException {
        if (!entry.encrypted()) {
            return;
        }
        if ((entry.generalPurposeFlags & STRONG_ENCRYPTION_FLAG) != 0
                || (entry.method == WINZIP_AES_METHOD && entry.aesExtraField() == null)) {
            throw new IOException("Unsupported ZIP encryption method: " + path);
        }
    }

    /// Returns the password for an encrypted entry path.
    private byte[] passwordForEntry(Path path) throws IOException {
        ArkivoPasswordProvider passwordProvider = config.passwordProvider();
        if (passwordProvider == null) {
            throw new IOException("ZIP entry requires a password: " + path);
        }
        byte[] password = passwordProvider.passwordForEntry(path);
        if (password == null) {
            throw new IOException("ZIP entry requires a password: " + path);
        }
        return password;
    }

    /// Returns the traditional ZIP password verification byte.
    private static int encryptionVerificationByte(ZipEntryRecord entry) {
        if ((entry.generalPurposeFlags & DATA_DESCRIPTOR_FLAG) != 0) {
            return entry.lastModifiedDosTime >>> 8;
        }
        return (int) (entry.crc32 >>> 24);
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
    private static String entryKey(String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\")
                || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException("ZIP entry path must be relative");
        }
        ArrayList<String> names = new ArrayList<>();
        int start = 0;
        while (start <= path.length()) {
            int end = nextEntryPathSeparator(path, start);

            String name = path.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IOException("ZIP entry path must not contain ..");
                }
                names.add(name);
            }
            start = end + 1;
        }
        return String.join("/", names);
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

    /// Rejects real entries that also need to behave as directories for child entries.
    private static void validateDirectoryConflicts(
            HashMap<String, ZipEntryRecord> entries,
            HashSet<String> directories
    ) throws IOException {
        for (String directory : directories) {
            ZipEntryRecord entry = entries.get(directory);
            if (entry != null && !entry.directory) {
                throw new IOException("ZIP entry path conflicts with directory: " + entry.path);
            }
        }
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

    /// Reads raw local file header extra data and validates local header metadata.
    private static byte[] readLocalExtraData(
            SeekableByteChannel channel,
            long offset,
            byte[] expectedName,
            int expectedFlags,
            int expectedMethod,
            long expectedCrc32,
            long expectedCompressedSize,
            long expectedUncompressedSize,
            byte[] expectedExtraData
    ) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(ZIP_LOCAL_FILE_HEADER_MIN_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, offset, header);
        header.flip();
        if (header.getInt(0) != LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IOException("Invalid ZIP local file header");
        }
        int flags = Short.toUnsignedInt(header.getShort(6));
        int method = Short.toUnsignedInt(header.getShort(8));
        long crc32 = Integer.toUnsignedLong(header.getInt(ZIP_LOCAL_FILE_HEADER_CRC32_OFFSET));
        long compressedSize = Integer.toUnsignedLong(header.getInt(ZIP_LOCAL_FILE_HEADER_COMPRESSED_SIZE_OFFSET));
        long uncompressedSize = Integer.toUnsignedLong(header.getInt(ZIP_LOCAL_FILE_HEADER_UNCOMPRESSED_SIZE_OFFSET));
        int nameLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET));
        int extraLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET));
        long extraOffset = localHeaderVariableOffset(offset, nameLength, "local extra data offset");
        if (flags != expectedFlags) {
            throw new IOException("ZIP local header flags do not match central directory");
        }
        if (method != expectedMethod) {
            throw new IOException("ZIP local header method does not match central directory");
        }
        if (nameLength != expectedName.length) {
            throw new IOException("ZIP local header name does not match central directory");
        }
        ByteBuffer name = ByteBuffer.allocate(nameLength);
        readFully(
                channel,
                checkedZipOffsetAdd(offset, ZIP_LOCAL_FILE_HEADER_MIN_SIZE, "local file name offset"),
                name
        );
        if (!Arrays.equals(name.array(), expectedName)) {
            throw new IOException("ZIP local header name does not match central directory");
        }

        ByteBuffer extra = ByteBuffer.allocate(extraLength);
        readFully(channel, extraOffset, extra);
        byte[] extraData = extra.array();
        ZipExtraFields.validate(extraData);
        validateLocalAesExtraData(flags, method, extraData, expectedExtraData);
        if ((flags & DATA_DESCRIPTOR_FLAG) == 0) {
            if (crc32 != expectedCrc32) {
                throw new IOException("ZIP local header CRC-32 does not match central directory");
            }
            Zip64Values localZip64 = Zip64Values.read(extraData, uncompressedSize, compressedSize, 0L);
            if (localZip64.compressedSize != expectedCompressedSize) {
                throw new IOException("ZIP local header compressed size does not match central directory");
            }
            if (localZip64.uncompressedSize != expectedUncompressedSize) {
                throw new IOException("ZIP local header uncompressed size does not match central directory");
            }
        }
        return extraData;
    }

    /// Validates local WinZip AES metadata against the central directory metadata that drives entry decoding.
    private static void validateLocalAesExtraData(
            int flags,
            int method,
            byte[] localExtraData,
            byte[] centralDirectoryExtraData
    ) throws IOException {
        if ((flags & ENCRYPTED_FLAG) == 0 || method != WINZIP_AES_METHOD) {
            return;
        }

        ZipAesExtraField centralAes = ZipAesExtraField.readValidated(centralDirectoryExtraData);
        if (centralAes == null) {
            return;
        }

        ZipAesExtraField localAes = ZipAesExtraField.readValidated(localExtraData);
        if (localAes == null || !localAes.metadataMatches(centralAes)) {
            throw new IOException("ZIP local header WinZip AES extra field does not match central directory");
        }
    }

    /// Returns the offset of the local header variable data after the entry name.
    private static long localHeaderVariableOffset(
            long localHeaderOffset,
            int nameLength,
            String description
    ) throws IOException {
        long offset = checkedZipOffsetAdd(
                localHeaderOffset,
                ZIP_LOCAL_FILE_HEADER_MIN_SIZE,
                description
        );
        return checkedZipOffsetAdd(offset, nameLength, description);
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
    private static ZipEndRecord readEndRecord(ArchiveChannel channel) throws IOException {
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
            if (buffer.getInt(index) != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
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
            byte[] archiveComment = readBytes(
                    buffer,
                    index + ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE,
                    commentLength
            );
            if (centralDirectorySize == UINT32_MAX || centralDirectoryOffset == UINT32_MAX) {
                return readZip64EndRecord(channel, searchOffset + index, archiveComment);
            }

            int centralDirectoryDiskNumber = Short.toUnsignedInt(buffer.getShort(index + 6));
            long actualCentralDirectoryOffset = searchOffset + index - centralDirectorySize;
            long storedCentralDirectoryOffset = checkedZipOffsetAdd(
                    channel.volumeStartOffset(centralDirectoryDiskNumber),
                    centralDirectoryOffset,
                    "central directory offset"
            );
            if (actualCentralDirectoryOffset < storedCentralDirectoryOffset) {
                throw new IOException("ZIP central directory offset is inconsistent");
            }
            return new ZipEndRecord(
                    centralDirectorySize,
                    centralDirectoryOffset,
                    actualCentralDirectoryOffset,
                    actualCentralDirectoryOffset - storedCentralDirectoryOffset,
                    archiveComment
            );
        }

        throw new IOException("ZIP end of central directory record not found");
    }

    /// Returns a central directory size that can be buffered for indexing.
    private static int centralDirectoryIndexSize(long size) throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new IOException("ZIP central directory is too large to index");
        }
        return (int) size;
    }

    /// Reads ZIP64 central directory location from the ZIP64 end records.
    private static ZipEndRecord readZip64EndRecord(
            ArchiveChannel channel,
            long eocdOffset,
            byte[] archiveComment
    ) throws IOException {
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

        long zip64EndDiskNumber = Integer.toUnsignedLong(locator.getInt(4));
        long storedZip64EndOffset = locator.getLong(8);
        long actualZip64EndOffset = locateZip64EndRecord(
                channel,
                locatorOffset,
                zip64EndDiskNumber,
                storedZip64EndOffset
        );
        ByteBuffer fixedRecord = ByteBuffer.allocate(ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, actualZip64EndOffset, fixedRecord);
        fixedRecord.flip();
        if (fixedRecord.getInt(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            throw new IOException("ZIP64 end of central directory record not found");
        }

        long centralDirectorySize = readZip64UnsignedLong(
                fixedRecord,
                40,
                "central directory size"
        );
        long centralDirectoryOffset = readZip64UnsignedLong(
                fixedRecord,
                48,
                "central directory offset"
        );
        long centralDirectoryDiskNumber = Integer.toUnsignedLong(fixedRecord.getInt(20));
        long actualCentralDirectoryOffset = actualZip64EndOffset - centralDirectorySize;
        long storedCentralDirectoryOffset = checkedZipOffsetAdd(
                channel.volumeStartOffset(centralDirectoryDiskNumber),
                centralDirectoryOffset,
                "central directory offset"
        );
        if (actualCentralDirectoryOffset < storedCentralDirectoryOffset) {
            throw new IOException("ZIP64 central directory offset is inconsistent");
        }
        return new ZipEndRecord(
                centralDirectorySize,
                centralDirectoryOffset,
                actualCentralDirectoryOffset,
                actualCentralDirectoryOffset - storedCentralDirectoryOffset,
                archiveComment
        );
    }

    /// Adds ZIP storage offsets and rejects values that overflow Java offsets.
    private static long checkedZipOffsetAdd(long left, long right, String description) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException("ZIP " + description + " is too large", exception);
        }
    }

    /// Reads a ZIP64 unsigned 64-bit value that must fit in a Java `long`.
    private static long readZip64UnsignedLong(ByteBuffer buffer, int offset, String description) throws IOException {
        long value = buffer.getLong(offset);
        if (value < 0) {
            throw new IOException("ZIP64 " + description + " is too large");
        }
        return value;
    }

    /// Locates the actual ZIP64 end record offset in physical storage.
    private static long locateZip64EndRecord(
            ArchiveChannel channel,
            long locatorOffset,
            long storedZip64EndDiskNumber,
            long storedZip64EndOffset
    ) throws IOException {
        long storedZip64EndVolumeOffset = channel.volumeStartOffset(storedZip64EndDiskNumber);
        long storedZip64EndAbsoluteOffset = zip64StoredEndOffset(
                storedZip64EndVolumeOffset,
                storedZip64EndOffset
        );
        long storedZip64EndLimit = zip64StoredEndLimit(storedZip64EndAbsoluteOffset);
        if (storedZip64EndLimit >= 0 && storedZip64EndLimit <= locatorOffset) {
            ByteBuffer signature = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, storedZip64EndAbsoluteOffset, signature);
            signature.flip();
            if (signature.getInt(0) == ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                return storedZip64EndAbsoluteOffset;
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

    /// Returns a stored ZIP64 end offset, or a negative sentinel when the stored value is unusable.
    private static long zip64StoredEndOffset(long volumeOffset, long storedOffset) throws IOException {
        if (storedOffset < 0) {
            return -1L;
        }
        try {
            return Math.addExact(volumeOffset, storedOffset);
        } catch (ArithmeticException exception) {
            return -1L;
        }
    }

    /// Returns the end boundary of a stored ZIP64 end record, or a negative sentinel when unusable.
    private static long zip64StoredEndLimit(long absoluteOffset) {
        if (absoluteOffset < 0) {
            return -1L;
        }
        try {
            return Math.addExact(absoluteOffset, ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE);
        } catch (ArithmeticException exception) {
            return -1L;
        }
    }

    /// Opens a channel for the physical storage that contains the beginning of this ZIP archive.
    private ArchiveChannel openArchiveChannel() throws IOException {
        if (archivePath != null) {
            List<Path> splitVolumePaths = ZipSplitVolumePaths.discover(archivePath);
            if (splitVolumePaths != null) {
                return ConcatenatedArchiveChannel.open(index -> {
                    if (index < 0 || index >= splitVolumePaths.size()) {
                        return null;
                    }
                    return Files.newByteChannel(splitVolumePaths.get((int) index), config.openOptions());
                });
            }
            return new SingleArchiveChannel(Files.newByteChannel(archivePath, config.openOptions()));
        }

        assert volumes != null;
        return ConcatenatedArchiveChannel.open(volumes);
    }

    /// Closes a channel after setup failed without replacing the setup failure.
    private static void closeAfterFailedOpen(SeekableByteChannel channel, @Nullable Throwable failure)
            throws IOException {
        try {
            channel.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
            } else {
                throw exception;
            }
        }
    }

    /// Returns the current failure with the given exception added as a suppressed failure when needed.
    private static Throwable mergeFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure != null) {
            failure.addSuppressed(exception);
            return failure;
        }
        return exception;
    }

    /// Throws the given failure while preserving its original checked or unchecked type.
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
            if (buffer.getInt(index) != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
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

            if (centralDirectorySize == UINT32_MAX || centralDirectoryOffset == UINT32_MAX) {
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
                if (buffer.getInt(index) != LOCAL_FILE_HEADER_SIGNATURE) {
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
        if (header.getInt(0) != LOCAL_FILE_HEADER_SIGNATURE) {
            return false;
        }

        int nameLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_NAME_LENGTH_OFFSET));
        int extraLength = Short.toUnsignedInt(header.getShort(ZIP_LOCAL_FILE_HEADER_EXTRA_LENGTH_OFFSET));
        return nameLength > 0
                && offset + ZIP_LOCAL_FILE_HEADER_MIN_SIZE + (long) nameLength + extraLength <= storageSize;
    }

    /// Exposes ZIP archive bytes together with logical volume start offsets.
    private interface ArchiveChannel extends SeekableByteChannel {
        /// Returns the absolute logical stream offset where a ZIP volume starts.
        long volumeStartOffset(long volumeIndex) throws IOException;
    }

    /// Adapts a single physical archive channel to the archive channel contract.
    @NotNullByDefault
    private static final class SingleArchiveChannel implements ArchiveChannel {
        /// The wrapped archive channel.
        private final SeekableByteChannel channel;

        /// Whether this archive channel is open.
        private boolean open = true;

        /// Whether the wrapped archive channel has been closed.
        private boolean channelClosed;

        /// Creates a single-volume archive channel.
        private SingleArchiveChannel(SeekableByteChannel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        /// Reads bytes from the wrapped channel.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            return channel.read(destination);
        }

        /// Writes bytes to the wrapped channel.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            return channel.write(source);
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return channel.position();
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            channel.position(newPosition);
            return this;
        }

        /// Returns the wrapped channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return channel.size();
        }

        /// Truncates the wrapped channel.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            channel.truncate(size);
            return this;
        }

        /// Returns whether the wrapped channel is open.
        @Override
        public boolean isOpen() {
            return open && channel.isOpen();
        }

        /// Closes the wrapped channel.
        @Override
        public void close() throws IOException {
            if (!open && channelClosed) {
                return;
            }
            open = false;
            channel.close();
            channelClosed = true;
        }

        /// Returns the start offset of the only physical volume.
        @Override
        public long volumeStartOffset(long volumeIndex) throws IOException {
            ensureOpen();
            if (volumeIndex != 0) {
                throw new IOException("ZIP volume is not available: " + volumeIndex);
            }
            return 0L;
        }

        /// Requires this archive channel to be open.
        private void ensureOpen() throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Presents finite ZIP volumes as one read-only logical seekable channel.
    @NotNullByDefault
    private static final class ConcatenatedArchiveChannel implements ArchiveChannel {
        /// The opened volume channels.
        private final SeekableByteChannel[] channels;

        /// The logical start offset of each volume.
        private final long[] starts;

        /// The total logical archive size.
        private final long size;

        /// The current logical channel position.
        private long position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Whether each opened volume channel has been closed.
        private final boolean[] channelClosed;

        /// Opens all available volumes from the source.
        private static ConcatenatedArchiveChannel open(ArkivoVolumeSource volumes) throws IOException {
            ArrayList<SeekableByteChannel> channels = new ArrayList<>();
            ArrayList<Long> starts = new ArrayList<>();
            try {
                long offset = 0L;
                for (long index = 0; ; index++) {
                    SeekableByteChannel channel = volumes.openVolume(index);
                    if (channel == null) {
                        break;
                    }
                    channels.add(channel);
                    starts.add(offset);
                    try {
                        offset = Math.addExact(offset, channel.size());
                    } catch (ArithmeticException exception) {
                        throw new IOException("ZIP volumes are too large", exception);
                    }
                }
                if (channels.isEmpty()) {
                    throw new IOException("ZIP first volume is not available");
                }
                long[] startOffsets = new long[starts.size()];
                for (int index = 0; index < starts.size(); index++) {
                    startOffsets[index] = starts.get(index);
                }
                return new ConcatenatedArchiveChannel(
                        channels.toArray(SeekableByteChannel[]::new),
                        startOffsets,
                        offset
                );
            } catch (IOException | RuntimeException | Error exception) {
                closeAllAfterFailedOpen(channels, exception);
                throw exception;
            }
        }

        /// Closes opened channels after setup fails without replacing the setup failure.
        private static void closeAllAfterFailedOpen(List<SeekableByteChannel> channels, Throwable failure) {
            try {
                closeAll(channels);
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
        }

        /// Creates a concatenated archive channel.
        private ConcatenatedArchiveChannel(SeekableByteChannel[] channels, long[] starts, long size) {
            this.channels = channels.clone();
            this.starts = starts.clone();
            this.size = size;
            this.channelClosed = new boolean[channels.length];
        }

        /// Reads bytes from the current logical channel position.
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

            int total = 0;
            while (destination.hasRemaining() && position < size) {
                int volumeIndex = volumeIndex(position);
                SeekableByteChannel channel = channels[volumeIndex];
                long localPosition = position - starts[volumeIndex];
                long volumeEnd = volumeIndex + 1 < starts.length ? starts[volumeIndex + 1] : size;
                long availableInVolume = volumeEnd - position;
                int originalLimit = destination.limit();
                if (destination.remaining() > availableInVolume) {
                    destination.limit(destination.position() + (int) availableInVolume);
                }

                int read;
                try {
                    channel.position(localPosition);
                    read = channel.read(destination);
                } finally {
                    destination.limit(originalLimit);
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    return total > 0 ? total : 0;
                }
                position += read;
                total += read;
            }
            return total > 0 ? total : -1;
        }

        /// Always rejects writes because split ZIP archive views are read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            Objects.requireNonNull(source, "source");
            throw new NonWritableChannelException();
        }

        /// Returns the current logical position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current logical position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the total logical size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return size;
        }

        /// Always rejects truncation because split ZIP archive views are read-only.
        @Override
        public SeekableByteChannel truncate(long newSize) throws IOException {
            ensureOpen();
            if (newSize < 0) {
                throw new IllegalArgumentException("newSize must not be negative");
            }
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes all opened volume channels.
        @Override
        public void close() throws IOException {
            if (!open && allChannelsClosed()) {
                return;
            }
            open = false;
            closeRemainingChannels();
        }

        /// Returns the start offset of a logical ZIP volume.
        @Override
        public long volumeStartOffset(long volumeIndex) throws IOException {
            ensureOpen();
            if (volumeIndex < 0 || volumeIndex >= starts.length) {
                throw new IOException("ZIP volume is not available: " + volumeIndex);
            }
            return starts[(int) volumeIndex];
        }

        /// Returns the volume index that contains the given logical position.
        private int volumeIndex(long logicalPosition) {
            int index = Arrays.binarySearch(starts, logicalPosition);
            if (index >= 0) {
                return index;
            }
            return -index - 2;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }

        /// Returns whether every opened volume channel has been closed.
        private boolean allChannelsClosed() {
            for (boolean closed : channelClosed) {
                if (!closed) {
                    return false;
                }
            }
            return true;
        }

        /// Closes channels whose cleanup has not completed yet.
        private void closeRemainingChannels() throws IOException {
            Throwable failure = null;
            for (int index = 0; index < channels.length; index++) {
                if (channelClosed[index]) {
                    continue;
                }
                try {
                    channels[index].close();
                    channelClosed[index] = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = mergeFailure(failure, exception);
                }
            }
            throwFailure(failure);
        }

        /// Closes all channels and preserves suppressed close failures.
        private static void closeAll(List<SeekableByteChannel> channels) throws IOException {
            Throwable failure = null;
            for (SeekableByteChannel channel : channels) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = mergeFailure(failure, exception);
                }
            }
            throwFailure(failure);
        }
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

    /// Exposes a source to a temporary reader without transferring source ownership.
    @NotNullByDefault
    private record BorrowedVolumeSource(ArkivoSeekableChannelSource delegate) implements ArkivoVolumeSource {
        /// Creates a borrowed single-volume source.
        private BorrowedVolumeSource {
            Objects.requireNonNull(delegate, "delegate");
        }

        /// Opens the requested source volume.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            return delegate.openVolume(index);
        }

        /// Leaves source ownership with the caller.
        @Override
        public void close() {
        }
    }

    /// Stores central directory entry names and raw entry records from an existing ZIP archive.
    ///
    /// @param entryCount     the number of central directory entries
    /// @param entryNames     the normalized entry names already present in the archive
    /// @param entries        the raw central directory entries keyed by normalized entry name
    /// @param preambleSize   the number of bytes before the first ZIP local record
    /// @param archiveComment the raw end of central directory comment bytes
    record CentralDirectorySnapshot(
            long entryCount,
            @Unmodifiable Set<String> entryNames,
            @Unmodifiable List<CentralDirectoryEntrySnapshot> entries,
            long preambleSize,
            byte @Unmodifiable [] archiveComment
    ) {
        /// Creates a central directory snapshot.
        CentralDirectorySnapshot {
            entryNames = Set.copyOf(Objects.requireNonNull(entryNames, "entryNames"));
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            archiveComment = Objects.requireNonNull(archiveComment, "archiveComment").clone();
        }

        /// Returns a copy of the raw end of central directory comment bytes.
        @Override
        public byte @Unmodifiable [] archiveComment() {
            return archiveComment.clone();
        }
    }

    /// Stores one raw central directory entry from an existing ZIP archive.
    ///
    /// @param entryName         the normalized entry name
    /// @param bytes             the raw central directory entry bytes
    /// @param localHeaderOffset the actual local header offset in physical storage
    /// @param localRecordSize   the exact size of the local header, data, and optional descriptor
    record CentralDirectoryEntrySnapshot(
            String entryName,
            byte @Unmodifiable [] bytes,
            long localHeaderOffset,
            long localRecordSize
    ) {
        /// Creates a central directory entry snapshot.
        CentralDirectoryEntrySnapshot {
            Objects.requireNonNull(entryName, "entryName");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        /// Returns a copy of the raw central directory entry bytes.
        @Override
        public byte @Unmodifiable [] bytes() {
            return bytes.clone();
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

        /// The raw end of central directory comment bytes.
        private final byte @Unmodifiable [] archiveComment;

        /// Creates parsed ZIP end record values.
        private ZipEndRecord(
                long centralDirectorySize,
                long centralDirectoryOffset,
                long actualCentralDirectoryOffset,
                long offsetAdjustment,
                byte[] archiveComment
        ) {
            this.centralDirectorySize = centralDirectorySize;
            this.centralDirectoryOffset = centralDirectoryOffset;
            this.actualCentralDirectoryOffset = actualCentralDirectoryOffset;
            this.offsetAdjustment = offsetAdjustment;
            this.archiveComment = Objects.requireNonNull(archiveComment, "archiveComment").clone();
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

        /// The decoded comment text, or `null` when absent.
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

        /// The raw DOS last modification time field.
        private final int lastModifiedDosTime;

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
                @Nullable String comment,
                byte @Nullable [] rawComment,
                int lastModifiedDosTime,
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
            this.comment = comment;
            this.rawComment = rawComment;
            this.lastModifiedDosTime = lastModifiedDosTime;
            this.lastModifiedTime = lastModifiedTime;
            this.directory = directory;
        }

        /// Returns whether this entry is encrypted.
        private boolean encrypted() {
            return (generalPurposeFlags & ENCRYPTED_FLAG) != 0;
        }

        /// Returns the actual ZIP compression method for this entry.
        private int compressionMethod() {
            return ZipAesExtraField.compressionMethod(generalPurposeFlags, method, centralDirectoryExtraData);
        }

        /// Returns the ZIP encryption method for this entry.
        private ZipEncryption encryption() {
            return ZipAesExtraField.encryption(generalPurposeFlags, method, centralDirectoryExtraData);
        }

        /// Returns WinZip AES metadata for this entry, or `null` when the entry does not use WinZip AES.
        private @Nullable ZipAesExtraField aesExtraField() {
            return ZipAesExtraField.read(centralDirectoryExtraData);
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
        public Object getAttribute(String attribute) throws IOException {
            return ArkivoFileStoreAttributes.get(this, attribute);
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

        /// Returns a copy of the raw encoded ZIP entry path bytes.
        @Override
        public byte[] rawPath() {
            ZipEntryRecord record = entry;
            return record != null ? record.rawPath.clone() : new byte[0];
        }

        /// Returns the decoded ZIP entry path text.
        @Override
        public String path() {
            ZipEntryRecord record = entry;
            return record != null ? record.path : Objects.requireNonNull(syntheticDirectoryKey, "syntheticDirectoryKey");
        }

        /// Returns the decoded ZIP entry comment text, or `null` when no comment is present.
        @Override
        public @Nullable String comment() {
            ZipEntryRecord record = entry;
            return record != null ? record.comment : null;
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
            return record != null ? ZipMethod.of(record.compressionMethod()) : ZipMethod.stored();
        }

        /// Returns the ZIP encryption method.
        @Override
        public ZipEncryption encryption() {
            ZipEntryRecord record = entry;
            return record != null ? record.encryption() : ZipEncryption.none();
        }

        /// Returns a copy of the raw local file header extra data bytes.
        @Override
        public byte[] localExtraData() {
            ZipEntryRecord record = entry;
            return record != null ? record.localExtraData.clone() : new byte[0];
        }

        /// Returns a copy of the raw central directory extra data bytes.
        @Override
        public byte[] centralDirectoryExtraData() {
            ZipEntryRecord record = entry;
            return record != null ? record.centralDirectoryExtraData.clone() : new byte[0];
        }

        /// Returns a copy of the raw ZIP entry comment bytes, or `null` when no comment is present.
        @Override
        public byte @Nullable [] rawComment() {
            ZipEntryRecord record = entry;
            return record != null && record.rawComment != null ? record.rawComment.clone() : null;
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
            return record != null
                    && !record.directory
                    && !ZipPosixSupport.isSymbolicLink(record.versionMadeBy, record.externalAttributes);
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
            ZipEntryRecord record = entry;
            return record != null && ZipPosixSupport.isSymbolicLink(record.versionMadeBy, record.externalAttributes);
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
            ZipEntryRecord record = entry;
            return record != null
                    ? ZipPosixSupport.permissions(record.versionMadeBy, record.externalAttributes, record.directory)
                    : ZipPosixSupport.defaultPermissions(true);
        }
    }

    /// Implements the synthesized POSIX entry attribute view.
    private static final class PosixEntryAttributeView implements PosixFileAttributeView {
        /// The file system used to read attributes.
        private final ZipArkivoFileSystemImpl fileSystem;

        /// The path whose attributes are exposed.
        private final Path path;

        /// Creates a synthesized POSIX attribute view.
        private PosixEntryAttributeView(ZipArkivoFileSystemImpl fileSystem, Path path) {
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
            return fileSystem.readZipAttributes(path);
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal getOwner() throws IOException {
            return fileSystem.readZipAttributes(path).owner();
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

        /// Sets the file owner.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets the file group.
        @Override
        public void setGroup(GroupPrincipal group) {
            Objects.requireNonNull(group, "group");
            throw new java.nio.file.ReadOnlyFileSystemException();
        }

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            throw new java.nio.file.ReadOnlyFileSystemException();
        }
    }

    /// Implements the synthesized owner entry attribute view.
    private static final class OwnerEntryAttributeView implements FileOwnerAttributeView {
        /// The file system used to read attributes.
        private final ZipArkivoFileSystemImpl fileSystem;

        /// The path whose owner is exposed.
        private final Path path;

        /// Creates a synthesized owner attribute view.
        private OwnerEntryAttributeView(ZipArkivoFileSystemImpl fileSystem, Path path) {
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
            return fileSystem.readZipAttributes(path).owner();
        }

        /// Sets the file owner.
        @Override
        public void setOwner(UserPrincipal owner) {
            Objects.requireNonNull(owner, "owner");
            throw new java.nio.file.ReadOnlyFileSystemException();
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

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
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

        /// Sets the raw ZIP entry comment bytes.
        @Override
        public void setRawComment(byte @Nullable [] rawComment) {
            throw new java.nio.file.ReadOnlyFileSystemException();
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

    /// Reads ZIP entry data and validates it against central directory metadata when the stream ends.
    @NotNullByDefault
    private static final class ValidatingEntryInputStream extends InputStream {
        /// The decoded entry data stream.
        private final InputStream input;

        /// The expected CRC-32 value from the central directory.
        private final long expectedCrc32;

        /// The expected uncompressed size from the central directory.
        private final long expectedUncompressedSize;

        /// The CRC-32 value of bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// The number of decoded bytes returned so far.
        private long uncompressedSize;

        /// Whether end-of-entry validation has completed.
        private boolean finishedEntry;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether the decoded entry data stream has been closed.
        private boolean inputClosed;

        /// Creates a validating ZIP entry stream.
        private ValidatingEntryInputStream(InputStream input, long expectedCrc32, long expectedUncompressedSize) {
            this.input = Objects.requireNonNull(input, "input");
            this.expectedCrc32 = expectedCrc32;
            this.expectedUncompressedSize = expectedUncompressedSize;
        }

        /// Reads one decoded byte from the entry.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads decoded bytes from the entry.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads decoded bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
            if (finishedEntry) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }

            int read = input.read(bytes, offset, length);
            if (read > 0) {
                crc32.update(bytes, offset, read);
                uncompressedSize += read;
            } else if (read < 0) {
                finishEntry();
            }
            return read;
        }

        /// Closes this entry stream after draining unread bytes for validation.
        @Override
        public void close() throws IOException {
            if (closed && inputClosed) {
                return;
            }

            Throwable failure = null;
            if (!closed) {
                closed = true;
                try {
                    byte[] discard = new byte[8192];
                    while (readUnchecked(discard, 0, discard.length) >= 0) {
                        // Drain unread entry data so central directory metadata can be validated.
                    }
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }

            if (!inputClosed) {
                try {
                    input.close();
                    inputClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = mergeFailure(failure, exception);
                }
            }

            throwFailure(failure);
        }

        /// Validates the decoded entry data against central directory metadata.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;
            if (expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32 && crc32.getValue() != expectedCrc32) {
                throw new IOException("ZIP entry data does not match central directory");
            }
            if (expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                    && uncompressedSize != expectedUncompressedSize) {
                throw new IOException("ZIP entry data does not match central directory");
            }
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP entry input stream is closed");
            }
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

        /// Whether the wrapped storage channel has been closed.
        private boolean channelClosed;

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
                channel.position(checkedZipOffsetAdd(offset, position, "bounded channel offset"));
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
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
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
        public SeekableByteChannel truncate(long newSize) throws IOException {
            ensureOpen();
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
            if (!open && channelClosed) {
                return;
            }
            open = false;
            channel.close();
            channelClosed = true;
        }

        /// Requires this bounded channel to be open.
        private void ensureOpen() throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Exposes a stored entry channel and validates sequentially consumed data against central directory metadata.
    @NotNullByDefault
    private static final class ValidatingStoredEntryByteChannel implements SeekableByteChannel {
        /// The bounded stored entry data channel.
        private final SeekableByteChannel channel;

        /// The expected CRC-32 value from the central directory.
        private final long expectedCrc32;

        /// The expected uncompressed size from the central directory.
        private final long expectedUncompressedSize;

        /// The CRC-32 value of sequential bytes read so far.
        private final CRC32 crc32 = new CRC32();

        /// The next channel position that can still be validated sequentially.
        private long validationPosition;

        /// Whether reads have stayed sequential from the beginning of the entry.
        private boolean validationActive = true;

        /// Whether end-of-entry validation has completed.
        private boolean finishedEntry;

        /// Whether this channel has been closed.
        private boolean closed;

        /// Whether the bounded stored entry data channel has been closed.
        private boolean channelClosed;

        /// Creates a validating stored entry channel.
        private ValidatingStoredEntryByteChannel(
                SeekableByteChannel channel,
                long expectedCrc32,
                long expectedUncompressedSize
        ) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.expectedCrc32 = expectedCrc32;
            this.expectedUncompressedSize = expectedUncompressedSize;
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            return readUnchecked(destination);
        }

        /// Reads bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(ByteBuffer destination) throws IOException {
            long readPosition = channel.position();
            int startPosition = destination.position();
            int read = channel.read(destination);
            if (read > 0) {
                if (validationActive && readPosition == validationPosition) {
                    ByteBuffer readBytes = destination.duplicate();
                    readBytes.position(startPosition);
                    readBytes.limit(startPosition + read);
                    crc32.update(readBytes);
                    validationPosition += read;
                } else {
                    validationActive = false;
                }
            } else if (read < 0 && validationActive) {
                finishEntry();
            }
            return read;
        }

        /// Always rejects writes because ZIP entry channels are read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            Objects.requireNonNull(source, "source");
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return channel.position();
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            channel.position(newPosition);
            if (newPosition != validationPosition) {
                validationActive = false;
            }
            return this;
        }

        /// Returns the visible entry size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return channel.size();
        }

        /// Always rejects truncation because ZIP entry channels are read-only.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return !closed && channel.isOpen();
        }

        /// Closes this channel after draining unread sequential data for validation.
        @Override
        public void close() throws IOException {
            if (closed && channelClosed) {
                return;
            }

            Throwable failure = null;
            if (!closed) {
                closed = true;
                if (validationActive && !finishedEntry && channel.isOpen()) {
                    try {
                        ByteBuffer discard = ByteBuffer.allocate(8192);
                        while (readUnchecked(discard) >= 0) {
                            discard.clear();
                        }
                    } catch (IOException | RuntimeException | Error exception) {
                        failure = exception;
                    }
                }
            }

            if (!channelClosed) {
                try {
                    channel.close();
                    channelClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = mergeFailure(failure, exception);
                }
            }

            throwFailure(failure);
        }

        /// Validates the sequential entry bytes against central directory metadata.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;
            if (expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32 && crc32.getValue() != expectedCrc32) {
                throw new IOException("ZIP entry data does not match central directory");
            }
            if (expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                    && validationPosition != expectedUncompressedSize) {
                throw new IOException("ZIP entry data does not match central directory");
            }
        }

        /// Requires this channel to be open.
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
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
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
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
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

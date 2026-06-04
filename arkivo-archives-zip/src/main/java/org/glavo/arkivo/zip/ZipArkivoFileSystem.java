// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemOption;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Opens ZIP archives as NIO file systems.
@NotNullByDefault
public final class ZipArkivoFileSystem extends ArkivoFileSystem {
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

    /// The minimum ZIP end of central directory record size.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 22;

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

    /// The environment option for an `ArkivoPasswordProvider` value.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArkivoFileSystemOption.of("arkivo.zip", "passwordProvider", ArkivoPasswordProvider.class);

    /// The environment option for a fixed `byte[]` password value.
    public static final ArkivoFileSystemOption<byte[]> PASSWORD =
            ArkivoFileSystemOption.of("arkivo.zip", "password", byte[].class, ZipArkivoFileSystem::passwordOptionValue);

    /// The environment option for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final ArkivoFileSystemOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArkivoFileSystemOption.of(
                    "arkivo.zip",
                    "defaultEncryption",
                    ZipEncryption.class,
                    ZipArkivoFileSystem::defaultEncryptionOptionValue
            );

    /// The environment option for a `Long` value that sets the maximum size of each output volume.
    public static final ArkivoFileSystemOption<Long> SPLIT_SIZE =
            ArkivoFileSystemOption.of("arkivo.zip", "splitSize", Long.class, ZipArkivoFileSystem::splitSizeOptionValue);

    /// The environment option for a `ZipEntryNameEncoding` value that controls entry name decoding.
    public static final ArkivoFileSystemOption<ZipEntryNameEncoding> ENTRY_NAME_ENCODING =
            ArkivoFileSystemOption.of(
                    "arkivo.zip",
                    "entryNameEncoding",
                    ZipEntryNameEncoding.class,
                    ZipArkivoFileSystem::entryNameEncodingOptionValue
            );

    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The archive path backing this file system, or `null` for split volume sources.
    private final @Nullable Path archivePath;

    /// The split volume source backing this file system, or `null` for single archive paths.
    private final @Nullable ArkivoVolumeSource volumes;

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// The cached preamble size, or `-1` when it has not been located yet.
    private volatile long preambleSize = -1;

    /// Whether this file system is open.
    private volatile boolean open = true;

    /// Creates a ZIP archive file system instance.
    ZipArkivoFileSystem(
            ZipArkivoFileSystemProvider provider,
            @Nullable Path archivePath,
            @Nullable ArkivoVolumeSource volumes,
            ZipArkivoFileSystemConfig config
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
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Opens a ZIP archive file system.
    public static ZipArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a ZIP archive file system with environment options.
    public static ZipArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ZipArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a split ZIP archive file system.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, Map.of());
    }

    /// Opens a split ZIP archive file system with environment options.
    public static ZipArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return new ZipArkivoFileSystem(ZipArkivoFileSystemProvider.instance(), null, volumes, config);
    }

    /// Returns the archive path backing this file system, or `null` for split volume sources.
    public @Nullable Path archivePath() {
        return archivePath;
    }

    /// Returns the split volume source backing this file system, or `null` for single archive paths.
    public @Nullable ArkivoVolumeSource volumes() {
        return volumes;
    }

    /// Returns the parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig config() {
        return config;
    }

    /// Returns the root path for this ZIP file system.
    ZipArkivoPath rootPath() {
        return rootPath;
    }

    /// Returns the number of bytes stored before the ZIP archive body.
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
        if (volumes != null) {
            volumes.close();
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
        return List.of();
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

    /// Returns a path matcher for this ZIP file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("ZIP path matchers are not implemented yet");
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
    void checkOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
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

    /// Converts a raw password option value.
    private static byte[] passwordOptionValue(Object value) {
        if (value instanceof byte[] password) {
            return password;
        }
        throw new IllegalArgumentException("Expected byte[] for key: " + PASSWORD.key());
    }

    /// Converts a raw default encryption option value.
    private static ZipEncryption defaultEncryptionOptionValue(Object value) {
        if (value instanceof ZipEncryption encryption) {
            return encryption;
        }
        if (value instanceof String stringValue) {
            return ZipEncryption.of(stringValue);
        }
        throw new IllegalArgumentException("Expected ZipEncryption or String for key: " + DEFAULT_ENCRYPTION.key());
    }

    /// Converts a raw split size option value.
    private static Long splitSizeOptionValue(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected Long, compatible integral number, or String for key: " + SPLIT_SIZE.key()
        );
    }

    /// Converts a raw entry name encoding option value.
    private static ZipEntryNameEncoding entryNameEncodingOptionValue(Object value) {
        if (value instanceof ZipEntryNameEncoding encoding) {
            return encoding;
        }
        if (value instanceof String stringValue) {
            return ZipEntryNameEncoding.parse(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected ZipEntryNameEncoding or String for key: " + ENTRY_NAME_ENCODING.key()
        );
    }

    /// Exposes the leading bytes of a seekable channel as a read-only seekable channel.
    @NotNullByDefault
    private static final class BoundedSeekableByteChannel implements SeekableByteChannel {
        /// The wrapped storage channel.
        private final SeekableByteChannel channel;

        /// The visible channel size.
        private final long size;

        /// The current position inside the bounded channel.
        private long position;

        /// Whether this bounded channel is open.
        private boolean open = true;

        /// Creates a bounded channel over the first `size` bytes of the given channel.
        private BoundedSeekableByteChannel(SeekableByteChannel channel, long size) {
            this.channel = Objects.requireNonNull(channel, "channel");
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
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
                channel.position(position);
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
}

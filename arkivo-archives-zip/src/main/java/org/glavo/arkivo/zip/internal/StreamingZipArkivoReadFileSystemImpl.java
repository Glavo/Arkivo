// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.glavo.arkivo.zip.internal.ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.DATA_DESCRIPTOR_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.DATA_DESCRIPTOR_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.DEFLATED_METHOD;
import static org.glavo.arkivo.zip.internal.ZipConstants.ENCRYPTED_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.LOCAL_FILE_HEADER_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.STORED_METHOD;
import static org.glavo.arkivo.zip.internal.ZipConstants.STRONG_ENCRYPTION_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.UINT32_MAX;
import static org.glavo.arkivo.zip.internal.ZipConstants.ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.readInt;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.readIntOrEnd;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.readUnsignedShort;

/// Implements a forward-only ZIP archive file system for streaming reads.
@NotNullByDefault
public final class StreamingZipArkivoReadFileSystemImpl extends ZipArkivoFileSystem {
    /// The pushback buffer size used to return bytes read past the end of a deflated stream.
    private static final int PUSHBACK_BUFFER_SIZE = 8192;

    /// The data descriptor signature bytes in stream order.
    private static final byte @Unmodifiable [] DATA_DESCRIPTOR_SIGNATURE_BYTES = new byte[]{
            0x50,
            0x4b,
            0x07,
            0x08
    };

    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The input stream that provides ZIP bytes.
    private final PushbackInputStream input;

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// The current local entry, or `null` when no entry is active.
    private @Nullable LocalEntry currentEntry;

    /// The current entry input stream, or `null` when no entry input stream is active.
    private @Nullable CurrentEntryInputStream currentInput;

    /// The current parsed entry exposed to streaming reader adapters, or `null` when no entry is active.
    private @Nullable LocalEntry currentStreamingEntry;

    /// Whether this file system is open.
    private boolean open = true;

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoReadFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            ReadableByteChannel source,
            ZipArkivoFileSystemConfig config
    ) {
        this(provider, Channels.newInputStream(Objects.requireNonNull(source, "source")), config);
    }

    /// Creates a streaming ZIP archive file system from an input stream.
    public StreamingZipArkivoReadFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            InputStream source,
            ZipArkivoFileSystemConfig config
    ) {
        super(config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.input = new PushbackInputStream(Objects.requireNonNull(source, "source"), PUSHBACK_BUFFER_SIZE);
        this.config = Objects.requireNonNull(config, "config");
        this.lock = ZipLocks.create(config.threadSafety());
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and the input stream.
    @Override
    public void close() throws IOException {
        lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            input.close();
        } finally {
            unlock();
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
        return List.of(rootPath);
    }

    /// Returns the file stores exposed by this ZIP file system.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        return List.of(StreamingZipFileStore.READ_ONLY);
    }

    /// Returns the supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "zip", "owner", "posix");
    }

    /// Returns a path inside this ZIP file system.
    @Override
    public Path getPath(String first, String... more) {
        checkOpen();
        return ZipArkivoPath.of(this, first, more);
    }

    /// Returns a path matcher for paths in this ZIP file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        checkOpen();
        return ArkivoPathMatchers.create(syntaxAndPattern, '/');
    }

    /// Always rejects watch services because ZIP watch services are not supported.
    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("ZIP watch services are not supported");
    }

    /// Returns a user principal lookup service for synthesized ZIP principals.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return ZipPosixSupport.userPrincipalLookupService();
    }

    /// Advances to the next streaming ZIP entry.
    boolean nextStreamingEntry() throws IOException {
        lock();
        try {
            checkOpen();
            closeCurrentEntry();

            int signature = readIntOrEnd(input);
            if (signature < 0
                    || signature == CENTRAL_DIRECTORY_HEADER_SIGNATURE
                    || signature == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                currentEntry = null;
                currentStreamingEntry = null;
                return false;
            }
            if (signature != LOCAL_FILE_HEADER_SIGNATURE) {
                throw new IOException("Unexpected ZIP stream record signature: " + Integer.toHexString(signature));
            }

            int versionNeededToExtract = readUnsignedShort(input);
            int flags = readUnsignedShort(input);
            int method = readUnsignedShort(input);
            int lastModifiedDosTime = readUnsignedShort(input);
            readUnsignedShort(input);
            boolean hasDataDescriptor = (flags & DATA_DESCRIPTOR_FLAG) != 0;
            long headerCrc32 = Integer.toUnsignedLong(readInt(input));
            long headerCompressedSize = Integer.toUnsignedLong(readInt(input));
            long headerUncompressedSize = Integer.toUnsignedLong(readInt(input));
            long crc32 = hasDataDescriptor ? ZipArkivoEntryAttributes.UNKNOWN_CRC32 : headerCrc32;
            long compressedSize = hasDataDescriptor ? ZipArkivoEntryAttributes.UNKNOWN_SIZE : headerCompressedSize;
            long uncompressedSize = hasDataDescriptor ? ZipArkivoEntryAttributes.UNKNOWN_SIZE : headerUncompressedSize;
            int nameLength = readUnsignedShort(input);
            int extraLength = readUnsignedShort(input);
            byte[] rawName = readBytes(nameLength);
            byte[] extraData = readBytes(extraLength);
            if (!hasDataDescriptor && (headerCompressedSize == UINT32_MAX || headerUncompressedSize == UINT32_MAX)) {
                Zip64LocalSizes zip64Sizes = readZip64LocalSizes(
                        extraData,
                        headerUncompressedSize,
                        headerCompressedSize
                );
                compressedSize = zip64Sizes.compressedSize;
                uncompressedSize = zip64Sizes.uncompressedSize;
            }
            boolean zip64DataDescriptor = hasDataDescriptor
                    && (headerCompressedSize == UINT32_MAX
                    || headerUncompressedSize == UINT32_MAX
                    || hasZip64ExtraField(extraData));

            String path = decodePath(rawName, flags, extraData);
            LocalEntry entry = new LocalEntry(
                    path,
                    rawName,
                    flags,
                    method,
                    versionNeededToExtract,
                    lastModifiedDosTime,
                    crc32,
                    compressedSize,
                    uncompressedSize,
                    extraData,
                    zip64DataDescriptor,
                    path.endsWith("/")
            );
            currentEntry = entry;
            currentStreamingEntry = entry;
            return true;
        } finally {
            unlock();
        }
    }

    /// Opens a readable channel for the current streaming ZIP entry.
    ReadableByteChannel openCurrentEntryChannel() throws IOException {
        lock();
        try {
            checkOpen();
            LocalEntry entry = currentEntry;
            if (entry == null) {
                throw new IOException("No current ZIP entry");
            }
            if (entry.directory) {
                throw new IOException("ZIP entry is a directory: " + entry.path);
            }
            if (currentInput != null) {
                throw new IOException("Current ZIP entry input stream is already open");
            }

            CurrentEntryInputStream entryInput = new CurrentEntryInputStream(this, entryInputStream(entry));
            currentInput = entryInput;
            return Channels.newChannel(entryInput);
        } finally {
            unlock();
        }
    }

    /// Closes or drains the current streaming ZIP entry.
    void closeCurrentStreamingEntry() throws IOException {
        lock();
        try {
            closeCurrentEntry();
        } finally {
            unlock();
        }
    }

    /// Returns the current ZIP entry attributes, or `null` when no entry is active.
    @Nullable ZipArkivoEntryAttributes currentEntryAttributes() {
        lock();
        try {
            LocalEntry entry = currentStreamingEntry;
            return entry != null ? entry.attributes() : null;
        } finally {
            unlock();
        }
    }

    /// Returns the number of bytes stored before the ZIP archive body.
    @Override
    public long preambleSize() {
        throw new UnsupportedOperationException("Streaming ZIP input does not expose a preamble");
    }

    /// Opens a read-only channel over the bytes stored before the ZIP archive body.
    @Override
    public SeekableByteChannel openPreambleChannel() {
        throw new UnsupportedOperationException("Streaming ZIP input does not expose a preamble");
    }

    /// Requires this file system to be open.
    private void checkOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
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

    /// Reads exactly `length` bytes.
    private byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP stream");
            }
            offset += read;
        }
        return bytes;
    }

    /// Reads ZIP data descriptor fields and returns whether they match the entry data.
    private static boolean readAndMatchesDataDescriptor(
            PushbackInputStream input,
            boolean zip64,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) throws IOException {
        int first = readInt(input);
        if (first == DATA_DESCRIPTOR_SIGNATURE) {
            return readAndMatchesDataDescriptorAfterSignature(
                    input,
                    zip64,
                    crc32,
                    compressedSize,
                    uncompressedSize,
                    false
            );
        }

        byte[] descriptor = new byte[dataDescriptorSize(false)];
        writeIntLE(descriptor, 0, first);
        int offset = 4;
        while (offset < descriptor.length) {
            int read = input.read(descriptor, offset, descriptor.length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP data descriptor");
            }
            offset += read;
        }
        return matchesReadDataDescriptor(input, descriptor, zip64, crc32, compressedSize, uncompressedSize, false);
    }

    /// Reads ZIP data descriptor fields after a consumed signature.
    private static boolean readAndMatchesDataDescriptorAfterSignature(
            PushbackInputStream input,
            boolean zip64,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            boolean unreadOnMismatch
    ) throws IOException {
        byte[] descriptor = new byte[dataDescriptorSize(false)];
        int offset = 0;
        while (offset < descriptor.length) {
            int read = input.read(descriptor, offset, descriptor.length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP data descriptor");
            }
            offset += read;
        }
        return matchesReadDataDescriptor(
                input,
                descriptor,
                zip64,
                crc32,
                compressedSize,
                uncompressedSize,
                unreadOnMismatch
        );
    }

    /// Reads descriptor fields after a consumed signature, checks sizes, and pushes the fields back.
    private static boolean readAndMatchesDataDescriptorSizesAfterSignature(
            PushbackInputStream input,
            boolean zip64,
            long compressedSize,
            long uncompressedSize
    ) throws IOException {
        byte[] descriptor = new byte[dataDescriptorSize(false)];
        int offset = 0;
        while (offset < descriptor.length) {
            int read = input.read(descriptor, offset, descriptor.length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP data descriptor");
            }
            offset += read;
        }

        byte[] tail = new byte[dataDescriptorSize(true) - dataDescriptorSize(false)];
        int tailSize = 0;
        try {
            if (zip64) {
                tailSize = readPotentialDescriptorTail(input, tail);
                if (tailSize == tail.length) {
                    byte[] zip64Descriptor = new byte[dataDescriptorSize(true)];
                    System.arraycopy(descriptor, 0, zip64Descriptor, 0, descriptor.length);
                    System.arraycopy(tail, 0, zip64Descriptor, descriptor.length, tail.length);
                    if (matchesDataDescriptorSizes(zip64Descriptor, compressedSize, uncompressedSize)) {
                        return true;
                    }
                }
            }
            return matchesDataDescriptorSizes(descriptor, compressedSize, uncompressedSize);
        } finally {
            unread(input, tail, tailSize);
            unread(input, descriptor, descriptor.length);
        }
    }

    /// Matches descriptor fields and preserves following stream bytes when a ZIP32 fallback is needed.
    private static boolean matchesReadDataDescriptor(
            PushbackInputStream input,
            byte[] zip32Descriptor,
            boolean zip64,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            boolean unreadOnMismatch
    ) throws IOException {
        if (zip64) {
            byte[] tail = new byte[dataDescriptorSize(true) - dataDescriptorSize(false)];
            int tailSize = readPotentialZip64DescriptorTail(input, tail);
            if (tailSize == tail.length) {
                byte[] zip64Descriptor = new byte[dataDescriptorSize(true)];
                System.arraycopy(zip32Descriptor, 0, zip64Descriptor, 0, zip32Descriptor.length);
                System.arraycopy(tail, 0, zip64Descriptor, zip32Descriptor.length, tail.length);
                if (matchesDataDescriptor(zip64Descriptor, crc32, compressedSize, uncompressedSize)) {
                    return true;
                }
                unread(input, tail, tailSize);
            }
        }

        if (matchesDataDescriptor(zip32Descriptor, crc32, compressedSize, uncompressedSize)) {
            return true;
        }
        if (unreadOnMismatch) {
            unread(input, zip32Descriptor, zip32Descriptor.length);
        }
        return false;
    }

    /// Reads bytes that may be the 64-bit size suffix of a ZIP64 data descriptor.
    private static int readPotentialZip64DescriptorTail(PushbackInputStream input, byte[] tail) throws IOException {
        int offset = 0;
        while (offset < tail.length) {
            int read = input.read(tail, offset, tail.length - offset);
            if (read < 0) {
                unread(input, tail, offset);
                return offset;
            }
            offset += read;
        }
        return offset;
    }

    /// Reads optional bytes after a ZIP32 descriptor without pushing them back.
    private static int readPotentialDescriptorTail(PushbackInputStream input, byte[] tail) throws IOException {
        int offset = 0;
        while (offset < tail.length) {
            int read = input.read(tail, offset, tail.length - offset);
            if (read < 0) {
                return offset;
            }
            offset += read;
        }
        return offset;
    }

    /// Pushes bytes back in reverse read order.
    private static void unread(PushbackInputStream input, byte[] bytes, int length) throws IOException {
        for (int index = length - 1; index >= 0; index--) {
            input.unread(bytes[index]);
        }
    }

    /// Returns the number of bytes used by data descriptor fields after an optional signature.
    private static int dataDescriptorSize(boolean zip64) {
        return Integer.BYTES + (zip64 ? 2 * Long.BYTES : 2 * Integer.BYTES);
    }

    /// Returns whether descriptor fields match the bytes already read from the entry.
    private static boolean matchesDataDescriptor(
            byte[] descriptor,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        long descriptorCrc32 = Integer.toUnsignedLong(readInt(descriptor, 0));
        long descriptorCompressedSize;
        long descriptorUncompressedSize;
        if (descriptor.length == dataDescriptorSize(true)) {
            descriptorCompressedSize = readLong(descriptor, 4);
            descriptorUncompressedSize = readLong(descriptor, 12);
        } else {
            descriptorCompressedSize = Integer.toUnsignedLong(readInt(descriptor, 4));
            descriptorUncompressedSize = Integer.toUnsignedLong(readInt(descriptor, 8));
        }
        return descriptorCrc32 == crc32
                && descriptorCompressedSize == compressedSize
                && descriptorUncompressedSize == uncompressedSize;
    }

    /// Returns whether descriptor size fields match the given sizes.
    private static boolean matchesDataDescriptorSizes(
            byte[] descriptor,
            long compressedSize,
            long uncompressedSize
    ) {
        long descriptorCompressedSize;
        long descriptorUncompressedSize;
        if (descriptor.length == dataDescriptorSize(true)) {
            descriptorCompressedSize = readLong(descriptor, 4);
            descriptorUncompressedSize = readLong(descriptor, 12);
        } else {
            descriptorCompressedSize = Integer.toUnsignedLong(readInt(descriptor, 4));
            descriptorUncompressedSize = Integer.toUnsignedLong(readInt(descriptor, 8));
        }
        return descriptorCompressedSize == compressedSize
                && descriptorUncompressedSize == uncompressedSize;
    }

    /// Reads a little-endian signed 64-bit integer from a byte array.
    private static long readLong(byte[] value, int offset) {
        return Byte.toUnsignedLong(value[offset])
                | (Byte.toUnsignedLong(value[offset + 1]) << 8)
                | (Byte.toUnsignedLong(value[offset + 2]) << 16)
                | (Byte.toUnsignedLong(value[offset + 3]) << 24)
                | (Byte.toUnsignedLong(value[offset + 4]) << 32)
                | (Byte.toUnsignedLong(value[offset + 5]) << 40)
                | (Byte.toUnsignedLong(value[offset + 6]) << 48)
                | (Byte.toUnsignedLong(value[offset + 7]) << 56);
    }

    /// Returns whether extra data contains a ZIP64 extended information field.
    private static boolean hasZip64ExtraField(byte[] extraData) throws IOException {
        int offset = 0;
        while (offset + 4 <= extraData.length) {
            int fieldId = readUnsignedShortLE(extraData, offset);
            int fieldSize = readUnsignedShortLE(extraData, offset + 2);
            int nextOffset = offset + 4 + fieldSize;
            if (nextOffset > extraData.length) {
                throw new IOException("Invalid ZIP extra field length");
            }
            if (fieldId == ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID) {
                return true;
            }
            offset = nextOffset;
        }
        return false;
    }

    /// Reads local ZIP64 size values when 32-bit local header fields require them.
    private static Zip64LocalSizes readZip64LocalSizes(
            byte[] extraData,
            long uncompressedSize,
            long compressedSize
    ) throws IOException {
        boolean needsUncompressedSize = uncompressedSize == UINT32_MAX;
        boolean needsCompressedSize = compressedSize == UINT32_MAX;
        if (!needsUncompressedSize && !needsCompressedSize) {
            return new Zip64LocalSizes(uncompressedSize, compressedSize);
        }

        int offset = 0;
        while (offset + 4 <= extraData.length) {
            int fieldId = readUnsignedShortLE(extraData, offset);
            int fieldSize = readUnsignedShortLE(extraData, offset + 2);
            int dataOffset = offset + 4;
            int nextOffset = dataOffset + fieldSize;
            if (nextOffset > extraData.length) {
                throw new IOException("Invalid ZIP extra field length");
            }
            if (fieldId == ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID) {
                if (needsUncompressedSize) {
                    uncompressedSize = readZip64Size(extraData, dataOffset, nextOffset);
                    dataOffset += Long.BYTES;
                }
                if (needsCompressedSize) {
                    compressedSize = readZip64Size(extraData, dataOffset, nextOffset);
                }
                return new Zip64LocalSizes(uncompressedSize, compressedSize);
            }
            offset = nextOffset;
        }

        throw new IOException("Required ZIP64 extended information extra field is missing");
    }

    /// Reads one non-negative ZIP64 size value from extra data.
    private static long readZip64Size(byte[] extraData, int offset, int limit) throws IOException {
        if (offset + Long.BYTES > limit) {
            throw new IOException("Invalid ZIP64 extended information extra field");
        }
        long value = readLong(extraData, offset);
        if (value < 0) {
            throw new IOException("ZIP64 size is too large");
        }
        return value;
    }

    /// Reads a little-endian unsigned 16-bit value from a byte array.
    private static int readUnsignedShortLE(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset])
                | (Byte.toUnsignedInt(value[offset + 1]) << 8);
    }

    /// Writes one little-endian `int` value into a byte array.
    private static void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    /// Decodes an entry path.
    private String decodePath(byte[] rawName, int flags, byte[] extraData) throws IOException {
        try {
            String path = new ZipEntryNameDecoder(config.entryNameEncoding()).decodePath(rawName, flags, extraData);
            requireValidEntryPath(path);
            return path;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IOException("Failed to decode ZIP entry name", exception);
        }
    }

    /// Requires a decoded entry path to contain a usable archive-local path.
    private static void requireValidEntryPath(String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\")
                || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException("ZIP entry path must be relative");
        }
        boolean hasName = false;
        int start = 0;
        while (start <= path.length()) {
            int end = nextPathSeparator(path, start);

            String name = path.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IOException("ZIP entry path must not contain ..");
                }
                hasName = true;
            }
            start = end + 1;
        }
        if (!hasName) {
            throw new IOException("ZIP entry is missing a path");
        }
    }

    /// Returns the index of the next entry path separator, or the path length.
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

    /// Opens an input stream for an entry.
    private InputStream entryInputStream(LocalEntry entry) throws IOException {
        boolean hasDataDescriptor = (entry.flags & DATA_DESCRIPTOR_FLAG) != 0;
        boolean encrypted = entry.encrypted();
        ZipAesExtraField aes = encrypted ? ZipAesExtraField.read(entry.extraData) : null;
        if (encrypted && aes == null) {
            requireTraditionalEncryption(entry);
        }
        int compressionMethod = aes != null ? aes.compressionMethod() : entry.method;
        if (aes != null) {
            if (hasDataDescriptor) {
                if (compressionMethod == DEFLATED_METHOD) {
                    return new AesDataDescriptorInflaterInputStream(
                            input,
                            openAesDecryptor(entry, aes, input),
                            aes.authenticationCodeSize(),
                            aes.overheadSize(),
                            entry.zip64DataDescriptor
                    );
                }
                if (compressionMethod == STORED_METHOD) {
                    return new AesStoredDataDescriptorInputStream(
                            input,
                            openAesDecryptor(entry, aes, input),
                            aes.authenticationCodeSize(),
                            aes.overheadSize(),
                            entry.zip64DataDescriptor
                    );
                }
                throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
            }
            InputStream encryptedData = new BoundedInputStream(input, entry.compressedSize);
            InputStream decryptedData = openAesDecryptingStream(entry, aes, encryptedData);
            if (compressionMethod == STORED_METHOD) {
                return decryptedData;
            }
            if (compressionMethod == DEFLATED_METHOD) {
                return new EntryInflaterInputStream(decryptedData, new Inflater(true), null, false);
            }
            throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
        }

        if (compressionMethod == STORED_METHOD) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return new EncryptedStoredDataDescriptorInputStream(
                            input,
                            openTraditionalDecryptor(entry, input),
                            entry.zip64DataDescriptor
                    );
                }
                return new StoredDataDescriptorInputStream(input, entry.zip64DataDescriptor);
            }
            InputStream stored = new BoundedInputStream(input, entry.compressedSize);
            return encrypted ? openTraditionalDecryptingStream(entry, stored) : stored;
        }
        if (compressionMethod == DEFLATED_METHOD) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return new EncryptedDataDescriptorInflaterInputStream(
                            input,
                            openTraditionalDecryptor(entry, input),
                            entry.zip64DataDescriptor
                    );
                }
                return new EntryInflaterInputStream(input, new Inflater(true), input, entry.zip64DataDescriptor);
            }

            InputStream compressed = new BoundedInputStream(input, entry.compressedSize);
            if (encrypted) {
                compressed = openTraditionalDecryptingStream(entry, compressed);
            }
            return new EntryInflaterInputStream(compressed, new Inflater(true), null, false);
        }
        throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
    }

    /// Opens a WinZip AES decrypting stream for an entry with known compressed size.
    private InputStream openAesDecryptingStream(
            LocalEntry entry,
            ZipAesExtraField aes,
            InputStream input
    ) throws IOException {
        if ((entry.flags & STRONG_ENCRYPTION_FLAG) != 0) {
            throw new IOException("Unsupported ZIP encryption method: " + entry.path);
        }
        return ZipAesCrypto.openDecryptingStream(input, aes, passwordForEntry(entry), entry.compressedSize);
    }

    /// Opens a WinZip AES decryptor after consuming salt and password verifier bytes.
    private ZipAesCrypto.Decryptor openAesDecryptor(
            LocalEntry entry,
            ZipAesExtraField aes,
            InputStream input
    ) throws IOException {
        if ((entry.flags & STRONG_ENCRYPTION_FLAG) != 0) {
            throw new IOException("Unsupported ZIP encryption method: " + entry.path);
        }
        return ZipAesCrypto.openDecryptor(input, aes, passwordForEntry(entry));
    }

    /// Opens a traditional ZIP decrypting stream for an entry with known compressed size.
    private InputStream openTraditionalDecryptingStream(LocalEntry entry, InputStream input) throws IOException {
        if (entry.compressedSize < ZipTraditionalCrypto.HEADER_SIZE) {
            throw new IOException("Encrypted ZIP entry is missing its encryption header: " + entry.path);
        }
        return ZipTraditionalCrypto.openDecryptingStream(
                input,
                passwordForEntry(entry),
                encryptionVerificationByte(entry)
        );
    }

    /// Opens a traditional ZIP decryptor after consuming and validating the encryption header.
    private ZipTraditionalCrypto.Decryptor openTraditionalDecryptor(
            LocalEntry entry,
            InputStream input
    ) throws IOException {
        return ZipTraditionalCrypto.openDecryptor(
                input,
                passwordForEntry(entry),
                encryptionVerificationByte(entry)
        );
    }

    /// Requires an encrypted entry to use the traditional ZIP encryption method.
    private static void requireTraditionalEncryption(LocalEntry entry) throws IOException {
        if ((entry.flags & STRONG_ENCRYPTION_FLAG) != 0 || ZipAesExtraField.isAes(entry.method, entry.extraData)) {
            throw new IOException("Unsupported ZIP encryption method: " + entry.path);
        }
    }

    /// Returns the password for an encrypted entry.
    private byte[] passwordForEntry(LocalEntry entry) throws IOException {
        ArkivoPasswordProvider passwordProvider = config.passwordProvider();
        if (passwordProvider == null) {
            throw new IOException("ZIP entry requires a password: " + entry.path);
        }
        byte[] password = passwordProvider.passwordForEntry(getPath("/" + entry.path));
        if (password == null) {
            throw new IOException("ZIP entry requires a password: " + entry.path);
        }
        return password;
    }

    /// Returns the traditional ZIP password verification byte.
    private static int encryptionVerificationByte(LocalEntry entry) {
        if ((entry.flags & DATA_DESCRIPTOR_FLAG) != 0) {
            return entry.lastModifiedDosTime >>> 8;
        }
        return (int) (entry.crc32 >>> 24);
    }

    /// Closes or drains the current entry.
    private void closeCurrentEntry() throws IOException {
        CurrentEntryInputStream entryInput = currentInput;
        if (entryInput != null) {
            entryInput.close();
            return;
        }

        LocalEntry entry = currentEntry;
        if (entry != null && !entry.directory) {
            if (shouldSkipRawEntryData(entry)) {
                skipRawEntryData(entry);
            } else {
                try (InputStream ignored = entryInputStream(entry)) {
                    ignored.transferTo(OutputStream.nullOutputStream());
                }
            }
        }
        currentEntry = null;
        currentStreamingEntry = null;
    }

    /// Returns whether an unsupported entry can be skipped without decoding its content.
    private static boolean shouldSkipRawEntryData(LocalEntry entry) {
        if ((entry.flags & DATA_DESCRIPTOR_FLAG) != 0) {
            return false;
        }
        if (entry.encrypted()
                && ((entry.flags & STRONG_ENCRYPTION_FLAG) != 0
                || ZipAesExtraField.isAes(entry.method, entry.extraData))) {
            return true;
        }
        return entry.method != STORED_METHOD && entry.method != DEFLATED_METHOD;
    }

    /// Skips an entry body by raw compressed size.
    private void skipRawEntryData(LocalEntry entry) throws IOException {
        try (InputStream ignored = new BoundedInputStream(input, entry.compressedSize)) {
            ignored.transferTo(OutputStream.nullOutputStream());
        }
    }

    /// Marks the current input stream as closed.
    private void closeCurrentInput(CurrentEntryInputStream inputStream) {
        lock();
        try {
            if (currentInput == inputStream) {
                currentInput = null;
                currentEntry = null;
                currentStreamingEntry = null;
            }
        } finally {
            unlock();
        }
    }

    /// Reads bytes from the current ZIP entry.
    @NotNullByDefault
    private static final class CurrentEntryInputStream extends InputStream {
        /// The owner file system.
        private final StreamingZipArkivoReadFileSystemImpl owner;

        /// The delegate entry input stream.
        private final InputStream input;

        /// Whether this stream is open.
        private boolean inputOpen = true;

        /// Creates a current entry input stream.
        private CurrentEntryInputStream(StreamingZipArkivoReadFileSystemImpl owner, InputStream input) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.input = Objects.requireNonNull(input, "input");
        }

        /// Reads one byte from the entry.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads bytes from the entry.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            owner.lock();
            try {
                if (!inputOpen) {
                    throw new IOException("ZIP entry input stream is closed");
                }
                if (length == 0) {
                    return 0;
                }
                return input.read(bytes, offset, length);
            } finally {
                owner.unlock();
            }
        }

        /// Closes this entry input stream after draining unread bytes.
        @Override
        public void close() throws IOException {
            owner.lock();
            try {
                if (!inputOpen) {
                    return;
                }
                inputOpen = false;
                try {
                    input.transferTo(OutputStream.nullOutputStream());
                    input.close();
                } finally {
                    owner.closeCurrentInput(this);
                }
            } finally {
                owner.unlock();
            }
        }
    }

    /// Inflates raw ZIP deflate data and pushes back bytes read past the deflate stream.
    @NotNullByDefault
    private final class EntryInflaterInputStream extends InflaterInputStream {
        /// The inflater used by this stream.
        private final Inflater inflater;

        /// The pushback input used when this entry has a data descriptor, or `null` otherwise.
        private final @Nullable PushbackInputStream pushbackInput;

        /// The CRC-32 of inflated bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// Whether end-of-entry cleanup has run.
        private boolean finishedEntry;

        /// Creates an entry inflater stream.
        private EntryInflaterInputStream(
                InputStream input,
                Inflater inflater,
                @Nullable PushbackInputStream pushbackInput,
                boolean zip64DataDescriptor
        ) {
            super(input, inflater);
            this.inflater = inflater;
            this.pushbackInput = pushbackInput;
            this.zip64DataDescriptor = zip64DataDescriptor;
        }

        /// Reads bytes from the inflated entry.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (finishedEntry) {
                return -1;
            }
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                crc32.update(bytes, offset, read);
            }
            if (read < 0) {
                finishEntry();
            }
            return read;
        }

        /// Closes this inflater stream.
        @Override
        public void close() throws IOException {
            try {
                byte[] discard = new byte[8192];
                while (read(discard) >= 0) {
                    // Drain the deflate stream so the following descriptor can be parsed.
                }
            } finally {
                finishEntry();
            }
        }

        /// Finishes the current entry and releases the inflater.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;
            try {
                PushbackInputStream pushback = pushbackInput;
                if (pushback != null) {
                    int remaining = inflater.getRemaining();
                    if (remaining > 0) {
                        pushback.unread(buf, len - remaining, remaining);
                    }
                    if (!readAndMatchesDataDescriptor(
                            pushback,
                            zip64DataDescriptor,
                            crc32.getValue(),
                            inflater.getBytesRead(),
                            inflater.getBytesWritten()
                    )) {
                        throw new IOException("ZIP data descriptor does not match entry data");
                    }
                } else {
                    in.close();
                }
            } finally {
                inflater.end();
            }
        }
    }

    /// Reads stored ZIP entry data until a signed data descriptor is found.
    @NotNullByDefault
    private final class StoredDataDescriptorInputStream extends InputStream {
        /// The source stream.
        private final PushbackInputStream input;

        /// The CRC-32 of stored bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// The number of stored bytes returned so far.
        private long size;

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// Whether the data descriptor has been consumed.
        private boolean finishedEntry;

        /// Creates a stored entry input stream.
        private StoredDataDescriptorInputStream(PushbackInputStream input, boolean zip64DataDescriptor) {
            this.input = Objects.requireNonNull(input, "input");
            this.zip64DataDescriptor = zip64DataDescriptor;
        }

        /// Reads one stored entry byte.
        @Override
        public int read() throws IOException {
            if (finishedEntry) {
                return -1;
            }

            int first = input.read();
            if (first < 0) {
                throw new EOFException("Unexpected end of stored ZIP entry before data descriptor");
            }
            if (first != Byte.toUnsignedInt(DATA_DESCRIPTOR_SIGNATURE_BYTES[0])) {
                return storedByte(first);
            }

            byte[] candidate = new byte[DATA_DESCRIPTOR_SIGNATURE_BYTES.length - 1];
            int count = 0;
            while (count < candidate.length) {
                int value = input.read();
                if (value < 0) {
                    unread(candidate, count);
                    return storedByte(first);
                }
                candidate[count++] = (byte) value;
                if (value != Byte.toUnsignedInt(DATA_DESCRIPTOR_SIGNATURE_BYTES[count])) {
                    unread(candidate, count);
                    return storedByte(first);
                }
            }

            if (!readAndMatchesDataDescriptorAfterSignature(
                    input,
                    zip64DataDescriptor,
                    crc32.getValue(),
                    size,
                    size,
                    true
            )) {
                unread(candidate, count);
                return storedByte(first);
            }
            finishedEntry = true;
            return -1;
        }

        /// Reads stored entry bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                int value = read();
                if (value < 0) {
                    break;
                }
                bytes[offset + count] = (byte) value;
                count++;
            }
            return count;
        }

        /// Drains the stored entry.
        @Override
        public void close() throws IOException {
            byte[] discard = new byte[8192];
            while (read(discard) >= 0) {
                // Drain stored data until the signed descriptor has been consumed.
            }
        }

        /// Pushes bytes back in reverse read order.
        private void unread(byte[] bytes, int length) throws IOException {
            for (int index = length - 1; index >= 0; index--) {
                input.unread(bytes[index]);
            }
        }

        /// Records and returns one stored entry byte.
        private int storedByte(int value) {
            crc32.update(value);
            size++;
            return value;
        }
    }

    /// Reads WinZip AES stored ZIP entry data until a signed data descriptor is found after authentication bytes.
    @NotNullByDefault
    private final class AesStoredDataDescriptorInputStream extends InputStream {
        /// The source stream.
        private final PushbackInputStream input;

        /// The WinZip AES decryptor.
        private final ZipAesCrypto.Decryptor decryptor;

        /// The authentication code size in bytes.
        private final int authenticationCodeSize;

        /// The total WinZip AES entry body overhead.
        private final int overheadSize;

        /// Raw bytes withheld until they are known not to be authentication or descriptor bytes.
        private final ArrayDeque<Integer> pendingRaw = new ArrayDeque<>();

        /// Plain bytes that became available while finishing the entry.
        private final ArrayDeque<Integer> pendingPlain = new ArrayDeque<>();

        /// The CRC-32 of decrypted bytes returned or pending so far.
        private final CRC32 crc32 = new CRC32();

        /// The number of encrypted content bytes consumed after salt and password verifier bytes.
        private long encryptedContentSize;

        /// The number of decrypted stored bytes returned or pending so far.
        private long uncompressedSize;

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// Whether the data descriptor has been consumed.
        private boolean finishedEntry;

        /// Creates a WinZip AES stored entry input stream.
        private AesStoredDataDescriptorInputStream(
                PushbackInputStream input,
                ZipAesCrypto.Decryptor decryptor,
                int authenticationCodeSize,
                int overheadSize,
                boolean zip64DataDescriptor
        ) {
            this.input = Objects.requireNonNull(input, "input");
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
            this.authenticationCodeSize = authenticationCodeSize;
            this.overheadSize = overheadSize;
            this.zip64DataDescriptor = zip64DataDescriptor;
        }

        /// Reads one decrypted stored entry byte.
        @Override
        public int read() throws IOException {
            Integer pending = pendingPlain.pollFirst();
            if (pending != null) {
                return pending;
            }
            if (finishedEntry) {
                return -1;
            }

            while (true) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException("Unexpected end of WinZip AES stored ZIP entry before data descriptor");
                }
                pendingRaw.addLast(value);
                if (pendingRaw.size() >= authenticationCodeSize + DATA_DESCRIPTOR_SIGNATURE_BYTES.length
                        && endsWithDescriptorSignature()
                        && canFinishEntry()) {
                    finishEntry();
                    Integer plain = pendingPlain.pollFirst();
                    return plain != null ? plain : -1;
                }
                if (pendingRaw.size() > authenticationCodeSize + DATA_DESCRIPTOR_SIGNATURE_BYTES.length) {
                    return decryptRawByte(pendingRaw.removeFirst());
                }
            }
        }

        /// Reads decrypted stored entry bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                int value = read();
                if (value < 0) {
                    break;
                }
                bytes[offset + count] = (byte) value;
                count++;
            }
            return count;
        }

        /// Drains the encrypted stored entry.
        @Override
        public void close() throws IOException {
            byte[] discard = new byte[8192];
            while (read(discard) >= 0) {
                // Drain encrypted stored data until authentication and descriptor bytes have been consumed.
            }
        }

        /// Returns whether the withheld raw bytes end with a signed data descriptor marker.
        private boolean endsWithDescriptorSignature() {
            if (pendingRaw.size() < DATA_DESCRIPTOR_SIGNATURE_BYTES.length) {
                return false;
            }
            int skip = pendingRaw.size() - DATA_DESCRIPTOR_SIGNATURE_BYTES.length;
            int index = 0;
            for (int value : pendingRaw) {
                if (index >= skip
                        && value != Byte.toUnsignedInt(DATA_DESCRIPTOR_SIGNATURE_BYTES[index - skip])) {
                    return false;
                }
                index++;
            }
            return true;
        }

        /// Returns whether the descriptor candidate after the signed marker matches the pending entry sizes.
        private boolean canFinishEntry() throws IOException {
            int encryptedTailSize = pendingRaw.size() - authenticationCodeSize - DATA_DESCRIPTOR_SIGNATURE_BYTES.length;
            long compressedSize = overheadSize + encryptedContentSize + encryptedTailSize;
            long pendingUncompressedSize = uncompressedSize + encryptedTailSize;
            if (!readAndMatchesDataDescriptorSizesAfterSignature(
                    input,
                    zip64DataDescriptor,
                    compressedSize,
                    pendingUncompressedSize
            )) {
                return false;
            }
            return matchesAuthenticationAfterPendingTail(encryptedTailSize);
        }

        /// Returns whether the candidate authentication bytes can match after pending encrypted content bytes.
        private boolean matchesAuthenticationAfterPendingTail(int encryptedTailSize) throws IOException {
            byte[] additionalEncryptedContent = new byte[encryptedTailSize];
            byte[] expectedAuthentication = new byte[authenticationCodeSize];
            int index = 0;
            for (int value : pendingRaw) {
                if (index < encryptedTailSize) {
                    additionalEncryptedContent[index] = (byte) value;
                } else if (index < encryptedTailSize + authenticationCodeSize) {
                    expectedAuthentication[index - encryptedTailSize] = (byte) value;
                } else {
                    break;
                }
                index++;
            }
            return decryptor.authenticationCanMatchAfter(additionalEncryptedContent, expectedAuthentication);
        }

        /// Finishes the current entry and consumes its descriptor.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;

            for (int index = 0; index < DATA_DESCRIPTOR_SIGNATURE_BYTES.length; index++) {
                pendingRaw.removeLast();
            }
            if (pendingRaw.size() < authenticationCodeSize) {
                throw new EOFException("Unexpected end of WinZip AES authentication code");
            }

            int encryptedTailSize = pendingRaw.size() - authenticationCodeSize;
            for (int index = 0; index < encryptedTailSize; index++) {
                pendingPlain.addLast(decryptRawByte(pendingRaw.removeFirst()));
            }

            byte[] expectedAuthentication = new byte[authenticationCodeSize];
            for (int index = 0; index < expectedAuthentication.length; index++) {
                expectedAuthentication[index] = (byte) pendingRaw.removeFirst().intValue();
            }
            if (!decryptor.verify(expectedAuthentication)) {
                throw new IOException("WinZip AES authentication failed");
            }
            long compressedSize = overheadSize + encryptedContentSize;
            if (!readAndMatchesDataDescriptorAfterSignature(
                    input,
                    zip64DataDescriptor,
                    crc32.getValue(),
                    compressedSize,
                    uncompressedSize,
                    false
            )) {
                throw new IOException("WinZip AES data descriptor does not match entry data");
            }
        }

        /// Decrypts one raw encrypted byte.
        private int decryptRawByte(int encrypted) throws IOException {
            byte[] buffer = new byte[]{(byte) encrypted};
            decryptor.decrypt(buffer, 0, buffer.length);
            int plain = Byte.toUnsignedInt(buffer[0]);
            crc32.update(plain);
            encryptedContentSize++;
            uncompressedSize++;
            return plain;
        }
    }

    /// Inflates a WinZip AES raw deflate stream followed by an authentication code and data descriptor.
    @NotNullByDefault
    private final class AesDataDescriptorInflaterInputStream extends InputStream {
        /// The raw ZIP input stream.
        private final PushbackInputStream input;

        /// The inflater used by this stream.
        private final Inflater inflater = new Inflater(true);

        /// The WinZip AES decryptor.
        private final ZipAesCrypto.Decryptor decryptor;

        /// The authentication code size in bytes.
        private final int authenticationCodeSize;

        /// The total WinZip AES entry body overhead.
        private final int overheadSize;

        /// The one-byte input buffer passed to the inflater.
        private final byte[] plainByte = new byte[1];

        /// The CRC-32 of inflated bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// The number of encrypted deflate bytes consumed after salt and password verifier bytes.
        private long encryptedContentSize;

        /// The number of inflated bytes returned so far.
        private long uncompressedSize;

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// Whether end-of-entry cleanup has run.
        private boolean finishedEntry;

        /// Creates a WinZip AES deflate stream with a following authentication code and data descriptor.
        private AesDataDescriptorInflaterInputStream(
                PushbackInputStream input,
                ZipAesCrypto.Decryptor decryptor,
                int authenticationCodeSize,
                int overheadSize,
                boolean zip64DataDescriptor
        ) {
            this.input = Objects.requireNonNull(input, "input");
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
            this.authenticationCodeSize = authenticationCodeSize;
            this.overheadSize = overheadSize;
            this.zip64DataDescriptor = zip64DataDescriptor;
        }

        /// Reads one inflated byte.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads inflated bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (finishedEntry) {
                return -1;
            }

            try {
                while (true) {
                    int read = inflater.inflate(bytes, offset, length);
                    if (read > 0) {
                        crc32.update(bytes, offset, read);
                        uncompressedSize += read;
                        return read;
                    }
                    if (inflater.finished()) {
                        finishEntry();
                        return -1;
                    }
                    if (inflater.needsDictionary()) {
                        throw new IOException("ZIP deflate stream requires a preset dictionary");
                    }
                    if (inflater.needsInput()) {
                        int encrypted = input.read();
                        if (encrypted < 0) {
                            throw new EOFException(
                                    "Unexpected end of WinZip AES deflated ZIP entry before authentication code"
                            );
                        }
                        plainByte[0] = (byte) encrypted;
                        decryptor.decrypt(plainByte, 0, plainByte.length);
                        encryptedContentSize++;
                        inflater.setInput(plainByte, 0, plainByte.length);
                    }
                }
            } catch (DataFormatException exception) {
                throw new IOException("Invalid ZIP deflate stream", exception);
            }
        }

        /// Closes this inflater stream.
        @Override
        public void close() throws IOException {
            try {
                byte[] discard = new byte[8192];
                while (read(discard) >= 0) {
                    // Drain the AES deflate stream so authentication and descriptor bytes can be parsed.
                }
            } finally {
                finishEntry();
            }
        }

        /// Finishes the current entry, verifies authentication, and releases the inflater.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;
            try {
                byte[] expectedAuthentication = new byte[authenticationCodeSize];
                int offset = 0;
                while (offset < expectedAuthentication.length) {
                    int read = input.read(expectedAuthentication, offset, expectedAuthentication.length - offset);
                    if (read < 0) {
                        throw new EOFException("Unexpected end of WinZip AES authentication code");
                    }
                    offset += read;
                }
                if (!decryptor.verify(expectedAuthentication)) {
                    throw new IOException("WinZip AES authentication failed");
                }
                long compressedSize = overheadSize + encryptedContentSize;
                if (!readAndMatchesDataDescriptor(
                        input,
                        zip64DataDescriptor,
                        crc32.getValue(),
                        compressedSize,
                        uncompressedSize
                )) {
                    throw new IOException("WinZip AES data descriptor does not match entry data");
                }
            } finally {
                inflater.end();
            }
        }
    }

    /// Inflates an encrypted raw deflate stream followed by a data descriptor.
    @NotNullByDefault
    private final class EncryptedDataDescriptorInflaterInputStream extends InputStream {
        /// The raw ZIP input stream.
        private final PushbackInputStream input;

        /// The inflater used by this stream.
        private final Inflater inflater = new Inflater(true);

        /// The traditional ZIP decryptor.
        private final ZipTraditionalCrypto.Decryptor decryptor;

        /// The one-byte input buffer passed to the inflater.
        private final byte[] plainByte = new byte[1];

        /// The CRC-32 of inflated bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// The number of encrypted deflate bytes consumed after the traditional encryption header.
        private long encryptedContentSize;

        /// The number of inflated bytes returned so far.
        private long uncompressedSize;

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// Whether end-of-entry cleanup has run.
        private boolean finishedEntry;

        /// Creates an encrypted deflate stream with a following data descriptor.
        private EncryptedDataDescriptorInflaterInputStream(
                PushbackInputStream input,
                ZipTraditionalCrypto.Decryptor decryptor,
                boolean zip64DataDescriptor
        ) {
            this.input = Objects.requireNonNull(input, "input");
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
            this.zip64DataDescriptor = zip64DataDescriptor;
        }

        /// Reads one inflated byte.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads inflated bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (finishedEntry) {
                return -1;
            }

            try {
                while (true) {
                    int read = inflater.inflate(bytes, offset, length);
                    if (read > 0) {
                        crc32.update(bytes, offset, read);
                        uncompressedSize += read;
                        return read;
                    }
                    if (inflater.finished()) {
                        finishEntry();
                        return -1;
                    }
                    if (inflater.needsDictionary()) {
                        throw new IOException("ZIP deflate stream requires a preset dictionary");
                    }
                    if (inflater.needsInput()) {
                        int encrypted = input.read();
                        if (encrypted < 0) {
                            throw new EOFException(
                                    "Unexpected end of encrypted deflated ZIP entry before data descriptor"
                            );
                        }
                        plainByte[0] = (byte) decryptor.decrypt(encrypted);
                        encryptedContentSize++;
                        inflater.setInput(plainByte, 0, plainByte.length);
                    }
                }
            } catch (DataFormatException exception) {
                throw new IOException("Invalid ZIP deflate stream", exception);
            }
        }

        /// Closes this inflater stream.
        @Override
        public void close() throws IOException {
            try {
                byte[] discard = new byte[8192];
                while (read(discard) >= 0) {
                    // Drain the encrypted deflate stream so the following descriptor can be parsed.
                }
            } finally {
                finishEntry();
            }
        }

        /// Finishes the current entry and releases the inflater.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;
            try {
                long compressedSize = ZipTraditionalCrypto.HEADER_SIZE + encryptedContentSize;
                if (!readAndMatchesDataDescriptor(
                        input,
                        zip64DataDescriptor,
                        crc32.getValue(),
                        compressedSize,
                        uncompressedSize
                )) {
                    throw new IOException("ZIP data descriptor does not match entry data");
                }
            } finally {
                inflater.end();
            }
        }
    }

    /// Reads encrypted stored ZIP entry data until a signed data descriptor is found.
    @NotNullByDefault
    private final class EncryptedStoredDataDescriptorInputStream extends InputStream {
        /// The source stream.
        private final PushbackInputStream input;

        /// The traditional ZIP decryptor.
        private final ZipTraditionalCrypto.Decryptor decryptor;

        /// The CRC-32 of decrypted bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// The number of encrypted content bytes consumed after the traditional encryption header.
        private long encryptedContentSize;

        /// The number of decrypted stored bytes returned so far.
        private long uncompressedSize;

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// Whether the data descriptor has been consumed.
        private boolean finishedEntry;

        /// Creates an encrypted stored entry input stream.
        private EncryptedStoredDataDescriptorInputStream(
                PushbackInputStream input,
                ZipTraditionalCrypto.Decryptor decryptor,
                boolean zip64DataDescriptor
        ) {
            this.input = Objects.requireNonNull(input, "input");
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
            this.zip64DataDescriptor = zip64DataDescriptor;
        }

        /// Reads one decrypted stored entry byte.
        @Override
        public int read() throws IOException {
            if (finishedEntry) {
                return -1;
            }

            int first = input.read();
            if (first < 0) {
                throw new EOFException("Unexpected end of encrypted stored ZIP entry before data descriptor");
            }
            if (first != Byte.toUnsignedInt(DATA_DESCRIPTOR_SIGNATURE_BYTES[0])) {
                return decryptedByte(first);
            }

            byte[] candidate = new byte[DATA_DESCRIPTOR_SIGNATURE_BYTES.length - 1];
            int count = 0;
            while (count < candidate.length) {
                int value = input.read();
                if (value < 0) {
                    unread(candidate, count);
                    return decryptedByte(first);
                }
                candidate[count++] = (byte) value;
                if (value != Byte.toUnsignedInt(DATA_DESCRIPTOR_SIGNATURE_BYTES[count])) {
                    unread(candidate, count);
                    return decryptedByte(first);
                }
            }

            long compressedSize = ZipTraditionalCrypto.HEADER_SIZE + encryptedContentSize;
            if (!readAndMatchesDataDescriptorAfterSignature(
                    input,
                    zip64DataDescriptor,
                    crc32.getValue(),
                    compressedSize,
                    uncompressedSize,
                    true
            )) {
                unread(candidate, count);
                return decryptedByte(first);
            }
            finishedEntry = true;
            return -1;
        }

        /// Reads decrypted stored entry bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                int value = read();
                if (value < 0) {
                    break;
                }
                bytes[offset + count] = (byte) value;
                count++;
            }
            return count;
        }

        /// Drains the encrypted stored entry.
        @Override
        public void close() throws IOException {
            byte[] discard = new byte[8192];
            while (read(discard) >= 0) {
                // Drain encrypted stored data until the signed descriptor has been consumed.
            }
        }

        /// Pushes bytes back in reverse read order.
        private void unread(byte[] bytes, int length) throws IOException {
            for (int index = length - 1; index >= 0; index--) {
                input.unread(bytes[index]);
            }
        }

        /// Decrypts, records, and returns one stored entry byte.
        private int decryptedByte(int encrypted) {
            int plain = decryptor.decrypt(encrypted);
            crc32.update(plain);
            encryptedContentSize++;
            uncompressedSize++;
            return plain;
        }
    }

    /// Reads a bounded number of bytes from an input stream without closing it.
    @NotNullByDefault
    private static final class BoundedInputStream extends InputStream {
        /// The source input stream.
        private final InputStream input;

        /// The remaining bytes.
        private long remaining;

        /// Creates a bounded input stream.
        private BoundedInputStream(InputStream input, long size) {
            this.input = Objects.requireNonNull(input, "input");
            this.remaining = size;
        }

        /// Reads one byte.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int read = input.read(bytes, offset, requested);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP entry data");
            }
            remaining -= read;
            return read;
        }

        /// Drains remaining bytes.
        @Override
        public void close() throws IOException {
            byte[] discard = new byte[8192];
            while (read(discard) >= 0) {
                // Drain remaining bounded data.
            }
        }
    }

    /// Stores local ZIP64 size values.
    ///
    /// @param uncompressedSize the uncompressed entry size
    /// @param compressedSize the compressed entry size
    @NotNullByDefault
    private record Zip64LocalSizes(long uncompressedSize, long compressedSize) {
    }

    /// Stores one local file header entry.
    @NotNullByDefault
    private static final class LocalEntry {
        /// The decoded entry path.
        private final String path;

        /// The raw entry path bytes.
        private final byte[] rawPath;

        /// The general purpose bit flags.
        private final int flags;

        /// The compression method.
        private final int method;

        /// The ZIP version needed to extract field.
        private final int versionNeededToExtract;

        /// The raw DOS last modification time field.
        private final int lastModifiedDosTime;

        /// The CRC-32 value from the local header.
        private final long crc32;

        /// The compressed size from the local header.
        private final long compressedSize;

        /// The uncompressed size from the local header.
        private final long uncompressedSize;

        /// The raw local file header extra data bytes.
        private final byte[] extraData;

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// Whether this entry is a directory.
        private final boolean directory;

        /// Creates a local entry.
        private LocalEntry(
                String path,
                byte[] rawPath,
                int flags,
                int method,
                int versionNeededToExtract,
                int lastModifiedDosTime,
                long crc32,
                long compressedSize,
                long uncompressedSize,
                byte[] extraData,
                boolean zip64DataDescriptor,
                boolean directory
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.rawPath = Objects.requireNonNull(rawPath, "rawPath");
            this.flags = flags;
            this.method = method;
            this.versionNeededToExtract = versionNeededToExtract;
            this.lastModifiedDosTime = lastModifiedDosTime;
            this.crc32 = crc32;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.extraData = Objects.requireNonNull(extraData, "extraData");
            this.zip64DataDescriptor = zip64DataDescriptor;
            this.directory = directory;
        }

        /// Returns ZIP entry attributes for this local entry.
        private ZipArkivoEntryAttributes attributes() {
            return new StreamingZipEntryAttributes(
                    path,
                    rawPath,
                    flags,
                    method,
                    versionNeededToExtract,
                    crc32,
                    compressedSize,
                    uncompressedSize,
                    extraData,
                    directory
            );
        }

        /// Returns whether this entry is encrypted.
        private boolean encrypted() {
            return (flags & ENCRYPTED_FLAG) != 0;
        }
    }

}

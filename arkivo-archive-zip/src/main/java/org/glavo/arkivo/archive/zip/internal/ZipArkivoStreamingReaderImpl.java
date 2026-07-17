// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;


import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipLegacyCharsetDetector;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.zip.internal.ZipConstants.BZIP2_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DATA_DESCRIPTOR_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DATA_DESCRIPTOR_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DEFLATED_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.DEFLATE64_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ENCRYPTED_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_EOS_MARKER_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LZMA_PROPERTY_SIZE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.LOCAL_FILE_HEADER_SIGNATURE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.STORED_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.STRONG_ENCRYPTION_FLAG;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.UINT32_MAX;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.XZ_METHOD;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.isZstandardMethod;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readInt;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readIntOrEnd;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readRequiredByte;
import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readUnsignedShort;

/// Implements the public forward-only ZIP streaming reader API.
@NotNullByDefault
public final class ZipArkivoStreamingReaderImpl extends ZipArkivoStreamingReader {
    /// The pushback buffer size used to return bytes read past the end of a deflated stream.
    private static final int PUSHBACK_BUFFER_SIZE = 8192;

    /// The fixed local file header size, including its signature.
    private static final int LOCAL_FILE_HEADER_SIZE = 30;

    /// The ZIP LZMA version and property-header size in bytes.
    private static final int LZMA_STREAM_HEADER_SIZE = 2 * Short.BYTES + LZMA_PROPERTY_SIZE;

    /// The data descriptor signature bytes in stream order.
    private static final byte @Unmodifiable [] DATA_DESCRIPTOR_SIGNATURE_BYTES = new byte[]{
            0x50,
            0x4b,
            0x07,
            0x08
    };

    /// The source stream whose closure is owned by this reader.
    private final InputStream source;

    /// The pushback input stream used to parse ZIP bytes.
    private final PushbackInputStream input;

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The common archive read-limit tracker.
    private final ArkivoReadLimitTracker readLimits;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// Whether closure of the streaming input has completed.
    private boolean inputClosed;

    /// The current local entry, or `null` when no entry is active.
    private @Nullable LocalEntry currentEntry;

    /// The current entry input stream, or `null` when no entry input stream is active.
    private @Nullable CurrentEntryInputStream currentInput;

    /// The current ZIP entry attributes, or `null` when no entry is active.
    private @Nullable ZipArkivoEntryAttributes currentAttributes;

    /// The completed known-size entry that may be followed by an undeclared signed data descriptor.
    private @Nullable LocalEntry unexpectedDataDescriptorCandidate;

    /// Whether this reader is open.
    private boolean open = true;

    /// Whether the next record signature is the first physical signature in the archive.
    private boolean archiveStart = true;

    /// Creates a ZIP streaming reader.
    public ZipArkivoStreamingReaderImpl(
            ReadableByteChannel source,
            ZipArkivoFileSystemConfig config
    ) {
        Objects.requireNonNull(config, "config");
        this.source = StreamChannelAdapters.inputStream(Objects.requireNonNull(source, "source"));
        this.input = new PushbackInputStream(this.source, PUSHBACK_BUFFER_SIZE);
        this.config = config;
        this.readLimits = ArkivoReadLimitTracker.fromLimits(
                config.maximumEntryCount(),
                config.maximumEntrySize(),
                config.maximumTotalEntrySize(),
                config.maximumMetadataSize()
        );
        this.lock = ZipLocks.create(config.threadSafety());
    }

    /// Closes this streaming reader, its current entry, and the source stream.
    @Override
    protected void closeReader() throws IOException {
        lock();
        try {
            open = false;
            currentAttributes = null;
            Throwable failure = null;
            try {
                closeCurrentEntry();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            if (!inputClosed) {
                try {
                    source.close();
                    inputClosed = true;
                } catch (IOException | RuntimeException | Error exception) {
                    failure = mergeFailure(failure, exception);
                }
            }
            throwFailure(failure);
        } finally {
            unlock();
        }
    }

    /// Advances to the next ZIP entry and returns whether an entry is available.
    @Override
    protected boolean advance() throws IOException {
        lock();
        try {
            ensureOpen();
            if (!nextStreamingEntry()) {
                currentAttributes = null;
                return false;
            }
            LocalEntry entry = Objects.requireNonNull(currentEntry, "currentEntry");
            currentAttributes = entry.attributes();
            return true;
        } finally {
            unlock();
        }
    }

    /// Reads the current archive entry attributes as the requested attribute type.
    @Override
    protected <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) throws IOException {
        lock();
        try {
            ensureOpen();
            Objects.requireNonNull(type, "type");
            ZipArkivoEntryAttributes attributes = currentAttributes;
            if (attributes == null) {
                throw new IllegalStateException("ZIP streaming reader is not positioned at an entry");
            }
            if (type.isInstance(attributes)) {
                return type.cast(attributes);
            }
            throw new UnsupportedOperationException("Unsupported ZIP streaming attributes type: " + type.getName());
        } finally {
            unlock();
        }
    }

    /// Opens a readable channel for the current file entry.
    @Override
    protected ReadableByteChannel openCurrentChannel() throws IOException {
        lock();
        try {
            ensureOpen();
            if (currentAttributes == null) {
                throw new IOException("ZIP streaming reader has not advanced to an entry");
            }
            return openCurrentEntryChannel();
        } finally {
            unlock();
        }
    }

    /// Advances to the next streaming ZIP entry.
    private boolean nextStreamingEntry() throws IOException {
        ensureOpen();
        readLimits.requireWithinLimits();
        closeCurrentEntry();

        LocalEntry descriptorCandidate = unexpectedDataDescriptorCandidate;
        unexpectedDataDescriptorCandidate = null;
        int signature = readIntOrEnd(input);
        if (archiveStart) {
            archiveStart = false;
            if (signature == DATA_DESCRIPTOR_SIGNATURE) {
                signature = readIntOrEnd(input);
            }
        } else if (signature == DATA_DESCRIPTOR_SIGNATURE && descriptorCandidate != null) {
            if (!readAndMatchesDataDescriptorAfterSignature(
                    input,
                    descriptorCandidate.zip64DataDescriptor,
                    descriptorCandidate.crc32,
                    descriptorCandidate.compressedSize,
                    descriptorCandidate.uncompressedSize,
                    false
            )) {
                throw new IOException("Undeclared ZIP data descriptor does not match entry data");
            }
            signature = readIntOrEnd(input);
        }
        if (signature < 0
                || signature == CENTRAL_DIRECTORY_HEADER_SIGNATURE
                || signature == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            currentEntry = null;
            return false;
        }
        if (signature != LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IOException("Unexpected ZIP stream record signature: " + Integer.toHexString(signature));
        }
        readLimits.acceptMetadata(LOCAL_FILE_HEADER_SIZE, null);

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
        readLimits.acceptMetadata((long) nameLength + extraLength, null);
        byte[] rawName = readBytes(nameLength);
        byte[] extraData = readBytes(extraLength);
        ZipExtraFields.validate(extraData);
        if (!hasDataDescriptor && (headerCompressedSize == UINT32_MAX || headerUncompressedSize == UINT32_MAX)) {
            Zip64LocalSizes zip64Sizes = readZip64LocalSizes(
                    extraData,
                    headerUncompressedSize,
                    headerCompressedSize
            );
            compressedSize = zip64Sizes.compressedSize;
            uncompressedSize = zip64Sizes.uncompressedSize;
        }
        boolean zip64DataDescriptor = headerCompressedSize == UINT32_MAX
                || headerUncompressedSize == UINT32_MAX
                || hasZip64ExtraField(extraData);

        String path = decodePath(rawName, flags, extraData, versionNeededToExtract);
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
        readLimits.acceptEntry(path, uncompressedSize);
        currentEntry = entry;
        return true;
    }

    /// Opens a readable channel for the current streaming ZIP entry.
    private ReadableByteChannel openCurrentEntryChannel() throws IOException {
        ensureOpen();
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

        InputStream decoded;
        try {
            decoded = trackedEntryInputStream(entry);
        } catch (IOException | RuntimeException | Error exception) {
            // Known-size setup paths clear currentEntry only after their bounded body has been drained successfully.
            // Other setup failures may have consumed an unknown prefix, so the forward-only reader cannot recover.
            if (currentEntry != null) {
                open = false;
                currentEntry = null;
            }
            currentAttributes = null;
            throw exception;
        }
        CurrentEntryInputStream entryInput = new CurrentEntryInputStream(this, decoded);
        currentInput = entryInput;
        return StreamChannelAdapters.readableChannel(entryInput);
    }

    /// Requires this streaming reader to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("ZIP streaming reader is closed");
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

    /// Reads exactly `length` bytes from the given input stream.
    private static byte[] readBytes(InputStream input, int length, String failureMessage) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new EOFException(failureMessage);
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
        ByteArrayAccess.writeIntLittleEndian(descriptor, 0, first);
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

    /// Reads stored-entry descriptor fields after a consumed signature.
    private static boolean readStoredDataDescriptorAfterSignature(
            PushbackInputStream input,
            boolean zip64,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            String failureMessage
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
        if (zip64) {
            byte[] tail = new byte[dataDescriptorSize(true) - dataDescriptorSize(false)];
            int tailSize = readPotentialZip64DescriptorTail(input, tail);
            if (tailSize == tail.length) {
                byte[] zip64Descriptor = new byte[dataDescriptorSize(true)];
                System.arraycopy(descriptor, 0, zip64Descriptor, 0, descriptor.length);
                System.arraycopy(tail, 0, zip64Descriptor, descriptor.length, tail.length);
                if (matchesDataDescriptor(zip64Descriptor, crc32, compressedSize, uncompressedSize)) {
                    return true;
                }
                if (matchesDataDescriptorSizes(zip64Descriptor, compressedSize, uncompressedSize)
                        && followedByZipRecordSignature(input)) {
                    throw new IOException(failureMessage);
                }
                unread(input, tail, tailSize);
            }
        }

        if (matchesDataDescriptor(descriptor, crc32, compressedSize, uncompressedSize)) {
            return true;
        }
        if (matchesDataDescriptorSizes(descriptor, compressedSize, uncompressedSize)
                && followedByZipRecordSignature(input)) {
            throw new IOException(failureMessage);
        }
        unread(input, descriptor, descriptor.length);
        return false;
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

    /// Returns whether the next bytes are a ZIP stream record signature without consuming them.
    private static boolean followedByZipRecordSignature(PushbackInputStream input) throws IOException {
        byte[] signatureBytes = new byte[Integer.BYTES];
        int length = 0;
        try {
            while (length < signatureBytes.length) {
                int read = input.read(signatureBytes, length, signatureBytes.length - length);
                if (read < 0) {
                    return false;
                }
                length += read;
            }
            int signature = readInt(signatureBytes, 0);
            return signature == LOCAL_FILE_HEADER_SIGNATURE
                    || signature == CENTRAL_DIRECTORY_HEADER_SIGNATURE
                    || signature == END_OF_CENTRAL_DIRECTORY_SIGNATURE;
        } finally {
            unread(input, signatureBytes, length);
        }
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
            descriptorCompressedSize = ByteArrayAccess.readLongLittleEndian(descriptor, 4);
            descriptorUncompressedSize = ByteArrayAccess.readLongLittleEndian(descriptor, 12);
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
            descriptorCompressedSize = ByteArrayAccess.readLongLittleEndian(descriptor, 4);
            descriptorUncompressedSize = ByteArrayAccess.readLongLittleEndian(descriptor, 12);
        } else {
            descriptorCompressedSize = Integer.toUnsignedLong(readInt(descriptor, 4));
            descriptorUncompressedSize = Integer.toUnsignedLong(readInt(descriptor, 8));
        }
        return descriptorCompressedSize == compressedSize
                && descriptorUncompressedSize == uncompressedSize;
    }

    /// Returns whether extra data contains a ZIP64 extended information field.
    private static boolean hasZip64ExtraField(byte[] extraData) throws IOException {
        return ZipExtraFields.find(extraData, ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID) != null;
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

        ZipExtraFields.Field field = ZipExtraFields.find(extraData, ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID);
        if (field == null) {
            throw new IOException("Required ZIP64 extended information extra field is missing");
        }

        int dataOffset = field.dataOffset();
        int dataEnd = field.nextOffset();
        if (needsUncompressedSize) {
            uncompressedSize = readZip64Size(extraData, dataOffset, dataEnd);
            dataOffset += Long.BYTES;
        }
        if (needsCompressedSize) {
            compressedSize = readZip64Size(extraData, dataOffset, dataEnd);
        }
        return new Zip64LocalSizes(uncompressedSize, compressedSize);
    }

    /// Reads one non-negative ZIP64 size value from extra data.
    private static long readZip64Size(byte[] extraData, int offset, int limit) throws IOException {
        if (offset + Long.BYTES > limit) {
            throw new IOException("Invalid ZIP64 extended information extra field");
        }
        long value = ByteArrayAccess.readLongLittleEndian(extraData, offset);
        if (value < 0) {
            throw new IOException("ZIP64 size is too large");
        }
        return value;
    }

    /// Decodes an entry path.
    private String decodePath(
            byte[] rawName,
            int flags,
            byte[] extraData,
            int versionNeededToExtract
    ) throws IOException {
        try {
            String path = new ZipEntryNameDecoder(config.legacyCharsetDetector())
                    .decodePath(
                            rawName,
                            flags,
                            extraData,
                            ZipLegacyCharsetDetector.HeaderSource.LOCAL_FILE_HEADER,
                            versionNeededToExtract,
                            ZipLegacyCharsetDetector.UNKNOWN_HEADER_VALUE
                    );
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

    /// Opens an input stream that accounts for an entry whose logical size was absent from its header.
    private InputStream trackedEntryInputStream(LocalEntry entry) throws IOException {
        InputStream decoded = entryInputStream(entry);
        if (entry.uncompressedSize == ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
            return readLimits.trackUnknownEntrySize(entry.path, decoded);
        }
        return decoded;
    }

    /// Opens an input stream for an entry.
    private InputStream entryInputStream(LocalEntry entry) throws IOException {
        boolean hasDataDescriptor = (entry.flags & DATA_DESCRIPTOR_FLAG) != 0;
        boolean encrypted = entry.encrypted();
        ZipAesExtraField aes = encrypted ? ZipAesExtraField.readValidated(entry.extraData) : null;
        if (encrypted && aes == null) {
            requireTraditionalEncryption(entry);
        }
        int compressionMethod = aes != null ? aes.compressionMethod() : entry.method;
        if (aes != null) {
            if (hasDataDescriptor) {
                if (compressionMethod == DEFLATED_METHOD) {
                    ZipAesCrypto.Decryptor decryptor = openAesDecryptor(entry, aes, input);
                    return openDeflateDataDescriptorInputStream(
                            input,
                            new AesDecryptingInputStream(input, decryptor),
                            aes.overheadSize(),
                            decryptor,
                            aes.authenticationCodeSize(),
                            "WinZip AES data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                if (compressionMethod == DEFLATE64_METHOD) {
                    ZipAesCrypto.Decryptor decryptor = openAesDecryptor(entry, aes, input);
                    return openDeflate64DataDescriptorInputStream(
                            input,
                            new AesDecryptingInputStream(input, decryptor),
                            aes.overheadSize(),
                            decryptor,
                            aes.authenticationCodeSize(),
                            "WinZip AES data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
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
                if (compressionMethod == BZIP2_METHOD) {
                    ZipAesCrypto.Decryptor decryptor = openAesDecryptor(entry, aes, input);
                    return openBzip2DataDescriptorInputStream(
                            input,
                            new AesDecryptingInputStream(input, decryptor),
                            aes.overheadSize(),
                            decryptor,
                            aes.authenticationCodeSize(),
                            "WinZip AES data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                if (compressionMethod == LZMA_METHOD) {
                    ZipAesCrypto.Decryptor decryptor = openAesDecryptor(entry, aes, input);
                    return openLzmaDataDescriptorInputStream(
                            input,
                            new AesDecryptingInputStream(input, decryptor),
                            aes.overheadSize(),
                            decryptor,
                            aes.authenticationCodeSize(),
                            "WinZip AES data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false,
                            entry.flags
                    );
                }
                if (compressionMethod == XZ_METHOD) {
                    ZipAesCrypto.Decryptor decryptor = openAesDecryptor(entry, aes, input);
                    return openXzDataDescriptorInputStream(
                            input,
                            new AesDecryptingInputStream(input, decryptor),
                            aes.overheadSize(),
                            decryptor,
                            aes.authenticationCodeSize(),
                            "WinZip AES data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                if (isZstandardMethod(compressionMethod)) {
                    ZipAesCrypto.Decryptor decryptor = openAesDecryptor(entry, aes, input);
                    return new CodecDataDescriptorInputStream(
                            input,
                            new AesDecryptingInputStream(input, decryptor),
                            aes.overheadSize(),
                            decryptor,
                            aes.authenticationCodeSize(),
                            "WinZip AES data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
            }
            if (compressionMethod != STORED_METHOD
                    && compressionMethod != DEFLATED_METHOD
                    && compressionMethod != DEFLATE64_METHOD
                    && compressionMethod != BZIP2_METHOD
                    && compressionMethod != LZMA_METHOD
                    && compressionMethod != XZ_METHOD
                    && !isZstandardMethod(compressionMethod)) {
                throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
            }

            InputStream encryptedData = new BoundedInputStream(input, entry.compressedSize);
            InputStream decryptedData;
            try {
                decryptedData = openAesDecryptingStream(entry, aes, encryptedData);
            } catch (IOException | RuntimeException | Error exception) {
                closeEntryAfterFailedSetup(encryptedData, exception);
                throw exception;
            }
            if (compressionMethod == STORED_METHOD) {
                return new KnownSizeEntryInputStream(
                        decryptedData,
                        ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                        entry.uncompressedSize
                );
            }
            if (compressionMethod == BZIP2_METHOD) {
                return new KnownSizeEntryInputStream(
                        openBzip2InputStream(decryptedData, entry.uncompressedSize),
                        ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                        entry.uncompressedSize
                );
            }
            if (compressionMethod == LZMA_METHOD) {
                return new KnownSizeEntryInputStream(
                        openLzmaInputStream(decryptedData, entry.uncompressedSize, entry.flags),
                        ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                        entry.uncompressedSize
                );
            }
            if (compressionMethod == XZ_METHOD) {
                return new KnownSizeEntryInputStream(
                        openXzInputStream(decryptedData, entry.uncompressedSize),
                        ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                        entry.uncompressedSize
                );
            }
            if (compressionMethod == DEFLATE64_METHOD) {
                return new KnownSizeEntryInputStream(
                        openDeflate64InputStream(decryptedData, entry.uncompressedSize),
                        ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                        entry.uncompressedSize
                );
            }
            if (isZstandardMethod(compressionMethod)) {
                return new KnownSizeEntryInputStream(
                        openZstandardInputStream(decryptedData, entry.uncompressedSize),
                        ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                        entry.uncompressedSize
                );
            }
            return openDeflateKnownSizeInputStream(
                    decryptedData,
                    aes.overheadSize(),
                    entry.compressedSize,
                    ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                    entry.uncompressedSize
            );
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
            InputStream data = encrypted ? openTraditionalKnownSizeDecryptingStream(entry, stored) : stored;
            return new KnownSizeEntryInputStream(data, entry.crc32, entry.uncompressedSize);
        }
        if (compressionMethod == DEFLATED_METHOD) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return openDeflateDataDescriptorInputStream(
                            input,
                            new TraditionalDecryptingInputStream(input, openTraditionalDecryptor(entry, input)),
                            ZipTraditionalCrypto.HEADER_SIZE,
                            null,
                            0,
                            "ZIP data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                return openDeflateDataDescriptorInputStream(
                        input,
                        new NonClosingInputStream(input),
                        0L,
                        null,
                        0,
                        "ZIP data descriptor does not match entry data",
                        entry.zip64DataDescriptor,
                        true
                );
            }

            InputStream compressed = new BoundedInputStream(input, entry.compressedSize);
            long compressedSizeOffset = 0L;
            if (encrypted) {
                compressed = openTraditionalKnownSizeDecryptingStream(entry, compressed);
                compressedSizeOffset = ZipTraditionalCrypto.HEADER_SIZE;
            }
            return openDeflateKnownSizeInputStream(
                    compressed,
                    compressedSizeOffset,
                    entry.compressedSize,
                    entry.crc32,
                    entry.uncompressedSize
            );
        }
        if (compressionMethod == BZIP2_METHOD) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return openBzip2DataDescriptorInputStream(
                            input,
                            new TraditionalDecryptingInputStream(input, openTraditionalDecryptor(entry, input)),
                            ZipTraditionalCrypto.HEADER_SIZE,
                            null,
                            0,
                            "ZIP data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                return openBzip2DataDescriptorInputStream(
                        input,
                        new NonClosingInputStream(input),
                        0,
                        null,
                        0,
                        "ZIP data descriptor does not match entry data",
                        entry.zip64DataDescriptor,
                        true
                );
            }

            InputStream compressed = new BoundedInputStream(input, entry.compressedSize);
            if (encrypted) {
                compressed = openTraditionalKnownSizeDecryptingStream(entry, compressed);
            }
            return new KnownSizeEntryInputStream(
                    openBzip2InputStream(compressed, entry.uncompressedSize),
                    entry.crc32,
                    entry.uncompressedSize
            );
        }
        if (compressionMethod == LZMA_METHOD) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return openLzmaDataDescriptorInputStream(
                            input,
                            new TraditionalDecryptingInputStream(input, openTraditionalDecryptor(entry, input)),
                            ZipTraditionalCrypto.HEADER_SIZE,
                            null,
                            0,
                            "ZIP data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false,
                            entry.flags
                    );
                }
                return openLzmaDataDescriptorInputStream(
                        input,
                        new NonClosingInputStream(input),
                        0,
                        null,
                        0,
                        "ZIP data descriptor does not match entry data",
                        entry.zip64DataDescriptor,
                        true,
                        entry.flags
                );
            }

            InputStream compressed = new BoundedInputStream(input, entry.compressedSize);
            if (encrypted) {
                compressed = openTraditionalKnownSizeDecryptingStream(entry, compressed);
            }
            return new KnownSizeEntryInputStream(
                    openLzmaInputStream(compressed, entry.uncompressedSize, entry.flags),
                    entry.crc32,
                    entry.uncompressedSize
            );
        }
        if (compressionMethod == XZ_METHOD) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return openXzDataDescriptorInputStream(
                            input,
                            new TraditionalDecryptingInputStream(input, openTraditionalDecryptor(entry, input)),
                            ZipTraditionalCrypto.HEADER_SIZE,
                            null,
                            0,
                            "ZIP data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                return openXzDataDescriptorInputStream(
                        input,
                        new NonClosingInputStream(input),
                        0,
                        null,
                        0,
                        "ZIP data descriptor does not match entry data",
                        entry.zip64DataDescriptor,
                        true
                );
            }

            InputStream compressed = new BoundedInputStream(input, entry.compressedSize);
            if (encrypted) {
                compressed = openTraditionalKnownSizeDecryptingStream(entry, compressed);
            }
            return new KnownSizeEntryInputStream(
                    openXzInputStream(compressed, entry.uncompressedSize),
                    entry.crc32,
                    entry.uncompressedSize
            );
        }
        if (isZstandardMethod(compressionMethod)) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return new CodecDataDescriptorInputStream(
                            input,
                            new TraditionalDecryptingInputStream(input, openTraditionalDecryptor(entry, input)),
                            ZipTraditionalCrypto.HEADER_SIZE,
                            null,
                            0,
                            "ZIP data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                return new CodecDataDescriptorInputStream(
                        input,
                        new NonClosingInputStream(input),
                        0,
                        null,
                        0,
                        "ZIP data descriptor does not match entry data",
                        entry.zip64DataDescriptor,
                        true
                );
            }

            InputStream compressed = new BoundedInputStream(input, entry.compressedSize);
            if (encrypted) {
                compressed = openTraditionalKnownSizeDecryptingStream(entry, compressed);
            }
            return new KnownSizeEntryInputStream(
                    openZstandardInputStream(compressed, entry.uncompressedSize),
                    entry.crc32,
                    entry.uncompressedSize
            );
        }
        if (compressionMethod == DEFLATE64_METHOD) {
            if (hasDataDescriptor) {
                if (encrypted) {
                    return openDeflate64DataDescriptorInputStream(
                            input,
                            new TraditionalDecryptingInputStream(input, openTraditionalDecryptor(entry, input)),
                            ZipTraditionalCrypto.HEADER_SIZE,
                            null,
                            0,
                            "ZIP data descriptor does not match entry data",
                            entry.zip64DataDescriptor,
                            false
                    );
                }
                return openDeflate64DataDescriptorInputStream(
                        input,
                        new NonClosingInputStream(input),
                        0,
                        null,
                        0,
                        "ZIP data descriptor does not match entry data",
                        entry.zip64DataDescriptor,
                        true
                );
            }

            InputStream compressed = new BoundedInputStream(input, entry.compressedSize);
            if (encrypted) {
                compressed = openTraditionalKnownSizeDecryptingStream(entry, compressed);
            }
            return new KnownSizeEntryInputStream(
                    openDeflate64InputStream(compressed, entry.uncompressedSize),
                    entry.crc32,
                    entry.uncompressedSize
            );
        }
        throw new IOException("Unsupported ZIP compression method: " + compressionMethod);
    }

    /// Opens a Deflate64 decoding stream and owns the compressed stream.
    private InputStream openDeflate64InputStream(InputStream input, long decodedSize) throws IOException {
        return ZipCompressionFormats.newInputStream("deflate64", input, decodedSize, config.readLimits());
    }

    /// Opens a BZip2 decoding stream and owns the compressed stream.
    private InputStream openBzip2InputStream(InputStream input, long decodedSize) throws IOException {
        return ZipCompressionFormats.newInputStream("bzip2", input, decodedSize, config.readLimits());
    }

    /// Opens a Zstandard decoding stream and owns the compressed stream.
    private InputStream openZstandardInputStream(InputStream input, long decodedSize) throws IOException {
        return ZipCompressionFormats.newInputStream("zstd", input, decodedSize, config.readLimits());
    }

    /// Opens a ZIP LZMA decoding stream and closes the compressed stream if setup fails.
    private InputStream openLzmaInputStream(
            InputStream input,
            long uncompressedSize,
            int flags
    ) throws IOException {
        try {
            LzmaDecoderParameters parameters = readLzmaDecoderParameters(input, uncompressedSize, flags);
            return ZipCompressionFormats.openRawLZMADecoder(
                    input,
                    parameters.property(),
                    parameters.dictionarySize(),
                    parameters.expectedUncompressedSize(),
                    uncompressedSize,
                    config.readLimits()
            );
        } catch (IOException | RuntimeException | Error exception) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Reads and validates the ZIP-specific LZMA property header.
    private static LzmaDecoderParameters readLzmaDecoderParameters(
            InputStream input,
            long uncompressedSize,
            int flags
    ) throws IOException {
        readUnsignedShort(input);
        int propertySize = readUnsignedShort(input);
        if (propertySize != LZMA_PROPERTY_SIZE) {
            throw new IOException("Unsupported ZIP LZMA property size: " + propertySize);
        }
        int property = readRequiredByte(input);
        int dictionarySize = readInt(input);
        if (dictionarySize < 0) {
            throw new IOException("Unsupported ZIP LZMA dictionary size: "
                    + Integer.toUnsignedLong(dictionarySize));
        }
        boolean usesEndMarker = (flags & LZMA_EOS_MARKER_FLAG) != 0;
        if (!usesEndMarker && uncompressedSize == ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
            throw new IOException("ZIP LZMA entry without EOS marker requires an uncompressed size");
        }
        return new LzmaDecoderParameters(
                property,
                Integer.toUnsignedLong(dictionarySize),
                usesEndMarker ? ZipArkivoEntryAttributes.UNKNOWN_SIZE : uncompressedSize
        );
    }

    /// Opens an XZ decoding stream and owns the compressed stream.
    private InputStream openXzInputStream(InputStream input, long decodedSize) throws IOException {
        return ZipCompressionFormats.newInputStream("xz", input, decodedSize, config.readLimits());
    }

    /// Opens a raw Deflate decoder for an entry with known metadata.
    private InputStream openDeflateKnownSizeInputStream(
            InputStream compressedInput,
            long compressedSizeOffset,
            long expectedCompressedSize,
            long expectedCrc32,
            long expectedUncompressedSize
    ) throws IOException {
        DecompressingReadableByteChannel decoder = ZipCompressionFormats.newReadableByteChannel(
                "deflate",
                compressedInput,
                expectedUncompressedSize,
                config.readLimits()
        );
        return new KnownSizeEntryInputStream(
                decoder,
                compressedSizeOffset,
                expectedCompressedSize,
                expectedCrc32,
                expectedUncompressedSize
        );
    }

    /// Opens a raw Deflate decoder for an entry followed by a data descriptor.
    private InputStream openDeflateDataDescriptorInputStream(
            PushbackInputStream input,
            InputStream compressedInput,
            long compressedSizeOffset,
            @Nullable ZipAesCrypto.Decryptor aesDecryptor,
            int authenticationCodeSize,
            String descriptorMismatchMessage,
            boolean zip64DataDescriptor,
            boolean pushbackSourceRemainder
    ) throws IOException {
        return new CodecDataDescriptorInputStream(
                "deflate",
                "Deflate",
                false,
                CodecResult.Status.END_OF_INPUT,
                input,
                compressedInput,
                compressedSizeOffset,
                aesDecryptor,
                authenticationCodeSize,
                descriptorMismatchMessage,
                zip64DataDescriptor,
                pushbackSourceRemainder
        );
    }

    /// Opens a Deflate64 decoder for an entry followed by a data descriptor.
    private InputStream openDeflate64DataDescriptorInputStream(
            PushbackInputStream input,
            InputStream compressedInput,
            long compressedSizeOffset,
            @Nullable ZipAesCrypto.Decryptor aesDecryptor,
            int authenticationCodeSize,
            String descriptorMismatchMessage,
            boolean zip64DataDescriptor,
            boolean pushbackSourceRemainder
    )
            throws IOException {
        return new CodecDataDescriptorInputStream(
                "deflate64",
                "Deflate64",
                false,
                CodecResult.Status.END_OF_INPUT,
                input,
                compressedInput,
                compressedSizeOffset,
                aesDecryptor,
                authenticationCodeSize,
                descriptorMismatchMessage,
                zip64DataDescriptor,
                pushbackSourceRemainder
        );
    }

    /// Opens a BZip2 decoder for an entry followed by a data descriptor.
    private InputStream openBzip2DataDescriptorInputStream(
            PushbackInputStream input,
            InputStream compressedInput,
            long compressedSizeOffset,
            @Nullable ZipAesCrypto.Decryptor aesDecryptor,
            int authenticationCodeSize,
            String descriptorMismatchMessage,
            boolean zip64DataDescriptor,
            boolean pushbackSourceRemainder
    )
            throws IOException {
        return new CodecDataDescriptorInputStream(
                "bzip2",
                "BZip2",
                true,
                CodecResult.Status.FRAME_FINISHED,
                input,
                compressedInput,
                compressedSizeOffset,
                aesDecryptor,
                authenticationCodeSize,
                descriptorMismatchMessage,
                zip64DataDescriptor,
                pushbackSourceRemainder
        );
    }

    /// Opens a raw LZMA decoder for an entry followed by a data descriptor.
    private InputStream openLzmaDataDescriptorInputStream(
            PushbackInputStream input,
            InputStream compressedInput,
            long compressedSizeOffset,
            @Nullable ZipAesCrypto.Decryptor aesDecryptor,
            int authenticationCodeSize,
            String descriptorMismatchMessage,
            boolean zip64DataDescriptor,
            boolean pushbackSourceRemainder,
            int flags
    )
            throws IOException {
        Objects.requireNonNull(compressedInput, "compressedInput");
        try {
            LzmaDecoderParameters parameters = readLzmaDecoderParameters(
                    compressedInput,
                    ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                    flags
            );
            DecompressingReadableByteChannel decoder = ZipCompressionFormats.openRawLZMADecoderContext(
                    dataDescriptorDecoderSource(compressedInput, pushbackSourceRemainder),
                    parameters.property(),
                    parameters.dictionarySize(),
                    parameters.expectedUncompressedSize(),
                    ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                    config.readLimits()
            );
            return new CodecDataDescriptorInputStream(
                    "LZMA",
                    false,
                    CodecResult.Status.END_OF_INPUT,
                    input,
                    decoder,
                    compressedSizeOffset + LZMA_STREAM_HEADER_SIZE,
                    aesDecryptor,
                    authenticationCodeSize,
                    descriptorMismatchMessage,
                    zip64DataDescriptor,
                    pushbackSourceRemainder
            );
        } catch (IOException | RuntimeException | Error exception) {
            try {
                compressedInput.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens an XZ decoder for an entry followed by a data descriptor.
    private InputStream openXzDataDescriptorInputStream(
            PushbackInputStream input,
            InputStream compressedInput,
            long compressedSizeOffset,
            @Nullable ZipAesCrypto.Decryptor aesDecryptor,
            int authenticationCodeSize,
            String descriptorMismatchMessage,
            boolean zip64DataDescriptor,
            boolean pushbackSourceRemainder
    )
            throws IOException {
        return new CodecDataDescriptorInputStream(
                "xz",
                "XZ",
                true,
                CodecResult.Status.FRAME_FINISHED,
                input,
                compressedInput,
                compressedSizeOffset,
                aesDecryptor,
                authenticationCodeSize,
                descriptorMismatchMessage,
                zip64DataDescriptor,
                pushbackSourceRemainder
        );
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
        byte[] password = passwordForEntry(entry, aes.encryption());
        try {
            return ZipAesCrypto.openDecryptingStream(input, aes, password, entry.compressedSize);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
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
        byte[] password = passwordForEntry(entry, aes.encryption());
        try {
            return ZipAesCrypto.openDecryptor(input, aes, password);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Opens a traditional ZIP decrypting stream for an entry with known compressed size.
    private InputStream openTraditionalDecryptingStream(LocalEntry entry, InputStream input) throws IOException {
        if (entry.compressedSize < ZipTraditionalCrypto.HEADER_SIZE) {
            throw new IOException("Encrypted ZIP entry is missing its encryption header: " + entry.path);
        }
        byte[] password = passwordForEntry(entry, ZipEncryption.ZIP_CRYPTO);
        try {
            return ZipTraditionalCrypto.openDecryptingStream(input, password, encryptionVerificationByte(entry));
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Opens a traditional ZIP decrypting stream and drains known-size raw data if setup fails.
    private InputStream openTraditionalKnownSizeDecryptingStream(LocalEntry entry, InputStream input) throws IOException {
        try {
            return openTraditionalDecryptingStream(entry, input);
        } catch (IOException | RuntimeException | Error exception) {
            closeEntryAfterFailedSetup(input, exception);
            throw exception;
        }
    }

    /// Opens a traditional ZIP decryptor after consuming and validating the encryption header.
    private ZipTraditionalCrypto.Decryptor openTraditionalDecryptor(
            LocalEntry entry,
            InputStream input
    ) throws IOException {
        byte[] password = passwordForEntry(entry, ZipEncryption.ZIP_CRYPTO);
        try {
            return ZipTraditionalCrypto.openDecryptor(input, password, encryptionVerificationByte(entry));
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Requires an encrypted entry to use the traditional ZIP encryption method.
    private static void requireTraditionalEncryption(LocalEntry entry) throws IOException {
        if ((entry.flags & STRONG_ENCRYPTION_FLAG) != 0 || ZipAesExtraField.isAes(entry.method, entry.extraData)) {
            throw new IOException("Unsupported ZIP encryption method: " + entry.path);
        }
    }

    /// Returns the password for an encrypted entry.
    private byte[] passwordForEntry(LocalEntry entry, ZipEncryption encryption) throws IOException {
        ArkivoPasswordProvider passwordProvider = config.passwordProvider();
        if (passwordProvider == null) {
            throw new IOException("ZIP entry requires a password: " + entry.path);
        }
        byte[] password = passwordProvider.password(ZipPasswordSupport.entryRequest("/" + entry.path, encryption));
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

    /// Drains a known-size entry after stream setup fails and clears current entry state when recovery succeeds.
    private void closeEntryAfterFailedSetup(InputStream input, Throwable failure) {
        try {
            input.close();
            currentEntry = null;
        } catch (IOException | RuntimeException | Error exception) {
            failure.addSuppressed(exception);
        }
    }

    /// Validates known-size entry data against local file header metadata.
    private static void validateKnownEntryData(
            long compressedSize,
            long crc32,
            long uncompressedSize,
            long expectedCompressedSize,
            long expectedCrc32,
            long expectedUncompressedSize
    ) throws IOException {
        if (expectedCompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                && compressedSize != expectedCompressedSize) {
            throw new IOException("ZIP entry data does not match local header");
        }
        if (expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32 && crc32 != expectedCrc32) {
            throw new IOException("ZIP entry data does not match local header");
        }
        if (expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                && uncompressedSize != expectedUncompressedSize) {
            throw new IOException("ZIP entry data does not match local header");
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

    /// Closes or drains the current entry.
    private void closeCurrentEntry() throws IOException {
        CurrentEntryInputStream entryInput = currentInput;
        if (entryInput != null) {
            entryInput.close();
            return;
        }

        LocalEntry entry = currentEntry;
        if (entry != null) {
            if (shouldSkipRawEntryData(entry)) {
                skipRawEntryData(entry);
            } else {
                try (InputStream ignored = trackedEntryInputStream(entry)) {
                    ignored.transferTo(OutputStream.nullOutputStream());
                }
            }
            rememberUnexpectedDataDescriptorCandidate(entry);
        }
        currentEntry = null;
    }

    /// Remembers a completed known-size entry whose local header may have omitted its descriptor flag.
    private void rememberUnexpectedDataDescriptorCandidate(LocalEntry entry) {
        if ((entry.flags & DATA_DESCRIPTOR_FLAG) == 0
                && entry.crc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32
                && entry.compressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                && entry.uncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
            unexpectedDataDescriptorCandidate = entry;
        }
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
        return entry.method != STORED_METHOD
                && entry.method != DEFLATED_METHOD
                && entry.method != DEFLATE64_METHOD
                && entry.method != BZIP2_METHOD
                && entry.method != LZMA_METHOD
                && entry.method != XZ_METHOD
                && !isZstandardMethod(entry.method);
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
                LocalEntry entry = currentEntry;
                if (entry != null) {
                    rememberUnexpectedDataDescriptorCandidate(entry);
                }
                currentInput = null;
                currentEntry = null;
            }
        } finally {
            unlock();
        }
    }

    /// Reads known-size ZIP entry data and validates it when the stream ends.
    @NotNullByDefault
    private static final class KnownSizeEntryInputStream extends InputStream {
        /// The source stream.
        private final InputStream input;

        /// The decoder that reports compressed progress, or `null` for non-codec streams.
        private final @Nullable DecompressingReadableByteChannel decoder;

        /// The compressed bytes consumed before the decoder source.
        private final long compressedSizeOffset;

        /// The expected total compressed size, or `-1` when it is not validated here.
        private final long expectedCompressedSize;

        /// The expected CRC-32 value.
        private final long expectedCrc32;

        /// The expected uncompressed size.
        private final long expectedUncompressedSize;

        /// The CRC-32 of bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// The number of bytes returned so far.
        private long uncompressedSize;

        /// Whether end-of-entry validation has run.
        private boolean finishedEntry;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a known-size entry stream without compressed-progress validation.
        private KnownSizeEntryInputStream(InputStream input, long expectedCrc32, long expectedUncompressedSize) {
            this(
                    input,
                    null,
                    0L,
                    ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                    expectedCrc32,
                    expectedUncompressedSize
            );
        }

        /// Creates a known-size entry stream over a channel-first decoder.
        private KnownSizeEntryInputStream(
                DecompressingReadableByteChannel decoder,
                long compressedSizeOffset,
                long expectedCompressedSize,
                long expectedCrc32,
                long expectedUncompressedSize
        ) {
            this(
                    StreamChannelAdapters.inputStream(decoder),
                    decoder,
                    compressedSizeOffset,
                    expectedCompressedSize,
                    expectedCrc32,
                    expectedUncompressedSize
            );
        }

        /// Creates a known-size entry stream with optional compressed-progress validation.
        private KnownSizeEntryInputStream(
                InputStream input,
                @Nullable DecompressingReadableByteChannel decoder,
                long compressedSizeOffset,
                long expectedCompressedSize,
                long expectedCrc32,
                long expectedUncompressedSize
        ) {
            this.input = Objects.requireNonNull(input, "input");
            this.decoder = decoder;
            this.compressedSizeOffset = compressedSizeOffset;
            this.expectedCompressedSize = expectedCompressedSize;
            this.expectedCrc32 = expectedCrc32;
            this.expectedUncompressedSize = expectedUncompressedSize;
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
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
            if (finishedEntry) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            int read;
            try {
                read = input.read(bytes, offset, length);
            } catch (EOFException exception) {
                throw new IOException("ZIP entry data does not match local header", exception);
            }
            if (read > 0) {
                crc32.update(bytes, offset, read);
                uncompressedSize += read;
            }
            if (read < 0) {
                finishEntry();
            }
            return read;
        }

        /// Closes this entry stream after draining unread bytes.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;

            Throwable failure = null;
            try {
                byte[] discard = new byte[8192];
                while (readUnchecked(discard, 0, discard.length) >= 0) {
                    // Drain known-size data so it can be validated.
                }
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            try {
                finishEntry();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
            try {
                input.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
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

        /// Finishes the current entry and validates the known metadata.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;
            DecompressingReadableByteChannel codecDecoder = decoder;
            validateKnownEntryData(
                    codecDecoder == null
                            ? ZipArkivoEntryAttributes.UNKNOWN_SIZE
                            : compressedSizeOffset + codecDecoder.inputBytes(),
                    crc32.getValue(),
                    uncompressedSize,
                    expectedCompressedSize,
                    expectedCrc32,
                    expectedUncompressedSize
            );
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP entry input stream is closed");
            }
        }
    }

    /// Reads bytes from the current ZIP entry.
    @NotNullByDefault
    private static final class CurrentEntryInputStream extends InputStream {
        /// The owner file system.
        private final ZipArkivoStreamingReaderImpl owner;

        /// The delegate entry input stream.
        private final InputStream input;

        /// Whether this stream is open.
        private boolean inputOpen = true;

        /// Creates a current entry input stream.
        private CurrentEntryInputStream(ZipArkivoStreamingReaderImpl owner, InputStream input) {
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
                Throwable failure = null;
                try {
                    input.transferTo(OutputStream.nullOutputStream());
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                } finally {
                    try {
                        input.close();
                    } catch (IOException | RuntimeException | Error exception) {
                        if (failure != null) {
                            failure.addSuppressed(exception);
                        } else {
                            failure = exception;
                        }
                    } finally {
                        owner.closeCurrentInput(this);
                    }
                }
                throwFailure(failure);
            } finally {
                owner.unlock();
            }
        }
    }

    /// Selects a decoder source that can preserve the following plaintext ZIP metadata.
    private static InputStream dataDescriptorDecoderSource(
            InputStream compressedInput,
            boolean pushbackSourceRemainder
    ) {
        Objects.requireNonNull(compressedInput, "compressedInput");
        return pushbackSourceRemainder
                ? compressedInput
                : new SingleByteBulkInputStream(compressedInput);
    }

    /// Reads a channel-first codec ZIP entry followed by a data descriptor.
    @NotNullByDefault
    private final class CodecDataDescriptorInputStream extends InputStream {
        /// The raw ZIP input stream.
        private final PushbackInputStream input;

        /// The channel-first compression decoder.
        private final DecompressingReadableByteChannel decoder;

        /// The compression format display name used in diagnostics.
        private final String formatDisplayName;

        /// Whether decoding stops at the current compressed frame boundary.
        private final boolean stopAtFrame;

        /// The decoder status that marks the compressed stream boundary.
        private final CodecResult.Status terminalStatus;

        /// The CRC-32 of decoded bytes returned so far.
        private final CRC32 crc32 = new CRC32();

        /// Whether this entry's data descriptor stores ZIP64 sizes.
        private final boolean zip64DataDescriptor;

        /// The number of bytes already consumed before compressed content.
        private final long compressedSizeOffset;

        /// The WinZip AES decryptor, or `null` when this entry is not WinZip AES encrypted.
        private final @Nullable ZipAesCrypto.Decryptor aesDecryptor;

        /// The WinZip AES authentication code size in bytes.
        private final int authenticationCodeSize;

        /// The message to use when the data descriptor does not match the entry data.
        private final String descriptorMismatchMessage;

        /// Whether bytes read past the compressed stream can be pushed back into the raw ZIP input.
        private final boolean pushbackSourceRemainder;

        /// The number of decoded bytes returned so far.
        private long uncompressedSize;

        /// Whether the decoder has reached the end of the compressed stream.
        private boolean streamFinished;

        /// Whether the following data descriptor has been consumed.
        private boolean finishedEntry;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a Zstandard data descriptor input stream.
        private CodecDataDescriptorInputStream(
                PushbackInputStream input,
                InputStream compressedInput,
                long compressedSizeOffset,
                @Nullable ZipAesCrypto.Decryptor aesDecryptor,
                int authenticationCodeSize,
                String descriptorMismatchMessage,
                boolean zip64DataDescriptor,
                boolean pushbackSourceRemainder
        ) throws IOException {
            this(
                    "zstd",
                    "Zstandard",
                    true,
                    CodecResult.Status.FRAME_FINISHED,
                    input,
                    compressedInput,
                    compressedSizeOffset,
                    aesDecryptor,
                    authenticationCodeSize,
                    descriptorMismatchMessage,
                    zip64DataDescriptor,
                    pushbackSourceRemainder
            );
        }

        /// Creates a data descriptor input stream for the default codec of a named compression format.
        private CodecDataDescriptorInputStream(
                String formatName,
                String formatDisplayName,
                boolean stopAtFrame,
                CodecResult.Status terminalStatus,
                PushbackInputStream input,
                InputStream compressedInput,
                long compressedSizeOffset,
                @Nullable ZipAesCrypto.Decryptor aesDecryptor,
                int authenticationCodeSize,
                String descriptorMismatchMessage,
                boolean zip64DataDescriptor,
                boolean pushbackSourceRemainder
        ) throws IOException {
            this(
                    formatDisplayName,
                    stopAtFrame,
                    terminalStatus,
                    input,
                    ZipCompressionFormats.newReadableByteChannel(
                            formatName,
                            dataDescriptorDecoderSource(compressedInput, pushbackSourceRemainder),
                            ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                            config.readLimits()
                    ),
                    compressedSizeOffset,
                    aesDecryptor,
                    authenticationCodeSize,
                    descriptorMismatchMessage,
                    zip64DataDescriptor,
                    pushbackSourceRemainder
            );
        }

        /// Creates a data descriptor input stream over a configured channel-first decoder.
        private CodecDataDescriptorInputStream(
                String formatDisplayName,
                boolean stopAtFrame,
                CodecResult.Status terminalStatus,
                PushbackInputStream input,
                DecompressingReadableByteChannel decoder,
                long compressedSizeOffset,
                @Nullable ZipAesCrypto.Decryptor aesDecryptor,
                int authenticationCodeSize,
                String descriptorMismatchMessage,
                boolean zip64DataDescriptor,
                boolean pushbackSourceRemainder
        ) {
            this.input = Objects.requireNonNull(input, "input");
            this.decoder = Objects.requireNonNull(decoder, "decoder");
            this.formatDisplayName = Objects.requireNonNull(formatDisplayName, "formatDisplayName");
            if (stopAtFrame && !(decoder instanceof DecompressingReadableByteChannel.Framed)) {
                throw new IllegalArgumentException("Frame-stopping decode requires a framed decoder");
            }
            this.stopAtFrame = stopAtFrame;
            this.terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus");
            this.compressedSizeOffset = compressedSizeOffset;
            this.aesDecryptor = aesDecryptor;
            this.authenticationCodeSize = authenticationCodeSize;
            this.descriptorMismatchMessage = Objects.requireNonNull(descriptorMismatchMessage, "descriptorMismatchMessage");
            this.zip64DataDescriptor = zip64DataDescriptor;
            this.pushbackSourceRemainder = pushbackSourceRemainder;
        }

        /// Reads one decoded byte.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads decoded bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads decoded bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (finishedEntry) {
                return -1;
            }

            while (true) {
                if (streamFinished) {
                    finishEntry();
                    return -1;
                }

                ByteBuffer target = ByteBuffer.wrap(bytes, offset, length);
                CodecResult result = stopAtFrame
                        ? ((DecompressingReadableByteChannel.Framed) decoder).decodeFrame(target)
                        : decoder.decode(target);
                int decoded = Math.toIntExact(result.outputBytes());
                if (result.status() == terminalStatus) {
                    streamFinished = true;
                    unreadSourceRemainder();
                } else if (result.status() == CodecResult.Status.END_OF_INPUT) {
                    throw new EOFException("Unexpected end of " + formatDisplayName + " ZIP entry before data descriptor");
                }

                if (decoded > 0) {
                    crc32.update(bytes, offset, decoded);
                    uncompressedSize += decoded;
                    return decoded;
                }
                if (result.status() == CodecResult.Status.ACTIVE && result.inputBytes() == 0L) {
                    throw new IOException(formatDisplayName + " ZIP decoder made no progress");
                }
            }
        }

        /// Closes this compressed entry stream after draining unread bytes.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;

            Throwable failure = null;
            try {
                byte[] discard = new byte[8192];
                while (readUnchecked(discard, 0, discard.length) >= 0) {
                    // Drain compressed data so the following descriptor can be parsed.
                }
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            try {
                finishEntry();
            } catch (IOException | RuntimeException | Error exception) {
                failure = mergeFailure(failure, exception);
            }
            throwFailure(failure);
        }


        /// Pushes bytes read past the compressed frame back into the raw ZIP stream.
        private void unreadSourceRemainder() throws IOException {
            ByteBuffer remainder = decoder.unconsumedInput();
            if (!remainder.hasRemaining()) {
                return;
            }
            if (!pushbackSourceRemainder) {
                throw new IOException(formatDisplayName + " ZIP decoder read past encrypted entry data");
            }
            if (remainder.remaining() > PUSHBACK_BUFFER_SIZE) {
                throw new IOException("ZIP pushback buffer is too small for " + formatDisplayName + " data descriptor");
            }
            for (int index = remainder.limit() - 1; index >= remainder.position(); index--) {
                input.unread(Byte.toUnsignedInt(remainder.get(index)));
            }
            remainder.position(remainder.limit());
        }

        /// Finishes the current entry and validates the following data descriptor.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;

            Throwable failure = null;
            try {
                if (!streamFinished) {
                    failure = new EOFException("Unexpected end of " + formatDisplayName + " ZIP entry before data descriptor");
                } else {
                    ZipAesCrypto.Decryptor decryptor = aesDecryptor;
                    if (decryptor != null) {
                        byte[] expectedAuthentication = readBytes(
                                input,
                                authenticationCodeSize,
                                "Unexpected end of WinZip AES authentication code"
                        );
                        if (!decryptor.verify(expectedAuthentication)) {
                            failure = new IOException("WinZip AES authentication failed");
                        }
                    }
                }
                if (streamFinished && !readAndMatchesDataDescriptor(
                        input,
                        zip64DataDescriptor,
                        crc32.getValue(),
                        compressedSizeOffset + decoder.inputBytes(),
                        uncompressedSize
                )) {
                    failure = mergeFailure(failure, new IOException(descriptorMismatchMessage));
                }
            } catch (IOException | RuntimeException | Error exception) {
                failure = mergeFailure(failure, exception);
            }
            try {
                decoder.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = mergeFailure(failure, exception);
            }
            throwFailure(failure);
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP entry input stream is closed");
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

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a stored entry input stream.
        private StoredDataDescriptorInputStream(PushbackInputStream input, boolean zip64DataDescriptor) {
            this.input = Objects.requireNonNull(input, "input");
            this.zip64DataDescriptor = zip64DataDescriptor;
        }

        /// Reads one stored entry byte.
        @Override
        public int read() throws IOException {
            ensureOpen();
            return readUnchecked();
        }

        /// Reads one stored entry byte without checking the wrapper open state for close-time draining.
        private int readUnchecked() throws IOException {
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

            boolean descriptorMatches;
            try {
                descriptorMatches = readStoredDataDescriptorAfterSignature(
                        input,
                        zip64DataDescriptor,
                        crc32.getValue(),
                        size,
                        size,
                        "ZIP data descriptor does not match entry data"
                );
            } catch (IOException | RuntimeException | Error exception) {
                finishedEntry = true;
                throw exception;
            }
            if (!descriptorMatches) {
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
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads stored entry bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int first = readUnchecked();
            if (first < 0) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                int value = readUnchecked();
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
            if (closed) {
                return;
            }
            closed = true;

            byte[] discard = new byte[8192];
            while (readUnchecked(discard, 0, discard.length) >= 0) {
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

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP entry input stream is closed");
            }
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

        /// Whether this stream has been closed.
        private boolean closed;

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
            ensureOpen();
            return readUnchecked();
        }

        /// Reads one decrypted stored entry byte without checking the wrapper open state for close-time draining.
        private int readUnchecked() throws IOException {
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
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads decrypted stored entry bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int first = readUnchecked();
            if (first < 0) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                int value = readUnchecked();
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
            if (closed) {
                return;
            }
            closed = true;

            byte[] discard = new byte[8192];
            while (readUnchecked(discard, 0, discard.length) >= 0) {
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
            Throwable failure = null;
            if (!decryptor.verify(expectedAuthentication)) {
                failure = new IOException("WinZip AES authentication failed");
            }
            long compressedSize = overheadSize + encryptedContentSize;
            try {
                if (!readStoredDataDescriptorAfterSignature(
                        input,
                        zip64DataDescriptor,
                        crc32.getValue(),
                        compressedSize,
                        uncompressedSize,
                        "WinZip AES data descriptor does not match entry data"
                )) {
                    IOException exception = new IOException("WinZip AES data descriptor does not match entry data");
                    failure = mergeFailure(failure, exception);
                }
            } catch (IOException | RuntimeException | Error exception) {
                failure = mergeFailure(failure, exception);
            }
            if (failure != null) {
                throwFailure(failure);
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

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP entry input stream is closed");
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

        /// Whether this stream has been closed.
        private boolean closed;

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
            ensureOpen();
            return readUnchecked();
        }

        /// Reads one decrypted stored entry byte without checking the wrapper open state for close-time draining.
        private int readUnchecked() throws IOException {
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
            boolean descriptorMatches;
            try {
                descriptorMatches = readStoredDataDescriptorAfterSignature(
                        input,
                        zip64DataDescriptor,
                        crc32.getValue(),
                        compressedSize,
                        uncompressedSize,
                        "ZIP data descriptor does not match entry data"
                );
            } catch (IOException | RuntimeException | Error exception) {
                finishedEntry = true;
                throw exception;
            }
            if (!descriptorMatches) {
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
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads decrypted stored entry bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int first = readUnchecked();
            if (first < 0) {
                return -1;
            }
            bytes[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                int value = readUnchecked();
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
            if (closed) {
                return;
            }
            closed = true;

            byte[] discard = new byte[8192];
            while (readUnchecked(discard, 0, discard.length) >= 0) {
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

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP entry input stream is closed");
            }
        }
    }

    /// Decrypts WinZip AES bytes without closing the wrapped stream.
    @NotNullByDefault
    private static final class AesDecryptingInputStream extends InputStream {
        /// The wrapped encrypted input stream.
        private final InputStream input;

        /// The WinZip AES decryptor.
        private final ZipAesCrypto.Decryptor decryptor;

        /// Creates a WinZip AES decrypting input stream wrapper.
        private AesDecryptingInputStream(InputStream input, ZipAesCrypto.Decryptor decryptor) {
            this.input = Objects.requireNonNull(input, "input");
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
        }

        /// Reads and decrypts one byte from the wrapped stream.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, buffer.length);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads and decrypts bytes from the wrapped stream.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }

            int read = input.read(bytes, offset, length);
            if (read < 0) {
                return -1;
            }
            decryptor.decrypt(bytes, offset, read);
            return read;
        }

        /// Does not close the wrapped stream.
        @Override
        public void close() {
        }
    }

    /// Decrypts traditional ZIP bytes without closing the wrapped stream.
    @NotNullByDefault
    private static final class TraditionalDecryptingInputStream extends InputStream {
        /// The wrapped encrypted input stream.
        private final InputStream input;

        /// The traditional ZIP decryptor.
        private final ZipTraditionalCrypto.Decryptor decryptor;

        /// Creates a traditional decrypting input stream wrapper.
        private TraditionalDecryptingInputStream(InputStream input, ZipTraditionalCrypto.Decryptor decryptor) {
            this.input = Objects.requireNonNull(input, "input");
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
        }

        /// Reads and decrypts one byte from the wrapped stream.
        @Override
        public int read() throws IOException {
            int encrypted = input.read();
            return encrypted < 0 ? -1 : decryptor.decrypt(encrypted);
        }

        /// Reads and decrypts bytes from the wrapped stream.
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

        /// Does not close the wrapped stream.
        @Override
        public void close() {
        }
    }

    /// Limits bulk reads to one byte so an encrypted decoder cannot consume following plaintext metadata.
    @NotNullByDefault
    private static final class SingleByteBulkInputStream extends InputStream {
        /// The wrapped decrypted input stream.
        private final InputStream input;

        /// Creates a single-byte bulk-read view.
        private SingleByteBulkInputStream(InputStream input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        /// Reads one byte from the wrapped stream.
        @Override
        public int read() throws IOException {
            return input.read();
        }

        /// Reads at most one byte from the wrapped stream.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : input.read(bytes, offset, 1);
        }

        /// Closes the wrapped stream.
        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    /// Forwards reads without closing the wrapped stream.
    @NotNullByDefault
    private static final class NonClosingInputStream extends InputStream {
        /// The wrapped input stream.
        private final InputStream input;

        /// Creates a non-closing input stream wrapper.
        private NonClosingInputStream(InputStream input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        /// Reads one byte from the wrapped stream.
        @Override
        public int read() throws IOException {
            return input.read();
        }

        /// Reads bytes from the wrapped stream.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return input.read(bytes, offset, length);
        }

        /// Does not close the wrapped stream.
        @Override
        public void close() {
        }
    }

    /// Reads a bounded number of bytes from an input stream without closing it.
    @NotNullByDefault
    private static final class BoundedInputStream extends InputStream {
        /// The source input stream.
        private final InputStream input;

        /// The remaining bytes.
        private long remaining;

        /// Whether this stream has been closed.
        private boolean closed;

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
            ensureOpen();
            return readUnchecked(bytes, offset, length);
        }

        /// Reads bytes without checking the wrapper open state for close-time draining.
        private int readUnchecked(byte[] bytes, int offset, int length) throws IOException {
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
            if (closed) {
                return;
            }
            closed = true;

            byte[] discard = new byte[8192];
            while (readUnchecked(discard, 0, discard.length) >= 0) {
                // Drain remaining bounded data.
            }
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP entry input stream is closed");
            }
        }
    }

    /// Stores parameters decoded from a ZIP LZMA property header.
    ///
    /// @param property                 the packed LZMA model property byte
    /// @param dictionarySize           the unsigned dictionary size
    /// @param expectedUncompressedSize the exact decoded size, or `-1` for an EOS-terminated stream
    @NotNullByDefault
    private record LzmaDecoderParameters(
            int property,
            long dictionarySize,
            long expectedUncompressedSize
    ) {
    }

    /// Stores local ZIP64 size values.
    ///
    /// @param uncompressedSize the uncompressed entry size
    /// @param compressedSize   the compressed entry size
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

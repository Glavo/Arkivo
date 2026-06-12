// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.glavo.arkivo.rar.RarArkivoEntryAttributes;
import org.glavo.arkivo.rar.RarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.zip.CRC32;

/// Implements the public forward-only RAR streaming reader API.
@NotNullByDefault
public final class RarArkivoStreamingReaderImpl extends RarArkivoStreamingReader {
    /// The maximum self-extracting stub size searched before the RAR signature.
    private static final int MAX_SFX_SIZE = 1024 * 1024;

    /// The maximum RAR5 header data size accepted by current RAR implementations.
    private static final int MAX_HEADER_SIZE = 2 * 1024 * 1024;

    /// The RAR5 archive signature.
    private static final byte @Unmodifiable [] RAR5_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// The main archive header type.
    private static final int HEADER_TYPE_MAIN = 1;

    /// The file header type.
    private static final int HEADER_TYPE_FILE = 2;

    /// The service header type.
    private static final int HEADER_TYPE_SERVICE = 3;

    /// The encrypted archive header type.
    private static final int HEADER_TYPE_ARCHIVE_ENCRYPTION = 4;

    /// The end of archive header type.
    private static final int HEADER_TYPE_END = 5;

    /// Header flag indicating that an extra area is present.
    private static final long HEADER_FLAG_EXTRA_AREA = 0x0001L;

    /// Header flag indicating that a data area follows the header.
    private static final long HEADER_FLAG_DATA_AREA = 0x0002L;

    /// Header flag indicating that data continues from the previous volume.
    private static final long HEADER_FLAG_CONTINUE_PREVIOUS = 0x0008L;

    /// Header flag indicating that data continues in the next volume.
    private static final long HEADER_FLAG_CONTINUE_NEXT = 0x0010L;

    /// File flag indicating that the entry is a directory.
    private static final long FILE_FLAG_DIRECTORY = 0x0001L;

    /// File flag indicating that the Unix modification time field is present.
    private static final long FILE_FLAG_UNIX_TIME = 0x0002L;

    /// File flag indicating that the data CRC32 field is present.
    private static final long FILE_FLAG_DATA_CRC = 0x0004L;

    /// File flag indicating that the unpacked size field should be ignored.
    private static final long FILE_FLAG_UNKNOWN_UNPACKED_SIZE = 0x0008L;

    /// The mask for RAR5 compression method bits.
    private static final long COMPRESSION_METHOD_MASK = 0x0380L;

    /// The number of low bits before the compression method field.
    private static final int COMPRESSION_METHOD_SHIFT = 7;

    /// The file encryption extra record type.
    private static final int EXTRA_TYPE_FILE_ENCRYPTION = 0x01;

    /// The file hash extra record type.
    private static final int EXTRA_TYPE_FILE_HASH = 0x02;

    /// The file time extra record type.
    private static final int EXTRA_TYPE_FILE_TIME = 0x03;

    /// The file system redirection extra record type.
    private static final int EXTRA_TYPE_REDIRECTION = 0x05;

    /// The Unix owner extra record type.
    private static final int EXTRA_TYPE_UNIX_OWNER = 0x06;

    /// Unix owner flag indicating that the user name is present.
    private static final long UNIX_OWNER_USER_NAME = 0x0001L;

    /// Unix owner flag indicating that the group name is present.
    private static final long UNIX_OWNER_GROUP_NAME = 0x0002L;

    /// Unix owner flag indicating that the numeric user identifier is present.
    private static final long UNIX_OWNER_USER_ID = 0x0004L;

    /// Unix owner flag indicating that the numeric group identifier is present.
    private static final long UNIX_OWNER_GROUP_ID = 0x0008L;

    /// File time flag indicating that time values use Unix time instead of Windows FILETIME.
    private static final long FILE_TIME_UNIX = 0x0001L;

    /// File time flag indicating that the modification time is present.
    private static final long FILE_TIME_MODIFIED = 0x0002L;

    /// File time flag indicating that the creation time is present.
    private static final long FILE_TIME_CREATED = 0x0004L;

    /// File time flag indicating that the last access time is present.
    private static final long FILE_TIME_ACCESSED = 0x0008L;

    /// File time flag indicating that Unix time values include nanosecond precision fields.
    private static final long FILE_TIME_UNIX_NANOS = 0x0010L;

    /// The number of 100-nanosecond Windows FILETIME ticks per second.
    private static final long WINDOWS_FILETIME_TICKS_PER_SECOND = 10_000_000L;

    /// The Unix epoch offset in Windows FILETIME seconds.
    private static final long WINDOWS_FILETIME_UNIX_EPOCH_SECONDS = 11_644_473_600L;

    /// The RAR5 BLAKE2sp hash byte length.
    private static final int BLAKE2SP_HASH_SIZE = 32;

    /// The RAR5 hash type for BLAKE2sp.
    private static final long HASH_TYPE_BLAKE2SP = 0L;

    /// The fallback timestamp used when a RAR entry omits all time metadata.
    private static final FileTime MISSING_TIME = FileTime.fromMillis(0L);

    /// The backing archive input stream.
    private final InputStream source;

    /// The CRC32 calculator reused for header validation.
    private final CRC32 headerCrc32 = new CRC32();

    /// The CRC32 calculator reused for stored file data validation.
    private final CRC32 dataCrc32 = new CRC32();

    /// The current entry attributes, or `null` when no entry is active.
    private @Nullable RarEntryAttributes currentAttributes;

    /// The current physical file-part attributes, or `null` when no entry part is active.
    private @Nullable RarEntryAttributes currentPartAttributes;

    /// The remaining packed bytes in the current entry data area.
    private long currentPackedRemaining;

    /// Whether the current entry body has already been opened.
    private boolean currentBodyOpened;

    /// Whether the current stored body is being checked against a CRC32 value.
    private boolean currentCrcActive;

    /// Whether the current stored body CRC32 has already been verified.
    private boolean currentCrcVerified;

    /// The expected unsigned CRC32 value for the current stored body.
    private long currentExpectedCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

    /// Whether the RAR signature has been consumed.
    private boolean signatureRead;

    /// Whether the end of archive marker has been consumed.
    private boolean finished;

    /// Whether this reader is open for archive operations.
    private boolean open = true;

    /// Whether the backing archive input stream has been closed.
    private boolean sourceClosed;

    /// Creates a streaming RAR reader.
    public RarArkivoStreamingReaderImpl(InputStream source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Advances to the next RAR file entry and returns whether an entry is available.
    @Override
    public boolean next() throws IOException {
        ensureOpen();
        readSignature();
        if (finished) {
            currentAttributes = null;
            currentPartAttributes = null;
            return false;
        }

        skipCurrentEntryBody();
        currentAttributes = null;
        currentPartAttributes = null;

        while (true) {
            @Nullable BlockHeader block = readBlockHeader();
            if (block == null) {
                finished = true;
                return false;
            }

            switch (block.type()) {
                case HEADER_TYPE_MAIN -> {
                    skipBlockData(block);
                }
                case HEADER_TYPE_FILE -> {
                    currentAttributes = parseFileHeader(block);
                    currentPartAttributes = currentAttributes;
                    currentPackedRemaining = block.dataSize();
                    currentBodyOpened = false;
                    prepareCurrentCrc();
                    return true;
                }
                case HEADER_TYPE_SERVICE -> skipBlockData(block);
                case HEADER_TYPE_ARCHIVE_ENCRYPTION ->
                        throw new IOException("Encrypted RAR headers are not supported");
                case HEADER_TYPE_END -> {
                    skipBlockData(block);
                    finished = true;
                    return false;
                }
                default -> skipBlockData(block);
            }
        }
    }

    /// Reads the current archive entry attributes as the requested attribute type.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        RarEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IllegalStateException("RAR streaming reader is not positioned at an entry");
        }
        if (type == BasicFileAttributes.class || type == RarArkivoEntryAttributes.class) {
            return type.cast(attributes);
        }
        throw new UnsupportedOperationException("Unsupported RAR streaming attributes type: " + type.getName());
    }

    /// Opens a readable channel for the current stored file entry.
    @Override
    public ReadableByteChannel openChannel() throws IOException {
        ensureOpen();
        RarEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IOException("RAR streaming reader has not advanced to an entry");
        }
        if (currentBodyOpened) {
            throw new IOException("RAR entry body has already been opened");
        }
        if (attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            currentBodyOpened = true;
            return Channels.newChannel(InputStream.nullInputStream());
        }
        if (attributes.isEncrypted()) {
            throw new IOException("Encrypted RAR entries are not supported");
        }
        if (attributes.continuesFromPreviousVolume()) {
            throw new IOException("RAR entry starts in a previous volume");
        }
        if (attributes.compressionMethod() != 0) {
            throw new IOException("Unsupported RAR compression method: " + attributes.compressionMethod());
        }
        currentBodyOpened = true;
        return Channels.newChannel(new EntryInputStream());
    }

    /// Closes this streaming reader.
    @Override
    public void close() throws IOException {
        if (!open && sourceClosed) {
            return;
        }
        open = false;
        currentAttributes = null;
        currentPartAttributes = null;
        currentPackedRemaining = 0L;
        source.close();
        sourceClosed = true;
    }

    /// Reads and validates the RAR signature.
    private void readSignature() throws IOException {
        if (signatureRead) {
            return;
        }

        byte[] window = new byte[RAR5_SIGNATURE.length];
        int count = 0;
        for (int readBytes = 0; readBytes < MAX_SFX_SIZE + RAR5_SIGNATURE.length; readBytes++) {
            int value = source.read();
            if (value < 0) {
                throw new EOFException("RAR signature is missing");
            }
            window[count % window.length] = (byte) value;
            count++;
            if (endsWith(window, count, RAR5_SIGNATURE)) {
                signatureRead = true;
                return;
            }
            if (endsWith(window, count, RAR4_SIGNATURE)) {
                throw new IOException("RAR4 archives are not supported yet");
            }
        }
        throw new IOException("RAR signature is missing");
    }

    /// Returns whether the rolling window ends with the expected byte sequence.
    private static boolean endsWith(byte[] window, int count, byte[] expected) {
        if (count < expected.length) {
            return false;
        }
        int start = count - expected.length;
        for (int index = 0; index < expected.length; index++) {
            if (window[(start + index) % window.length] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    /// Reads the next RAR block header, or `null` at archive EOF.
    private @Nullable BlockHeader readBlockHeader() throws IOException {
        byte[] crcBytes = new byte[Integer.BYTES];
        int first = source.read();
        if (first < 0) {
            return null;
        }
        crcBytes[0] = (byte) first;
        readFully(crcBytes, 1, crcBytes.length - 1, "Unexpected end of RAR header CRC32");
        long expectedCrc = littleEndianUInt32(crcBytes, 0);

        VintBytes headerSize = readSourceVint("RAR header size", 3);
        if (headerSize.value() > MAX_HEADER_SIZE) {
            throw new IOException("RAR header is too large");
        }
        byte[] headerData = new byte[(int) headerSize.value()];
        readFully(headerData, 0, headerData.length, "Unexpected end of RAR header");

        headerCrc32.reset();
        headerCrc32.update(headerSize.bytes(), 0, headerSize.bytes().length);
        headerCrc32.update(headerData, 0, headerData.length);
        if (headerCrc32.getValue() != expectedCrc) {
            throw new IOException("Invalid RAR header CRC32");
        }

        HeaderReader reader = new HeaderReader(headerData, 0, headerData.length);
        int type = readIntVint(reader, "RAR header type");
        long flags = reader.readVint("RAR header flags");
        long extraSize = 0L;
        long dataSize = 0L;
        if ((flags & HEADER_FLAG_EXTRA_AREA) != 0) {
            extraSize = reader.readVint("RAR extra area size");
        }
        if ((flags & HEADER_FLAG_DATA_AREA) != 0) {
            dataSize = reader.readVint("RAR data area size");
        }
        if (extraSize > headerData.length - reader.position()) {
            throw new IOException("RAR extra area exceeds header size");
        }
        int extraOffset = headerData.length - (int) extraSize;
        if (reader.position() > extraOffset) {
            throw new IOException("RAR header fields exceed header size");
        }
        return new BlockHeader(type, flags, dataSize, headerData, reader.position(), extraOffset);
    }

    /// Parses a RAR5 file header block.
    private static RarEntryAttributes parseFileHeader(BlockHeader block) throws IOException {
        HeaderReader reader = new HeaderReader(block.headerData(), block.fieldsOffset(), block.extraOffset());
        long fileFlags = reader.readVint("RAR file flags");
        long rawUnpackedSize = reader.readVint("RAR unpacked size");
        long fileAttributes = reader.readVint("RAR file attributes");
        FileTime lastModifiedTime = MISSING_TIME;
        if ((fileFlags & FILE_FLAG_UNIX_TIME) != 0) {
            lastModifiedTime = fileTimeFromEpochSecond(reader.readUInt32("RAR modification time"));
        }
        long dataCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
        if ((fileFlags & FILE_FLAG_DATA_CRC) != 0) {
            dataCrc32 = reader.readUInt32("RAR data CRC32");
        }
        long compressionInformation = reader.readVint("RAR compression information");
        int compressionMethod = (int) ((compressionInformation & COMPRESSION_METHOD_MASK) >>> COMPRESSION_METHOD_SHIFT);
        int hostOs = readIntVint(reader, "RAR host OS");
        int nameLength = readIntVint(reader, "RAR name length");
        String path = validatePath(reader.readString(nameLength, "RAR entry name"));
        ExtraMetadata extraMetadata = parseExtraArea(block.headerData(), block.extraOffset(), block.headerData().length);
        if (extraMetadata.lastModifiedTime != null) {
            lastModifiedTime = extraMetadata.lastModifiedTime;
        }
        FileTime creationTime = extraMetadata.creationTime != null ? extraMetadata.creationTime : lastModifiedTime;
        FileTime lastAccessTime = extraMetadata.lastAccessTime != null ? extraMetadata.lastAccessTime : lastModifiedTime;

        boolean directory = (fileFlags & FILE_FLAG_DIRECTORY) != 0;
        boolean symbolicLink = extraMetadata.redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK
                || extraMetadata.redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_SYMLINK;
        boolean other = extraMetadata.redirectionType != RarArkivoEntryAttributes.NO_REDIRECTION_TYPE && !symbolicLink;
        long unpackedSize = (fileFlags & FILE_FLAG_UNKNOWN_UNPACKED_SIZE) != 0
                ? RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                : rawUnpackedSize;
        boolean splitFilePart = (block.flags() & (HEADER_FLAG_CONTINUE_PREVIOUS | HEADER_FLAG_CONTINUE_NEXT)) != 0;
        if (!directory
                && !symbolicLink
                && compressionMethod == 0
                && !splitFilePart
                && unpackedSize != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                && block.dataSize() != unpackedSize) {
            throw new IOException("RAR stored file packed and unpacked sizes differ");
        }

        return new RarEntryAttributes(
                path,
                directory,
                symbolicLink,
                other,
                extraMetadata.linkName,
                extraMetadata.redirectionType,
                extraMetadata.redirectionFlags,
                extraMetadata.redirectionTarget,
                extraMetadata.userName,
                extraMetadata.groupName,
                extraMetadata.userId,
                extraMetadata.groupId,
                hostOs,
                fileAttributes,
                compressionMethod,
                block.dataSize(),
                unpackedSize,
                dataCrc32,
                extraMetadata.blake2spHash,
                extraMetadata.encrypted,
                (block.flags() & HEADER_FLAG_CONTINUE_PREVIOUS) != 0,
                (block.flags() & HEADER_FLAG_CONTINUE_NEXT) != 0,
                lastModifiedTime,
                creationTime,
                lastAccessTime
        );
    }

    /// Parses RAR file extra area records used by this reader.
    private static ExtraMetadata parseExtraArea(byte[] headerData, int offset, int limit) throws IOException {
        ExtraMetadata metadata = new ExtraMetadata();
        HeaderReader reader = new HeaderReader(headerData, offset, limit);
        while (reader.hasRemaining()) {
            long recordSize = reader.readVint("RAR extra record size");
            if (recordSize <= 0 || recordSize > reader.remaining()) {
                throw new IOException("Invalid RAR extra record boundary");
            }
            int recordEnd = checkedAdd(reader.position(), recordSize, "RAR extra record size");
            int type = readIntVint(reader, "RAR extra record type");
            switch (type) {
                case EXTRA_TYPE_FILE_ENCRYPTION -> metadata.encrypted = true;
                case EXTRA_TYPE_FILE_HASH -> parseFileHashRecord(reader, recordEnd, metadata);
                case EXTRA_TYPE_FILE_TIME -> parseFileTimeRecord(reader, recordEnd, metadata);
                case EXTRA_TYPE_REDIRECTION -> parseRedirectionRecord(reader, recordEnd, metadata);
                case EXTRA_TYPE_UNIX_OWNER -> parseUnixOwnerRecord(reader, recordEnd, metadata);
                default -> {
                }
            }
            reader.position(recordEnd);
        }
        return metadata;
    }

    /// Parses a RAR file hash extra record.
    private static void parseFileHashRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        long hashType = reader.readVint("RAR file hash type");
        if (hashType != HASH_TYPE_BLAKE2SP) {
            return;
        }
        if (recordEnd - reader.position() != BLAKE2SP_HASH_SIZE) {
            throw new IOException("RAR BLAKE2sp hash has invalid size");
        }
        metadata.blake2spHash = reader.readBytes(BLAKE2SP_HASH_SIZE, "RAR BLAKE2sp hash");
    }

    /// Parses a RAR file time extra record.
    private static void parseFileTimeRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        long flags = reader.readVint("RAR file time flags");
        boolean unixTime = (flags & FILE_TIME_UNIX) != 0;
        boolean unixNanos = unixTime && (flags & FILE_TIME_UNIX_NANOS) != 0;
        if ((flags & FILE_TIME_MODIFIED) != 0) {
            metadata.lastModifiedTime = readFileTimeValue(reader, recordEnd, unixTime, unixNanos, "modification time");
        }
        if ((flags & FILE_TIME_CREATED) != 0) {
            metadata.creationTime = readFileTimeValue(reader, recordEnd, unixTime, unixNanos, "creation time");
        }
        if ((flags & FILE_TIME_ACCESSED) != 0) {
            metadata.lastAccessTime = readFileTimeValue(reader, recordEnd, unixTime, unixNanos, "last access time");
        }
    }

    /// Reads one RAR file time value.
    private static FileTime readFileTimeValue(
            HeaderReader reader,
            int recordEnd,
            boolean unixTime,
            boolean unixNanos,
            String description
    ) throws IOException {
        if (unixTime) {
            long seconds = reader.readUInt32("RAR " + description);
            long nanos = 0L;
            if (unixNanos) {
                if (Integer.BYTES > recordEnd - reader.position()) {
                    throw new EOFException("Unexpected end of RAR " + description + " nanoseconds");
                }
                nanos = reader.readUInt32("RAR " + description + " nanoseconds");
                if (nanos > 999_999_999L) {
                    throw new IOException("RAR " + description + " nanoseconds are out of range");
                }
            }
            return fileTimeFromEpochSecond(seconds, nanos, description);
        }
        long windowsFileTime = reader.readUInt64("RAR " + description);
        return fileTimeFromWindowsFileTime(windowsFileTime, description);
    }

    /// Parses a RAR file system redirection extra record.
    private static void parseRedirectionRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        int redirectionType = readIntVint(reader, "RAR redirection type");
        long redirectionFlags = reader.readVint("RAR redirection flags");
        int nameLength = readIntVint(reader, "RAR redirection name length");
        if (nameLength > recordEnd - reader.position()) {
            throw new IOException("RAR redirection name exceeds record size");
        }
        String target = reader.readString(nameLength, "RAR redirection name");
        metadata.redirectionType = redirectionType;
        metadata.redirectionFlags = redirectionFlags;
        metadata.redirectionTarget = target;
        if (redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK
                || redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_SYMLINK) {
            metadata.linkName = target;
        }
    }

    /// Parses a RAR Unix owner extra record.
    private static void parseUnixOwnerRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        long flags = reader.readVint("RAR Unix owner flags");
        if ((flags & UNIX_OWNER_USER_NAME) != 0) {
            metadata.userName = readRecordString(reader, recordEnd, "RAR Unix owner user name");
        }
        if ((flags & UNIX_OWNER_GROUP_NAME) != 0) {
            metadata.groupName = readRecordString(reader, recordEnd, "RAR Unix owner group name");
        }
        if ((flags & UNIX_OWNER_USER_ID) != 0) {
            metadata.userId = reader.readVint("RAR Unix owner user ID");
        }
        if ((flags & UNIX_OWNER_GROUP_ID) != 0) {
            metadata.groupId = reader.readVint("RAR Unix owner group ID");
        }
    }

    /// Reads a length-prefixed string from one extra record.
    private static String readRecordString(HeaderReader reader, int recordEnd, String description) throws IOException {
        int length = readIntVint(reader, description + " length");
        if (length > recordEnd - reader.position()) {
            throw new IOException(description + " exceeds record size");
        }
        return reader.readString(length, description);
    }

    /// Validates a decoded RAR entry path.
    private static String validatePath(String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\")
                || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException("RAR entry path must be relative");
        }
        boolean hasName = false;
        int start = 0;
        while (start <= path.length()) {
            int end = nextPathSeparator(path, start);
            String name = path.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IOException("RAR entry path must not contain ..");
                }
                hasName = true;
            }
            start = end + 1;
        }
        if (!hasName) {
            throw new IOException("RAR entry is missing a path");
        }
        return path;
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

    /// Converts epoch seconds to a file time.
    private static FileTime fileTimeFromEpochSecond(long seconds) throws IOException {
        return fileTimeFromEpochSecond(seconds, 0L, "modification time");
    }

    /// Converts epoch seconds and nanoseconds to a file time.
    private static FileTime fileTimeFromEpochSecond(long seconds, long nanos, String description) throws IOException {
        try {
            return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
        } catch (DateTimeException exception) {
            throw new IOException("RAR " + description + " is out of range", exception);
        }
    }

    /// Converts a Windows FILETIME value to a file time.
    private static FileTime fileTimeFromWindowsFileTime(long windowsFileTime, String description) throws IOException {
        long seconds = Math.floorDiv(windowsFileTime, WINDOWS_FILETIME_TICKS_PER_SECOND)
                - WINDOWS_FILETIME_UNIX_EPOCH_SECONDS;
        long nanos = Math.floorMod(windowsFileTime, WINDOWS_FILETIME_TICKS_PER_SECOND) * 100L;
        return fileTimeFromEpochSecond(seconds, nanos, description);
    }

    /// Prepares CRC32 validation state for the current entry.
    private void prepareCurrentCrc() {
        RarEntryAttributes attributes = Objects.requireNonNull(currentAttributes, "currentAttributes");
        currentCrcActive = attributes.compressionMethod() == 0
                && !attributes.isDirectory()
                && !attributes.isSymbolicLink()
                && attributes.dataCrc32() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
        currentCrcVerified = false;
        currentExpectedCrc32 = attributes.dataCrc32();
        if (currentCrcActive) {
            dataCrc32.reset();
        }
    }

    /// Skips any unread bytes from the current entry body.
    private void skipCurrentEntryBody() throws IOException {
        boolean requireReadableContinuation = currentBodyOpened
                && currentAttributes != null
                && !currentAttributes.isEncrypted()
                && currentAttributes.compressionMethod() == 0
                && !currentAttributes.continuesFromPreviousVolume();
        while (true) {
            if (currentPackedRemaining > 0) {
                if (currentCrcActive) {
                    skipStoredDataWithCrc(currentPackedRemaining);
                } else {
                    skipFully(currentPackedRemaining);
                    currentPackedRemaining = 0L;
                }
            }
            if (!moveToNextContinuationPartIfPresent(requireReadableContinuation)) {
                break;
            }
        }
        verifyCurrentCrcIfComplete();
        currentBodyOpened = false;
        currentCrcActive = false;
        currentCrcVerified = false;
        currentExpectedCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
    }

    /// Advances to the next continuation part when the current physical part is exhausted.
    private boolean moveToNextContinuationPartIfPresent(boolean requireReadableData) throws IOException {
        RarEntryAttributes previousPartAttributes = currentPartAttributes;
        if (previousPartAttributes == null || !previousPartAttributes.continuesInNextVolume()) {
            return false;
        }

        @Nullable BlockHeader block;
        while (true) {
            block = readBlockHeader();
            if (block == null) {
                throw new EOFException("Unexpected end of RAR multi-volume entry");
            }
            if (block.type() == HEADER_TYPE_MAIN || block.type() == HEADER_TYPE_SERVICE || block.type() == HEADER_TYPE_END) {
                skipBlockData(block);
                continue;
            }
            if (block.type() == HEADER_TYPE_ARCHIVE_ENCRYPTION) {
                throw new IOException("Encrypted RAR headers are not supported");
            }
            if (block.type() != HEADER_TYPE_FILE) {
                throw new IOException("RAR multi-volume entry continuation is missing");
            }
            break;
        }
        RarEntryAttributes nextPartAttributes = parseFileHeader(block);
        validateContinuationPart(previousPartAttributes, nextPartAttributes, requireReadableData);
        currentPartAttributes = nextPartAttributes;
        currentPackedRemaining = block.dataSize();
        return true;
    }

    /// Validates that a physical file part belongs to the current logical entry.
    private void validateContinuationPart(
            RarEntryAttributes previousPartAttributes,
            RarEntryAttributes nextPartAttributes,
            boolean requireReadableData
    ) throws IOException {
        RarEntryAttributes entryAttributes = Objects.requireNonNull(currentAttributes, "currentAttributes");
        if (!nextPartAttributes.continuesFromPreviousVolume()) {
            throw new IOException("RAR multi-volume entry continuation is missing the previous-volume flag");
        }
        if (!entryAttributes.path().equals(nextPartAttributes.path())) {
            throw new IOException("RAR multi-volume entry continuation path differs");
        }
        if (previousPartAttributes.isRegularFile() != nextPartAttributes.isRegularFile()
                || previousPartAttributes.isDirectory() != nextPartAttributes.isDirectory()
                || previousPartAttributes.isSymbolicLink() != nextPartAttributes.isSymbolicLink()
                || previousPartAttributes.isOther() != nextPartAttributes.isOther()) {
            throw new IOException("RAR multi-volume entry continuation type differs");
        }
        if (entryAttributes.unpackedSize() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                && nextPartAttributes.unpackedSize() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                && entryAttributes.unpackedSize() != nextPartAttributes.unpackedSize()) {
            throw new IOException("RAR multi-volume entry continuation size differs");
        }
        if (requireReadableData) {
            if (nextPartAttributes.isEncrypted()) {
                throw new IOException("Encrypted RAR entries are not supported");
            }
            if (!nextPartAttributes.isRegularFile()) {
                throw new IOException("RAR multi-volume entry continuation is not a regular file");
            }
            if (nextPartAttributes.compressionMethod() != 0) {
                throw new IOException("Unsupported RAR compression method: " + nextPartAttributes.compressionMethod());
            }
        }
    }

    /// Ensures that the current logical entry has physical data available to read.
    private boolean ensureCurrentEntryDataAvailable() throws IOException {
        while (currentPackedRemaining == 0L) {
            if (!moveToNextContinuationPartIfPresent(true)) {
                verifyCurrentCrcIfComplete();
                return false;
            }
        }
        return true;
    }

    /// Skips all data attached to a non-file block.
    private void skipBlockData(BlockHeader block) throws IOException {
        skipFully(block.dataSize());
    }

    /// Skips stored file bytes while updating the current CRC32.
    private void skipStoredDataWithCrc(long count) throws IOException {
        byte[] discard = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int read = source.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of RAR entry data");
            }
            dataCrc32.update(discard, 0, read);
            remaining -= read;
            currentPackedRemaining -= read;
        }
    }

    /// Skips exactly the requested byte count from the archive source.
    private void skipFully(long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = source.skip(remaining);
            if (skipped == 0) {
                if (source.read() < 0) {
                    throw new EOFException("Unexpected end of RAR data area");
                }
                skipped = 1L;
            }
            remaining -= skipped;
        }
    }

    /// Verifies the current stored body CRC32 when all packed bytes have been consumed.
    private void verifyCurrentCrcIfComplete() throws IOException {
        RarEntryAttributes partAttributes = currentPartAttributes;
        if (currentCrcActive
                && !currentCrcVerified
                && currentPackedRemaining == 0L
                && (partAttributes == null || !partAttributes.continuesInNextVolume())) {
            long actual = dataCrc32.getValue();
            if (actual != currentExpectedCrc32) {
                throw new IOException("Invalid RAR entry CRC32");
            }
            currentCrcVerified = true;
        }
    }

    /// Reads a RAR variable length integer from the archive source.
    private VintBytes readSourceVint(String description, int maxBytes) throws IOException {
        byte[] bytes = new byte[maxBytes];
        long value = 0L;
        int shift = 0;
        for (int count = 0; count < maxBytes; count++) {
            int next = source.read();
            if (next < 0) {
                throw new EOFException("Unexpected end of " + description);
            }
            bytes[count] = (byte) next;
            if (count == 9 && (next & 0x7e) != 0) {
                throw new IOException(description + " is too large");
            }
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                byte[] exact = new byte[count + 1];
                System.arraycopy(bytes, 0, exact, 0, exact.length);
                return new VintBytes(value, exact);
            }
            shift += 7;
        }
        throw new IOException(description + " is too large");
    }

    /// Reads bytes fully from the archive source.
    private void readFully(byte[] buffer, int offset, int length, String eofMessage) throws IOException {
        int position = offset;
        int end = offset + length;
        while (position < end) {
            int read = source.read(buffer, position, end - position);
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            position += read;
        }
    }

    /// Reads an integer-sized variable length integer.
    private static int readIntVint(HeaderReader reader, String description) throws IOException {
        long value = reader.readVint(description);
        if (value > Integer.MAX_VALUE) {
            throw new IOException(description + " is too large");
        }
        return (int) value;
    }

    /// Adds an unsigned size to a position after checking the result.
    private static int checkedAdd(int position, long size, String description) throws IOException {
        if (size > Integer.MAX_VALUE - position) {
            throw new IOException(description + " is too large");
        }
        return position + (int) size;
    }

    /// Reads a little-endian unsigned 32-bit integer from a byte array.
    private static long littleEndianUInt32(byte[] data, int offset) {
        return (long) Byte.toUnsignedInt(data[offset])
                | (long) Byte.toUnsignedInt(data[offset + 1]) << 8
                | (long) Byte.toUnsignedInt(data[offset + 2]) << 16
                | (long) Byte.toUnsignedInt(data[offset + 3]) << 24;
    }

    /// Requires this reader to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("RAR streaming reader is closed");
        }
    }

    /// Stores one decoded RAR block header.
    ///
    /// @param type the RAR block type
    /// @param flags the RAR block flags
    /// @param dataSize the optional data area size
    /// @param headerData the CRC-covered header data bytes
    /// @param fieldsOffset the offset of block-type-specific fields
    /// @param extraOffset the offset of the optional extra area
    @NotNullByDefault
    private record BlockHeader(
            int type,
            long flags,
            long dataSize,
            byte @Unmodifiable [] headerData,
            int fieldsOffset,
            int extraOffset
    ) {
        /// Creates a decoded RAR block header.
        private BlockHeader {
            Objects.requireNonNull(headerData, "headerData");
            if (type < 0 || flags < 0 || dataSize < 0 || fieldsOffset < 0 || extraOffset < fieldsOffset
                    || extraOffset > headerData.length) {
                throw new IllegalArgumentException("Invalid RAR block header");
            }
        }
    }

    /// Stores a source-read variable length integer and its encoded bytes.
    ///
    /// @param value the decoded integer value
    /// @param bytes the exact encoded bytes consumed from the source
    @NotNullByDefault
    private record VintBytes(long value, byte @Unmodifiable [] bytes) {
        /// Creates a source-read variable length integer.
        private VintBytes {
            Objects.requireNonNull(bytes, "bytes");
            if (value < 0) {
                throw new IllegalArgumentException("value must not be negative");
            }
        }
    }

    /// Reads primitive values from a bounded RAR header byte range.
    @NotNullByDefault
    private static final class HeaderReader {
        /// The header bytes.
        private final byte @Unmodifiable [] data;

        /// The exclusive read limit.
        private final int limit;

        /// The current read position.
        private int position;

        /// Creates a header reader over the given byte range.
        private HeaderReader(byte @Unmodifiable [] data, int offset, int limit) {
            this.data = Objects.requireNonNull(data, "data");
            if (offset < 0 || limit < offset || limit > data.length) {
                throw new IllegalArgumentException("Invalid RAR header reader range");
            }
            this.position = offset;
            this.limit = limit;
        }

        /// Returns the current read position.
        private int position() {
            return position;
        }

        /// Moves the current read position.
        private void position(int position) {
            if (position < this.position || position > limit) {
                throw new IllegalArgumentException("Invalid RAR header reader position");
            }
            this.position = position;
        }

        /// Returns the remaining readable byte count.
        private int remaining() {
            return limit - position;
        }

        /// Returns whether any bytes remain.
        private boolean hasRemaining() {
            return position < limit;
        }

        /// Reads one RAR variable length integer.
        private long readVint(String description) throws IOException {
            long value = 0L;
            int shift = 0;
            for (int count = 0; count < 10; count++) {
                int next = readUnsignedByte(description);
                if (count == 9 && (next & 0x7e) != 0) {
                    throw new IOException(description + " is too large");
                }
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new IOException(description + " is too large");
        }

        /// Reads one little-endian unsigned 32-bit integer.
        private long readUInt32(String description) throws IOException {
            requireRemaining(Integer.BYTES, description);
            long value = littleEndianUInt32(data, position);
            position += Integer.BYTES;
            return value;
        }

        /// Reads one little-endian unsigned 64-bit integer that fits in a signed `long`.
        private long readUInt64(String description) throws IOException {
            requireRemaining(Long.BYTES, description);
            long value = (long) Byte.toUnsignedInt(data[position])
                    | (long) Byte.toUnsignedInt(data[position + 1]) << 8
                    | (long) Byte.toUnsignedInt(data[position + 2]) << 16
                    | (long) Byte.toUnsignedInt(data[position + 3]) << 24
                    | (long) Byte.toUnsignedInt(data[position + 4]) << 32
                    | (long) Byte.toUnsignedInt(data[position + 5]) << 40
                    | (long) Byte.toUnsignedInt(data[position + 6]) << 48
                    | (long) Byte.toUnsignedInt(data[position + 7]) << 56;
            position += Long.BYTES;
            if (value < 0) {
                throw new IOException(description + " is too large");
            }
            return value;
        }

        /// Reads one UTF-8 string of the requested byte length.
        private String readString(int length, String description) throws IOException {
            if (length < 0) {
                throw new IOException(description + " length must not be negative");
            }
            requireRemaining(length, description);
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }

        /// Reads raw bytes of the requested length.
        private byte @Unmodifiable [] readBytes(int length, String description) throws IOException {
            if (length < 0) {
                throw new IOException(description + " length must not be negative");
            }
            requireRemaining(length, description);
            byte[] value = new byte[length];
            System.arraycopy(data, position, value, 0, length);
            position += length;
            return value;
        }

        /// Reads one unsigned byte.
        private int readUnsignedByte(String description) throws IOException {
            requireRemaining(1, description);
            return Byte.toUnsignedInt(data[position++]);
        }

        /// Requires the requested number of bytes to remain.
        private void requireRemaining(int byteCount, String description) throws IOException {
            if (byteCount > remaining()) {
                throw new EOFException("Unexpected end of " + description);
            }
        }
    }

    /// Stores metadata decoded from file extra records.
    @NotNullByDefault
    private static final class ExtraMetadata {
        /// Whether the entry is encrypted.
        private boolean encrypted;

        /// The symbolic link target, or `null` when absent.
        private @Nullable String linkName;

        /// The RAR redirection type.
        private int redirectionType = RarArkivoEntryAttributes.NO_REDIRECTION_TYPE;

        /// The RAR redirection flags.
        private long redirectionFlags;

        /// The RAR redirection target, or `null` when absent.
        private @Nullable String redirectionTarget;

        /// The Unix owner user name, or `null` when absent.
        private @Nullable String userName;

        /// The Unix owner group name, or `null` when absent.
        private @Nullable String groupName;

        /// The numeric Unix owner user identifier.
        private long userId = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

        /// The numeric Unix owner group identifier.
        private long groupId = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

        /// The BLAKE2sp hash, or `null` when absent.
        private byte @Nullable @Unmodifiable [] blake2spHash;

        /// The high precision last modified time, or `null` when absent.
        private @Nullable FileTime lastModifiedTime;

        /// The high precision creation time, or `null` when absent.
        private @Nullable FileTime creationTime;

        /// The high precision last access time, or `null` when absent.
        private @Nullable FileTime lastAccessTime;

        /// Creates empty extra metadata.
        private ExtraMetadata() {
        }
    }

    /// Reads bytes from the current stored entry body.
    @NotNullByDefault
    private final class EntryInputStream extends InputStream {
        /// Reads one byte from the current entry body.
        @Override
        public int read() throws IOException {
            ensureOpen();
            if (!ensureCurrentEntryDataAvailable()) {
                return -1;
            }
            int value = source.read();
            if (value < 0) {
                throw new EOFException("Unexpected end of RAR entry data");
            }
            currentPackedRemaining--;
            if (currentCrcActive) {
                dataCrc32.update(value);
            }
            if (currentPackedRemaining == 0L) {
                verifyCurrentCrcIfComplete();
            }
            return value;
        }

        /// Reads bytes from the current entry body.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (!ensureCurrentEntryDataAvailable()) {
                return -1;
            }
            int read = source.read(buffer, offset, (int) Math.min(length, currentPackedRemaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of RAR entry data");
            }
            currentPackedRemaining -= read;
            if (currentCrcActive) {
                dataCrc32.update(buffer, offset, read);
            }
            if (currentPackedRemaining == 0L) {
                verifyCurrentCrcIfComplete();
            }
            return read;
        }

        /// Skips bytes from the current entry body.
        @Override
        public long skip(long count) throws IOException {
            ensureOpen();
            if (count <= 0 || !ensureCurrentEntryDataAvailable()) {
                return 0L;
            }
            long toSkip = Math.min(count, currentPackedRemaining);
            if (currentCrcActive) {
                skipStoredDataWithCrc(toSkip);
                if (currentPackedRemaining == 0L) {
                    verifyCurrentCrcIfComplete();
                }
                return toSkip;
            }
            long skipped = source.skip(toSkip);
            currentPackedRemaining -= skipped;
            return skipped;
        }

        /// Returns the approximate available current entry body bytes.
        @Override
        public int available() throws IOException {
            ensureOpen();
            return Math.min(source.available(), (int) Math.min(Integer.MAX_VALUE, currentPackedRemaining));
        }

        /// Closes this entry stream after draining the entry body.
        @Override
        public void close() throws IOException {
            skipCurrentEntryBody();
        }
    }
}

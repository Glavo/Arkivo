// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Implements the public forward-only TAR streaming reader API.
@NotNullByDefault
public final class TarArkivoStreamingReaderImpl extends TarArkivoStreamingReader {
    /// The TAR record size.
    private static final int RECORD_SIZE = 512;

    /// The byte offset of old GNU sparse descriptors in the main header.
    private static final int OLD_GNU_SPARSE_OFFSET = 386;

    /// The number of old GNU sparse descriptors in the main header.
    private static final int OLD_GNU_MAIN_SPARSE_COUNT = 4;

    /// The number of old GNU sparse descriptors in an extension header.
    private static final int OLD_GNU_EXTENSION_SPARSE_COUNT = 21;

    /// The byte width of one old GNU sparse descriptor.
    private static final int OLD_GNU_SPARSE_DESCRIPTOR_SIZE = 24;

    /// The byte offset of the main old GNU sparse extension flag.
    private static final int OLD_GNU_MAIN_EXTENSION_FLAG_OFFSET = 482;

    /// The byte offset of the old GNU sparse logical size.
    private static final int OLD_GNU_REAL_SIZE_OFFSET = 483;

    /// The byte offset of an old GNU sparse extension header continuation flag.
    private static final int OLD_GNU_EXTENSION_FLAG_OFFSET = 504;

    /// The backing archive input stream.
    private final InputStream source;

    /// The current entry attributes, or `null` when no entry is active.
    private @Nullable TarEntryAttributes currentAttributes;

    /// The unread body bytes for the current entry.
    private long currentBodyRemaining;

    /// The unread body padding bytes for the current entry.
    private long currentPaddingRemaining;

    /// Whether the current entry body has already been opened.
    private boolean currentBodyOpened;

    /// The sparse map for the current logical file body, or `null` for a contiguous body.
    private @Nullable SparseMap currentSparseMap;

    /// The active global PAX key-value records.
    private final HashMap<String, String> globalPaxHeaders = new HashMap<>();

    /// The pending per-entry PAX key-value records.
    private @Nullable HashMap<String, String> pendingPaxHeaders;

    /// The ordered pending per-entry PAX records, including repeated GNU sparse keys.
    private @Nullable ArrayList<PaxRecord> pendingPaxRecords;

    /// The pending GNU long path value.
    private @Nullable String pendingLongPath;

    /// The pending GNU long symbolic link target value.
    private @Nullable String pendingLongLink;

    /// Whether this reader has consumed the TAR end marker.
    private boolean finished;

    /// Whether this reader is open.
    private boolean open = true;

    /// Whether the backing archive input stream has been closed.
    private boolean sourceClosed;

    /// Creates a streaming TAR reader.
    public TarArkivoStreamingReaderImpl(InputStream source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Advances to the next TAR entry and returns whether an entry is available.
    @Override
    public boolean next() throws IOException {
        ensureOpen();
        if (finished) {
            currentAttributes = null;
            return false;
        }

        skipCurrentEntryBody();
        while (true) {
            byte[] header = source.readNBytes(RECORD_SIZE);
            if (header.length == 0) {
                finished = true;
                currentAttributes = null;
                clearPendingEntryMetadata();
                return false;
            }
            if (header.length != RECORD_SIZE) {
                throw new EOFException("Unexpected end of TAR header");
            }
            if (isZeroBlock(header)) {
                finished = true;
                currentAttributes = null;
                clearPendingEntryMetadata();
                return false;
            }

            TarEntryAttributes attributes = parseHeader(header);
            currentAttributes = null;
            currentBodyRemaining = attributes.bodySize();
            currentPaddingRemaining = paddingSize(currentBodyRemaining);
            currentBodyOpened = false;
            currentSparseMap = null;

            byte typeFlag = attributes.typeFlag();
            if (typeFlag == TarEntryAttributes.GNU_LONG_PATH_TYPE) {
                pendingLongPath = readCurrentEntryBodyString("GNU long path");
                skipCurrentEntryBody();
                continue;
            }
            if (typeFlag == TarEntryAttributes.GNU_LONG_LINK_TYPE) {
                pendingLongLink = readCurrentEntryBodyString("GNU long link");
                skipCurrentEntryBody();
                continue;
            }
            if (typeFlag == TarEntryAttributes.PAX_EXTENDED_HEADER_TYPE) {
                mergePendingPaxHeaders(parsePaxHeaders(readCurrentEntryBodyBytes("PAX extended header")));
                skipCurrentEntryBody();
                continue;
            }
            if (typeFlag == TarEntryAttributes.PAX_GLOBAL_EXTENDED_HEADER_TYPE) {
                mergeGlobalPaxHeaders(parsePaxHeaders(readCurrentEntryBodyBytes("PAX global header")));
                skipCurrentEntryBody();
                continue;
            }

            @Nullable OldGnuSparseSpec oldGnuSparseSpec = typeFlag == TarEntryAttributes.OLD_GNU_SPARSE_TYPE
                    ? readOldGnuSparseSpec(header)
                    : null;
            ResolvedEntry resolvedEntry = applyPendingEntryMetadata(attributes, oldGnuSparseSpec);
            TarEntryAttributes resolvedAttributes = resolvedEntry.attributes();
            currentBodyRemaining = resolvedEntry.storedBodySize();
            currentPaddingRemaining = paddingSize(currentBodyRemaining);
            currentBodyOpened = false;
            SparseSpec sparseSpec = resolvedEntry.sparseSpec();
            if (sparseSpec != null) {
                @Nullable @Unmodifiable List<SparseBlock> declaredBlocks = sparseSpec.blocks();
                currentSparseMap = declaredBlocks != null
                        ? validateSparseMap(sparseSpec.logicalSize(), declaredBlocks, currentBodyRemaining)
                        : readSparseMapFromBody(sparseSpec.logicalSize());
            }
            currentAttributes = resolvedAttributes;
            return true;
        }
    }

    /// Reads the current archive entry attributes as the requested attribute type.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        TarEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IllegalStateException("TAR streaming reader is not positioned at an entry");
        }
        if (type == BasicFileAttributes.class || type == TarArkivoEntryAttributes.class || type == PosixFileAttributes.class) {
            return type.cast(attributes);
        }
        throw new UnsupportedOperationException("Unsupported TAR streaming attributes type: " + type.getName());
    }

    /// Opens a readable channel for the current entry body.
    @Override
    public ReadableByteChannel openChannel() throws IOException {
        ensureOpen();
        TarEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IOException("TAR streaming reader has not advanced to an entry");
        }
        if (currentBodyOpened) {
            throw new IOException("TAR entry body has already been opened");
        }
        currentBodyOpened = true;
        if (attributes.bodySize() == 0L) {
            return StreamChannelAdapters.readableChannel(InputStream.nullInputStream());
        }
        SparseMap sparseMap = currentSparseMap;
        return StreamChannelAdapters.readableChannel(sparseMap != null
                ? new SparseEntryInputStream(sparseMap)
                : new EntryInputStream());
    }

    /// Closes this streaming reader.
    @Override
    public void close() throws IOException {
        if (!open && sourceClosed) {
            return;
        }
        open = false;
        currentAttributes = null;
        currentSparseMap = null;
        clearPendingEntryMetadata();
        globalPaxHeaders.clear();
        source.close();
        sourceClosed = true;
    }

    /// Parses one TAR header block.
    private static TarEntryAttributes parseHeader(byte[] header) throws IOException {
        validateChecksum(header);
        byte typeFlag = header[156];
        String name = readString(header, 0, 100);
        String prefix = typeFlag == TarEntryAttributes.OLD_GNU_SPARSE_TYPE
                ? ""
                : readString(header, 345, 155);
        String path = prefix.isEmpty() ? name : prefix + "/" + name;
        if (path.isEmpty()) {
            throw new IOException("TAR entry is missing a path");
        }

        long size = parseNonNegativeNumeric(header, 124, 12, "entry size");
        FileTime lastModifiedTime = fileTimeFromEpochSecond(
                parseNumeric(header, 136, 12, "modification time"),
                "modification time"
        );
        FileTime lastAccessTime = lastModifiedTime;
        FileTime creationTime = lastModifiedTime;
        if (typeFlag == TarEntryAttributes.OLD_GNU_SPARSE_TYPE) {
            lastAccessTime = fileTimeFromEpochSecond(
                    parseNumeric(header, 345, 12, "old GNU access time"),
                    "old GNU access time"
            );
            creationTime = fileTimeFromEpochSecond(
                    parseNumeric(header, 357, 12, "old GNU change time"),
                    "old GNU change time"
            );
        }
        return new TarEntryAttributes(
                path,
                typeFlag,
                parseIntNumeric(header, 100, 8, "entry mode"),
                parseNonNegativeNumeric(header, 108, 8, "user id"),
                parseNonNegativeNumeric(header, 116, 8, "group id"),
                emptyToNull(readString(header, 265, 32)),
                emptyToNull(readString(header, 297, 32)),
                emptyToNull(readString(header, 157, 100)),
                size,
                lastModifiedTime,
                lastAccessTime,
                creationTime
        );
    }

    /// Reads the fixed sparse descriptors and any chained extension headers for an old GNU sparse entry.
    private OldGnuSparseSpec readOldGnuSparseSpec(byte[] header) throws IOException {
        if (header[OLD_GNU_REAL_SIZE_OFFSET] == 0) {
            throw new IOException("Old GNU sparse entry is missing its logical size");
        }
        long logicalSize = parseNonNegativeNumeric(
                header,
                OLD_GNU_REAL_SIZE_OFFSET,
                12,
                "old GNU sparse logical size"
        );
        ArrayList<SparseBlock> blocks = new ArrayList<>();
        appendOldGnuSparseBlocks(
                header,
                OLD_GNU_SPARSE_OFFSET,
                OLD_GNU_MAIN_SPARSE_COUNT,
                blocks
        );

        boolean hasExtension = header[OLD_GNU_MAIN_EXTENSION_FLAG_OFFSET] != 0;
        while (hasExtension) {
            byte[] extension = source.readNBytes(RECORD_SIZE);
            if (extension.length != RECORD_SIZE) {
                throw new EOFException("Unexpected end of old GNU sparse extension header");
            }
            appendOldGnuSparseBlocks(extension, 0, OLD_GNU_EXTENSION_SPARSE_COUNT, blocks);
            hasExtension = extension[OLD_GNU_EXTENSION_FLAG_OFFSET] != 0;
        }
        return new OldGnuSparseSpec(logicalSize, List.copyOf(blocks));
    }

    /// Appends old GNU sparse descriptors until the first unused descriptor.
    private static void appendOldGnuSparseBlocks(
            byte[] header,
            int offset,
            int count,
            ArrayList<SparseBlock> blocks
    ) throws IOException {
        for (int index = 0; index < count; index++) {
            int descriptorOffset = offset + index * OLD_GNU_SPARSE_DESCRIPTOR_SIZE;
            if (header[descriptorOffset] == 0) {
                return;
            }
            blocks.add(new SparseBlock(
                    parseNonNegativeNumeric(
                            header,
                            descriptorOffset,
                            12,
                            "old GNU sparse block offset"
                    ),
                    parseNonNegativeNumeric(
                            header,
                            descriptorOffset + 12,
                            12,
                            "old GNU sparse block size"
                    )
            ));
        }
    }

    /// Validates the TAR header checksum.
    private static void validateChecksum(byte[] header) throws IOException {
        long expected = parseOctal(header, 148, 8, "header checksum");
        long unsignedActual = 0;
        long signedActual = 0;
        for (int index = 0; index < header.length; index++) {
            if (index >= 148 && index < 156) {
                unsignedActual += ' ';
                signedActual += ' ';
            } else {
                unsignedActual += Byte.toUnsignedInt(header[index]);
                signedActual += header[index];
            }
        }
        if (expected != unsignedActual && expected != signedActual) {
            throw new IOException("Invalid TAR header checksum");
        }
    }

    /// Reads a null-terminated TAR string field.
    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    /// Parses an octal or base-256 TAR numeric field.
    private static long parseNumeric(byte[] header, int offset, int length, String description) throws IOException {
        if ((header[offset] & 0x80) == 0) {
            return parseOctal(header, offset, length, description);
        }
        return parseBase256(header, offset, length, description);
    }

    /// Parses a non-negative octal or base-256 TAR numeric field.
    private static long parseNonNegativeNumeric(
            byte[] header,
            int offset,
            int length,
            String description
    ) throws IOException {
        long value = parseNumeric(header, offset, length, description);
        if (value < 0) {
            throw new IOException("TAR " + description + " must not be negative");
        }
        return value;
    }

    /// Parses an octal or base-256 TAR numeric field that must fit in an `int`.
    private static int parseIntNumeric(byte[] header, int offset, int length, String description) throws IOException {
        long value = parseNonNegativeNumeric(header, offset, length, description);
        if (value > Integer.MAX_VALUE) {
            throw new IOException("TAR " + description + " is too large");
        }
        return (int) value;
    }

    /// Parses an octal TAR numeric field.
    private static long parseOctal(byte[] header, int offset, int length, String description) throws IOException {
        long value = 0L;
        int index = offset;
        int limit = offset + length;
        while (index < limit && (header[index] == 0 || header[index] == ' ')) {
            index++;
        }
        while (index < limit && header[index] >= '0' && header[index] <= '7') {
            value = (value << 3) + (header[index] - '0');
            index++;
        }
        while (index < limit && (header[index] == 0 || header[index] == ' ')) {
            index++;
        }
        if (index != limit) {
            throw new IOException("Invalid TAR " + description);
        }
        return value;
    }

    /// Parses a base-256 TAR numeric field.
    private static long parseBase256(byte[] header, int offset, int length, String description) throws IOException {
        byte[] valueBytes = new byte[length];
        System.arraycopy(header, offset, valueBytes, 0, length);
        if (valueBytes[0] != (byte) 0xff) {
            valueBytes[0] &= 0x7f;
        }
        try {
            return new BigInteger(valueBytes).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IOException("TAR " + description + " is too large", exception);
        }
    }

    /// Returns `null` when the value is empty.
    private static @Nullable String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /// Parses POSIX PAX key-value records.
    private static ParsedPaxHeaders parsePaxHeaders(byte[] body) throws IOException {
        HashMap<String, String> records = new HashMap<>();
        ArrayList<PaxRecord> orderedRecords = new ArrayList<>();
        int index = 0;
        while (index < body.length) {
            int space = index;
            long recordLength = 0L;
            while (space < body.length && body[space] >= '0' && body[space] <= '9') {
                recordLength = recordLength * 10L + (body[space] - '0');
                if (recordLength > Integer.MAX_VALUE) {
                    throw new IOException("TAR PAX record length is too large");
                }
                space++;
            }
            if (space == index || space >= body.length || body[space] != ' ') {
                throw new IOException("Invalid TAR PAX record length");
            }
            int remaining = body.length - index;
            if (recordLength <= 0 || recordLength > remaining) {
                throw new IOException("Invalid TAR PAX record boundary");
            }
            int end = index + (int) recordLength;
            if (body[end - 1] != '\n') {
                throw new IOException("Invalid TAR PAX record boundary");
            }

            int equals = space + 1;
            while (equals < end - 1 && body[equals] != '=') {
                equals++;
            }
            if (equals == space + 1 || equals >= end - 1) {
                throw new IOException("Invalid TAR PAX key-value record");
            }

            String key = new String(body, space + 1, equals - space - 1, StandardCharsets.UTF_8);
            String value = new String(body, equals + 1, end - equals - 2, StandardCharsets.UTF_8);
            records.put(key, value);
            orderedRecords.add(new PaxRecord(key, value));
            index = end;
        }
        return new ParsedPaxHeaders(records, List.copyOf(orderedRecords));
    }

    /// Parses a non-negative decimal PAX integer value.
    private static long parsePaxNonNegativeLong(String value, String key) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IOException("TAR PAX " + key + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid TAR PAX " + key + " value", ex);
        }
    }

    /// Parses a decimal PAX integer value.
    private static long parsePaxLong(String value, String key) throws IOException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid TAR PAX " + key + " value", ex);
        }
    }

    /// Parses a PAX timestamp value with optional fractional seconds.
    private static FileTime parsePaxFileTime(String value, String key) throws IOException {
        int dot = value.indexOf('.');
        if (dot < 0) {
            return fileTimeFromEpochSecond(parsePaxLong(value, key), "PAX " + key);
        }
        if (dot + 1 == value.length()) {
            throw new IOException("Invalid TAR PAX " + key + " value");
        }

        boolean negative = value.startsWith("-");
        String secondsText = value.substring(0, dot);
        long seconds;
        if (secondsText.isEmpty() || secondsText.equals("-")) {
            seconds = 0L;
        } else {
            seconds = parsePaxLong(secondsText, key);
        }

        long nanos = 0L;
        int parsedDigits = 0;
        for (int index = dot + 1; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < '0' || ch > '9') {
                throw new IOException("Invalid TAR PAX " + key + " value");
            }
            if (parsedDigits < 9) {
                nanos = nanos * 10L + (ch - '0');
                parsedDigits++;
            }
        }
        while (parsedDigits < 9) {
            nanos *= 10L;
            parsedDigits++;
        }
        return fileTimeFromEpochSecond(seconds, negative ? -nanos : nanos, "PAX " + key);
    }

    /// Converts epoch seconds to a file time.
    private static FileTime fileTimeFromEpochSecond(long seconds, String description) throws IOException {
        return fileTimeFromEpochSecond(seconds, 0L, description);
    }

    /// Converts epoch seconds and nanoseconds to a file time.
    private static FileTime fileTimeFromEpochSecond(long seconds, long nanos, String description) throws IOException {
        try {
            return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
        } catch (DateTimeException exception) {
            throw new IOException("TAR " + description + " is out of range", exception);
        }
    }

    /// Returns whether a block contains only zero bytes.
    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /// Returns TAR body padding needed after a body of the given size.
    private static long paddingSize(long size) {
        return (RECORD_SIZE - (size % RECORD_SIZE)) % RECORD_SIZE;
    }

    /// Reads the current metadata entry body as bytes.
    private byte[] readCurrentEntryBodyBytes(String description) throws IOException {
        if (currentBodyRemaining > Integer.MAX_VALUE) {
            throw new IOException("TAR " + description + " is too large");
        }
        int size = (int) currentBodyRemaining;
        byte[] body = source.readNBytes(size);
        if (body.length != size) {
            throw new EOFException("Unexpected end of TAR " + description);
        }
        currentBodyRemaining = 0L;
        return body;
    }

    /// Reads the current metadata entry body as a string.
    private String readCurrentEntryBodyString(String description) throws IOException {
        byte[] body = readCurrentEntryBodyBytes(description);
        int length = body.length;
        while (length > 0 && body[length - 1] == 0) {
            length--;
        }
        return new String(body, 0, length, StandardCharsets.UTF_8);
    }

    /// Merges global PAX records for subsequent real entries.
    private void mergeGlobalPaxHeaders(ParsedPaxHeaders headers) {
        for (Map.Entry<String, String> record : headers.values().entrySet()) {
            if (record.getValue().isEmpty()) {
                globalPaxHeaders.remove(record.getKey());
            } else {
                globalPaxHeaders.put(record.getKey(), record.getValue());
            }
        }
    }

    /// Merges per-entry PAX records for the next real entry.
    private void mergePendingPaxHeaders(ParsedPaxHeaders headers) {
        HashMap<String, String> records = headers.values();
        HashMap<String, String> pending = pendingPaxHeaders;
        if (pending == null) {
            pendingPaxHeaders = records;
        } else {
            pending.putAll(records);
        }
        ArrayList<PaxRecord> ordered = pendingPaxRecords;
        if (ordered == null) {
            pendingPaxRecords = new ArrayList<>(headers.orderedRecords());
        } else {
            ordered.addAll(headers.orderedRecords());
        }
    }

    /// Applies pending GNU and PAX metadata to a real TAR entry.
    private ResolvedEntry applyPendingEntryMetadata(
            TarEntryAttributes attributes,
            @Nullable OldGnuSparseSpec oldGnuSparseSpec
    ) throws IOException {
        try {
            String path = pendingLongPath != null ? pendingLongPath : attributes.path();
            @Nullable String linkName = pendingLongLink != null ? pendingLongLink : attributes.linkName();
            int mode = attributes.mode();
            long userId = attributes.userId();
            long groupId = attributes.groupId();
            @Nullable String userName = attributes.userName();
            @Nullable String groupName = attributes.groupName();
            long size = attributes.bodySize();
            FileTime lastModifiedTime = attributes.lastModifiedTime();
            FileTime lastAccessTime = attributes.lastAccessTime();
            FileTime creationTime = attributes.creationTime();

            @Nullable String paxPath = paxValue("path");
            if (paxPath != null) {
                path = paxPath;
            }
            @Nullable String paxLinkPath = paxOptionalStringValue("linkpath");
            if (paxLinkPath != null) {
                linkName = paxLinkPath;
            } else if (isPaxValueDeleted("linkpath")) {
                linkName = null;
            }
            @Nullable String paxSize = paxValue("size");
            if (paxSize != null) {
                size = parsePaxNonNegativeLong(paxSize, "size");
            }
            @Nullable String paxUserId = paxValue("uid");
            if (paxUserId != null) {
                userId = parsePaxNonNegativeLong(paxUserId, "uid");
            }
            @Nullable String paxGroupId = paxValue("gid");
            if (paxGroupId != null) {
                groupId = parsePaxNonNegativeLong(paxGroupId, "gid");
            }
            @Nullable String paxUserName = paxValue("uname");
            if (paxUserName != null) {
                userName = paxUserName.isEmpty() ? null : paxUserName;
            }
            @Nullable String paxGroupName = paxValue("gname");
            if (paxGroupName != null) {
                groupName = paxGroupName.isEmpty() ? null : paxGroupName;
            }
            @Nullable String paxModifiedTime = paxValue("mtime");
            if (paxModifiedTime != null) {
                lastModifiedTime = parsePaxFileTime(paxModifiedTime, "mtime");
            }
            @Nullable String paxAccessTime = paxValue("atime");
            if (paxAccessTime != null) {
                lastAccessTime = parsePaxFileTime(paxAccessTime, "atime");
            }
            @Nullable String paxCreationTime = paxValue("ctime");
            if (paxCreationTime != null) {
                creationTime = parsePaxFileTime(paxCreationTime, "ctime");
            }
            @Nullable SparseSpec sparseSpec = parseSparseSpec(path);
            if (sparseSpec != null && oldGnuSparseSpec != null) {
                throw new IOException("Old GNU and GNU PAX sparse metadata cannot be combined");
            }
            if (sparseSpec != null) {
                if (!attributes.hasDataBody()) {
                    throw new IOException("GNU sparse metadata requires a regular file entry");
                }
                path = sparseSpec.path();
            } else if (oldGnuSparseSpec != null) {
                sparseSpec = new SparseSpec(
                        path,
                        oldGnuSparseSpec.logicalSize(),
                        oldGnuSparseSpec.blocks()
                );
            }
            requireValidEntryPath(path);

            return new ResolvedEntry(
                    new TarEntryAttributes(
                            path,
                            attributes.typeFlag(),
                            mode,
                            userId,
                            groupId,
                            userName,
                            groupName,
                            linkName,
                            sparseSpec != null ? sparseSpec.logicalSize() : size,
                            lastModifiedTime,
                            lastAccessTime,
                            creationTime
                    ),
                    size,
                    sparseSpec
            );
        } finally {
            clearPendingEntryMetadata();
        }
    }

    /// Parses GNU PAX sparse metadata for the pending entry, or returns `null` for a contiguous entry.
    private @Nullable SparseSpec parseSparseSpec(String defaultPath) throws IOException {
        @Nullable String majorText = paxValue("GNU.sparse.major");
        @Nullable String minorText = paxValue("GNU.sparse.minor");
        @Nullable String realSizeText = paxValue("GNU.sparse.realsize");
        @Nullable String sparseSizeText = paxValue("GNU.sparse.size");
        @Nullable String blockCountText = paxValue("GNU.sparse.numblocks");
        @Nullable String mapText = paxValue("GNU.sparse.map");
        @Nullable String sparseName = paxOptionalStringValue("GNU.sparse.name");
        boolean hasRepeatedMap = hasPendingPaxRecord("GNU.sparse.offset")
                || hasPendingPaxRecord("GNU.sparse.numbytes");
        if (majorText == null && minorText == null
                && realSizeText == null && sparseSizeText == null
                && blockCountText == null && mapText == null
                && sparseName == null && !hasRepeatedMap) {
            return null;
        }

        String path = sparseName != null ? sparseName : defaultPath;
        if (majorText != null || minorText != null || realSizeText != null) {
            if (majorText == null || minorText == null || realSizeText == null) {
                throw new IOException("Incomplete GNU sparse version 1.0 metadata");
            }
            long major = parsePaxNonNegativeLong(majorText, "GNU.sparse.major");
            long minor = parsePaxNonNegativeLong(minorText, "GNU.sparse.minor");
            if (major != 1L || minor != 0L) {
                throw new IOException("Unsupported GNU sparse format version: " + major + "." + minor);
            }
            long logicalSize = parsePaxNonNegativeLong(realSizeText, "GNU.sparse.realsize");
            return new SparseSpec(path, logicalSize, null);
        }

        if (sparseSizeText == null || blockCountText == null) {
            throw new IOException("Incomplete GNU sparse format 0.x metadata");
        }
        long logicalSize = parsePaxNonNegativeLong(sparseSizeText, "GNU.sparse.size");
        int blockCount = parseSparseBlockCount(blockCountText);
        @Unmodifiable List<SparseBlock> blocks;
        if (mapText != null) {
            blocks = parseSparseMapText(mapText, blockCount);
        } else {
            blocks = parseRepeatedSparseMap(blockCount);
        }
        return new SparseSpec(path, logicalSize, blocks);
    }

    /// Returns whether the pending PAX headers contain an ordered record with the given key.
    private boolean hasPendingPaxRecord(String key) {
        ArrayList<PaxRecord> records = pendingPaxRecords;
        if (records == null) {
            return false;
        }
        for (PaxRecord record : records) {
            if (record.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /// Parses and bounds a GNU sparse block count.
    private static int parseSparseBlockCount(String value) throws IOException {
        long count = parsePaxNonNegativeLong(value, "GNU.sparse.numblocks");
        if (count > Integer.MAX_VALUE) {
            throw new IOException("GNU sparse block count is too large");
        }
        return (int) count;
    }

    /// Parses a GNU sparse 0.1 comma-separated map.
    private static @Unmodifiable List<SparseBlock> parseSparseMapText(
            String map,
            int expectedBlockCount
    ) throws IOException {
        if (map.isEmpty()) {
            if (expectedBlockCount == 0) {
                return List.of();
            }
            throw new IOException("GNU sparse map has the wrong block count");
        }
        String[] values = map.split(",", -1);
        if (values.length != expectedBlockCount * 2L) {
            throw new IOException("GNU sparse map has the wrong block count");
        }
        ArrayList<SparseBlock> blocks = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) {
            blocks.add(new SparseBlock(
                    parsePaxNonNegativeLong(values[index], "GNU.sparse.map offset"),
                    parsePaxNonNegativeLong(values[index + 1], "GNU.sparse.map size")
            ));
        }
        return List.copyOf(blocks);
    }

    /// Parses a GNU sparse 0.0 map from repeated offset and size PAX records.
    private @Unmodifiable List<SparseBlock> parseRepeatedSparseMap(int expectedBlockCount) throws IOException {
        ArrayList<PaxRecord> records = pendingPaxRecords;
        if (records == null) {
            if (expectedBlockCount == 0) {
                return List.of();
            }
            throw new IOException("GNU sparse map records are missing");
        }
        ArrayList<SparseBlock> blocks = new ArrayList<>();
        long pendingOffset = -1L;
        for (PaxRecord record : records) {
            if (record.key().equals("GNU.sparse.offset")) {
                if (pendingOffset >= 0L) {
                    throw new IOException("GNU sparse map contains consecutive offsets");
                }
                pendingOffset = parsePaxNonNegativeLong(record.value(), "GNU.sparse.offset");
            } else if (record.key().equals("GNU.sparse.numbytes")) {
                if (pendingOffset < 0L) {
                    throw new IOException("GNU sparse map size is missing its offset");
                }
                blocks.add(new SparseBlock(
                        pendingOffset,
                        parsePaxNonNegativeLong(record.value(), "GNU.sparse.numbytes")
                ));
                pendingOffset = -1L;
            }
        }
        if (pendingOffset >= 0L) {
            throw new IOException("GNU sparse map offset is missing its size");
        }
        if (blocks.size() != expectedBlockCount) {
            throw new IOException("GNU sparse map has the wrong block count");
        }
        return List.copyOf(blocks);
    }

    /// Reads and validates a GNU sparse 1.0 map stored at the beginning of the entry body.
    private SparseMap readSparseMapFromBody(long logicalSize) throws IOException {
        SparseMapBodyReader reader = new SparseMapBodyReader();
        long countValue = reader.readNumber("block count");
        if (countValue > Integer.MAX_VALUE) {
            throw new IOException("GNU sparse block count is too large");
        }
        int blockCount = (int) countValue;
        ArrayList<SparseBlock> blocks = new ArrayList<>();
        for (int index = 0; index < blockCount; index++) {
            blocks.add(new SparseBlock(
                    reader.readNumber("block offset"),
                    reader.readNumber("block size")
            ));
        }
        reader.finishPadding();
        return validateSparseMap(logicalSize, blocks, currentBodyRemaining);
    }

    /// Validates sparse block ordering, logical bounds, and the packed data size.
    private static SparseMap validateSparseMap(
            long logicalSize,
            @Unmodifiable List<SparseBlock> blocks,
            long packedSize
    ) throws IOException {
        long previousEnd = 0L;
        long totalPackedSize = 0L;
        for (SparseBlock block : blocks) {
            long offset = block.offset();
            long size = block.size();
            if (offset < previousEnd) {
                throw new IOException("GNU sparse map blocks overlap or are out of order");
            }
            long end = checkedSparseAdd(offset, size, "block end");
            if (end > logicalSize) {
                throw new IOException("GNU sparse map block exceeds the logical file size");
            }
            totalPackedSize = checkedSparseAdd(totalPackedSize, size, "packed size");
            previousEnd = end;
        }
        if (totalPackedSize != packedSize) {
            throw new IOException(
                    "GNU sparse packed size mismatch: expected " + totalPackedSize + " bytes, found " + packedSize
            );
        }
        return new SparseMap(logicalSize, List.copyOf(blocks));
    }

    /// Adds non-negative sparse values and reports overflow as an archive error.
    private static long checkedSparseAdd(long left, long right, String description) throws IOException {
        if (right > Long.MAX_VALUE - left) {
            throw new IOException("GNU sparse " + description + " is too large");
        }
        return left + right;
    }

    /// Requires a decoded TAR entry path to contain a usable archive-local path.
    private static void requireValidEntryPath(String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\")
                || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException("TAR entry path must be relative");
        }
        boolean hasName = false;
        int start = 0;
        while (start <= path.length()) {
            int end = nextPathSeparator(path, start);

            String name = path.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IOException("TAR entry path must not contain ..");
                }
                hasName = true;
            }
            start = end + 1;
        }
        if (!hasName) {
            throw new IOException("TAR entry is missing a path");
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

    /// Returns the active PAX value for a key, preferring per-entry records.
    private @Nullable String paxValue(String key) {
        HashMap<String, String> pending = pendingPaxHeaders;
        if (pending != null) {
            String value = pending.get(key);
            if (value != null) {
                return value;
            }
        }
        return globalPaxHeaders.get(key);
    }

    /// Returns the active PAX string value, or `null` when the value is absent or deleted.
    private @Nullable String paxOptionalStringValue(String key) {
        String value = paxValue(key);
        return value != null && !value.isEmpty() ? value : null;
    }

    /// Returns whether the per-entry PAX records delete a value for this key.
    private boolean isPaxValueDeleted(String key) {
        HashMap<String, String> pending = pendingPaxHeaders;
        return pending != null && "".equals(pending.get(key));
    }

    /// Clears metadata that applies only to the next real entry.
    private void clearPendingEntryMetadata() {
        pendingPaxHeaders = null;
        pendingPaxRecords = null;
        pendingLongPath = null;
        pendingLongLink = null;
    }

    /// Skips unread current entry body and padding bytes.
    private void skipCurrentEntryBody() throws IOException {
        skipFully(currentBodyRemaining);
        skipFully(currentPaddingRemaining);
        currentBodyRemaining = 0L;
        currentPaddingRemaining = 0L;
        currentBodyOpened = false;
        currentSparseMap = null;
    }

    /// Skips the requested number of bytes from the archive source.
    private void skipFully(long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = source.skip(remaining);
            if (skipped == 0) {
                if (source.read() < 0) {
                    throw new EOFException("Unexpected end of TAR entry body");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /// Requires this reader to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("TAR streaming reader is closed");
        }
    }

    /// Stores parsed PAX values together with their original ordered records.
    ///
    /// @param values         the last value for each PAX key
    /// @param orderedRecords every PAX record in archive order
    @NotNullByDefault
    private record ParsedPaxHeaders(
            HashMap<String, String> values,
            @Unmodifiable List<PaxRecord> orderedRecords
    ) {
        /// Creates parsed PAX headers.
        private ParsedPaxHeaders {
            Objects.requireNonNull(values, "values");
            orderedRecords = List.copyOf(orderedRecords);
        }
    }

    /// Stores one ordered PAX key-value record.
    ///
    /// @param key   the PAX key
    /// @param value the PAX value
    @NotNullByDefault
    private record PaxRecord(String key, String value) {
        /// Creates an ordered PAX record.
        private PaxRecord {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /// Stores sparse metadata read from an old GNU main header and its extension headers.
    ///
    /// @param logicalSize the expanded logical file size
    /// @param blocks      the ordered non-hole extents
    @NotNullByDefault
    private record OldGnuSparseSpec(
            long logicalSize,
            @Unmodifiable List<SparseBlock> blocks
    ) {
        /// Creates an old GNU sparse specification.
        private OldGnuSparseSpec {
            if (logicalSize < 0L) {
                throw new IllegalArgumentException("logicalSize must not be negative");
            }
            blocks = List.copyOf(blocks);
        }
    }

    /// Stores resolved entry metadata and the physical body size used by TAR framing.
    ///
    /// @param attributes     the logical entry attributes exposed to callers
    /// @param storedBodySize the physical body size declared by the TAR and PAX size fields
    /// @param sparseSpec     the GNU sparse metadata, or `null` for a contiguous entry
    @NotNullByDefault
    private record ResolvedEntry(
            TarEntryAttributes attributes,
            long storedBodySize,
            @Nullable SparseSpec sparseSpec
    ) {
        /// Creates resolved entry metadata.
        private ResolvedEntry {
            Objects.requireNonNull(attributes, "attributes");
            if (storedBodySize < 0L) {
                throw new IllegalArgumentException("storedBodySize must not be negative");
            }
        }
    }

    /// Stores GNU sparse metadata before a version 1.0 body map is read.
    ///
    /// @param path        the real logical entry path
    /// @param logicalSize the expanded logical file size
    /// @param blocks      the PAX-declared blocks, or `null` when the map is stored in the body
    @NotNullByDefault
    private record SparseSpec(
            String path,
            long logicalSize,
            @Nullable @Unmodifiable List<SparseBlock> blocks
    ) {
        /// Creates a GNU sparse specification.
        private SparseSpec {
            Objects.requireNonNull(path, "path");
            if (logicalSize < 0L) {
                throw new IllegalArgumentException("logicalSize must not be negative");
            }
            if (blocks != null) {
                blocks = List.copyOf(blocks);
            }
        }
    }

    /// Stores one non-hole extent in a sparse logical file.
    ///
    /// @param offset the absolute logical file offset
    /// @param size   the number of packed data bytes
    @NotNullByDefault
    private record SparseBlock(long offset, long size) {
        /// Creates a sparse data block.
        private SparseBlock {
            if (offset < 0L || size < 0L) {
                throw new IllegalArgumentException("Sparse block values must not be negative");
            }
        }
    }

    /// Stores a validated sparse map for the current entry body.
    ///
    /// @param logicalSize the expanded logical file size
    /// @param blocks      the ordered non-hole extents
    @NotNullByDefault
    private record SparseMap(long logicalSize, @Unmodifiable List<SparseBlock> blocks) {
        /// Creates a validated sparse map.
        private SparseMap {
            if (logicalSize < 0L) {
                throw new IllegalArgumentException("logicalSize must not be negative");
            }
            blocks = List.copyOf(blocks);
        }
    }

    /// Parses a GNU sparse 1.0 map directly from the current physical entry body.
    @NotNullByDefault
    private final class SparseMapBodyReader {
        /// The number of unpadded map bytes consumed from the body.
        private long byteCount;

        /// Reads one newline-terminated non-negative decimal map number.
        private long readNumber(String description) throws IOException {
            long value = 0L;
            boolean hasDigit = false;
            while (true) {
                if (currentBodyRemaining == 0L) {
                    throw new EOFException("Unexpected end of GNU sparse map");
                }
                int next = source.read();
                if (next < 0) {
                    throw new EOFException("Unexpected end of GNU sparse map");
                }
                currentBodyRemaining--;
                byteCount++;
                if (next == '\n') {
                    if (!hasDigit) {
                        throw new IOException("GNU sparse " + description + " is empty");
                    }
                    return value;
                }
                if (next < '0' || next > '9') {
                    throw new IOException("Invalid GNU sparse " + description);
                }
                int digit = next - '0';
                if (value > (Long.MAX_VALUE - digit) / 10L) {
                    throw new IOException("GNU sparse " + description + " is too large");
                }
                value = value * 10L + digit;
                hasDigit = true;
            }
        }

        /// Consumes and validates null padding through the next TAR block boundary.
        private void finishPadding() throws IOException {
            long padding = paddingSize(byteCount);
            if (padding > currentBodyRemaining) {
                throw new EOFException("Unexpected end of GNU sparse map padding");
            }
            for (long index = 0L; index < padding; index++) {
                int next = source.read();
                if (next < 0) {
                    throw new EOFException("Unexpected end of GNU sparse map padding");
                }
                currentBodyRemaining--;
                if (next != 0) {
                    throw new IOException("GNU sparse map padding must contain only null bytes");
                }
            }
        }
    }

    /// Expands the current packed sparse body into its logical byte stream.
    @NotNullByDefault
    private final class SparseEntryInputStream extends InputStream {
        /// The validated sparse map.
        private final SparseMap sparseMap;

        /// A reusable single-byte read buffer.
        private final byte[] singleByte = new byte[1];

        /// The current logical file position.
        private long logicalPosition;

        /// The index of the current sparse data block.
        private int blockIndex;

        /// Creates a sparse entry input stream.
        private SparseEntryInputStream(SparseMap sparseMap) {
            this.sparseMap = Objects.requireNonNull(sparseMap, "sparseMap");
        }

        /// Reads one expanded logical byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads expanded logical bytes, synthesizing zeros for sparse holes.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (logicalPosition == sparseMap.logicalSize()) {
                return -1;
            }

            int total = 0;
            while (total < length && logicalPosition < sparseMap.logicalSize()) {
                advanceCompletedBlocks();
                @Nullable SparseBlock block = currentBlock();
                if (block == null || logicalPosition < block.offset()) {
                    long holeEnd = block != null ? block.offset() : sparseMap.logicalSize();
                    int count = (int) Math.min(length - total, holeEnd - logicalPosition);
                    java.util.Arrays.fill(buffer, offset + total, offset + total + count, (byte) 0);
                    logicalPosition += count;
                    total += count;
                    continue;
                }

                long blockEnd = block.offset() + block.size();
                int requested = (int) Math.min(length - total, blockEnd - logicalPosition);
                int read = source.read(buffer, offset + total, requested);
                if (read < 0) {
                    throw new EOFException("Unexpected end of GNU sparse entry data");
                }
                if (read == 0) {
                    throw new IOException("GNU sparse entry read made no progress");
                }
                currentBodyRemaining -= read;
                logicalPosition += read;
                total += read;
            }
            return total;
        }

        /// Skips expanded logical bytes while consuming packed data extents as needed.
        @Override
        public long skip(long count) throws IOException {
            ensureOpen();
            if (count <= 0L || logicalPosition == sparseMap.logicalSize()) {
                return 0L;
            }
            long available = sparseMap.logicalSize() - logicalPosition;
            long target = logicalPosition + Math.min(count, available);
            long start = logicalPosition;
            while (logicalPosition < target) {
                advanceCompletedBlocks();
                @Nullable SparseBlock block = currentBlock();
                if (block == null || logicalPosition < block.offset()) {
                    long holeEnd = block != null ? block.offset() : sparseMap.logicalSize();
                    logicalPosition = Math.min(target, holeEnd);
                    continue;
                }
                long blockEnd = block.offset() + block.size();
                skipPackedData(Math.min(target - logicalPosition, blockEnd - logicalPosition));
            }
            return logicalPosition - start;
        }

        /// Returns immediately available logical bytes from the current hole or data block.
        @Override
        public int available() throws IOException {
            ensureOpen();
            if (logicalPosition == sparseMap.logicalSize()) {
                return 0;
            }
            advanceCompletedBlocks();
            @Nullable SparseBlock block = currentBlock();
            long available;
            if (block == null || logicalPosition < block.offset()) {
                long holeEnd = block != null ? block.offset() : sparseMap.logicalSize();
                available = holeEnd - logicalPosition;
            } else {
                long blockRemaining = block.offset() + block.size() - logicalPosition;
                available = Math.min(blockRemaining, source.available());
            }
            return (int) Math.min(Integer.MAX_VALUE, available);
        }

        /// Advances past sparse blocks ending at the current logical position.
        private void advanceCompletedBlocks() {
            @Unmodifiable List<SparseBlock> blocks = sparseMap.blocks();
            while (blockIndex < blocks.size()) {
                SparseBlock block = blocks.get(blockIndex);
                if (logicalPosition < block.offset() + block.size()) {
                    return;
                }
                blockIndex++;
            }
        }

        /// Returns the current sparse block, or `null` after the final data block.
        private @Nullable SparseBlock currentBlock() {
            @Unmodifiable List<SparseBlock> blocks = sparseMap.blocks();
            return blockIndex < blocks.size() ? blocks.get(blockIndex) : null;
        }

        /// Skips an exact number of packed data bytes.
        private void skipPackedData(long count) throws IOException {
            long remaining = count;
            while (remaining > 0L) {
                long skipped = source.skip(remaining);
                if (skipped > remaining) {
                    throw new IOException("Invalid GNU sparse entry skip result");
                }
                if (skipped == 0L) {
                    if (source.read() < 0) {
                        throw new EOFException("Unexpected end of GNU sparse entry data");
                    }
                    skipped = 1L;
                }
                currentBodyRemaining -= skipped;
                logicalPosition += skipped;
                remaining -= skipped;
            }
        }
    }

    /// Exposes the current regular file body without consuming the following padding.
    @NotNullByDefault
    private final class EntryInputStream extends InputStream {
        /// Reads one byte from the current entry body.
        @Override
        public int read() throws IOException {
            ensureOpen();
            if (currentBodyRemaining == 0) {
                return -1;
            }
            int value = source.read();
            if (value < 0) {
                throw new EOFException("Unexpected end of TAR entry body");
            }
            currentBodyRemaining--;
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
            if (currentBodyRemaining == 0) {
                return -1;
            }
            int read = source.read(buffer, offset, (int) Math.min(length, currentBodyRemaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of TAR entry body");
            }
            currentBodyRemaining -= read;
            return read;
        }

        /// Skips bytes from the current entry body.
        @Override
        public long skip(long count) throws IOException {
            ensureOpen();
            if (count <= 0 || currentBodyRemaining == 0) {
                return 0;
            }
            long skipped = source.skip(Math.min(count, currentBodyRemaining));
            currentBodyRemaining -= skipped;
            return skipped;
        }

        /// Returns the approximate available current entry body bytes.
        @Override
        public int available() throws IOException {
            ensureOpen();
            return Math.min(source.available(), (int) Math.min(Integer.MAX_VALUE, currentBodyRemaining));
        }
    }
}

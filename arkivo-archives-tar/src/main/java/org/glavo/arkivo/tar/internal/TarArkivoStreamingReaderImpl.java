// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar.internal;

import org.glavo.arkivo.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.tar.TarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Implements the public forward-only TAR streaming reader API.
@NotNullByDefault
public final class TarArkivoStreamingReaderImpl extends TarArkivoStreamingReader {
    /// The TAR record size.
    private static final int RECORD_SIZE = 512;

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

    /// The active global PAX key-value records.
    private final HashMap<String, String> globalPaxHeaders = new HashMap<>();

    /// The pending per-entry PAX key-value records.
    private @Nullable HashMap<String, String> pendingPaxHeaders;

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

            TarEntryAttributes resolvedAttributes = applyPendingEntryMetadata(attributes);
            currentAttributes = resolvedAttributes;
            currentBodyRemaining = resolvedAttributes.bodySize();
            currentPaddingRemaining = paddingSize(currentBodyRemaining);
            currentBodyOpened = false;
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
        if (type == BasicFileAttributes.class || type == TarArkivoEntryAttributes.class) {
            return type.cast(attributes);
        }
        throw new UnsupportedOperationException("Unsupported TAR streaming attributes type: " + type.getName());
    }

    /// Opens a readable channel for the current file entry.
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
        if (!attributes.isRegularFile()) {
            return Channels.newChannel(InputStream.nullInputStream());
        }
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
        clearPendingEntryMetadata();
        globalPaxHeaders.clear();
        source.close();
        sourceClosed = true;
    }

    /// Parses one TAR header block.
    private static TarEntryAttributes parseHeader(byte[] header) throws IOException {
        validateChecksum(header);
        String name = readString(header, 0, 100);
        String prefix = readString(header, 345, 155);
        String path = prefix.isEmpty() ? name : prefix + "/" + name;
        if (path.isEmpty()) {
            throw new IOException("TAR entry is missing a path");
        }

        byte typeFlag = header[156];
        long size = parseNonNegativeNumeric(header, 124, 12, "entry size");
        FileTime lastModifiedTime = fileTimeFromEpochSecond(
                parseNumeric(header, 136, 12, "modification time"),
                "modification time"
        );
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
                lastModifiedTime,
                lastModifiedTime
        );
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
    private static HashMap<String, String> parsePaxHeaders(byte[] body) throws IOException {
        HashMap<String, String> records = new HashMap<>();
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
            index = end;
        }
        return records;
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
    private void mergeGlobalPaxHeaders(HashMap<String, String> records) {
        for (Map.Entry<String, String> record : records.entrySet()) {
            if (record.getValue().isEmpty()) {
                globalPaxHeaders.remove(record.getKey());
            } else {
                globalPaxHeaders.put(record.getKey(), record.getValue());
            }
        }
    }

    /// Merges per-entry PAX records for the next real entry.
    private void mergePendingPaxHeaders(HashMap<String, String> records) {
        HashMap<String, String> pending = pendingPaxHeaders;
        if (pending == null) {
            pendingPaxHeaders = records;
        } else {
            pending.putAll(records);
        }
    }

    /// Applies pending GNU and PAX metadata to a real TAR entry.
    private TarEntryAttributes applyPendingEntryMetadata(TarEntryAttributes attributes) throws IOException {
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
            requireValidEntryPath(path);

            return new TarEntryAttributes(
                    path,
                    attributes.typeFlag(),
                    mode,
                    userId,
                    groupId,
                    userName,
                    groupName,
                    linkName,
                    size,
                    lastModifiedTime,
                    lastAccessTime,
                    creationTime
            );
        } finally {
            clearPendingEntryMetadata();
        }
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar.internal;

import org.glavo.arkivo.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.ar.ArArkivoStreamingReader;
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
import java.util.Arrays;
import java.util.Objects;

/// Implements the public forward-only AR streaming reader API.
@NotNullByDefault
public final class ArArkivoStreamingReaderImpl extends ArArkivoStreamingReader {
    /// The global AR archive signature.
    private static final byte @Unmodifiable [] GLOBAL_HEADER = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);

    /// The fixed AR member header size.
    private static final int MEMBER_HEADER_SIZE = 60;

    /// The AR member header trailer.
    private static final byte @Unmodifiable [] MEMBER_TRAILER = new byte[]{'`', '\n'};

    /// The backing archive input stream.
    private final InputStream source;

    /// Whether the global archive signature has been consumed.
    private boolean globalHeaderRead;

    /// Whether this reader is open for archive operations.
    private boolean open = true;

    /// Whether the backing archive input stream has been closed.
    private boolean sourceClosed;

    /// The current entry attributes, or `null` when no entry is active.
    private @Nullable ArEntryAttributes currentAttributes;

    /// The remaining bytes in the current member body.
    private long remainingEntryBytes;

    /// Whether the current member body has a one-byte alignment padding after it.
    private boolean pendingEntryPadding;

    /// Whether the current member body has already been opened.
    private boolean currentBodyOpened;

    /// The GNU long filename table, or `null` until such a table is read.
    private byte @Nullable @Unmodifiable [] gnuNameTable;

    /// Creates a streaming AR reader.
    public ArArkivoStreamingReaderImpl(InputStream source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Advances to the next real archive member.
    @Override
    public boolean next() throws IOException {
        ensureOpen();
        readGlobalHeader();
        skipCurrentEntry();

        while (true) {
            byte[] header = readMemberHeader();
            if (header.length == 0) {
                currentAttributes = null;
                return false;
            }

            HeaderFields fields = parseHeader(header);
            String identifier = fields.identifier();
            if ("//".equals(identifier)) {
                gnuNameTable = readBytes(fields.size(), "Unexpected end of AR GNU filename table");
                skipPadding(fields.size());
                continue;
            }
            if ("/".equals(identifier) || "/SYM64/".equals(identifier) || isBsdSymbolTable(identifier)) {
                skipBytes(fields.size());
                skipPadding(fields.size());
                continue;
            }

            ResolvedName resolvedName = resolveName(identifier, fields.size());
            if (isBsdSymbolTable(resolvedName.path())) {
                skipBytes(resolvedName.dataSize());
                skipPadding(fields.size());
                continue;
            }
            remainingEntryBytes = resolvedName.dataSize();
            pendingEntryPadding = (fields.size() & 1L) != 0;
            currentBodyOpened = false;
            currentAttributes = new ArEntryAttributes(
                    resolvedName.path(),
                    identifier,
                    fields.userId(),
                    fields.groupId(),
                    fields.mode(),
                    resolvedName.dataSize(),
                    lastModifiedTime(fields.timestamp())
            );
            return true;
        }
    }

    /// Returns whether an identifier names a BSD archive symbol table.
    private static boolean isBsdSymbolTable(String identifier) {
        String name = identifier.endsWith("/")
                ? identifier.substring(0, identifier.length() - 1)
                : identifier;
        return "__.SYMDEF".equals(name)
                || "__.SYMDEF SORTED".equals(name)
                || "__.SYMDEF_64".equals(name)
                || "__.SYMDEF_64 SORTED".equals(name);
    }

    /// Reads the current archive member attributes as the requested attribute type.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        ArEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IllegalStateException("No current AR entry");
        }
        if (!type.isInstance(attributes)) {
            throw new UnsupportedOperationException("Unsupported AR attribute type: " + type.getName());
        }
        return type.cast(attributes);
    }

    /// Opens a readable channel for the current member body.
    @Override
    public ReadableByteChannel openChannel() throws IOException {
        ensureOpen();
        if (currentAttributes == null) {
            throw new IllegalStateException("No current AR entry");
        }
        if (currentBodyOpened) {
            throw new IOException("AR member body has already been opened");
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
        remainingEntryBytes = 0L;
        pendingEntryPadding = false;
        currentBodyOpened = false;
        source.close();
        sourceClosed = true;
    }

    /// Reads and validates the global AR archive header.
    private void readGlobalHeader() throws IOException {
        if (globalHeaderRead) {
            return;
        }
        byte[] header = readBytes(GLOBAL_HEADER.length, "Unexpected end of AR global header");
        if (!Arrays.equals(header, GLOBAL_HEADER)) {
            throw new IOException("Invalid AR global header");
        }
        globalHeaderRead = true;
    }

    /// Reads the next member header, or an empty array at archive EOF.
    private byte[] readMemberHeader() throws IOException {
        byte[] header = new byte[MEMBER_HEADER_SIZE];
        int offset = 0;
        while (offset < header.length) {
            int read = source.read(header, offset, header.length - offset);
            if (read < 0) {
                if (offset == 0) {
                    return new byte[0];
                }
                throw new EOFException("Unexpected end of AR member header");
            }
            offset += read;
        }
        if (header[58] != MEMBER_TRAILER[0] || header[59] != MEMBER_TRAILER[1]) {
            throw new IOException("Invalid AR member header trailer");
        }
        return header;
    }

    /// Parses fixed-width header fields.
    private static HeaderFields parseHeader(byte[] header) throws IOException {
        String identifier = asciiField(header, 0, 16).trim();
        long timestamp = parseDecimalField(header, 16, 12, "timestamp");
        long userId = parseDecimalField(header, 28, 6, "user id");
        long groupId = parseDecimalField(header, 34, 6, "group id");
        int mode = parseOctalIntField(header, 40, 8, "mode");
        long size = parseDecimalField(header, 48, 10, "size");
        return new HeaderFields(identifier, timestamp, userId, groupId, mode, size);
    }

    /// Resolves the member path and data size from the raw AR identifier.
    private ResolvedName resolveName(String identifier, long memberSize) throws IOException {
        if (identifier.startsWith("#1/")) {
            int nameLength = parsePositiveDecimal(identifier.substring(3), "BSD long name length");
            if (nameLength > memberSize) {
                throw new IOException("AR BSD long name exceeds member size");
            }
            String path = new String(
                    readBytes(nameLength, "Unexpected end of AR BSD long name"),
                    StandardCharsets.UTF_8
            );
            return new ResolvedName(validatePath(path), memberSize - nameLength);
        }
        if (identifier.startsWith("/") && identifier.length() > 1) {
            return new ResolvedName(resolveGnuLongName(identifier.substring(1)), memberSize);
        }
        String path = identifier.endsWith("/") ? identifier.substring(0, identifier.length() - 1) : identifier;
        return new ResolvedName(validatePath(path), memberSize);
    }

    /// Resolves a GNU long filename table reference.
    private String resolveGnuLongName(String offsetText) throws IOException {
        int offset = parseNonNegativeDecimal(offsetText, "GNU long name offset");
        byte @Nullable @Unmodifiable [] table = gnuNameTable;
        if (table == null) {
            throw new IOException("AR GNU long name table is missing");
        }
        if (offset >= table.length) {
            throw new IOException("AR GNU long name offset is out of range");
        }
        int end = offset;
        while (end < table.length && table[end] != '\n') {
            end++;
        }
        int nameEnd = end;
        if (nameEnd > offset && table[nameEnd - 1] == '/') {
            nameEnd--;
        }
        return validatePath(new String(table, offset, nameEnd - offset, StandardCharsets.UTF_8));
    }

    /// Converts an AR timestamp from epoch seconds to a file time.
    private static FileTime lastModifiedTime(long timestamp) throws IOException {
        try {
            return FileTime.fromMillis(Math.multiplyExact(timestamp, 1000L));
        } catch (ArithmeticException exception) {
            throw new IOException("AR timestamp is out of range", exception);
        }
    }

    /// Validates a decoded archive member path.
    private static String validatePath(String path) throws IOException {
        if (path.isEmpty()) {
            throw new IOException("AR member is missing a path");
        }
        if (path.startsWith("/") || path.startsWith("\\") || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException("AR member path must be relative");
        }
        String[] parts = path.split("[/\\\\]+");
        for (String part : parts) {
            if ("..".equals(part)) {
                throw new IOException("AR member path must not contain ..");
            }
        }
        return path;
    }

    /// Skips any unread bytes from the current member and its alignment padding.
    private void skipCurrentEntry() throws IOException {
        if (remainingEntryBytes > 0) {
            skipBytes(remainingEntryBytes);
            remainingEntryBytes = 0L;
        }
        if (pendingEntryPadding) {
            skipBytes(1L);
            pendingEntryPadding = false;
        }
        currentBodyOpened = false;
    }

    /// Skips member padding when the full stored member size is odd.
    private void skipPadding(long memberSize) throws IOException {
        if ((memberSize & 1L) != 0) {
            skipBytes(1L);
        }
    }

    /// Reads exactly the requested byte count.
    private byte[] readBytes(long size, String eofMessage) throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new IOException("AR member is too large to buffer");
        }
        byte[] bytes = new byte[(int) size];
        int offset = 0;
        while (offset < bytes.length) {
            int read = source.read(bytes, offset, bytes.length - offset);
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            offset += read;
        }
        return bytes;
    }

    /// Skips exactly the requested byte count.
    private void skipBytes(long count) throws IOException {
        byte[] discard = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int read = source.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of AR member body");
            }
            remaining -= read;
        }
    }

    /// Returns one US-ASCII field from a member header.
    private static String asciiField(byte[] header, int offset, int length) {
        return new String(header, offset, length, StandardCharsets.US_ASCII);
    }

    /// Parses a non-negative decimal fixed-width field.
    private static long parseDecimalField(byte[] header, int offset, int length, String fieldName) throws IOException {
        String text = asciiField(header, offset, length).trim();
        return parseNonNegativeLong(text, fieldName, 10);
    }

    /// Parses a non-negative octal fixed-width field.
    private static int parseOctalIntField(byte[] header, int offset, int length, String fieldName) throws IOException {
        long value = parseNonNegativeLong(asciiField(header, offset, length).trim(), fieldName, 8);
        if (value > Integer.MAX_VALUE) {
            throw new IOException("AR " + fieldName + " is out of range");
        }
        return (int) value;
    }

    /// Parses a positive decimal value.
    private static int parsePositiveDecimal(String text, String fieldName) throws IOException {
        long value = parseNonNegativeLong(text, fieldName, 10);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IOException("AR " + fieldName + " is out of range");
        }
        return (int) value;
    }

    /// Parses a non-negative decimal value.
    private static int parseNonNegativeDecimal(String text, String fieldName) throws IOException {
        long value = parseNonNegativeLong(text, fieldName, 10);
        if (value > Integer.MAX_VALUE) {
            throw new IOException("AR " + fieldName + " is out of range");
        }
        return (int) value;
    }

    /// Parses a non-negative integer value with the requested radix.
    private static long parseNonNegativeLong(String text, String fieldName, int radix) throws IOException {
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(text, radix);
            if (value < 0) {
                throw new IOException("AR " + fieldName + " must not be negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid AR " + fieldName + ": " + text, exception);
        }
    }

    /// Requires this reader to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("AR streaming reader is closed");
        }
    }

    /// Stores parsed fixed-width member header fields.
    ///
    /// @param identifier the raw trimmed member identifier
    /// @param timestamp the modification time in epoch seconds
    /// @param userId the numeric user identifier
    /// @param groupId the numeric group identifier
    /// @param mode the stored POSIX mode bits
    /// @param size the complete stored member size
    @NotNullByDefault
    private record HeaderFields(
            String identifier,
            long timestamp,
            long userId,
            long groupId,
            int mode,
            long size
    ) {
        /// Creates parsed AR member header fields.
        private HeaderFields {
            Objects.requireNonNull(identifier, "identifier");
            if (timestamp < 0 || userId < 0 || groupId < 0 || mode < 0 || size < 0) {
                throw new IllegalArgumentException("AR header numeric fields must not be negative");
            }
        }
    }

    /// Stores a resolved member name and visible data size.
    ///
    /// @param path the decoded safe member path
    /// @param dataSize the visible member data size after long-name metadata
    @NotNullByDefault
    private record ResolvedName(String path, long dataSize) {
        /// Creates a resolved member name.
        private ResolvedName {
            Objects.requireNonNull(path, "path");
            if (dataSize < 0) {
                throw new IllegalArgumentException("dataSize must not be negative");
            }
        }
    }

    /// Reads bytes from the current member body.
    private final class EntryInputStream extends InputStream {
        /// Whether this entry stream has been closed.
        private boolean closed;

        /// Reads one byte from the current member body.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads bytes from the current member body.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (closed) {
                throw new IOException("AR entry input stream is closed");
            }
            ensureOpen();
            if (length == 0) {
                return 0;
            }
            if (remainingEntryBytes == 0) {
                return -1;
            }
            int read = source.read(buffer, offset, (int) Math.min(length, remainingEntryBytes));
            if (read < 0) {
                throw new EOFException("Unexpected end of AR member body");
            }
            remainingEntryBytes -= read;
            return read;
        }

        /// Closes this entry stream after draining the member body.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            skipCurrentEntry();
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributeView;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Implements the public forward-only TAR streaming writer API.
@NotNullByDefault
public final class TarArkivoStreamingWriterImpl extends TarArkivoStreamingWriter {
    /// The TAR record size.
    private static final int RECORD_SIZE = 512;

    /// The TAR end marker size.
    private static final int END_MARKER_SIZE = RECORD_SIZE * 2;

    /// The default regular file mode.
    private static final int DEFAULT_FILE_MODE = 0644;

    /// The default directory mode.
    private static final int DEFAULT_DIRECTORY_MODE = 0755;

    /// The default symbolic link mode.
    private static final int DEFAULT_SYMBOLIC_LINK_MODE = 0777;

    /// The default hard link mode.
    private static final int DEFAULT_HARD_LINK_MODE = 0644;

    /// The marker used when a POSIX mode has not been configured.
    private static final int UNKNOWN_MODE = -1;

    /// The Unix epoch timestamp used by default TAR entries.
    private static final FileTime UNIX_EPOCH = FileTime.fromMillis(0L);

    /// The synthesized default owner for pending POSIX attributes.
    private static final UserPrincipal DEFAULT_OWNER = TarPosixSupport.DEFAULT_OWNER;

    /// The synthesized default group for pending POSIX attributes.
    private static final GroupPrincipal DEFAULT_GROUP = TarPosixSupport.DEFAULT_GROUP;

    /// Identifies the type requested for a pending streaming entry.
    private enum EntryType {
        /// A regular file entry.
        FILE,

        /// A directory entry.
        DIRECTORY,

        /// A symbolic link entry.
        SYMBOLIC_LINK,

        /// A hard link entry.
        HARD_LINK
    }

    /// The backing archive output stream.
    private final OutputStream output;

    /// The storage used to stage file bodies until their TAR headers can be written.
    private final ArkivoEditStorage bodyStorage;

    /// Stored bodies whose first cleanup attempt failed and must be retried while closing.
    private final ArrayList<ArkivoStoredContent> retiredBodies = new ArrayList<>();

    /// The pending entry that has not yet been committed.
    private @Nullable PendingEntry pendingEntry;

    /// The open file body stream that will commit a file entry when closed.
    private @Nullable EntryBodyOutputStream currentBody;

    /// Whether this writer still accepts new entries.
    private boolean open = true;

    /// Whether the TAR end marker has already been written.
    private boolean finished;

    /// Whether the backing archive output stream has been closed.
    private boolean outputClosed;

    /// Whether the body storage has been closed.
    private boolean bodyStorageClosed;

    /// Creates a streaming TAR writer.
    public TarArkivoStreamingWriterImpl(OutputStream output) {
        this(output, ArkivoEditStorage.temporaryFiles(defaultBodyStorageDirectory()));
    }

    /// Creates a streaming TAR writer that owns the given body storage.
    public TarArkivoStreamingWriterImpl(OutputStream output, ArkivoEditStorage bodyStorage) {
        this.output = Objects.requireNonNull(output, "output");
        this.bodyStorage = Objects.requireNonNull(bodyStorage, "bodyStorage");
    }

    /// Returns the directory used by default staged body storage.
    private static Path defaultBodyStorageDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
    }

    /// Begins a pending regular file TAR entry for the given logical archive path.
    @Override
    public void beginFile(String path) throws IOException {
        beginEntry(path, EntryType.FILE, null);
    }

    /// Begins a pending directory TAR entry for the given logical archive path.
    @Override
    public void beginDirectory(String path) throws IOException {
        beginEntry(path, EntryType.DIRECTORY, null);
    }

    /// Begins a pending symbolic link TAR entry for the given logical archive path and target path text.
    @Override
    public void beginSymbolicLink(String path, String target) throws IOException {
        beginEntry(path, EntryType.SYMBOLIC_LINK, linkTargetText(target));
    }

    /// Begins a pending hard link TAR entry for the given logical archive path and target archive path.
    @Override
    public void beginHardLink(String path, String target) throws IOException {
        beginEntry(path, EntryType.HARD_LINK, hardLinkTargetText(target));
    }

    /// Writes an indexed TAR entry snapshot while preserving its logical type and metadata.
    /// Old GNU sparse entries are normalized to regular files because indexed storage exposes their expanded bodies.
    void writeSnapshot(
            TarArkivoEntryAttributes attributes,
            @Nullable ReadableByteChannel body,
            long bodySize
    ) throws IOException {
        ensureOpen();
        Objects.requireNonNull(attributes, "attributes");
        if (bodySize < 0L) {
            throw new IllegalArgumentException("TAR snapshot body size must not be negative");
        }
        if (pendingEntry != null) {
            throw new IllegalStateException("A TAR streaming entry is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("A TAR streaming entry body is still open");
        }

        byte typeFlag = attributes.typeFlag() == TarEntryAttributes.OLD_GNU_SPARSE_TYPE
                ? TarEntryAttributes.REGULAR_TYPE
                : attributes.typeFlag();
        boolean storesBody = typeFlag == TarEntryAttributes.REGULAR_TYPE
                || typeFlag == TarEntryAttributes.OLD_REGULAR_TYPE
                || attributes.isOther();
        long size = storesBody ? bodySize : 0L;
        if (size > 0L && body == null) {
            throw new IllegalArgumentException("TAR snapshot body channel is required for non-empty content");
        }
        String path = entryPathText(attributes.path(), attributes.isDirectory());
        @Nullable String configuredLinkName = attributes.linkName();
        String linkName = configuredLinkName != null ? configuredLinkName : "";

        LinkedHashMap<String, String> paxRecords = new LinkedHashMap<>();
        @Nullable HeaderPathFields pathFields = headerPathFields(path);
        if (pathFields == null) {
            paxRecords.put("path", path);
            pathFields = new HeaderPathFields("arkivo-pax-entry", "");
        }

        String headerLinkName = linkName;
        if (!linkName.isEmpty() && utf8Length(linkName) > 100) {
            paxRecords.put("linkpath", linkName);
            headerLinkName = "";
        }

        paxRecords.put("mtime", paxTimestamp(attributes.lastModifiedTime()));
        paxRecords.put("atime", paxTimestamp(attributes.lastAccessTime()));
        paxRecords.put("ctime", paxTimestamp(attributes.creationTime()));

        long headerUserId = attributes.userId();
        if (headerUserId > maxOctalValue(8)) {
            paxRecords.put("uid", Long.toString(headerUserId));
            headerUserId = 0L;
        }
        long headerGroupId = attributes.groupId();
        if (headerGroupId > maxOctalValue(8)) {
            paxRecords.put("gid", Long.toString(headerGroupId));
            headerGroupId = 0L;
        }

        @Nullable String configuredUserName = attributes.userName();
        String userName = configuredUserName != null ? configuredUserName : "";
        String headerUserName = userName;
        if (utf8Length(userName) > 32) {
            paxRecords.put("uname", userName);
            headerUserName = "";
        }
        @Nullable String configuredGroupName = attributes.groupName();
        String groupName = configuredGroupName != null ? configuredGroupName : "";
        String headerGroupName = groupName;
        if (utf8Length(groupName) > 32) {
            paxRecords.put("gname", groupName);
            headerGroupName = "";
        }

        writePaxHeader(paxRecords);
        writeHeader(
                pathFields.name,
                pathFields.prefix,
                attributes.mode(),
                headerUserId,
                headerGroupId,
                size,
                headerEpochSecond(attributes.lastModifiedTime()),
                typeFlag,
                headerLinkName,
                headerUserName,
                headerGroupName
        );
        if (size > 0L) {
            writeBody(Objects.requireNonNull(body, "body"), size);
            writePadding(size);
        }
    }

    /// Copies exactly one indexed entry body to the archive output using bounded memory.
    private void writeBody(ReadableByteChannel body, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = size;
        while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int) Math.min(remaining, buffer.capacity()));
            int count = body.read(buffer);
            if (count < 0) {
                throw new IOException("TAR snapshot body ended before its declared size");
            }
            if (count == 0) {
                continue;
            }
            output.write(buffer.array(), 0, count);
            remaining -= count;
        }
    }

    /// Begins a pending TAR entry for the given logical archive path and type.
    private void beginEntry(String path, EntryType type, @Nullable String linkTarget) throws IOException {
        ensureOpen();
        if (pendingEntry != null) {
            throw new IllegalStateException("A TAR streaming entry is already pending");
        }
        if (currentBody != null) {
            throw new IllegalStateException("A TAR streaming entry body is still open");
        }
        pendingEntry = new PendingEntry(entryPathText(path, type == EntryType.DIRECTORY), type, linkTarget);
    }

    /// Returns an attribute view used to configure the current pending entry before it is committed.
    @Override
    public <V extends FileAttributeView> @Nullable V attributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        PendingEntry entry = requirePendingEntry();
        if (type == BasicFileAttributeView.class) {
            return type.cast(entry.attributes);
        }
        if (type == TarArkivoEntryAttributeView.class) {
            return type.cast(entry.attributes);
        }
        if (type == PosixFileAttributeView.class) {
            return type.cast(entry.posixAttributes);
        }
        return null;
    }

    /// Commits the current pending entry without opening a body channel.
    @Override
    public void endEntry() throws IOException {
        ensureOpen();
        PendingEntry entry = requirePendingEntry();
        entry.ensurePending();
        pendingEntry = null;
        writeEntry(entry, null, 0L);
    }

    /// Opens a writable channel for the current pending file entry and commits it when the channel is closed.
    @Override
    public WritableByteChannel openChannel() throws IOException {
        return StreamChannelAdapters.writableChannel(openOutputStream());
    }

    /// Opens an output stream for the current pending file entry and commits it when the stream is closed.
    @Override
    public OutputStream openOutputStream() throws IOException {
        ensureOpen();
        PendingEntry entry = requirePendingEntry();
        entry.ensurePending();
        if (entry.type != EntryType.FILE) {
            throw new IllegalStateException("Only TAR file entries can open a body stream");
        }
        pendingEntry = null;
        EntryBodyOutputStream body = new EntryBodyOutputStream(entry);
        currentBody = body;
        return body;
    }

    /// Closes this streaming writer and finishes the TAR stream.
    @Override
    public void close() throws IOException {
        if (!open && outputClosed && retiredBodies.isEmpty() && bodyStorageClosed) {
            return;
        }

        IOException failure = null;
        EntryBodyOutputStream body = currentBody;
        if (body != null) {
            try {
                body.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }

        PendingEntry entry = pendingEntry;
        if (failure == null && entry != null) {
            pendingEntry = null;
            try {
                entry.ensurePending();
                writeEntry(entry, null, 0L);
            } catch (IOException exception) {
                pendingEntry = entry;
                failure = exception;
            }
        }

        if (failure == null && !finished) {
            try {
                output.write(new byte[END_MARKER_SIZE]);
                finished = true;
            } catch (IOException exception) {
                failure = exception;
            }
        }

        open = false;
        try {
            output.close();
            outputClosed = true;
        } catch (IOException exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
            } else {
                failure = exception;
            }
        }

        failure = closeBodyStorage(failure);

        if (failure != null) {
            throw failure;
        }
    }

    /// Returns the current pending entry.
    private PendingEntry requirePendingEntry() {
        PendingEntry entry = pendingEntry;
        if (entry == null) {
            throw new IllegalStateException("No TAR streaming entry is pending");
        }
        return entry;
    }

    /// Writes one real TAR entry, including any needed PAX metadata.
    private void writeEntry(
            PendingEntry entry,
            @Nullable ReadableByteChannel body,
            long bodySize
    ) throws IOException {
        if (bodySize < 0L) {
            throw new IllegalArgumentException("TAR entry body size must not be negative");
        }
        if (bodySize > 0L && body == null) {
            throw new IllegalArgumentException("TAR entry body channel is required for non-empty content");
        }
        entry.committed = true;
        byte typeFlag = 0;
        int defaultMode = 0;
        long size = 0L;
        String linkName = "";
        switch (entry.type) {
            case FILE -> {
                typeFlag = TarEntryAttributes.REGULAR_TYPE;
                defaultMode = DEFAULT_FILE_MODE;
                size = bodySize;
                linkName = "";
            }
            case DIRECTORY -> {
                typeFlag = TarEntryAttributes.DIRECTORY_TYPE;
                defaultMode = DEFAULT_DIRECTORY_MODE;
                size = 0L;
                linkName = "";
            }
            case SYMBOLIC_LINK -> {
                typeFlag = TarEntryAttributes.SYMBOLIC_LINK_TYPE;
                defaultMode = DEFAULT_SYMBOLIC_LINK_MODE;
                size = 0L;
                linkName = Objects.requireNonNull(entry.linkTarget, "linkTarget");
            }
            case HARD_LINK -> {
                typeFlag = TarEntryAttributes.HARD_LINK_TYPE;
                defaultMode = DEFAULT_HARD_LINK_MODE;
                size = 0L;
                linkName = Objects.requireNonNull(entry.linkTarget, "linkTarget");
            }
        }

        PendingTarEntryAttributeView attributes = entry.attributes;
        int mode = attributes.mode(defaultMode);
        long userId = attributes.userId();
        long groupId = attributes.groupId();
        long modificationTime = headerEpochSecond(attributes.lastModifiedTime());
        @Nullable String userName = attributes.userName();
        @Nullable String groupName = attributes.groupName();

        LinkedHashMap<String, String> paxRecords = new LinkedHashMap<>();
        @Nullable HeaderPathFields pathFields = headerPathFields(entry.path);
        if (pathFields == null) {
            paxRecords.put("path", entry.path);
            pathFields = new HeaderPathFields("arkivo-pax-entry", "");
        }

        String headerLinkName = linkName;
        if (!linkName.isEmpty() && utf8Length(linkName) > 100) {
            paxRecords.put("linkpath", linkName);
            headerLinkName = "";
        }

        if (attributes.lastModifiedTimeConfigured()) {
            paxRecords.put("mtime", paxTimestamp(attributes.lastModifiedTime()));
        }
        if (attributes.lastAccessTimeConfigured()) {
            paxRecords.put("atime", paxTimestamp(attributes.lastAccessTime()));
        }
        if (attributes.creationTimeConfigured()) {
            paxRecords.put("ctime", paxTimestamp(attributes.creationTime()));
        }

        long headerUserId = userId;
        if (attributes.userIdConfigured() && userId > maxOctalValue(8)) {
            paxRecords.put("uid", Long.toString(userId));
            headerUserId = 0L;
        }
        long headerGroupId = groupId;
        if (attributes.groupIdConfigured() && groupId > maxOctalValue(8)) {
            paxRecords.put("gid", Long.toString(groupId));
            headerGroupId = 0L;
        }

        String headerUserName = userName != null ? userName : "";
        if (userName != null && utf8Length(userName) > 32) {
            paxRecords.put("uname", userName);
            headerUserName = "";
        }
        String headerGroupName = groupName != null ? groupName : "";
        if (groupName != null && utf8Length(groupName) > 32) {
            paxRecords.put("gname", groupName);
            headerGroupName = "";
        }

        if (!paxRecords.isEmpty()) {
            writePaxHeader(paxRecords);
        }

        writeHeader(
                pathFields.name,
                pathFields.prefix,
                mode,
                headerUserId,
                headerGroupId,
                size,
                modificationTime,
                typeFlag,
                headerLinkName,
                headerUserName,
                headerGroupName
        );
        if (size > 0L) {
            writeBody(Objects.requireNonNull(body, "body"), size);
            writePadding(size);
        }
    }

    /// Closes retired staged bodies and their owning storage, preserving cleanup failures.
    private @Nullable IOException closeBodyStorage(@Nullable IOException failure) {
        var iterator = retiredBodies.iterator();
        while (iterator.hasNext()) {
            ArkivoStoredContent content = iterator.next();
            try {
                content.close();
                iterator.remove();
            } catch (IOException exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
        if (!bodyStorageClosed) {
            try {
                bodyStorage.close();
                bodyStorageClosed = true;
            } catch (IOException exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
        }
        return failure;
    }

    /// Releases staged content now or retains it for a close-time cleanup retry.
    private void releaseBody(ArkivoStoredContent content) {
        try {
            content.close();
        } catch (IOException exception) {
            retiredBodies.add(content);
        }
    }

    /// Writes one PAX extended header entry.
    private void writePaxHeader(Map<String, String> records) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> record : records.entrySet()) {
            body.write(paxRecord(record.getKey(), record.getValue()));
        }
        byte[] bodyBytes = body.toByteArray();
        writeHeader(
                "PaxHeaders/arkivo",
                "",
                DEFAULT_FILE_MODE,
                0L,
                0L,
                bodyBytes.length,
                0L,
                TarEntryAttributes.PAX_EXTENDED_HEADER_TYPE,
                "",
                "",
                ""
        );
        output.write(bodyBytes);
        writePadding(bodyBytes.length);
    }

    /// Writes one TAR header block.
    private void writeHeader(
            String name,
            String prefix,
            int mode,
            long userId,
            long groupId,
            long size,
            long modificationTime,
            byte typeFlag,
            String linkName,
            String userName,
            String groupName
    ) throws IOException {
        byte[] header = new byte[RECORD_SIZE];
        writeString(header, 0, 100, name, "entry name");
        writeOctal(header, 100, 8, mode, "entry mode");
        writeOctal(header, 108, 8, userId, "user id");
        writeOctal(header, 116, 8, groupId, "group id");
        writeOctal(header, 124, 12, size, "entry size");
        writeOctal(header, 136, 12, modificationTime, "modification time");
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = typeFlag;
        writeString(header, 157, 100, linkName, "link name");
        writeString(header, 257, 6, "ustar", "magic");
        writeRawString(header, 263, 2, "00");
        writeString(header, 265, 32, userName, "user name");
        writeString(header, 297, 32, groupName, "group name");
        writeString(header, 345, 155, prefix, "entry prefix");

        long checksum = 0L;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes padding bytes until the next TAR record boundary.
    private void writePadding(long size) throws IOException {
        int padding = (int) paddingSize(size);
        if (padding > 0) {
            output.write(new byte[padding]);
        }
    }

    /// Returns the padding size for a body with the given byte size.
    private static long paddingSize(long size) {
        return (RECORD_SIZE - size % RECORD_SIZE) % RECORD_SIZE;
    }

    /// Returns the epoch seconds that can be safely stored in the fixed-width TAR header.
    private static long headerEpochSecond(FileTime time) {
        long seconds = time.toInstant().getEpochSecond();
        if (seconds < 0L) {
            return 0L;
        }
        return Math.min(seconds, maxOctalValue(12));
    }

    /// Returns the maximum value that can be stored in a TAR octal field with the given byte length.
    private static long maxOctalValue(int length) {
        long value = 0L;
        for (int index = 0; index < length - 1; index++) {
            value = (value << 3) | 7L;
        }
        return value;
    }

    /// Returns an encoded PAX key-value record.
    private static byte[] paxRecord(String key, String value) {
        String payload = key + "=" + value + "\n";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int length = payloadBytes.length + 2;
        while (true) {
            int actualLength = decimalDigits(length) + 1 + payloadBytes.length;
            if (actualLength == length) {
                return (length + " " + payload).getBytes(StandardCharsets.UTF_8);
            }
            length = actualLength;
        }
    }

    /// Returns a decimal PAX timestamp value.
    private static String paxTimestamp(FileTime time) {
        Instant instant = time.toInstant();
        BigDecimal value = BigDecimal.valueOf(instant.getEpochSecond())
                .add(BigDecimal.valueOf(instant.getNano(), 9));
        return value.stripTrailingZeros().toPlainString();
    }

    /// Returns the number of decimal digits needed to represent the given positive value.
    private static int decimalDigits(int value) {
        int digits = 1;
        int remaining = value;
        while (remaining >= 10) {
            remaining /= 10;
            digits++;
        }
        return digits;
    }

    /// Writes an octal numeric field.
    private static void writeOctal(byte[] header, int offset, int length, long value, String fieldName) throws IOException {
        if (value < 0L) {
            throw new IOException("TAR " + fieldName + " must not be negative");
        }
        String text = Long.toOctalString(value);
        if (text.length() + 1 > length) {
            throw new IOException("TAR " + fieldName + " is too large");
        }
        int start = offset + length - text.length() - 1;
        for (int index = offset; index < start; index++) {
            header[index] = '0';
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, start, bytes.length);
    }

    /// Writes the TAR checksum field.
    private static void writeChecksum(byte[] header, long checksum) throws IOException {
        String text = Long.toOctalString(checksum);
        if (text.length() > 6) {
            throw new IOException("TAR header checksum is too large");
        }
        int start = 148 + 6 - text.length();
        for (int index = 148; index < start; index++) {
            header[index] = '0';
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, start, bytes.length);
        header[154] = 0;
        header[155] = ' ';
    }

    /// Writes a UTF-8 string field.
    private static void writeString(byte[] header, int offset, int length, String value, String fieldName)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > length) {
            throw new IOException("TAR " + fieldName + " is too long");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    /// Writes a raw US-ASCII string field.
    private static void writeRawString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != length) {
            throw new IllegalArgumentException("value length must match field length");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    /// Returns USTAR path fields for a path, or `null` when the path requires PAX metadata.
    private static @Nullable HeaderPathFields headerPathFields(String path) {
        if (utf8Length(path) <= 100) {
            return new HeaderPathFields(path, "");
        }

        int separator = path.lastIndexOf('/');
        while (separator > 0) {
            String prefix = path.substring(0, separator);
            String name = path.substring(separator + 1);
            if (!name.isEmpty() && utf8Length(name) <= 100 && utf8Length(prefix) <= 155) {
                return new HeaderPathFields(name, prefix);
            }
            separator = path.lastIndexOf('/', separator - 1);
        }
        return null;
    }

    /// Returns the UTF-8 encoded length for the given text.
    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /// Converts a logical entry path to normalized TAR path text.
    private static String entryPathText(String path, boolean directory) {
        Objects.requireNonNull(path, "path");
        String normalizedSeparators = path.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || normalizedSeparators.length() >= 2
                && normalizedSeparators.charAt(1) == ':') {
            throw new IllegalArgumentException("TAR streaming entry paths must be relative");
        }

        StringBuilder builder = new StringBuilder();
        int start = 0;
        while (start <= normalizedSeparators.length()) {
            int end = normalizedSeparators.indexOf('/', start);
            if (end < 0) {
                end = normalizedSeparators.length();
            }
            String name = normalizedSeparators.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IllegalArgumentException("TAR streaming entry paths must not contain ..");
                }
                if (!builder.isEmpty()) {
                    builder.append('/');
                }
                builder.append(name);
            }
            start = end + 1;
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("TAR streaming entry path is empty");
        }
        if (directory) {
            builder.append('/');
        }
        return builder.toString();
    }

    /// Converts a symbolic link target to TAR link target text.
    private static String linkTargetText(String target) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("TAR symbolic link target is empty");
        }
        return target;
    }

    /// Converts a hard link target to normalized TAR archive path text.
    private static String hardLinkTargetText(String target) {
        try {
            return entryPathText(target, false);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("TAR hard link target must be an archive-local path", exception);
        }
    }

    /// Ensures this writer is still open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("TAR streaming writer is closed");
        }
    }

    /// Stores USTAR path field values.
    ///
    /// @param name   the value to write into the USTAR name field
    /// @param prefix the value to write into the USTAR prefix field
    private record HeaderPathFields(
            String name,
            String prefix
    ) {
        /// Creates USTAR path field values.
        private HeaderPathFields {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(prefix, "prefix");
        }
    }

    /// Stores a pending TAR entry.
    private final class PendingEntry {
        /// The normalized logical entry path.
        private final String path;

        /// The requested entry type.
        private final EntryType type;

        /// The link target, or `null` for non-link entries.
        private final @Nullable String linkTarget;

        /// The pending basic metadata view.
        private final PendingTarEntryAttributeView attributes;

        /// The pending POSIX metadata view.
        private final PendingPosixEntryAttributeView posixAttributes;

        /// Whether this entry has already been committed.
        private boolean committed;

        /// Creates a pending TAR entry.
        private PendingEntry(String path, EntryType type, @Nullable String linkTarget) {
            this.path = Objects.requireNonNull(path, "path");
            this.type = Objects.requireNonNull(type, "type");
            this.linkTarget = linkTarget;
            this.attributes = new PendingTarEntryAttributeView(this);
            this.posixAttributes = new PendingPosixEntryAttributeView(this);
        }

        /// Ensures this entry can still be configured.
        private void ensurePending() {
            if (committed || pendingEntry != this) {
                throw new IllegalStateException("TAR streaming entry has already been committed");
            }
        }
    }

    /// Stores writable TAR entry metadata before a streaming entry is committed.
    @NotNullByDefault
    private final class PendingTarEntryAttributeView implements TarArkivoEntryAttributeView {
        /// The entry configured by this view.
        private final PendingEntry entry;

        /// The requested last modification time.
        private FileTime lastModifiedTime = UNIX_EPOCH;

        /// The requested last access time.
        private FileTime lastAccessTime = UNIX_EPOCH;

        /// The requested creation time.
        private FileTime creationTime = UNIX_EPOCH;

        /// Whether the last modification time was explicitly configured.
        private boolean lastModifiedTimeConfigured;

        /// Whether the last access time was explicitly configured.
        private boolean lastAccessTimeConfigured;

        /// Whether the creation time was explicitly configured.
        private boolean creationTimeConfigured;

        /// The requested POSIX mode bits, or `UNKNOWN_MODE` when defaults should be used.
        private int mode = UNKNOWN_MODE;

        /// The requested numeric user identifier.
        private long userId;

        /// The requested numeric group identifier.
        private long groupId;

        /// Whether the numeric user identifier was explicitly configured.
        private boolean userIdConfigured;

        /// Whether the numeric group identifier was explicitly configured.
        private boolean groupIdConfigured;

        /// The requested user name, or `null` when absent.
        private @Nullable String userName;

        /// The requested group name, or `null` when absent.
        private @Nullable String groupName;

        /// Creates a pending TAR entry attribute view.
        private PendingTarEntryAttributeView(PendingEntry entry) {
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "tar";
        }

        /// Reads the pending entry attributes.
        @Override
        public TarArkivoEntryAttributes readAttributes() {
            return new PendingTarEntryAttributes(entry, this);
        }

        /// Sets entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            entry.ensurePending();
            if (lastModifiedTime != null) {
                this.lastModifiedTime = lastModifiedTime;
                lastModifiedTimeConfigured = true;
            }
            if (lastAccessTime != null) {
                this.lastAccessTime = lastAccessTime;
                lastAccessTimeConfigured = true;
            }
            if (createTime != null) {
                this.creationTime = createTime;
                creationTimeConfigured = true;
            }
        }

        /// Returns the effective POSIX mode.
        private int mode(int defaultMode) {
            return mode != UNKNOWN_MODE ? mode : defaultMode;
        }

        /// Sets the numeric user identifier stored by the TAR header.
        @Override
        public void setUserId(long userId) {
            if (userId < 0L) {
                throw new IllegalArgumentException("userId must not be negative");
            }
            entry.ensurePending();
            this.userId = userId;
            userIdConfigured = true;
        }

        /// Sets the numeric group identifier stored by the TAR header.
        @Override
        public void setGroupId(long groupId) {
            if (groupId < 0L) {
                throw new IllegalArgumentException("groupId must not be negative");
            }
            entry.ensurePending();
            this.groupId = groupId;
            groupIdConfigured = true;
        }

        /// Sets the POSIX mode bits stored by the TAR header.
        @Override
        public void setMode(int mode) {
            if (mode < 0) {
                throw new IllegalArgumentException("mode must not be negative");
            }
            entry.ensurePending();
            this.mode = mode;
        }

        /// Sets the user name stored by the TAR header.
        @Override
        public void setUserName(@Nullable String userName) {
            entry.ensurePending();
            this.userName = userName;
        }

        /// Sets the group name stored by the TAR header.
        @Override
        public void setGroupName(@Nullable String groupName) {
            entry.ensurePending();
            this.groupName = groupName;
        }

        /// Returns the last modification time.
        private FileTime lastModifiedTime() {
            return lastModifiedTime;
        }

        /// Returns the last access time.
        private FileTime lastAccessTime() {
            return lastAccessTime;
        }

        /// Returns the creation time.
        private FileTime creationTime() {
            return creationTime;
        }

        /// Returns whether the last modification time was explicitly configured.
        private boolean lastModifiedTimeConfigured() {
            return lastModifiedTimeConfigured;
        }

        /// Returns whether the last access time was explicitly configured.
        private boolean lastAccessTimeConfigured() {
            return lastAccessTimeConfigured;
        }

        /// Returns whether the creation time was explicitly configured.
        private boolean creationTimeConfigured() {
            return creationTimeConfigured;
        }

        /// Returns the requested user name.
        private @Nullable String userName() {
            return userName;
        }

        /// Returns the requested group name.
        private @Nullable String groupName() {
            return groupName;
        }

        /// Returns the requested numeric user identifier.
        private long userId() {
            return userId;
        }

        /// Returns the requested numeric group identifier.
        private long groupId() {
            return groupId;
        }

        /// Returns whether the numeric user identifier was explicitly configured.
        private boolean userIdConfigured() {
            return userIdConfigured;
        }

        /// Returns whether the numeric group identifier was explicitly configured.
        private boolean groupIdConfigured() {
            return groupIdConfigured;
        }

        /// Returns the pending owner principal.
        private UserPrincipal owner() {
            return TarPosixSupport.owner(userName);
        }

        /// Sets the pending owner principal.
        private void setOwner(UserPrincipal owner) {
            entry.ensurePending();
            userName = Objects.requireNonNull(owner, "owner").getName();
        }

        /// Returns the pending group principal.
        private GroupPrincipal group() {
            return TarPosixSupport.group(groupName);
        }

        /// Sets the pending group principal.
        private void setGroup(GroupPrincipal group) {
            entry.ensurePending();
            groupName = Objects.requireNonNull(group, "group").getName();
        }
    }

    /// Stores writable POSIX entry metadata before a streaming entry is committed.
    @NotNullByDefault
    private final class PendingPosixEntryAttributeView implements PosixFileAttributeView {
        /// The entry configured by this view.
        private final PendingEntry entry;

        /// Creates a pending POSIX entry attribute view.
        private PendingPosixEntryAttributeView(PendingEntry entry) {
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads the pending POSIX entry attributes.
        @Override
        public PosixFileAttributes readAttributes() {
            return new PendingTarEntryAttributes(entry, entry.attributes);
        }

        /// Returns the pending owner.
        @Override
        public UserPrincipal getOwner() {
            return entry.attributes.owner();
        }

        /// Sets entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            entry.attributes.setTimes(lastModifiedTime, lastAccessTime, createTime);
        }

        /// Sets the pending owner.
        @Override
        public void setOwner(UserPrincipal owner) {
            entry.attributes.setOwner(owner);
        }

        /// Sets the pending group.
        @Override
        public void setGroup(GroupPrincipal group) {
            entry.attributes.setGroup(group);
        }

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            entry.attributes.setMode(PosixModes.permissionBits(permissions));
        }
    }

    /// Exposes a snapshot of pending TAR entry metadata.
    @NotNullByDefault
    private static final class PendingTarEntryAttributes implements PosixFileAttributes, TarArkivoEntryAttributes {
        /// The pending entry.
        private final PendingEntry entry;

        /// The pending metadata view.
        private final PendingTarEntryAttributeView attributes;

        /// Creates a pending TAR entry attribute snapshot.
        private PendingTarEntryAttributes(PendingEntry entry, PendingTarEntryAttributeView attributes) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.attributes = Objects.requireNonNull(attributes, "attributes");
        }

        /// Returns the decoded archive entry path.
        @Override
        public String path() {
            return entry.path;
        }

        /// Returns the raw TAR type flag byte.
        @Override
        public byte typeFlag() {
            return switch (entry.type) {
                case FILE -> TarEntryAttributes.REGULAR_TYPE;
                case DIRECTORY -> TarEntryAttributes.DIRECTORY_TYPE;
                case SYMBOLIC_LINK -> TarEntryAttributes.SYMBOLIC_LINK_TYPE;
                case HARD_LINK -> TarEntryAttributes.HARD_LINK_TYPE;
            };
        }

        /// Returns the POSIX mode bits stored by the TAR header.
        @Override
        public int mode() {
            int defaultMode = switch (entry.type) {
                case FILE -> DEFAULT_FILE_MODE;
                case DIRECTORY -> DEFAULT_DIRECTORY_MODE;
                case SYMBOLIC_LINK -> DEFAULT_SYMBOLIC_LINK_MODE;
                case HARD_LINK -> DEFAULT_HARD_LINK_MODE;
            };
            return attributes.mode(defaultMode);
        }

        /// Returns the numeric user identifier stored by the TAR header.
        @Override
        public long userId() {
            return attributes.userId();
        }

        /// Returns the numeric group identifier stored by the TAR header.
        @Override
        public long groupId() {
            return attributes.groupId();
        }

        /// Returns the user name stored by the TAR header.
        @Override
        public @Nullable String userName() {
            return attributes.userName();
        }

        /// Returns the group name stored by the TAR header.
        @Override
        public @Nullable String groupName() {
            return attributes.groupName();
        }

        /// Returns the link target stored by the TAR header.
        @Override
        public @Nullable String linkName() {
            return entry.linkTarget;
        }

        /// Returns the last modification time.
        @Override
        public FileTime lastModifiedTime() {
            return attributes.lastModifiedTime();
        }

        /// Returns the last access time.
        @Override
        public FileTime lastAccessTime() {
            return attributes.lastAccessTime();
        }

        /// Returns the creation time.
        @Override
        public FileTime creationTime() {
            return attributes.creationTime();
        }

        /// Returns whether this pending entry is a regular file.
        @Override
        public boolean isRegularFile() {
            return entry.type == EntryType.FILE || entry.type == EntryType.HARD_LINK;
        }

        /// Returns whether this pending entry is a directory.
        @Override
        public boolean isDirectory() {
            return entry.type == EntryType.DIRECTORY;
        }

        /// Returns whether this pending entry is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return entry.type == EntryType.SYMBOLIC_LINK;
        }

        /// Returns whether this pending entry is another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the pending entry size.
        @Override
        public long size() {
            return 0L;
        }

        /// Returns no stable file key for a pending streaming entry.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }

        /// Returns the pending owner.
        @Override
        public UserPrincipal owner() {
            return attributes.owner();
        }

        /// Returns the pending group.
        @Override
        public GroupPrincipal group() {
            return attributes.group();
        }

        /// Returns the pending permissions.
        @Override
        public Set<PosixFilePermission> permissions() {
            return PosixModes.permissions(mode());
        }
    }

    /// Stages one file body before committing its TAR header and body.
    private final class EntryBodyOutputStream extends OutputStream {
        /// The entry metadata to commit.
        private final PendingEntry entry;

        /// The stored body owned by this stream until commit finishes.
        private final ArkivoStoredContent content;

        /// The output stream opened over the stored body.
        private final OutputStream storageOutput;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether this stream has finished its commit attempt.
        private boolean finishedBody;

        /// Creates an entry body output stream.
        private EntryBodyOutputStream(PendingEntry entry) throws IOException {
            this.entry = entry;
            this.content = bodyStorage.createContent(entry.path, ArkivoEditStorage.UNKNOWN_SIZE);
            try {
                this.storageOutput = Channels.newOutputStream(content.openChannel(Set.of(
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )));
            } catch (IOException | RuntimeException | Error exception) {
                releaseBody(content);
                throw exception;
            }
        }

        /// Writes one body byte.
        @Override
        public void write(int value) throws IOException {
            ensureWritable();
            storageOutput.write(value);
        }

        /// Writes body bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureWritable();
            storageOutput.write(bytes, offset, length);
        }

        /// Flushes staged body bytes.
        @Override
        public void flush() throws IOException {
            ensureWritable();
            storageOutput.flush();
        }

        /// Closes this body stream and commits its entry.
        @Override
        public void close() throws IOException {
            if (finishedBody) {
                return;
            }
            closed = true;
            @Nullable IOException failure = null;
            try {
                storageOutput.close();
                long size = content.size();
                try (SeekableByteChannel input = content.openChannel(Set.of(StandardOpenOption.READ))) {
                    writeEntry(entry, input, size);
                }
            } catch (IOException exception) {
                failure = exception;
            } finally {
                finishedBody = true;
                releaseBody(content);
                if (currentBody == this) {
                    currentBody = null;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        /// Ensures this body stream can still accept bytes.
        private void ensureWritable() throws IOException {
            if (!open) {
                throw new IOException("TAR streaming writer is closed");
            }
            if (closed) {
                throw new IOException("TAR entry body stream is closed");
            }
        }
    }
}

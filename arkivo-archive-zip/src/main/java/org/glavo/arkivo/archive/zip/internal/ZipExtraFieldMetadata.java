// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/// Parses entry metadata carried by recognized ZIP extra fields.
@NotNullByDefault
final class ZipExtraFieldMetadata {
    /// The Info-ZIP extended timestamp extra field identifier.
    private static final int EXTENDED_TIMESTAMP_FIELD_ID = 0x5455;

    /// The Info-ZIP new Unix extra field identifier.
    private static final int NEW_UNIX_FIELD_ID = 0x7875;

    /// The extended timestamp flag indicating a modification time.
    private static final int MODIFY_TIME_FLAG = 1;

    /// The extended timestamp flag indicating an access time.
    private static final int ACCESS_TIME_FLAG = 1 << 1;

    /// The extended timestamp flag indicating a creation time.
    private static final int CREATION_TIME_FLAG = 1 << 2;

    /// Prevents instantiation.
    private ZipExtraFieldMetadata() {
    }

    /// Resolves recognized metadata from local and central-directory extra fields.
    ///
    /// A local extended timestamp field replaces the corresponding central-directory field because the central form
    /// can carry only the modification time. The new Unix field is defined only for local file headers.
    static EntryMetadata resolve(
            byte @Unmodifiable [] localExtraData,
            byte @Unmodifiable [] centralExtraData,
            FileTime dosFallback
    ) throws IOException {
        @Nullable ZipExtraFields.Field timestampField =
                ZipExtraFields.find(localExtraData, EXTENDED_TIMESTAMP_FIELD_ID);
        byte @Unmodifiable [] timestampSource = localExtraData;
        if (timestampField == null) {
            timestampField = ZipExtraFields.find(centralExtraData, EXTENDED_TIMESTAMP_FIELD_ID);
            timestampSource = centralExtraData;
        }

        @Nullable TimestampMetadata timestamps = timestampField != null
                ? parseExtendedTimestamp(timestampSource, timestampField.dataOffset(), timestampField.dataSize())
                : null;
        FileTime lastModifiedTime = timestamps != null && timestamps.lastModifiedTime() != null
                ? timestamps.lastModifiedTime()
                : dosFallback;
        FileTime lastAccessTime = timestamps != null && timestamps.lastAccessTime() != null
                ? timestamps.lastAccessTime()
                : lastModifiedTime;
        FileTime creationTime = timestamps != null && timestamps.creationTime() != null
                ? timestamps.creationTime()
                : lastModifiedTime;

        long userId = ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID;
        long groupId = ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID;
        @Nullable ZipExtraFields.Field unixField = ZipExtraFields.find(localExtraData, NEW_UNIX_FIELD_ID);
        if (unixField != null) {
            UnixIds unixIds = parseNewUnix(localExtraData, unixField.dataOffset(), unixField.dataSize());
            userId = unixIds.userId();
            groupId = unixIds.groupId();
        }
        return new EntryMetadata(lastModifiedTime, lastAccessTime, creationTime, userId, groupId);
    }

    /// Converts ZIP DOS date and time fields to a file time.
    static FileTime dosTime(int date, int time) {
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

    /// Parses an Info-ZIP extended timestamp field payload.
    private static TimestampMetadata parseExtendedTimestamp(
            byte @Unmodifiable [] extraData,
            int offset,
            int length
    ) throws IOException {
        if (length < 1) {
            throw new IOException("Info-ZIP extended timestamp extra field is empty");
        }

        int limit = offset + length;
        int flags = Byte.toUnsignedInt(extraData[offset++]);
        @Nullable FileTime lastModifiedTime = null;
        @Nullable FileTime lastAccessTime = null;
        @Nullable FileTime creationTime = null;
        if ((flags & MODIFY_TIME_FLAG) != 0 && offset + Integer.BYTES <= limit) {
            lastModifiedTime = unixTime(extraData, offset);
            offset += Integer.BYTES;
        }
        if ((flags & ACCESS_TIME_FLAG) != 0 && offset + Integer.BYTES <= limit) {
            lastAccessTime = unixTime(extraData, offset);
            offset += Integer.BYTES;
        }
        if ((flags & CREATION_TIME_FLAG) != 0 && offset + Integer.BYTES <= limit) {
            creationTime = unixTime(extraData, offset);
        }
        return new TimestampMetadata(lastModifiedTime, lastAccessTime, creationTime);
    }

    /// Converts one signed 32-bit Unix timestamp to a file time.
    private static FileTime unixTime(byte @Unmodifiable [] extraData, int offset) {
        return FileTime.from(Instant.ofEpochSecond(ZipLittleEndian.readInt(extraData, offset)));
    }

    /// Parses an Info-ZIP new Unix extra field payload.
    private static UnixIds parseNewUnix(
            byte @Unmodifiable [] extraData,
            int offset,
            int length
    ) throws IOException {
        if (length < 3) {
            throw new IOException("Info-ZIP new Unix extra field is too short");
        }

        int start = offset;
        offset++;
        int userIdSize = Byte.toUnsignedInt(extraData[offset++]);
        if (userIdSize + 3 > length) {
            throw new IOException("Info-ZIP new Unix user identifier does not fit in the extra field");
        }
        long userId = readUnsignedLong(extraData, offset, userIdSize, "user identifier");
        offset += userIdSize;

        int groupIdSize = Byte.toUnsignedInt(extraData[offset++]);
        if (offset - start + groupIdSize > length) {
            throw new IOException("Info-ZIP new Unix group identifier does not fit in the extra field");
        }
        long groupId = readUnsignedLong(extraData, offset, groupIdSize, "group identifier");
        return new UnixIds(userId, groupId);
    }

    /// Reads a variable-width little-endian unsigned integer that fits in a non-negative Java `long`.
    private static long readUnsignedLong(
            byte @Unmodifiable [] data,
            int offset,
            int length,
            String description
    ) throws IOException {
        for (int index = Long.BYTES; index < length; index++) {
            if (data[offset + index] != 0) {
                throw new IOException("Info-ZIP new Unix " + description + " is too large");
            }
        }

        int significantLength = Math.min(length, Long.BYTES);
        long value = 0L;
        for (int index = significantLength - 1; index >= 0; index--) {
            value = (value << Byte.SIZE) | Byte.toUnsignedLong(data[offset + index]);
        }
        if (value < 0) {
            throw new IOException("Info-ZIP new Unix " + description + " is too large");
        }
        return value;
    }

    /// Stores resolved ZIP entry metadata.
    ///
    /// @param lastModifiedTime the resolved last modification time
    /// @param lastAccessTime   the resolved last access time
    /// @param creationTime     the resolved creation time
    /// @param userId           the numeric Unix user identifier, or the unknown-value sentinel
    /// @param groupId          the numeric Unix group identifier, or the unknown-value sentinel
    @NotNullByDefault
    record EntryMetadata(
            FileTime lastModifiedTime,
            FileTime lastAccessTime,
            FileTime creationTime,
            long userId,
            long groupId
    ) {
    }

    /// Stores the optional timestamps parsed from one extended timestamp field.
    ///
    /// @param lastModifiedTime the parsed last modification time, or `null` when absent
    /// @param lastAccessTime   the parsed last access time, or `null` when absent
    /// @param creationTime     the parsed creation time, or `null` when absent
    @NotNullByDefault
    private record TimestampMetadata(
            @Nullable FileTime lastModifiedTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime creationTime
    ) {
    }

    /// Stores numeric Unix owner identifiers parsed from one new Unix field.
    ///
    /// @param userId  the numeric Unix user identifier
    /// @param groupId the numeric Unix group identifier
    @NotNullByDefault
    private record UnixIds(long userId, long groupId) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.internal.ByteArrayAccess;
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
    /// The extended timestamp flag indicating a modification time.
    private static final int MODIFY_TIME_FLAG = 1;

    /// The extended timestamp flag indicating an access time.
    private static final int ACCESS_TIME_FLAG = 1 << 1;

    /// The extended timestamp flag indicating a creation time.
    private static final int CREATION_TIME_FLAG = 1 << 2;

    /// The number of 100-nanosecond FILETIME ticks in one second.
    private static final long FILETIME_TICKS_PER_SECOND = 10_000_000L;

    /// The number of seconds from the Windows epoch to the Unix epoch.
    private static final long WINDOWS_EPOCH_OFFSET = 11_644_473_600L;

    /// Prevents instantiation.
    private ZipExtraFieldMetadata() {
    }

    /// Resolves recognized metadata from local and central-directory extra fields.
    ///
    /// A local extended timestamp field replaces the corresponding central-directory field because the central form
    /// can carry only the modification time. NTFS timestamps take precedence over extended Unix timestamps, followed
    /// by the older Unix fields and the DOS fallback. The new Unix ID field is defined only for local file headers.
    static EntryMetadata resolve(
            byte @Unmodifiable [] localExtraData,
            byte @Unmodifiable [] centralExtraData,
            FileTime dosFallback
    ) throws IOException {
        @Nullable ZipExtraFields.Field timestampField =
                ZipExtraFields.find(localExtraData, ZipConstants.EXTENDED_TIMESTAMP_EXTRA_FIELD_ID);
        byte @Unmodifiable [] timestampSource = localExtraData;
        if (timestampField == null) {
            timestampField = ZipExtraFields.find(centralExtraData, ZipConstants.EXTENDED_TIMESTAMP_EXTRA_FIELD_ID);
            timestampSource = centralExtraData;
        }

        @Nullable TimestampMetadata timestamps = timestampField != null
                ? parseExtendedTimestamp(timestampSource, timestampField.dataOffset(), timestampField.dataSize())
                : null;
        // Merge complete fields in increasing precision, keeping absent timestamps from lower-priority fields.
        @Nullable TimestampMetadata legacy = readTimestampField(localExtraData, centralExtraData,
                ZipConstants.INFO_ZIP_UNIX_EXTRA_FIELD_ID);
        legacy = mergeTimestamps(readTimestampField(localExtraData, centralExtraData,
                ZipConstants.UNIX_EXTRA_FIELD_ID), legacy);
        timestamps = mergeTimestamps(timestamps, legacy);
        timestamps = mergeTimestamps(readTimestampField(localExtraData, centralExtraData,
                ZipConstants.NTFS_EXTRA_FIELD_ID), timestamps);
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
        @Nullable ZipExtraFields.Field unixField = ZipExtraFields.find(localExtraData, ZipConstants.NEW_UNIX_EXTRA_FIELD_ID);
        if (unixField != null) {
            UnixIds unixIds = parseNewUnix(localExtraData, unixField.dataOffset(), unixField.dataSize());
            userId = unixIds.userId();
            groupId = unixIds.groupId();
        }
        return new EntryMetadata(lastModifiedTime, lastAccessTime, creationTime, userId, groupId);
    }

    /// Resolves one timestamp field, preferring its local-header form over its central-directory form.
    private static @Nullable TimestampMetadata readTimestampField(
            byte @Unmodifiable [] local, byte @Unmodifiable [] central, int id
    ) throws IOException {
        @Nullable ZipExtraFields.Field field = ZipExtraFields.find(local, id);
        byte @Unmodifiable [] source = local;
        if (field == null) {
            field = ZipExtraFields.find(central, id);
            source = central;
        }
        if (field == null) {
            return null;
        }
        if (id == ZipConstants.NTFS_EXTRA_FIELD_ID) {
            return parseNtfsTimestamps(source, field.dataOffset(), field.dataSize());
        }
        if (field.dataSize() < 8) {
            throw new IOException("ZIP Unix timestamp extra field is too short");
        }
        int offset = field.dataOffset();
        return new TimestampMetadata(
                FileTime.from(Instant.ofEpochSecond(Integer.toUnsignedLong(ZipLittleEndian.readInt(source, offset + 4)))),
                FileTime.from(Instant.ofEpochSecond(Integer.toUnsignedLong(ZipLittleEndian.readInt(source, offset)))),
                null);
    }

    /// Fills missing high-priority timestamps from another field.
    private static @Nullable TimestampMetadata mergeTimestamps(
            @Nullable TimestampMetadata preferred, @Nullable TimestampMetadata fallback
    ) {
        if (preferred == null) {
            return fallback;
        }
        if (fallback == null) {
            return preferred;
        }
        return new TimestampMetadata(
                preferred.lastModifiedTime() != null ? preferred.lastModifiedTime() : fallback.lastModifiedTime(),
                preferred.lastAccessTime() != null ? preferred.lastAccessTime() : fallback.lastAccessTime(),
                preferred.creationTime() != null ? preferred.creationTime() : fallback.creationTime());
    }

    /// Reads the first NTFS time attribute and validates all nested attribute boundaries.
    private static @Nullable TimestampMetadata parseNtfsTimestamps(
            byte @Unmodifiable [] data, int offset, int length
    ) throws IOException {
        if (length < Integer.BYTES) {
            throw new IOException("ZIP NTFS extra field is too short");
        }
        int end = offset + length;
        offset += Integer.BYTES;
        @Nullable TimestampMetadata timestamps = null;
        while (offset < end) {
            if (end - offset < 4) {
                throw new IOException("Truncated ZIP NTFS attribute header");
            }
            int tag = ZipLittleEndian.readUnsignedShort(data, offset);
            int size = ZipLittleEndian.readUnsignedShort(data, offset + 2);
            offset += 4;
            if (size > end - offset) {
                throw new IOException("Truncated ZIP NTFS attribute");
            }
            if (tag == 1) {
                if (size != 3 * Long.BYTES) {
                    throw new IOException("Invalid ZIP NTFS timestamp attribute size");
                }
                if (timestamps == null) {
                    timestamps = new TimestampMetadata(fileTime(data, offset), fileTime(data, offset + 8),
                            fileTime(data, offset + 16));
                }
            }
            offset += size;
        }
        return timestamps;
    }

    /// Converts an unsigned FILETIME value without discarding submillisecond precision; zero denotes an absent time.
    private static @Nullable FileTime fileTime(byte @Unmodifiable [] data, int offset) {
        long ticks = ByteArrayAccess.readLongLittleEndian(data, offset);
        if (ticks == 0) {
            return null;
        }
        long seconds = Long.divideUnsigned(ticks, FILETIME_TICKS_PER_SECOND) - WINDOWS_EPOCH_OFFSET;
        long nanos = Long.remainderUnsigned(ticks, FILETIME_TICKS_PER_SECOND) * 100L;
        return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
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

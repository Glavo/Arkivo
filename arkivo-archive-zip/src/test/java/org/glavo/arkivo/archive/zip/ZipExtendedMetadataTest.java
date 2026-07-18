// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP timestamps and Unix owner identifiers carried by recognized extra fields.
@NotNullByDefault
final class ZipExtendedMetadataTest {
    /// The local file header signature.
    private static final long LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L;

    /// The central directory file header signature.
    private static final long CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50L;

    /// The end of central directory signature.
    private static final long END_SIGNATURE = 0x06054b50L;

    /// The Info-ZIP extended timestamp extra field identifier.
    private static final int EXTENDED_TIMESTAMP_FIELD_ID = 0x5455;

    /// The Info-ZIP new Unix extra field identifier.
    private static final int NEW_UNIX_FIELD_ID = 0x7875;

    /// The fixed entry name used by the generated archives.
    private static final byte @Unmodifiable [] ENTRY_NAME = "entry".getBytes(StandardCharsets.UTF_8);

    /// The fixed DOS date used by the generated archives.
    private static final int DOS_DATE = dosDate(2024, 3, 5);

    /// The fixed DOS time used by the generated archives.
    private static final int DOS_TIME = dosTime(6, 7, 8);

    /// Verifies signed extended timestamps and variable-width Unix identifiers in both reader modes.
    @Test
    void readsExtendedTimestampsAndUnixIds() throws IOException {
        byte @Unmodifiable [] localExtraData = concatenate(
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(7, -1, Integer.MIN_VALUE, Integer.MAX_VALUE)),
                extraField(NEW_UNIX_FIELD_ID, newUnix(0xffff_fffeL, 555_555L))
        );
        byte @Unmodifiable [] centralExtraData =
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(7, 42));
        byte @Unmodifiable [] archive = storedArchive(localExtraData, centralExtraData);

        ExpectedMetadata expected = new ExpectedMetadata(
                unixTime(-1),
                unixTime(Integer.MIN_VALUE),
                unixTime(Integer.MAX_VALUE),
                0xffff_fffeL,
                555_555L
        );
        assertMetadata(expected, readSeekableAttributes(archive));
        assertMetadata(expected, readStreamingAttributes(archive));

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(new SeekableInMemoryByteChannel(archive))) {
            Map<String, Object> namedAttributes = Files.readAttributes(
                    fileSystem.getPath("/entry"),
                    "zip:userId,groupId"
            );
            assertEquals(expected.userId(), namedAttributes.get("userId"));
            assertEquals(expected.groupId(), namedAttributes.get("groupId"));
        }
    }

    /// Verifies a local timestamp record replaces rather than field-wise merges with the central record.
    @Test
    void localExtendedTimestampReplacesCentralTimestamp() throws IOException {
        byte @Unmodifiable [] localExtraData =
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(2, 444));
        byte @Unmodifiable [] centralExtraData =
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(1, 333));
        byte @Unmodifiable [] archive = storedArchive(localExtraData, centralExtraData);
        FileTime dosTime = readDosFallback();
        ExpectedMetadata expected = new ExpectedMetadata(
                dosTime,
                unixTime(444),
                dosTime,
                ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID,
                ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID
        );

        assertMetadata(expected, readSeekableAttributes(archive));
        assertMetadata(expected, readStreamingAttributes(archive));
    }

    /// Verifies a central-only extended timestamp is available only to the seekable reader.
    @Test
    void seekableReaderUsesCentralExtendedTimestamp() throws IOException {
        byte @Unmodifiable [] archive = storedArchive(
                new byte[0],
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(1, 123_456))
        );
        ZipArkivoEntryAttributes seekable = readSeekableAttributes(archive);
        ZipArkivoEntryAttributes streaming = readStreamingAttributes(archive);

        assertEquals(unixTime(123_456), seekable.lastModifiedTime());
        assertEquals(streaming.lastModifiedTime(), streaming.lastAccessTime());
        assertEquals(streaming.lastModifiedTime(), streaming.creationTime());
        assertEquals(readDosFallback(), streaming.lastModifiedTime());
    }

    /// Verifies flagged timestamps without enough payload bytes are ignored as specified by Commons Compress.
    @Test
    void ignoresFlaggedTimestampsMissingFromPayload() throws IOException {
        byte @Unmodifiable [] archive = storedArchive(
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(7, 777)),
                new byte[0]
        );
        ExpectedMetadata expected = new ExpectedMetadata(
                unixTime(777),
                unixTime(777),
                unixTime(777),
                ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID,
                ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID
        );

        assertMetadata(expected, readSeekableAttributes(archive));
        assertMetadata(expected, readStreamingAttributes(archive));
    }

    /// Verifies an empty recognized extended timestamp payload is rejected in both reader modes.
    @Test
    void rejectsEmptyExtendedTimestampPayload() {
        byte @Unmodifiable [] archive =
                storedArchive(extraField(EXTENDED_TIMESTAMP_FIELD_ID, new byte[0]), new byte[0]);

        assertThrows(IOException.class, () -> readSeekableAttributes(archive));
        assertThrows(IOException.class, () -> readStreamingAttributes(archive));
    }

    /// Verifies malformed new Unix identifier lengths are rejected in both reader modes.
    @Test
    void rejectsInvalidNewUnixIdentifierLength() {
        byte @Unmodifiable [] malformed = new byte[]{1, 4, 1, 2, 3};
        byte @Unmodifiable [] archive = storedArchive(extraField(NEW_UNIX_FIELD_ID, malformed), new byte[0]);

        assertThrows(IOException.class, () -> readSeekableAttributes(archive));
        assertThrows(IOException.class, () -> readStreamingAttributes(archive));
    }

    /// Verifies the full non-negative Java `long` range accepted by Commons Compress is preserved.
    @Test
    void readsMaximumLongUnixIdentifiers() throws IOException {
        byte @Unmodifiable [] archive = storedArchive(
                extraField(NEW_UNIX_FIELD_ID, newUnix(Long.MAX_VALUE - 1, Long.MAX_VALUE)),
                new byte[0]
        );

        ExpectedMetadata expected = new ExpectedMetadata(
                readDosFallback(),
                readDosFallback(),
                readDosFallback(),
                Long.MAX_VALUE - 1,
                Long.MAX_VALUE
        );
        assertMetadata(expected, readSeekableAttributes(archive));
        assertMetadata(expected, readStreamingAttributes(archive));
    }

    /// Verifies a spec-permitted identifier wider than a non-negative Java `long` is rejected explicitly.
    @Test
    void rejectsUnixIdentifierTooLargeForLong() {
        byte @Unmodifiable [] tooLarge =
                new byte[]{1, 8, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80, 1, 0};
        byte @Unmodifiable [] archive = storedArchive(extraField(NEW_UNIX_FIELD_ID, tooLarge), new byte[0]);

        assertThrows(IOException.class, () -> readSeekableAttributes(archive));
        assertThrows(IOException.class, () -> readStreamingAttributes(archive));
    }

    /// Reads attributes for the generated entry through the seekable file-system API.
    private static ZipArkivoEntryAttributes readSeekableAttributes(
            byte @Unmodifiable [] archive
    ) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(new SeekableInMemoryByteChannel(archive))) {
            return Files.readAttributes(fileSystem.getPath("/entry"), ZipArkivoEntryAttributes.class);
        }
    }

    /// Reads attributes for the generated entry through the forward-only streaming API.
    private static ZipArkivoEntryAttributes readStreamingAttributes(
            byte @Unmodifiable [] archive
    ) throws IOException {
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            return reader.readAttributes(ZipArkivoEntryAttributes.class);
        }
    }

    /// Reads the DOS fallback produced by the streaming reader for an archive without extended timestamp fields.
    private static FileTime readDosFallback() throws IOException {
        return readStreamingAttributes(storedArchive(new byte[0], new byte[0])).lastModifiedTime();
    }

    /// Asserts all resolved metadata exposed by one ZIP entry.
    private static void assertMetadata(ExpectedMetadata expected, ZipArkivoEntryAttributes actual) {
        assertEquals(expected.lastModifiedTime(), actual.lastModifiedTime());
        assertEquals(expected.lastAccessTime(), actual.lastAccessTime());
        assertEquals(expected.creationTime(), actual.creationTime());
        assertEquals(expected.userId(), actual.userId());
        assertEquals(expected.groupId(), actual.groupId());
        assertEquals(expected.userId() == ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID
                        ? "owner"
                        : Long.toString(expected.userId()),
                actual.owner().getName());
        assertEquals(expected.groupId() == ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID
                        ? "group"
                        : Long.toString(expected.groupId()),
                actual.group().getName());
    }

    /// Builds one empty stored ZIP entry with independently configured local and central extra data.
    private static byte @Unmodifiable [] storedArchive(
            byte @Unmodifiable [] localExtraData,
            byte @Unmodifiable [] centralExtraData
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, DOS_TIME);
        writeShort(output, DOS_DATE);
        writeInt(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        writeShort(output, ENTRY_NAME.length);
        writeShort(output, localExtraData.length);
        output.writeBytes(ENTRY_NAME);
        output.writeBytes(localExtraData);

        int centralDirectoryOffset = output.size();
        writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 20);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, DOS_TIME);
        writeShort(output, DOS_DATE);
        writeInt(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        writeShort(output, ENTRY_NAME.length);
        writeShort(output, centralExtraData.length);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, 0);
        writeInt(output, 0);
        output.writeBytes(ENTRY_NAME);
        output.writeBytes(centralExtraData);
        int centralDirectorySize = output.size() - centralDirectoryOffset;

        writeInt(output, END_SIGNATURE);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 1);
        writeShort(output, 1);
        writeInt(output, centralDirectorySize);
        writeInt(output, centralDirectoryOffset);
        writeShort(output, 0);
        return output.toByteArray();
    }

    /// Encodes one complete ZIP extra field record.
    private static byte @Unmodifiable [] extraField(int identifier, byte @Unmodifiable [] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeShort(output, identifier);
        writeShort(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    /// Encodes an Info-ZIP extended timestamp payload.
    private static byte @Unmodifiable [] extendedTimestamp(
            int flags,
            int @Unmodifiable ... seconds
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(flags);
        for (int value : seconds) {
            writeInt(output, Integer.toUnsignedLong(value));
        }
        return output.toByteArray();
    }

    /// Encodes an Info-ZIP new Unix payload using the shortest non-empty little-endian integers.
    private static byte @Unmodifiable [] newUnix(long userId, long groupId) {
        byte @Unmodifiable [] userIdBytes = unsignedLittleEndian(userId);
        byte @Unmodifiable [] groupIdBytes = unsignedLittleEndian(groupId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(1);
        output.write(userIdBytes.length);
        output.writeBytes(userIdBytes);
        output.write(groupIdBytes.length);
        output.writeBytes(groupIdBytes);
        return output.toByteArray();
    }

    /// Encodes a non-negative integer in shortest-form little-endian byte order.
    private static byte @Unmodifiable [] unsignedLittleEndian(long value) {
        int length = 1;
        long remaining = value >>> Byte.SIZE;
        while (remaining != 0) {
            length++;
            remaining >>>= Byte.SIZE;
        }
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (value >>> (index * Byte.SIZE));
        }
        return bytes;
    }

    /// Concatenates generated extra field records.
    private static byte @Unmodifiable [] concatenate(
            byte @Unmodifiable [] @Unmodifiable ... values
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte @Unmodifiable [] value : values) {
            output.writeBytes(value);
        }
        return output.toByteArray();
    }

    /// Converts signed Unix seconds to a file time.
    private static FileTime unixTime(long seconds) {
        return FileTime.from(Instant.ofEpochSecond(seconds));
    }

    /// Encodes one ZIP DOS date value.
    private static int dosDate(int year, int month, int day) {
        return (year - 1980) << 9 | month << 5 | day;
    }

    /// Encodes one ZIP DOS time value.
    private static int dosTime(int hour, int minute, int second) {
        return hour << 11 | minute << 5 | second / 2;
    }

    /// Writes a little-endian unsigned 16-bit value.
    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> Byte.SIZE);
    }

    /// Writes a little-endian unsigned 32-bit value.
    private static void writeInt(ByteArrayOutputStream output, long value) {
        writeShort(output, (int) value);
        writeShort(output, (int) (value >>> Short.SIZE));
    }

    /// Stores expected metadata for one generated ZIP entry.
    ///
    /// @param lastModifiedTime the expected last modification time
    /// @param lastAccessTime   the expected last access time
    /// @param creationTime     the expected creation time
    /// @param userId           the expected numeric Unix user identifier
    /// @param groupId          the expected numeric Unix group identifier
    @NotNullByDefault
    private record ExpectedMetadata(
            FileTime lastModifiedTime,
            FileTime lastAccessTime,
            FileTime creationTime,
            long userId,
            long groupId
    ) {
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies direct parsing and fallback contracts for recognized ZIP metadata extra fields.
@NotNullByDefault
final class ZipExtraFieldMetadataTest {
    /// The Info-ZIP extended timestamp extra field identifier.
    private static final int EXTENDED_TIMESTAMP_FIELD_ID = 0x5455;

    /// The Info-ZIP new Unix extra field identifier.
    private static final int NEW_UNIX_FIELD_ID = 0x7875;

    /// Writes all unsigned FILETIME bits and truncates only the sub-100-nanosecond fraction.
    @Test
    void updatesNtfsModificationTimesAtRepresentationBoundaries() throws IOException {
        for (long ticks : new long[]{1L, 116_444_736_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            long seconds = Long.divideUnsigned(ticks, 10_000_000L) - 11_644_473_600L;
            int nanos = (int) (Long.remainderUnsigned(ticks, 10_000_000L) * 100L);
            FileTime time = FileTime.from(Instant.ofEpochSecond(seconds, nanos + 99));
            byte[] input = ntfsField(1, 2, 3);
            byte[] updated = ZipExtraFieldMetadata.withLastModifiedTime(input, time);
            assertArrayEquals(ntfsField(ticks, 2, 3), updated);
            assertArrayEquals(ntfsField(1, 2, 3), input);
        }
        byte[] input = ntfsField(1, 2, 3);
        for (Instant time : new Instant[]{Instant.MIN, Instant.MAX, Instant.parse("1601-01-01T00:00:00Z"),
                Instant.ofEpochSecond(Long.divideUnsigned(-1L, 10_000_000L) - 11_644_473_600L,
                        Long.remainderUnsigned(-1L, 10_000_000L) * 100L + 100L)}) {
            assertThrows(IOException.class, () -> ZipExtraFieldMetadata.withLastModifiedTime(input, FileTime.from(time)));
            assertArrayEquals(ntfsField(1, 2, 3), input);
        }
    }

    /// Retains unknown NTFS attributes, duplicate timestamp records, and the original input buffer.
    @Test
    void updatesDuplicateTimesWithoutDroppingUnknownMetadata() throws IOException {
        byte[] timestamp = ntfsField(1, 2, 3);
        byte[] payload = concatenate(new byte[4], new byte[]{2, 0, 3, 0, 4, 5, 6},
                java.util.Arrays.copyOfRange(timestamp, 8, timestamp.length));
        byte[] unknown = extraField(0xcafe, new byte[]{8, 9});
        byte[] input = concatenate(extraField(0x000a, payload), timestamp, unknown, new byte[3]);
        byte[] original = input.clone();
        byte[] expected = input.clone();
        ByteArrayAccess.writeLongLittleEndian(expected, 19, 116_444_736_000_000_000L);
        ByteArrayAccess.writeLongLittleEndian(expected, 4 + payload.length + 12, 116_444_736_000_000_000L);
        assertArrayEquals(expected, ZipExtraFieldMetadata.withLastModifiedTime(input, FileTime.fromMillis(0)));
        assertArrayEquals(original, input);
    }

    /// Checks the signed extended and unsigned legacy Unix boundaries without partially modifying the input.
    @Test
    void validatesUnixModificationTimeRanges() throws IOException {
        for (int id : new int[]{EXTENDED_TIMESTAMP_FIELD_ID, ZipConstants.UNIX_EXTRA_FIELD_ID,
                ZipConstants.INFO_ZIP_UNIX_EXTRA_FIELD_ID}) {
            byte[] payload = new byte[id == EXTENDED_TIMESTAMP_FIELD_ID ? 5 : 8];
            if (id == EXTENDED_TIMESTAMP_FIELD_ID) {
                payload[0] = 1;
            }
            byte[] input = extraField(id, payload);
            for (long seconds : new long[]{Integer.MIN_VALUE - 1L, Integer.MIN_VALUE, -1L, 0L,
                    Integer.MAX_VALUE, Integer.MAX_VALUE + 1L, 0xffff_ffffL, 0x1_0000_0000L}) {
                FileTime time = FileTime.from(Instant.ofEpochSecond(seconds, 999_999_999));
                boolean supported = id == EXTENDED_TIMESTAMP_FIELD_ID
                        ? seconds >= Integer.MIN_VALUE && seconds <= Integer.MAX_VALUE
                        : seconds >= 0 && seconds <= 0xffff_ffffL;
                if (supported) {
                    byte[] updated = ZipExtraFieldMetadata.withLastModifiedTime(input, time);
                    var metadata = ZipExtraFieldMetadata.resolve(updated, new byte[0], FileTime.fromMillis(0));
                    assertEquals(Instant.ofEpochSecond(seconds), metadata.lastModifiedTime().toInstant());
                } else {
                    assertThrows(IOException.class, () -> ZipExtraFieldMetadata.withLastModifiedTime(input, time));
                }
                assertArrayEquals(extraField(id, payload), input);
            }
        }
    }

    /// Leaves absent modification fields untouched and rejects incomplete recognized timestamp structures.
    @Test
    void preservesAbsentTimesAndRejectsMalformedUpdates() throws IOException {
        for (byte[] input : new byte[][]{new byte[0], new byte[3], extraField(EXTENDED_TIMESTAMP_FIELD_ID,
                new byte[]{2, 42, 0, 0, 0}), extraField(0x000a, new byte[4]),
                new byte[]{(byte) 0xfe, (byte) 0xca, 9, 0, 1}}) {
            assertArrayEquals(input, ZipExtraFieldMetadata.withLastModifiedTime(input, FileTime.fromMillis(0)));
        }
        for (byte[] input : new byte[][]{extraField(EXTENDED_TIMESTAMP_FIELD_ID, new byte[0]),
                extraField(0x000a, new byte[3]), extraField(0x000a, new byte[5]),
                extraField(0x000a, new byte[]{0, 0, 0, 0, 1, 0, 1, 0, 0}),
                extraField(ZipConstants.UNIX_EXTRA_FIELD_ID, new byte[7])}) {
            byte[] original = input.clone();
            assertThrows(IOException.class, () -> ZipExtraFieldMetadata.withLastModifiedTime(input, FileTime.fromMillis(0)));
            assertArrayEquals(original, input);
        }
    }

    /// Uses the DOS time and unknown Unix identifiers when no recognized metadata is present.
    @Test
    void resolvesEmptyMetadataToFallbackValues() throws IOException {
        FileTime fallback = FileTime.fromMillis(12_345L);

        ZipExtraFieldMetadata.EntryMetadata metadata = ZipExtraFieldMetadata.resolve(
                new byte[0],
                new byte[0],
                fallback
        );

        assertSame(fallback, metadata.lastModifiedTime());
        assertSame(fallback, metadata.lastAccessTime());
        assertSame(fallback, metadata.creationTime());
        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, metadata.userId());
        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, metadata.groupId());
    }

    /// Ignores a new Unix field in the central directory because the format defines it only for local headers.
    @Test
    void ignoresCentralDirectoryUnixIdentifiers() throws IOException {
        byte @Unmodifiable [] centralExtraData = extraField(
                NEW_UNIX_FIELD_ID,
                new byte[]{1, 1, 42, 1, 43}
        );

        ZipExtraFieldMetadata.EntryMetadata metadata = ZipExtraFieldMetadata.resolve(
                new byte[0],
                centralExtraData,
                FileTime.fromMillis(0L)
        );

        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, metadata.userId());
        assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, metadata.groupId());
    }

    /// Accepts identifiers wider than eight bytes when their excess most-significant bytes are zero.
    @Test
    void readsZeroExtendedUnixIdentifiers() throws IOException {
        byte @Unmodifiable [] payload = {
                1,
                10,
                0x34, 0x12, 0, 0, 0, 0, 0, 0, 0, 0,
                2,
                0x78, 0x56
        };

        ZipExtraFieldMetadata.EntryMetadata metadata = ZipExtraFieldMetadata.resolve(
                extraField(NEW_UNIX_FIELD_ID, payload),
                new byte[0],
                FileTime.fromMillis(0L)
        );

        assertEquals(0x1234L, metadata.userId());
        assertEquals(0x5678L, metadata.groupId());
    }

    /// Distinguishes a short payload from independently truncated user and group identifiers.
    @Test
    void rejectsTruncatedUnixIdentifierPayloads() {
        assertUnixFailure(
                new byte[]{1, 0},
                "Info-ZIP new Unix extra field is too short"
        );
        assertUnixFailure(
                new byte[]{1, 4, 1, 2, 3},
                "Info-ZIP new Unix user identifier does not fit in the extra field"
        );
        assertUnixFailure(
                new byte[]{1, 1, 9, 2, 7},
                "Info-ZIP new Unix group identifier does not fit in the extra field"
        );
    }

    /// Rejects both user and group identifiers whose unsigned values exceed a non-negative Java long.
    @Test
    void rejectsUnixIdentifiersAboveLongMaximum() {
        assertUnixFailure(
                new byte[]{1, 8, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80, 0},
                "Info-ZIP new Unix user identifier is too large"
        );
        assertUnixFailure(
                new byte[]{1, 1, 0, 8, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80},
                "Info-ZIP new Unix group identifier is too large"
        );
    }

    /// Uses the first complete field of each recognized type when duplicate records are present.
    @Test
    void usesFirstRecognizedDuplicateField() throws IOException {
        byte @Unmodifiable [] localExtraData = concatenate(
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(111)),
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(222)),
                extraField(NEW_UNIX_FIELD_ID, new byte[]{1, 1, 3, 1, 4}),
                extraField(NEW_UNIX_FIELD_ID, new byte[]{1, 1, 5, 1, 6})
        );

        ZipExtraFieldMetadata.EntryMetadata metadata = ZipExtraFieldMetadata.resolve(
                localExtraData,
                new byte[0],
                FileTime.fromMillis(0L)
        );

        assertEquals(FileTime.from(Instant.ofEpochSecond(111L)), metadata.lastModifiedTime());
        assertEquals(3L, metadata.userId());
        assertEquals(4L, metadata.groupId());
    }

    /// Preserves 100-nanosecond ticks across the full unsigned FILETIME range.
    @Test
    void preservesNtfsPrecisionAndUnsignedRange() throws IOException {
        for (long ticks : new long[]{1, 116_444_736_000_000_001L, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            var expected = FileTime.from(Instant.ofEpochSecond(
                    Long.divideUnsigned(ticks, 10_000_000L) - 11_644_473_600L,
                    Long.remainderUnsigned(ticks, 10_000_000L) * 100L));
            var metadata = ZipExtraFieldMetadata.resolve(ntfsField(ticks, ticks, ticks), new byte[0],
                    FileTime.fromMillis(0));
            assertEquals(expected, metadata.lastModifiedTime());
            assertEquals(expected, metadata.lastAccessTime());
            assertEquals(expected, metadata.creationTime());
        }
    }

    /// Uses local NTFS values, falls back from absent values, and gives NTFS precision priority over Unix seconds.
    @Test
    void resolvesNtfsAndUnixPrecedence() throws IOException {
        byte[] local = concatenate(ntfsField(116_444_736_000_000_001L, 0, 0),
                extraField(EXTENDED_TIMESTAMP_FIELD_ID, extendedTimestamp(123)));
        var metadata = ZipExtraFieldMetadata.resolve(local,
                ntfsField(116_444_737_000_000_000L, 0, 0), FileTime.fromMillis(0));
        assertEquals(Instant.ofEpochSecond(0, 100), metadata.lastModifiedTime().toInstant());
        assertEquals(metadata.lastModifiedTime(), metadata.lastAccessTime());
        assertEquals(metadata.lastModifiedTime(), metadata.creationTime());
        var centralOnly = ZipExtraFieldMetadata.resolve(new byte[0],
                ntfsField(116_444_736_000_000_001L, 0, 0), FileTime.fromMillis(0));
        assertEquals(metadata.lastModifiedTime(), centralOnly.lastModifiedTime());
    }

    /// Rejects truncated reserved data, nested headers, timestamp bodies, and trailing attributes.
    @Test
    void rejectsMalformedNtfsAttributes() {
        byte[] valid = ntfsField(1, 2, 3);
        for (int length = 0; length < 32; length++) {
            if (length == 4) {
                continue; // Reserved data alone contains no timestamp attribute.
            }
            byte[] payload = java.util.Arrays.copyOfRange(valid, 4, 4 + length);
            byte[] field = extraField(ZipConstants.NTFS_EXTRA_FIELD_ID, payload);
            assertThrows(IOException.class, () -> ZipExtraFieldMetadata.resolve(field, new byte[0],
                    FileTime.fromMillis(0)), "payload length " + length);
        }
        byte[] shortAttribute = java.util.Arrays.copyOfRange(valid, 4, valid.length - 1);
        ByteArrayAccess.writeShortLittleEndian(shortAttribute, 6, (short) 23);
        assertThrows(IOException.class, () -> ZipExtraFieldMetadata.resolve(
                extraField(ZipConstants.NTFS_EXTRA_FIELD_ID, shortAttribute), new byte[0], FileTime.fromMillis(0)));
        byte[] trailing = java.util.Arrays.copyOfRange(valid, 4, valid.length + 1);
        assertThrows(IOException.class, () -> ZipExtraFieldMetadata.resolve(
                extraField(ZipConstants.NTFS_EXTRA_FIELD_ID, trailing), new byte[0], FileTime.fromMillis(0)));
    }

    /// Skips unknown NTFS attributes using their declared lengths and retains the first timestamp attribute.
    @Test
    void skipsUnknownNtfsAttributes() throws IOException {
        byte[] timestamp = ntfsField(116_444_736_000_000_001L, 0, 0);
        byte[] payload = concatenate(new byte[4], new byte[]{2, 0, 3, 0, 4, 5, 6},
                java.util.Arrays.copyOfRange(timestamp, 8, timestamp.length));
        var metadata = ZipExtraFieldMetadata.resolve(extraField(ZipConstants.NTFS_EXTRA_FIELD_ID, payload),
                new byte[0], FileTime.fromMillis(0));
        assertEquals(Instant.ofEpochSecond(0, 100), metadata.lastModifiedTime().toInstant());
    }

    /// Parses both older Unix layouts and rejects short timestamp payloads.
    @Test
    void readsLegacyUnixTimestamps() throws IOException {
        for (int id : new int[]{ZipConstants.UNIX_EXTRA_FIELD_ID, ZipConstants.INFO_ZIP_UNIX_EXTRA_FIELD_ID}) {
            byte[] payload = new byte[12];
            ByteArrayAccess.writeIntLittleEndian(payload, 0, 42);
            ByteArrayAccess.writeIntLittleEndian(payload, 4, -1);
            var metadata = ZipExtraFieldMetadata.resolve(extraField(id, payload), new byte[0], FileTime.fromMillis(0));
            assertEquals(Instant.ofEpochSecond(0xffff_ffffL), metadata.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(42), metadata.lastAccessTime().toInstant());
            for (int size = 0; size < 8; size++) {
                byte[] truncated = extraField(id, new byte[size]);
                assertThrows(IOException.class, () -> ZipExtraFieldMetadata.resolve(truncated, new byte[0],
                        FileTime.fromMillis(0)));
            }
        }
    }

    /// Builds a complete NTFS field with one timestamp attribute and no other attributes.
    private static byte[] ntfsField(long modified, long accessed, long created) {
        byte[] payload = new byte[32];
        ByteArrayAccess.writeShortLittleEndian(payload, 4, (short) 1);
        ByteArrayAccess.writeShortLittleEndian(payload, 6, (short) 24);
        ByteArrayAccess.writeLongLittleEndian(payload, 8, modified);
        ByteArrayAccess.writeLongLittleEndian(payload, 16, accessed);
        ByteArrayAccess.writeLongLittleEndian(payload, 24, created);
        return extraField(ZipConstants.NTFS_EXTRA_FIELD_ID, payload);
    }

    /// Converts valid DOS fields in the system zone and maps invalid calendar or clock fields to the epoch.
    @Test
    void convertsDosTimeAndFallsBackForInvalidFields() {
        int validDate = (2025 - 1980) << 9 | 7 << 5 | 14;
        int validTime = 19 << 11 | 28 << 5 | 26 / 2;
        FileTime expected = FileTime.from(LocalDateTime.of(2025, 7, 14, 19, 28, 26)
                .atZone(ZoneId.systemDefault())
                .toInstant());

        assertEquals(expected, ZipExtraFieldMetadata.dosTime(validDate, validTime));
        assertEquals(FileTime.fromMillis(0L), ZipExtraFieldMetadata.dosTime(0, 0));
        assertEquals(FileTime.fromMillis(0L), ZipExtraFieldMetadata.dosTime(validDate, 63 << 5));
    }

    /// Resolves one new Unix payload and verifies its exact failure diagnostic.
    private static void assertUnixFailure(byte @Unmodifiable [] payload, String expectedMessage) {
        IOException exception = assertThrows(
                IOException.class,
                () -> ZipExtraFieldMetadata.resolve(
                        extraField(NEW_UNIX_FIELD_ID, payload),
                        new byte[0],
                        FileTime.fromMillis(0L)
                )
        );
        assertEquals(expectedMessage, exception.getMessage());
    }

    /// Encodes one extended timestamp payload containing only a modification time.
    private static byte @Unmodifiable [] extendedTimestamp(int seconds) {
        byte[] payload = new byte[1 + Integer.BYTES];
        payload[0] = 1;
        ByteArrayAccess.writeIntLittleEndian(payload, 1, seconds);
        return payload;
    }

    /// Encodes one complete ZIP extra field record.
    private static byte @Unmodifiable [] extraField(int identifier, byte @Unmodifiable [] payload) {
        byte[] result = new byte[Integer.BYTES + payload.length];
        ByteArrayAccess.writeShortLittleEndian(result, 0, (short) identifier);
        ByteArrayAccess.writeShortLittleEndian(result, Short.BYTES, (short) payload.length);
        System.arraycopy(payload, 0, result, Integer.BYTES, payload.length);
        return result;
    }

    /// Concatenates complete extra field records in encounter order.
    private static byte @Unmodifiable [] concatenate(
            byte @Unmodifiable [] @Unmodifiable ... fields
    ) {
        int length = 0;
        for (byte @Unmodifiable [] field : fields) {
            length += field.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte @Unmodifiable [] field : fields) {
            System.arraycopy(field, 0, result, offset, field.length);
            offset += field.length;
        }
        return result;
    }
}

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies direct parsing and fallback contracts for recognized ZIP metadata extra fields.
@NotNullByDefault
final class ZipExtraFieldMetadataTest {
    /// The Info-ZIP extended timestamp extra field identifier.
    private static final int EXTENDED_TIMESTAMP_FIELD_ID = 0x5455;

    /// The Info-ZIP new Unix extra field identifier.
    private static final int NEW_UNIX_FIELD_ID = 0x7875;

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

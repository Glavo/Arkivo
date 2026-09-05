// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies operation-scoped CPIO streaming read limits.
@NotNullByDefault
final class CPIOReadLimitsTest {
    /// Verifies entry-count, entry-size, total-entry-size, and metadata-size limits.
    @Test
    void enforcesAllStreamingReadLimits() throws IOException {
        byte[] archive = writeTwoFileArchive();

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumEntryCount(1L).build()
        )) {
            assertTrue(reader.next());
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.ENTRY_COUNT, 1L, 2L, null);
        }

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumEntrySize(4L).build()
        )) {
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.ENTRY_SIZE, 4L, 5L, "one.bin");
        }

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumTotalEntrySize(6L).build()
        )) {
            assertTrue(reader.next());
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, 6L, 12L, "two.bin");
        }

        try (CPIOArkivoStreamingReader reader = openWithLimits(
                archive,
                ArchiveReadLimits.builder().maximumMetadataSize(119L).build()
        )) {
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertLimit(exception, ArkivoReadLimitKind.METADATA_SIZE, 119L, 120L, null);
        }
    }

    /// Writes two regular files whose sizes cross the tested boundaries.
    private static byte[] writeTwoFileArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(output)) {
            try (OutputStream body = writer.beginFile("one.bin").openOutputStream()) {
                body.write(new byte[5]);
            }
            try (OutputStream body = writer.beginFile("two.bin").openOutputStream()) {
                body.write(new byte[7]);
            }
        }
        return output.toByteArray();
    }

    /// Opens one in-memory archive with the requested common read limits.
    private static CPIOArkivoStreamingReader openWithLimits(
            byte @Unmodifiable [] archive,
            ArchiveReadLimits limits
    ) {
        return CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                CPIOArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withLimits(limits)
                )
        );
    }

    /// Verifies the structured fields of one read-limit failure.
    private static void assertLimit(
            ArkivoReadLimitException exception,
            ArkivoReadLimitKind kind,
            long maximum,
            long actual,
            @Nullable String entryPath
    ) {
        assertEquals(kind, exception.kind());
        assertEquals(maximum, exception.maximum());
        assertEquals(actual, exception.actual());
        assertEquals(entryPath, exception.entryPath());
    }
}

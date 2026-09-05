// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies archive-wide read limits across the generated UDIF and HFS Plus metadata layers.
@NotNullByDefault
final class DMGReadLimitsTest {
    /// The directory containing each generated disk image.
    @TempDir
    Path temporaryDirectory;

    /// Rejects the second indexed HFS Plus entry at the configured count boundary.
    @Test
    void rejectsCatalogEntryCountBeyondLimit() throws IOException {
        ArkivoReadLimitException failure = openWithLimits(
                ArchiveReadLimits.builder().maximumEntryCount(1L).build()
        );

        assertLimit(failure, ArkivoReadLimitKind.ENTRY_COUNT, 1L, 2L, null);
    }

    /// Rejects an indexed HFS Plus entry whose logical size exceeds the per-entry boundary.
    @Test
    void rejectsCatalogEntryBeyondSizeLimit() throws IOException {
        ArkivoReadLimitException failure = openWithLimits(
                ArchiveReadLimits.builder().maximumEntrySize(8L).build()
        );

        assertLimit(failure, ArkivoReadLimitKind.ENTRY_SIZE, 8L, 9L, "link");
    }

    /// Rejects catalog entries whose combined logical sizes exceed the archive-wide boundary.
    @Test
    void rejectsCatalogTotalEntrySizeBeyondLimit() throws IOException {
        ArkivoReadLimitException failure = openWithLimits(
                ArchiveReadLimits.builder().maximumTotalEntrySize(13L).build()
        );

        assertLimit(failure, ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, 13L, 14L, "link");
    }

    /// Rejects UDIF metadata before parsing can exceed a zero-byte metadata budget.
    @Test
    void rejectsImageMetadataBeyondLimit() throws IOException {
        ArkivoReadLimitException failure = openWithLimits(
                ArchiveReadLimits.builder().maximumMetadataSize(0L).build()
        );

        assertEquals(ArkivoReadLimitKind.METADATA_SIZE, failure.kind());
        assertEquals(0L, failure.maximum());
        assertTrue(failure.actual() > 0L);
        assertNull(failure.entryPath());
    }

    /// Opens the shared generated image and returns its expected read-limit failure.
    ///
    /// @param limits the archive-wide limits to enforce
    /// @return the structured limit failure raised while opening the image
    /// @throws IOException if the fixture cannot be written
    private ArkivoReadLimitException openWithLimits(ArchiveReadLimits limits) throws IOException {
        Path imagePath = writeRawImage(
                temporaryDirectory.resolve("read-limits.dmg"),
                createHFSPlusDisk()
        );
        DMGArchiveOptions options = DMGArchiveOptions.DEFAULT.withCommon(
                ArchiveReadOptions.DEFAULT.withLimits(limits)
        );
        return assertThrows(
                ArkivoReadLimitException.class,
                () -> DMGArkivoFileSystem.open(imagePath, options)
        );
    }

    /// Verifies every structured field of one deterministic limit failure.
    ///
    /// @param failure the failure to inspect
    /// @param kind the expected limit category
    /// @param maximum the configured maximum
    /// @param actual the observed value that crossed the maximum
    /// @param entryPath the associated entry path, or `null` for an image-wide limit
    private static void assertLimit(
            ArkivoReadLimitException failure,
            ArkivoReadLimitKind kind,
            long maximum,
            long actual,
            @Nullable String entryPath
    ) {
        assertEquals(kind, failure.kind());
        assertEquals(maximum, failure.maximum());
        assertEquals(actual, failure.actual());
        if (entryPath == null) {
            assertNull(failure.entryPath());
        } else {
            assertEquals(entryPath, failure.entryPath());
        }
    }
}

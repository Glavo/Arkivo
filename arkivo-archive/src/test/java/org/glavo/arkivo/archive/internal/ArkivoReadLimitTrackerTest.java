// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests common archive read-limit accounting independently of concrete formats.
@NotNullByDefault
public final class ArkivoReadLimitTrackerTest {
    /// Verifies skipped unknown-size bytes remain subject to per-entry limits and failures are sticky.
    @Test
    public void accountsForSkippedUnknownSizeBytes() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(-1L, 3L, -1L);
        tracker.acceptEntry("entry.bin", -1L);
        try (InputStream input = tracker.trackUnknownEntrySize(
                "entry.bin",
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
        )) {
            assertEquals(2L, input.skip(2L));
            ArkivoReadLimitException first = assertThrows(
                    ArkivoReadLimitException.class,
                    () -> input.read(new byte[2])
            );
            assertEquals(ArkivoReadLimitKind.ENTRY_SIZE, first.kind());
            assertEquals(3L, first.maximum());
            assertEquals(4L, first.actual());
            assertEquals("entry.bin", first.entryPath());

            ArkivoReadLimitException repeated = assertThrows(ArkivoReadLimitException.class, input::read);
            assertEquals(first.kind(), repeated.kind());
            assertEquals(first.maximum(), repeated.maximum());
            assertEquals(first.actual(), repeated.actual());
            assertEquals(first.entryPath(), repeated.entryPath());
        }
    }

    /// Verifies known and observed entry sizes share one total-size budget.
    @Test
    public void combinesKnownAndObservedTotalSizes() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(-1L, -1L, 5L);
        tracker.acceptEntry("known.bin", 3L);
        tracker.acceptEntry("unknown.bin", -1L);
        try (InputStream input = tracker.trackUnknownEntrySize(
                "unknown.bin",
                new ByteArrayInputStream(new byte[]{4, 5, 6})
        )) {
            assertEquals(4, input.read());
            assertEquals(5, input.read());
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, input::read);
            assertEquals(ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, exception.kind());
            assertEquals(5L, exception.maximum());
            assertEquals(6L, exception.actual());
            assertEquals("unknown.bin", exception.entryPath());
        }
    }

    /// Verifies metadata accounting is cumulative and reports the associated entry path.
    @Test
    public void enforcesCumulativeMetadataSize() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(-1L, -1L, -1L, 10L);
        tracker.acceptMetadata(6L, null);
        tracker.acceptMetadata(4L, null);

        ArkivoReadLimitException exception = assertThrows(
                ArkivoReadLimitException.class,
                () -> tracker.acceptMetadata(1L, "entry.bin")
        );
        assertEquals(ArkivoReadLimitKind.METADATA_SIZE, exception.kind());
        assertEquals(10L, exception.maximum());
        assertEquals(11L, exception.actual());
        assertEquals("entry.bin", exception.entryPath());

        ArkivoReadLimitException repeated = assertThrows(
                ArkivoReadLimitException.class,
                () -> tracker.acceptEntry("ignored.bin", 0L)
        );
        assertEquals(exception.kind(), repeated.kind());
        assertEquals(exception.maximum(), repeated.maximum());
        assertEquals(exception.actual(), repeated.actual());
        assertEquals(exception.entryPath(), repeated.entryPath());
    }
}

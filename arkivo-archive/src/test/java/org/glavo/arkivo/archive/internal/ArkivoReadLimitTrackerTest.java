// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests common archive read-limit accounting independently of concrete formats.
@NotNullByDefault
public final class ArkivoReadLimitTrackerTest {
    /// Verifies public limits and internal option maps create independent trackers with equivalent policies.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void createsTrackersFromOptionsAndLimits() throws IOException {
        ArchiveReadLimits limits = ArchiveReadLimits.builder()
                .maximumEntryCount(0L)
                .maximumMetadataSize(1L)
                .build();

        ArkivoReadLimitTracker direct = ArkivoReadLimitTracker.fromLimits(limits);
        assertLimit(
                assertThrows(ArkivoReadLimitException.class, () -> direct.acceptEntry("entry.bin", -1L)),
                ArkivoReadLimitKind.ENTRY_COUNT,
                0L,
                1L,
                null
        );

        ArchiveOptions options = ArchiveOptions.EMPTY.with(ArchiveEnvironmentOptions.READ_LIMITS, limits);
        ArkivoReadLimitTracker configured = ArkivoReadLimitTracker.fromOptions(options);
        configured.acceptMetadata(1L, null);
        assertLimit(
                assertThrows(
                        ArkivoReadLimitException.class,
                        () -> configured.acceptMetadata(1L, "entry.bin")
                ),
                ArkivoReadLimitKind.METADATA_SIZE,
                1L,
                2L,
                "entry.bin"
        );

        ArkivoReadLimitTracker unrestricted = ArkivoReadLimitTracker.fromOptions(ArchiveOptions.EMPTY);
        unrestricted.acceptEntry("entry.bin", Long.MAX_VALUE);
        unrestricted.acceptMetadata(Long.MAX_VALUE, null);
        unrestricted.requireWithinLimits();

        assertThrows(NullPointerException.class, () -> ArkivoReadLimitTracker.fromOptions(null));
        assertThrows(
                NullPointerException.class,
                () -> ArkivoReadLimitTracker.fromLimits((ArchiveReadLimits) null)
        );
    }

    /// Verifies entry count, known entry size, and known total size fail at their exact boundaries.
    @Test
    public void enforcesEntryAndKnownSizeLimits() throws IOException {
        ArkivoReadLimitTracker countTracker = ArkivoReadLimitTracker.fromLimits(1L, -1L, -1L);
        countTracker.acceptEntry("first.bin", -1L);
        assertLimit(
                assertThrows(
                        ArkivoReadLimitException.class,
                        () -> countTracker.acceptEntry("second.bin", -1L)
                ),
                ArkivoReadLimitKind.ENTRY_COUNT,
                1L,
                2L,
                null
        );

        ArkivoReadLimitTracker entryTracker = ArkivoReadLimitTracker.fromLimits(-1L, 3L, -1L);
        entryTracker.acceptEntry("exact.bin", 3L);
        assertLimit(
                assertThrows(
                        ArkivoReadLimitException.class,
                        () -> entryTracker.acceptEntry("large.bin", 4L)
                ),
                ArkivoReadLimitKind.ENTRY_SIZE,
                3L,
                4L,
                "large.bin"
        );

        ArkivoReadLimitTracker totalTracker = ArkivoReadLimitTracker.fromLimits(-1L, -1L, 5L);
        totalTracker.acceptEntry("first.bin", 3L);
        totalTracker.acceptEntry("empty.bin", 0L);
        assertLimit(
                assertThrows(
                        ArkivoReadLimitException.class,
                        () -> totalTracker.acceptEntry("second.bin", 3L)
                ),
                ArkivoReadLimitKind.TOTAL_ENTRY_SIZE,
                5L,
                6L,
                "second.bin"
        );
    }

    /// Verifies primitive factories and accounting methods reject invalid arguments before changing counters.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void validatesAccountingArguments() throws IOException {
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoReadLimitTracker.fromLimits(-2L, -1L, -1L, -1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoReadLimitTracker.fromLimits(-1L, -2L, -1L, -1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoReadLimitTracker.fromLimits(-1L, -1L, -2L, -1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoReadLimitTracker.fromLimits(-1L, -1L, -1L, -2L)
        );

        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(-1L, -1L, -1L);
        assertThrows(IllegalArgumentException.class, () -> tracker.acceptMetadata(-1L, null));
        assertThrows(NullPointerException.class, () -> tracker.acceptEntry(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> tracker.acceptEntry("entry.bin", -2L));
        assertThrows(NullPointerException.class, () -> tracker.trackUnknownEntrySize(null, InputStream.nullInputStream()));
        assertThrows(NullPointerException.class, () -> tracker.trackUnknownEntrySize("entry.bin", null));
        tracker.requireWithinLimits();
    }

    /// Verifies cumulative counters saturate instead of wrapping when an unrestricted-sized addition overflows.
    @Test
    public void saturatesCumulativeSizesAtLongMaximum() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                -1L,
                -1L,
                Long.MAX_VALUE,
                Long.MAX_VALUE
        );

        tracker.acceptMetadata(Long.MAX_VALUE, null);
        tracker.acceptMetadata(1L, null);
        tracker.acceptEntry("maximum.bin", Long.MAX_VALUE);
        tracker.acceptEntry("overflow.bin", 1L);
        tracker.requireWithinLimits();
    }

    /// Verifies unknown-size streams account large skips, empty reads, and physical end of input.
    @Test
    public void handlesUnknownSizeStreamBoundaries() throws IOException {
        byte[] content = new byte[8_193];
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(-1L, 9_000L, 9_000L);
        tracker.acceptEntry("entry.bin", -1L);

        try (InputStream input = tracker.trackUnknownEntrySize(
                "entry.bin",
                new ByteArrayInputStream(content)
        )) {
            assertEquals(0, input.read(new byte[0]));
            assertEquals(0L, input.skip(0L));
            assertEquals(0L, input.skip(-1L));
            assertEquals(content.length, input.skip(9_000L));
            assertEquals(-1, input.read());
            assertEquals(-1, input.read(new byte[1]));
        }
        tracker.requireWithinLimits();
    }

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

    /// Verifies every structured property of one read-limit failure.
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

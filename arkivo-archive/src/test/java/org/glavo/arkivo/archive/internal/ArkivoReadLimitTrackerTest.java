// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests common archive read-limit accounting independently of concrete formats.
@NotNullByDefault
public final class ArkivoReadLimitTrackerTest {
    /// Verifies trackers created from the same limits retain independent counters.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void createsIndependentTrackersFromLimits() throws IOException {
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

        ArkivoReadLimitTracker configured = ArkivoReadLimitTracker.fromLimits(limits);
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

        ArkivoReadLimitTracker unrestricted = ArkivoReadLimitTracker.fromLimits(ArchiveReadLimits.UNLIMITED);
        unrestricted.acceptEntry("entry.bin", Long.MAX_VALUE);
        unrestricted.acceptMetadata(Long.MAX_VALUE, null);
        unrestricted.requireWithinLimits();

        assertThrows(
                NullPointerException.class,
                () -> ArkivoReadLimitTracker.fromLimits(null)
        );
    }

    /// Verifies entry count, known entry size, and known total size fail at their exact boundaries.
    @Test
    public void enforcesEntryAndKnownSizeLimits() throws IOException {
        ArkivoReadLimitTracker countTracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumEntryCount(1L).build());
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

        ArkivoReadLimitTracker entryTracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumEntrySize(3L).build());
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

        ArkivoReadLimitTracker totalTracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumTotalEntrySize(5L).build());
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

    /// Verifies accounting methods reject invalid arguments before changing counters.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void validatesAccountingArguments() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(ArchiveReadLimits.UNLIMITED);
        assertThrows(IllegalArgumentException.class, () -> tracker.acceptMetadata(-1L, null));
        assertThrows(NullPointerException.class, () -> tracker.acceptEntry(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> tracker.acceptEntry("entry.bin", -2L));
        assertThrows(NullPointerException.class, () -> tracker.trackUnknownEntrySize(null, InputStream.nullInputStream()));
        assertThrows(NullPointerException.class, () -> tracker.trackUnknownEntrySize("entry.bin", null));
        tracker.requireWithinLimits();
    }

    /// Verifies unrestricted cumulative counters saturate without rejecting further entries.
    @Test
    public void saturatesCumulativeSizesAtLongMaximum() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(ArchiveReadLimits.UNLIMITED);

        tracker.acceptMetadata(Long.MAX_VALUE, null);
        tracker.acceptMetadata(1L, null);
        tracker.acceptEntry("maximum.bin", Long.MAX_VALUE);
        tracker.acceptEntry("overflow.bin", 1L);
        tracker.requireWithinLimits();
    }

    /// Enforces an explicitly configured maximum even when the rejected sum cannot fit in a long.
    @ParameterizedTest
    @ValueSource(longs = {Long.MAX_VALUE - 1L, Long.MAX_VALUE})
    public void rejectsOverflowingCumulativeSizes(long maximum) throws IOException {
        ArkivoReadLimitTracker metadata = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumMetadataSize(maximum).build());
        metadata.acceptMetadata(maximum, null);
        metadata.acceptMetadata(0L, "empty.bin");
        ArkivoReadLimitException metadataFailure = assertThrows(ArkivoReadLimitException.class,
                () -> metadata.acceptMetadata(2L, "overflow.bin"));
        assertLimit(metadataFailure, ArkivoReadLimitKind.METADATA_SIZE, maximum, Long.MAX_VALUE, "overflow.bin");
        assertLimit(assertThrows(ArkivoReadLimitException.class, metadata::requireWithinLimits),
                metadataFailure.kind(), maximum, Long.MAX_VALUE, "overflow.bin");

        ArkivoReadLimitTracker known = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumTotalEntrySize(maximum).build());
        known.acceptEntry("maximum.bin", maximum);
        known.acceptEntry("empty.bin", 0L);
        ArkivoReadLimitException knownFailure = assertThrows(ArkivoReadLimitException.class,
                () -> known.acceptEntry("overflow.bin", 2L));
        assertLimit(knownFailure, ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, maximum, Long.MAX_VALUE, "overflow.bin");
        assertLimit(assertThrows(ArkivoReadLimitException.class, known::requireWithinLimits),
                knownFailure.kind(), maximum, Long.MAX_VALUE, "overflow.bin");
    }

    /// Rejects the first observed byte after known entries exhaust a maximum-long total budget.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void rejectsObservedBytesBeyondMaximumLongTotal(boolean bulk) throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumTotalEntrySize(Long.MAX_VALUE).build());
        tracker.acceptEntry("maximum.bin", Long.MAX_VALUE);
        tracker.acceptEntry("unknown.bin", -1L);
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{1, 2, 3});
        try (InputStream input = tracker.trackUnknownEntrySize("unknown.bin", source)) {
            assertEquals(0, input.read(new byte[1], 0, 0));
            ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class, () -> {
                if (bulk) {
                    input.read(new byte[16]);
                } else {
                    input.read();
                }
            });
            assertLimit(failure, ArkivoReadLimitKind.TOTAL_ENTRY_SIZE,
                    Long.MAX_VALUE, Long.MAX_VALUE, "unknown.bin");
            assertEquals(2, source.available());
            assertLimit(assertThrows(ArkivoReadLimitException.class, input::read),
                    failure.kind(), failure.maximum(), failure.actual(), failure.entryPath());
            assertEquals(2, source.available());
        }
    }

    /// Verifies unknown-size streams account large skips, empty reads, and physical end of input.
    @Test
    public void handlesUnknownSizeStreamBoundaries() throws IOException {
        byte[] content = new byte[8_193];
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumEntrySize(9_000L).maximumTotalEntrySize(9_000L).build());
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
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumEntrySize(3L).build());
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
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumTotalEntrySize(5L).build());
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

    /// Verifies oversized reads and skips stop at the first byte beyond the smaller remaining size allowance.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void boundsUnknownSizeReadsAndSkips(boolean skip) throws IOException {
        for (boolean totalLimit : new boolean[]{false, true}) {
            ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                    ArchiveReadLimits.builder()
                            .maximumEntrySize(totalLimit ? 100L : 3L)
                            .maximumTotalEntrySize(totalLimit ? 5L : 100L)
                            .build());
            tracker.acceptEntry("known.bin", 2L);
            tracker.acceptEntry("unknown.bin", -1L);
            ByteArrayInputStream source = new ByteArrayInputStream(new byte[16_384]);
            try (InputStream input = tracker.trackUnknownEntrySize("unknown.bin", source)) {
                assertEquals(0, input.read());
                ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class, () -> {
                    if (skip) {
                        input.skip(Long.MAX_VALUE);
                    } else {
                        input.read(new byte[16_384], 7, 16_000);
                    }
                });
                assertLimit(failure, totalLimit ? ArkivoReadLimitKind.TOTAL_ENTRY_SIZE
                        : ArkivoReadLimitKind.ENTRY_SIZE, totalLimit ? 5L : 3L,
                        totalLimit ? 6L : 4L, "unknown.bin");
                assertEquals(16_380, source.available());
                assertThrows(ArkivoReadLimitException.class, () -> input.read(new byte[16_384]));
                assertEquals(16_380, source.available());
            }
        }
    }

    /// Verifies an exact size limit permits EOF and empty reads, but only one byte is consumed on overflow.
    @ParameterizedTest
    @ValueSource(longs = {0L, 3L, Long.MAX_VALUE})
    public void probesUnknownSizeBoundariesWithoutOverflow(long maximum) throws IOException {
        int contentSize = maximum == Long.MAX_VALUE ? 3 : (int) maximum;
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumEntrySize(maximum).maximumTotalEntrySize(maximum).build());
        tracker.acceptEntry("entry.bin", -1L);
        try (InputStream input = tracker.trackUnknownEntrySize(
                "entry.bin", new ByteArrayInputStream(new byte[contentSize])
        )) {
            assertArrayEquals(new byte[contentSize], input.readAllBytes());
            assertEquals(0, input.read(new byte[1], 0, 0));
            assertEquals(-1, input.read(new byte[1]));
        }
        tracker.requireWithinLimits();

        if (maximum != Long.MAX_VALUE) {
            tracker.acceptEntry("overflow.bin", -1L);
            ByteArrayInputStream source = new ByteArrayInputStream(new byte[10]);
            try (InputStream input = tracker.trackUnknownEntrySize("overflow.bin", source)) {
                ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class,
                        () -> input.read(new byte[10]));
                assertLimit(failure, maximum == 0L ? ArkivoReadLimitKind.ENTRY_SIZE
                        : ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, maximum, maximum + 1L, "overflow.bin");
                assertEquals(9, source.available());
            }
        }
    }

    /// Verifies capping a read does not conceal an invalid caller range or modify bytes outside that range.
    @Test
    public void validatesOriginalReadRangeBeforeCapping() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumEntrySize(0L).build());
        tracker.acceptEntry("entry.bin", -1L);
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{42, 43});
        try (InputStream input = tracker.trackUnknownEntrySize("entry.bin", source)) {
            byte[] target = {7, 7, 7};
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(target, 2, 2));
            assertEquals(2, source.available());
            assertArrayEquals(new byte[]{7, 7, 7}, target);
            assertThrows(ArkivoReadLimitException.class, () -> input.read(target, 1, 2));
            assertArrayEquals(new byte[]{7, 42, 7}, target);
            assertEquals(1, source.available());
        }
    }

    /// Verifies interleaved entry bodies keep separate per-entry counts and share the archive's total budget.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void interleavedBodiesRetainTheirOwnCountsAndShareFailure(boolean totalLimit) throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder()
                        .maximumEntrySize(totalLimit ? 4L : 3L)
                        .maximumTotalEntrySize(totalLimit ? 4L : ArchiveReadLimits.UNLIMITED_SIZE)
                        .build());
        tracker.acceptEntry("first.bin", -1L);
        tracker.acceptEntry("second.bin", -1L);
        ByteArrayInputStream firstSource = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5});
        ByteArrayInputStream secondSource = new ByteArrayInputStream(new byte[]{6, 7, 8});
        try (InputStream first = tracker.trackUnknownEntrySize("first.bin", firstSource);
             InputStream second = tracker.trackUnknownEntrySize("second.bin", secondSource)) {
            assertArrayEquals(new byte[]{1, 2}, first.readNBytes(2));
            assertEquals(6, second.read());
            assertEquals(3, first.read());
            tracker.requireWithinLimits();
            ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class, first::read);
            ArkivoReadLimitKind kind = totalLimit ? ArkivoReadLimitKind.TOTAL_ENTRY_SIZE
                    : ArkivoReadLimitKind.ENTRY_SIZE;
            assertLimit(failure, kind, totalLimit ? 4L : 3L, totalLimit ? 5L : 4L, "first.bin");
            int firstRemaining = firstSource.available();
            int secondRemaining = secondSource.available();
            assertLimit(assertThrows(ArkivoReadLimitException.class, second::read),
                    failure.kind(), failure.maximum(), failure.actual(), failure.entryPath());
            assertLimit(assertThrows(ArkivoReadLimitException.class, () -> first.skip(1L)),
                    failure.kind(), failure.maximum(), failure.actual(), failure.entryPath());
            assertLimit(assertThrows(ArkivoReadLimitException.class, () -> second.read(new byte[2])),
                    failure.kind(), failure.maximum(), failure.actual(), failure.entryPath());
            assertLimit(assertThrows(ArkivoReadLimitException.class, () -> tracker.acceptEntry("third.bin", 0L)),
                    failure.kind(), failure.maximum(), failure.actual(), failure.entryPath());
            assertEquals(firstRemaining, firstSource.available());
            assertEquals(secondRemaining, secondSource.available());
        }
    }

    /// Verifies metadata accounting is cumulative and reports the associated entry path.
    @Test
    public void enforcesCumulativeMetadataSize() throws IOException {
        ArkivoReadLimitTracker tracker = ArkivoReadLimitTracker.fromLimits(
                ArchiveReadLimits.builder().maximumMetadataSize(10L).build());
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

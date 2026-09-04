// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies archive-wide limit defaults, construction, and effective decoder bounds.
@NotNullByDefault
public final class ArchiveReadLimitsTest {
    /// Verifies defaults leave byte and entry limits unrestricted while bounding recursive outer compression.
    @Test
    public void defaultsExposeDocumentedTrustPolicy() {
        ArchiveReadLimits unlimited = ArchiveReadLimits.UNLIMITED;
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumEntryCount());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumEntrySize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumTotalEntrySize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumMetadataSize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumCompressionWindowSize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumDecoderMemorySize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumDecodedArchiveSize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, unlimited.maximumOuterCompressionLayers());

        ArchiveReadLimits defaults = ArchiveReadLimits.DEFAULT;
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, defaults.maximumEntryCount());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, defaults.maximumEntrySize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, defaults.maximumTotalEntrySize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, defaults.maximumMetadataSize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, defaults.maximumCompressionWindowSize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, defaults.maximumDecoderMemorySize());
        assertEquals(ArchiveReadLimits.UNLIMITED_SIZE, defaults.maximumDecodedArchiveSize());
        assertEquals(
                ArchiveReadLimits.DEFAULT_MAXIMUM_OUTER_COMPRESSION_LAYERS,
                defaults.maximumOuterCompressionLayers()
        );
    }

    /// Verifies the builder preserves every independent limit and computes the stricter decoder bound.
    @Test
    public void builderPreservesEveryConfiguredLimit() {
        ArchiveReadLimits limits = ArchiveReadLimits.builder()
                .maximumEntryCount(1L)
                .maximumEntrySize(2L)
                .maximumTotalEntrySize(3L)
                .maximumMetadataSize(4L)
                .maximumCompressionWindowSize(5L)
                .maximumDecoderMemorySize(6L)
                .maximumDecodedArchiveSize(7L)
                .maximumOuterCompressionLayers(8L)
                .build();

        assertEquals(new ArchiveReadLimits(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), limits);
        assertEquals(5L, limits.effectiveCompressionWindowSize());
        assertEquals(
                11L,
                ArchiveReadLimits.builder().maximumCompressionWindowSize(11L).build()
                        .effectiveCompressionWindowSize()
        );
        assertEquals(
                12L,
                ArchiveReadLimits.builder().maximumDecoderMemorySize(12L).build()
                        .effectiveCompressionWindowSize()
        );
        assertEquals(
                ArchiveReadLimits.UNLIMITED_SIZE,
                ArchiveReadLimits.UNLIMITED.effectiveCompressionWindowSize()
        );
    }

    /// Verifies every builder axis rejects values below the single unrestricted sentinel.
    @Test
    public void builderRejectsInvalidLimits() {
        long invalid = ArchiveReadLimits.UNLIMITED_SIZE - 1L;

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumEntryCount(invalid)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumEntrySize(invalid)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumTotalEntrySize(invalid)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumMetadataSize(invalid)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumCompressionWindowSize(invalid)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumDecoderMemorySize(invalid)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumDecodedArchiveSize(invalid)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ArchiveReadLimits.builder().maximumOuterCompressionLayers(invalid)
                )
        );
    }

    /// Verifies direct record construction validates each limit independently of the builder.
    @Test
    public void constructorRejectsEveryInvalidComponent() {
        long unlimited = ArchiveReadLimits.UNLIMITED_SIZE;
        long invalid = unlimited - 1L;

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(invalid, unlimited, unlimited, unlimited, unlimited, unlimited, unlimited, unlimited)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(unlimited, invalid, unlimited, unlimited, unlimited, unlimited, unlimited, unlimited)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(unlimited, unlimited, invalid, unlimited, unlimited, unlimited, unlimited, unlimited)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(unlimited, unlimited, unlimited, invalid, unlimited, unlimited, unlimited, unlimited)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(unlimited, unlimited, unlimited, unlimited, invalid, unlimited, unlimited, unlimited)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(unlimited, unlimited, unlimited, unlimited, unlimited, invalid, unlimited, unlimited)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(unlimited, unlimited, unlimited, unlimited, unlimited, unlimited, invalid, unlimited)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new ArchiveReadLimits(unlimited, unlimited, unlimited, unlimited, unlimited, unlimited, unlimited, invalid))
        );
    }

    /// Verifies read-limit failures require a category and an observation strictly beyond a nonnegative maximum.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void readLimitExceptionRejectsInvalidConstruction() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ArkivoReadLimitException(null, 0L, 1L, null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ArkivoReadLimitException(
                                ArkivoReadLimitKind.ENTRY_SIZE,
                                -1L,
                                0L,
                                "entry.bin"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ArkivoReadLimitException(
                                ArkivoReadLimitKind.ENTRY_SIZE,
                                2L,
                                2L,
                                "entry.bin"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ArkivoReadLimitException(
                                ArkivoReadLimitKind.ENTRY_SIZE,
                                2L,
                                1L,
                                "entry.bin"
                        )
                )
        );
    }
}

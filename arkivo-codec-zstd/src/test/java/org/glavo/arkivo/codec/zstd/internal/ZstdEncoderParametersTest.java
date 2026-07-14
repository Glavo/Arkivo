// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies effective pure Java Zstandard encoder parameter selection.
@NotNullByDefault
public final class ZstdEncoderParametersTest {
    /// Verifies automatic job and overlap sizes follow the selected window and strategy.
    @Test
    public void selectsAutomaticParallelSizes() throws IOException {
        ZstdEncoderParameters fast = parameters(2, 0, 0, 1, false);
        ZstdEncoderParameters optimal = parameters(2, 0, 0, 7, false);
        ZstdEncoderParameters ultra2 = parameters(2, 0, 0, 9, false);

        assertEquals(1 << 20, fast.jobSize());
        assertEquals(1 << 14, fast.overlapSize());
        assertEquals(1 << 16, optimal.overlapSize());
        assertEquals(1 << 17, ultra2.overlapSize());
    }

    /// Verifies explicit values are normalized to supported job and history limits.
    @Test
    public void normalizesExplicitParallelSizes() throws IOException {
        ZstdEncoderParameters noOverlap = parameters(2, 1, 1, 1, false);
        ZstdEncoderParameters fullOverlap = parameters(2, 600_000, 9, 1, false);

        assertEquals(512 * 1024, noOverlap.jobSize());
        assertEquals(0, noOverlap.overlapSize());
        assertEquals(600_000, fullOverlap.jobSize());
        assertEquals(1 << 17, fullOverlap.overlapSize());
    }

    /// Verifies worker-only controls have no effective state in synchronous mode.
    @Test
    public void ignoresParallelSizesWithoutWorkers() throws IOException {
        ZstdEncoderParameters parameters = parameters(0, 600_000, 9, 1, false);

        assertEquals(0, parameters.jobSize());
        assertEquals(0, parameters.overlapSize());
    }

    /// Verifies long-distance mode uses its bounded job-size default.
    @Test
    public void selectsLongDistanceJobSize() throws IOException {
        ZstdEncoderParameters enabled = parameters(2, 0, 0, 1, true);

        assertEquals(1 << 21, enabled.jobSize());
        assertTrue(enabled.longDistanceMatching());
        assertFalse(parameters(2, 0, 0, 1, false).longDistanceMatching());
    }

    /// Verifies zero selects a level-derived strategy while explicit strategies remain unchanged.
    @Test
    public void selectsEffectiveStrategy() throws IOException {
        assertEquals(3, parameters(0, 0, 0, 0, false).strategy());
        assertEquals(7, parameters(0, 0, 0, 7, false).strategy());
    }

    /// Verifies invalid direct scheduling parameters are rejected.
    @Test
    public void rejectsInvalidParallelSizes() {
        assertThrows(IllegalArgumentException.class, () -> parameters(2, -1, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> parameters(2, 0, 10, 1, false));
        assertThrows(IllegalArgumentException.class, () -> parameters(2, 0, 0, -1, false));
    }

    /// Creates parameters for one parallel scheduling scenario.
    private static ZstdEncoderParameters parameters(
            int workerCount,
            int jobSize,
            int overlapLog,
            int strategy,
            boolean longDistanceMatching
    ) throws IOException {
        return new ZstdEncoderParameters(
                3,
                17,
                18,
                17,
                6,
                4,
                0,
                strategy,
                false,
                false,
                false,
                longDistanceMatching,
                workerCount,
                jobSize,
                overlapLog,
                -1L,
                null
        );
    }
}

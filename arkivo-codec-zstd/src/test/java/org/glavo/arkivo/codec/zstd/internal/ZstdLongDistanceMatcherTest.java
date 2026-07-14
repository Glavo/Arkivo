// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies sparse frame-wide Zstandard long-distance match planning.
@NotNullByDefault
public final class ZstdLongDistanceMatcherTest {
    /// Verifies matches beyond the ordinary chain limit are planned within the frame window.
    @Test
    public void plansRetainedLongDistanceMatches() throws IOException {
        int blockSize = 1 << 17;
        byte[] first = new byte[blockSize];
        byte[] separator = new byte[blockSize];
        Random random = new Random(0x1d15_7a6c_2026L);
        random.nextBytes(first);
        random.nextBytes(separator);

        ZstdLongDistanceMatcher matcher = new ZstdLongDistanceMatcher(parameters());
        assertTrue(matcher.plan(first, first.length).isEmpty());
        assertTrue(matcher.plan(separator, separator.length).isEmpty());
        List<ZstdLongDistanceMatcher.Match> matches = matcher.plan(first, first.length);

        assertFalse(matches.isEmpty());
        ZstdLongDistanceMatcher.Match firstMatch = matches.get(0);
        assertEquals(blockSize * 2, firstMatch.distance());
        assertTrue(firstMatch.length() >= blockSize / 2);
    }

    /// Verifies resetting removes frame history and retained-window eviction rejects old candidates.
    @Test
    public void resetsAndEvictsFrameHistory() throws IOException {
        int blockSize = 1 << 17;
        byte[] first = new byte[blockSize];
        byte[] separator = new byte[blockSize];
        Random random = new Random(0x6e71_57ad_2026L);
        random.nextBytes(first);
        random.nextBytes(separator);

        ZstdLongDistanceMatcher matcher = new ZstdLongDistanceMatcher(parameters());
        matcher.plan(first, first.length);
        for (int index = 0; index < 4; index++) {
            separator[0] = (byte) index;
            matcher.plan(separator, separator.length);
        }
        assertTrue(matcher.plan(first, first.length).isEmpty());

        matcher.reset();
        assertTrue(matcher.plan(first, first.length).isEmpty());
    }

    /// Creates long-distance parameters with a 512 KiB window and 128 KiB chain limit.
    private static ZstdEncoderParameters parameters() throws IOException {
        return new ZstdEncoderParameters(
                3,
                19,
                18,
                17,
                6,
                4,
                0,
                1,
                false,
                false,
                false,
                true,
                0,
                0,
                0,
                -1L,
                null
        );
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests public physical 7z packed-stream descriptors.
@NotNullByDefault
public final class SevenZipPackedStreamTest {
    /// Verifies valid descriptors retain logical offsets, sizes, and optional digests.
    @Test
    public void retainsPhysicalLayoutValues() {
        SevenZipPackedStream stream = new SevenZipPackedStream(
                32L,
                4096L,
                0xffff_ffffL
        );

        assertEquals(32L, stream.offset());
        assertEquals(4096L, stream.size());
        assertEquals(0xffff_ffffL, stream.crc32());
        assertEquals(
                new SevenZipPackedStream(0L, 0L, SevenZipPackedStream.UNKNOWN_CRC32),
                new SevenZipPackedStream(0L, 0L, SevenZipPackedStream.UNKNOWN_CRC32)
        );
    }

    /// Verifies invalid offsets, sizes, and CRC-32 values are rejected.
    @Test
    public void rejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipPackedStream(-1L, 0L, SevenZipPackedStream.UNKNOWN_CRC32)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipPackedStream(0L, -1L, SevenZipPackedStream.UNKNOWN_CRC32)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipPackedStream(0L, 0L, -2L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipPackedStream(0L, 0L, 0x1_0000_0000L)
        );
    }
}

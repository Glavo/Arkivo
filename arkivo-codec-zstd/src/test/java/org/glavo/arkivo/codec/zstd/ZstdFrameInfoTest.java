// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies public Zstandard frame-metadata records enforce their complete encoded domains.
@NotNullByDefault
final class ZstdFrameInfoTest {
    /// Verifies standard-frame metadata accepts every inclusive field boundary.
    @Test
    void acceptsStandardFrameBoundaries() {
        ZstdStandardFrameInfo minimum = new ZstdStandardFrameInfo(
                2,
                ZstdStandardFrameInfo.CONTENT_SIZE_OVERFLOW,
                0L,
                ZstdDictionary.NO_DICTIONARY_ID,
                false
        );
        assertEquals(2, minimum.headerSize());
        assertEquals(ZstdStandardFrameInfo.CONTENT_SIZE_OVERFLOW, minimum.contentSize());
        assertEquals(0L, minimum.windowSize());
        assertEquals(ZstdDictionary.NO_DICTIONARY_ID, minimum.dictionaryId());
        assertFalse(minimum.checksum());
        assertFalse(minimum.skippable());

        ZstdStandardFrameInfo maximum = new ZstdStandardFrameInfo(
                18,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0xffff_ffffL,
                true
        );
        assertEquals(18, maximum.headerSize());
        assertEquals(Long.MAX_VALUE, maximum.contentSize());
        assertEquals(Long.MAX_VALUE, maximum.windowSize());
        assertEquals(0xffff_ffffL, maximum.dictionaryId());
        assertTrue(maximum.checksum());

        ZstdStandardFrameInfo unknownSize = new ZstdStandardFrameInfo(
                6,
                CompressionCodec.UNKNOWN_SIZE,
                1L,
                0L,
                false
        );
        assertEquals(CompressionCodec.UNKNOWN_SIZE, unknownSize.contentSize());
        assertEquals(1L, unknownSize.windowSize());

        ZstdStandardFrameInfo empty = new ZstdStandardFrameInfo(6, 0L, 0L, 0L, false);
        assertEquals(0L, empty.contentSize());
        assertEquals(0L, empty.windowSize());
    }

    /// Verifies standard-frame metadata rejects each value immediately outside its encoded domain.
    @Test
    void rejectsInvalidStandardFrameMetadata() {
        assertInvalidStandard(1, 0L, 0L, 0L);
        assertInvalidStandard(19, 0L, 0L, 0L);
        assertInvalidStandard(2, ZstdStandardFrameInfo.CONTENT_SIZE_OVERFLOW - 1L, 0L, 0L);
        assertInvalidStandard(2, 0L, -1L, 0L);
        assertInvalidStandard(2, 0L, 0L, ZstdDictionary.NO_DICTIONARY_ID - 1L);
        assertInvalidStandard(2, 0L, 0L, 0x1_0000_0000L);
        assertInvalidStandard(2, CompressionCodec.UNKNOWN_SIZE, 0L, 0L);
    }

    /// Verifies skippable-frame metadata accepts both inclusive identifier and unsigned-size boundaries.
    @Test
    void acceptsSkippableFrameBoundaries() {
        ZstdSkippableFrameInfo minimum = new ZstdSkippableFrameInfo(0, 0L);
        assertEquals(0, minimum.id());
        assertEquals(0L, minimum.payloadSize());
        assertEquals(ZstdSkippableFrameInfo.HEADER_SIZE, minimum.headerSize());
        assertTrue(minimum.skippable());

        ZstdSkippableFrameInfo maximum = new ZstdSkippableFrameInfo(15, 0xffff_ffffL);
        assertEquals(15, maximum.id());
        assertEquals(0xffff_ffffL, maximum.payloadSize());
        assertEquals(ZstdSkippableFrameInfo.HEADER_SIZE, maximum.headerSize());
        assertTrue(maximum.skippable());
    }

    /// Verifies skippable-frame metadata rejects values immediately outside its encoded domain.
    @Test
    void rejectsInvalidSkippableFrameMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new ZstdSkippableFrameInfo(-1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ZstdSkippableFrameInfo(16, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ZstdSkippableFrameInfo(0, -1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ZstdSkippableFrameInfo(0, 0x1_0000_0000L)
        );
    }

    /// Verifies one standard-frame metadata tuple is rejected.
    private static void assertInvalidStandard(
            int headerSize,
            long contentSize,
            long windowSize,
            long dictionaryId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ZstdStandardFrameInfo(
                        headerSize,
                        contentSize,
                        windowSize,
                        dictionaryId,
                        false
                )
        );
    }
}

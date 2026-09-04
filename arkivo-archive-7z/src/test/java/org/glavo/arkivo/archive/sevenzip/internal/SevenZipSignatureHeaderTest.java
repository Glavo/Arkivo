// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies fixed 7z signature-header value validation and unsigned field access.
@NotNullByDefault
final class SevenZipSignatureHeaderTest {
    /// Verifies every field accepts and exposes both ends of its documented range.
    @Test
    void exposesUnsignedBoundaryValues() {
        SevenZipSignatureHeader minimum = new SevenZipSignatureHeader(0, 0, 0L, 0L, 0L);
        assertEquals(0, minimum.majorVersion());
        assertEquals(0, minimum.minorVersion());
        assertEquals(0L, minimum.nextHeaderOffset());
        assertEquals(0L, minimum.nextHeaderSize());
        assertEquals(0L, minimum.nextHeaderCrc32());

        SevenZipSignatureHeader maximum = new SevenZipSignatureHeader(
                0xff,
                0xff,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0xffff_ffffL
        );
        assertEquals(0xff, maximum.majorVersion());
        assertEquals(0xff, maximum.minorVersion());
        assertEquals(Long.MAX_VALUE, maximum.nextHeaderOffset());
        assertEquals(Long.MAX_VALUE, maximum.nextHeaderSize());
        assertEquals(0xffff_ffffL, maximum.nextHeaderCrc32());
    }

    /// Verifies values immediately outside every supported field range are rejected.
    @Test
    void rejectsOutOfRangeFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(-1, 0, 0L, 0L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(0x100, 0, 0L, 0L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(0, -1, 0L, 0L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(0, 0x100, 0L, 0L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(0, 0, -1L, 0L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(0, 0, 0L, -1L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(0, 0, 0L, 0L, -1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipSignatureHeader(0, 0, 0L, 0L, 0x1_0000_0000L)
        );
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests low-level 7z archive writer metadata serialization.
@NotNullByDefault
public final class SevenZipArchiveWriterTest {
    /// Verifies BCJ start offsets use the optional little-endian 7z coder property.
    @Test
    public void serializesBcjStartOffsetProperties() {
        assertArrayEquals(new byte[0], SevenZipArchiveWriter.bcjProperties(0));
        assertArrayEquals(
                new byte[]{0x78, 0x56, 0x34, 0x12},
                SevenZipArchiveWriter.bcjProperties(0x1234_5678L)
        );
        assertArrayEquals(
                new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                SevenZipArchiveWriter.bcjProperties(0xffff_ffffL)
        );
        assertThrows(IllegalArgumentException.class, () -> SevenZipArchiveWriter.bcjProperties(-1));
        assertThrows(IllegalArgumentException.class, () -> SevenZipArchiveWriter.bcjProperties(0x1_0000_0000L));
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests bounded 7z archive slice channel behavior.
@NotNullByDefault
public final class SevenZipFileSliceChannelTest {
    /// Verifies that slice-relative positions cannot overflow absolute archive offsets.
    @Test
    public void rejectsOverflowingAbsoluteReadPosition() throws IOException {
        try (SevenZipFileSliceChannel channel = new SevenZipFileSliceChannel(
                new SevenZipByteChannel(new byte[]{1}),
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE
        )) {
            channel.position(2);

            IOException exception = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));

            assertEquals(true, exception.getMessage().contains("7z slice offset is too large"));
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests in-memory 7z byte channel behavior.
@NotNullByDefault
public final class SevenZipByteChannelTest {
    /// Verifies that an empty destination buffer reports no bytes read even at EOF.
    @Test
    public void emptyReadAtEndOfChannelReturnsZero() throws IOException {
        try (SevenZipByteChannel channel = new SevenZipByteChannel(new byte[]{1})) {
            channel.position(1);

            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        }
    }

    /// Verifies that operations on a closed in-memory channel report closed state.
    @Test
    public void operationsAfterCloseAreRejectedAsClosed() throws IOException {
        SevenZipByteChannel channel = new SevenZipByteChannel(new byte[]{1, 2, 3});

        assertEquals(true, channel.isOpen());
        assertThrows(UnsupportedOperationException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(UnsupportedOperationException.class, () -> channel.truncate(0));

        channel.close();

        assertEquals(false, channel.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
        channel.close();
    }
}

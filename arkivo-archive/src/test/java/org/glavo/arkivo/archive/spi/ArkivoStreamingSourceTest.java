// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.spi;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies explicit ownership transfer for transformed streaming sources.
@NotNullByDefault
final class ArkivoStreamingSourceTest {
    /// Verifies taking a channel transfers cleanup responsibility exactly once.
    @Test
    void transfersResourceOwnershipExactlyOnce() throws IOException {
        ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));
        ArkivoStreamingSource source = new ArkivoStreamingSource(false, channel);

        assertFalse(source.transformed());
        ReadableByteChannel taken = source.takeChannel();
        assertSame(channel, taken);
        assertThrows(IllegalStateException.class, source::takeChannel);

        source.close();
        assertTrue(channel.isOpen());
        taken.close();
        assertFalse(channel.isOpen());
    }

    /// Verifies closing an untransferred result closes its logical channel idempotently.
    @Test
    void closesUntransferredChannel() throws IOException {
        ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));
        ArkivoStreamingSource source = new ArkivoStreamingSource(true, channel);

        assertTrue(source.transformed());
        source.close();
        source.close();

        assertFalse(channel.isOpen());
        assertThrows(IllegalStateException.class, source::takeChannel);
    }
}

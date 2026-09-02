// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /// Verifies a failed close retains ownership and permits one successful cleanup retry.
    @Test
    void retriesFailedChannelClose() throws IOException {
        FailingCloseReadableChannel channel = new FailingCloseReadableChannel();
        ArkivoStreamingSource source = new ArkivoStreamingSource(true, channel);

        IOException failure = assertThrows(IOException.class, source::close);

        assertEquals("close failed", failure.getMessage());
        assertEquals(1, channel.closeAttempts());
        assertTrue(channel.isOpen());

        source.close();
        source.close();

        assertEquals(2, channel.closeAttempts());
        assertFalse(channel.isOpen());
        assertThrows(IllegalStateException.class, source::takeChannel);
    }

    /// Implements a readable channel whose first close attempt fails without closing it.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Whether the channel is open.
        private boolean open = true;

        /// Number of close attempts made while open.
        private int closeAttempts;

        /// Reports physical end of input while the channel is open.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            return -1;
        }

        /// Returns whether the channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails the first close attempt and succeeds on the second.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeAttempts++;
            if (closeAttempts == 1) {
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Returns the number of close attempts made while open.
        private int closeAttempts() {
            return closeAttempts;
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.Channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies owned-channel closure state and failure composition.
@NotNullByDefault
final class OwnedChannelCloserTest {
    /// Verifies close failure is suppressed under a primary failure and remains retryable.
    @Test
    void suppressesAndRetriesCloseFailure() throws IOException {
        FailingOnceChannel channel = new FailingOnceChannel();
        OwnedChannelCloser closer = new OwnedChannelCloser(channel, ResourceOwnership.OWNED);
        IOException primary = new IOException("codec finalization failed");

        IOException thrown = assertThrows(IOException.class, () -> closer.closeAfter(primary));
        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("endpoint close failed", thrown.getSuppressed()[0].getMessage());
        assertEquals(1, channel.closeCount());
        assertFalse(closer.isComplete());

        closer.close();
        closer.close();
        assertEquals(2, channel.closeCount());
        assertTrue(closer.isComplete());
    }

    /// Verifies retained channels complete without receiving a close call while primary failure survives.
    @Test
    void retainsChannelAndRethrowsPrimaryFailure() {
        FailingOnceChannel channel = new FailingOnceChannel();
        OwnedChannelCloser closer = new OwnedChannelCloser(channel, ResourceOwnership.BORROWED);
        IllegalStateException primary = new IllegalStateException("codec failed");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> closer.closeAfter(primary)
        );
        assertSame(primary, thrown);
        assertEquals(0, channel.closeCount());
        assertTrue(closer.isComplete());
    }

    /// Implements a channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingOnceChannel implements Channel {
        /// Whether the channel remains open.
        private boolean open = true;

        /// Number of close attempts.
        private int closeCount;

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails once and succeeds on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("endpoint close failed");
            }
            open = false;
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }
    }
}

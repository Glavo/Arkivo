// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the transaction lifecycle of the channel-backed single-volume 7z target.
@NotNullByDefault
final class SevenZipSingleVolumeTargetTest {
    /// Verifies volume sequencing, commit preconditions, and terminal transaction behavior.
    @Test
    void enforcesSingleVolumeTransactionLifecycle() throws IOException {
        TrackingWritableByteChannel channel = new TrackingWritableByteChannel();
        SevenZipSingleVolumeTarget target = new SevenZipSingleVolumeTarget(channel);
        ArkivoVolumeOutput output = target.openOutput();

        assertThrows(IOException.class, target::openOutput);
        assertThrows(IllegalArgumentException.class, () -> output.openVolume(-1L));
        assertThrows(IllegalArgumentException.class, () -> output.openVolume(1L));
        assertThrows(IllegalArgumentException.class, () -> output.commit(0L));

        assertSame(channel, output.openVolume(0L));
        assertThrows(IllegalArgumentException.class, () -> output.openVolume(0L));
        assertThrows(IOException.class, () -> output.openVolume(1L));
        assertThrows(IllegalArgumentException.class, () -> output.commit(1L));
        IOException stillOpen = assertThrows(IOException.class, () -> output.commit(0L));
        assertEquals("7z output channel volume is still open", stillOpen.getMessage());

        channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        channel.close();
        output.commit(0L);
        output.close();
        output.rollback();
        assertArrayEquals(new byte[]{1, 2, 3}, channel.bytes.toByteArray());
        assertEquals(1, channel.closeAttempts);
        assertThrows(IOException.class, () -> output.openVolume(1L));
        assertThrows(IOException.class, () -> output.commit(0L));
    }

    /// Verifies rollback before volume creation closes the target-owned destination exactly once.
    @Test
    void rollbackBeforeVolumeOpenClosesDestination() throws IOException {
        TrackingWritableByteChannel channel = new TrackingWritableByteChannel();
        ArkivoVolumeOutput output = new SevenZipSingleVolumeTarget(channel).openOutput();

        output.rollback();
        output.rollback();
        output.close();

        assertFalse(channel.isOpen());
        assertEquals(1, channel.closeAttempts);
        assertThrows(IOException.class, () -> output.openVolume(0L));
        assertThrows(IOException.class, () -> output.commit(0L));
    }

    /// Verifies rollback is retried when a failed close leaves the destination channel open.
    @Test
    void retriesIncompleteRollback() throws IOException {
        TrackingWritableByteChannel channel = new TrackingWritableByteChannel();
        channel.closeFailures = 1;
        ArkivoVolumeOutput output = new SevenZipSingleVolumeTarget(channel).openOutput();

        IOException failure = assertThrows(IOException.class, output::close);
        assertEquals("close failure", failure.getMessage());
        assertTrue(channel.isOpen());
        assertEquals(1, channel.closeAttempts);

        output.close();
        output.close();
        assertFalse(channel.isOpen());
        assertEquals(2, channel.closeAttempts);
    }

    /// Verifies a close failure reported after physical closure does not trigger redundant cleanup.
    @Test
    void recognizesCleanupCompletedByFailingClose() throws IOException {
        TrackingWritableByteChannel channel = new TrackingWritableByteChannel();
        channel.closeFailures = 1;
        channel.closeBeforeFailure = true;
        ArkivoVolumeOutput output = new SevenZipSingleVolumeTarget(channel).openOutput();

        IOException failure = assertThrows(IOException.class, output::rollback);
        assertEquals("close failure", failure.getMessage());
        assertFalse(channel.isOpen());
        assertEquals(1, channel.closeAttempts);

        output.rollback();
        output.close();
        assertEquals(1, channel.closeAttempts);
    }

    /// Records written bytes and supports controlled close failures.
    @NotNullByDefault
    private static final class TrackingWritableByteChannel implements WritableByteChannel {
        /// Bytes accepted by the channel.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Number of close failures still scheduled.
        private int closeFailures;

        /// Whether a scheduled close failure also closes the channel.
        private boolean closeBeforeFailure;

        /// Number of close calls received.
        private int closeAttempts;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Writes all remaining source bytes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            int count = source.remaining();
            byte[] buffer = new byte[count];
            source.get(buffer);
            bytes.writeBytes(buffer);
            return count;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the channel or performs one configured close failure.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (!open) {
                return;
            }
            if (closeFailures > 0) {
                closeFailures--;
                if (closeBeforeFailure) {
                    open = false;
                }
                throw new IOException("close failure");
            }
            open = false;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}

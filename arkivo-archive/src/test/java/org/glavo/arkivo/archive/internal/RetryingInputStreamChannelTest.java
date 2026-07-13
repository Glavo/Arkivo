// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies input-stream channel adaptation and retryable ownership behavior.
@NotNullByDefault
final class RetryingInputStreamChannelTest {
    /// Verifies heap and direct targets receive bytes and advance by the read count.
    @Test
    void readsHeapAndDirectBuffers() throws IOException {
        try (RetryingInputStreamChannel heapChannel = new RetryingInputStreamChannel(
                new ByteArrayInputStream(new byte[]{1, 2})
        )) {
            ByteBuffer target = ByteBuffer.allocate(4);
            assertEquals(2, heapChannel.read(target));
            assertEquals(2, target.position());
        }

        try (RetryingInputStreamChannel directChannel = new RetryingInputStreamChannel(
                new ByteArrayInputStream(new byte[]{3, 4})
        )) {
            ByteBuffer target = ByteBuffer.allocateDirect(4);
            assertEquals(2, directChannel.read(target));
            assertEquals(2, target.position());
        }
    }

    /// Verifies a zero-progress stream result is returned without internal spinning.
    @Test
    void preservesZeroProgress() throws IOException {
        try (RetryingInputStreamChannel channel = new RetryingInputStreamChannel(new ZeroProgressInputStream())) {
            assertEquals(0, channel.read(ByteBuffer.allocate(1)));
        }
    }

    /// Verifies a read-only target is rejected before consuming source bytes.
    @Test
    void rejectsReadOnlyTargetBeforeReading() throws IOException {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{1});
        try (RetryingInputStreamChannel channel = new RetryingInputStreamChannel(source)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> channel.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
            );
            assertEquals(1, source.available());
        }
    }

    /// Verifies a failed close leaves the adapter open for a later retry.
    @Test
    void retriesFailedClose() throws IOException {
        FailingCloseInputStream source = new FailingCloseInputStream();
        RetryingInputStreamChannel channel = new RetryingInputStreamChannel(source);
        assertThrows(IOException.class, channel::close);
        assertTrue(channel.isOpen());
        channel.close();
        assertFalse(channel.isOpen());
        assertEquals(2, source.closeAttempts());
    }

    /// Returns zero progress for every read.
    @NotNullByDefault
    private static final class ZeroProgressInputStream extends InputStream {
        /// Returns zero progress.
        @Override
        public int read() {
            return 0;
        }

        /// Returns zero progress.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            return 0;
        }
    }

    /// Fails its first close attempt and accepts the second.
    @NotNullByDefault
    private static final class FailingCloseInputStream extends InputStream {
        /// Number of close attempts.
        private int closeAttempts;

        /// Reports end of input.
        @Override
        public int read() {
            return -1;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeAttempts == 1) {
                throw new IOException("close failure");
            }
        }

        /// Returns the number of close attempts.
        private int closeAttempts() {
            return closeAttempts;
        }
    }
}

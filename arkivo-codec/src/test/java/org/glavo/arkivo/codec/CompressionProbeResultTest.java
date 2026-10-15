// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies prefix isolation and channel ownership in compression probe results.
@NotNullByDefault
final class CompressionProbeResultTest {
    /// Preserves an explicit close failure when automatic result cleanup retries the same endpoint.
    @Test
    void publicProbePreservesRepeatedCloseFailure() throws IOException {
        TrackingReadableByteChannel source = new TrackingReadableByteChannel(false);
        IOException failure = new IOException("source failure");
        source.closeFailure = failure;
        CompressionProbeResult result = CompressionFormats.probe(source, ResourceOwnership.OWNED);
        assertSame(failure, assertThrows(IOException.class, () -> {
            try (result) {
                result.close();
            }
        }));
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
        assertSame(failure, failure.getSuppressed()[0].getCause());
        assertEquals(2, source.closeAttempts());
        source.closeFailure = null;
        result.close();
        result.close();
        assertEquals(3, source.closeAttempts());
        assertFalse(source.isOpen());
        assertThrows(IllegalStateException.class, result::takeChannel);
    }

    /// Retains failure protection and source ownership when the probe channel is transferred to its caller.
    @Test
    void transferredProbePreservesReadFailureDuringCleanup() throws IOException {
        for (ResourceOwnership ownership : ResourceOwnership.values()) {
            TrackingReadableByteChannel source = new TrackingReadableByteChannel(false);
            IOException failure = new IOException("source failure");
            try (CompressionProbeResult result = CompressionFormats.probe(source, ownership)) {
                ReadableByteChannel channel = result.takeChannel();
                source.readFailure = failure;
                source.closeFailure = failure;
                assertSame(failure, assertThrows(IOException.class, () -> {
                    try (channel) {
                        channel.read(ByteBuffer.allocate(1));
                    }
                }));
                boolean owned = ownership == ResourceOwnership.OWNED;
                assertEquals(owned ? 1 : 0, failure.getSuppressed().length);
                if (owned) {
                    assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
                    assertSame(failure, failure.getSuppressed()[0].getCause());
                }
                assertEquals(owned ? 1 : 0, source.closeAttempts());
                source.closeFailure = null;
                channel.close();
                channel.close();
                assertEquals(owned ? 2 : 0, source.closeAttempts());
                assertEquals(!owned, source.isOpen());
            }
            source.close();
        }
    }

    /// Verifies construction copies only the remaining prefix and every accessor returns a fresh read-only view.
    @Test
    void copiesPrefixAndReturnsIndependentReadOnlyViews() throws IOException {
        ByteBuffer sourcePrefix = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 8});
        sourcePrefix.position(1).limit(4);
        TrackingReadableByteChannel channel = new TrackingReadableByteChannel(false);

        try (CompressionProbeResult result = new CompressionProbeResult(null, sourcePrefix, channel)) {
            assertNull(result.format());
            assertEquals(1, sourcePrefix.position());
            assertEquals(4, sourcePrefix.limit());

            sourcePrefix.put(1, (byte) 99);
            ByteBuffer first = result.prefix();
            assertTrue(first.isReadOnly());
            assertEquals(0, first.position());
            assertEquals(3, first.remaining());
            assertArrayEquals(new byte[]{1, 2, 3}, remainingBytes(first));
            assertThrows(ReadOnlyBufferException.class, () -> first.put(0, (byte) 0));

            first.position(first.limit());
            ByteBuffer second = result.prefix();
            assertEquals(0, second.position());
            assertArrayEquals(new byte[]{1, 2, 3}, remainingBytes(second));
        }

        assertFalse(channel.isOpen());
        assertEquals(1, channel.closeAttempts());
    }

    /// Verifies channel ownership transfers exactly once and result closure becomes a no-op after transfer.
    @Test
    void transfersChannelOwnershipExactlyOnce() throws IOException {
        TrackingReadableByteChannel channel = new TrackingReadableByteChannel(false);
        CompressionProbeResult result = new CompressionProbeResult(null, ByteBuffer.allocate(0), channel);

        assertSame(channel, result.takeChannel());
        assertThrows(IllegalStateException.class, result::takeChannel);
        result.close();
        result.close();
        assertTrue(channel.isOpen());
        assertEquals(0, channel.closeAttempts());

        channel.close();
        assertFalse(channel.isOpen());
        assertEquals(1, channel.closeAttempts());
    }

    /// Verifies a failed close retains ownership for one later cleanup attempt.
    @Test
    void retriesChannelClosureAfterFailure() throws IOException {
        TrackingReadableByteChannel channel = new TrackingReadableByteChannel(true);
        CompressionProbeResult result = new CompressionProbeResult(null, ByteBuffer.allocate(0), channel);

        IOException exception = assertThrows(IOException.class, result::close);
        assertEquals("close failed", exception.getMessage());
        assertTrue(channel.isOpen());
        assertEquals(1, channel.closeAttempts());

        result.close();
        result.close();
        assertFalse(channel.isOpen());
        assertEquals(2, channel.closeAttempts());
        assertThrows(IllegalStateException.class, result::takeChannel);
    }

    /// Verifies constructor validation does not consume a valid prefix buffer.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesConstructorArgumentsWithoutConsumingPrefix() {
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{1, 2, 3});
        prefix.position(1);
        TrackingReadableByteChannel channel = new TrackingReadableByteChannel(false);

        assertThrows(NullPointerException.class, () -> new CompressionProbeResult(null, null, channel));
        assertEquals(1, prefix.position());
        assertThrows(NullPointerException.class, () -> new CompressionProbeResult(null, prefix, null));
        assertEquals(1, prefix.position());
        assertTrue(channel.isOpen());
        assertEquals(0, channel.closeAttempts());
    }

    /// Copies the remaining bytes from a buffer without changing the caller-visible buffer state.
    private static byte[] remainingBytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    /// Implements a readable channel with observable and optionally failing closure.
    @NotNullByDefault
    private static final class TrackingReadableByteChannel implements ReadableByteChannel {
        /// Whether the first close attempt should fail.
        private final boolean failFirstClose;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Number of close attempts.
        private int closeAttempts;

        /// Failure reported by reads after the initial probe completes.
        private @Nullable IOException readFailure;

        /// Failure repeated by close until cleared.
        private @Nullable IOException closeFailure;

        /// Creates a channel with the requested first-close behavior.
        private TrackingReadableByteChannel(boolean failFirstClose) {
            this.failFirstClose = failFirstClose;
        }

        /// Reports end of input while the channel is open.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new java.nio.channels.ClosedChannelException();
            }
            if (readFailure != null) {
                throw readFailure;
            }
            return -1;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel or reports the configured first-close failure.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            if (failFirstClose && closeAttempts == 1) {
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Returns the number of close attempts.
        private int closeAttempts() {
            return closeAttempts;
        }
    }
}

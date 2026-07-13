// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionProbeResult;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies non-destructive compression detection on forward-only channels.
@NotNullByDefault
final class CompressionChannelProbeTest {
    /// Verifies detected prefix bytes are replayed and default ownership retains the source.
    @Test
    void replaysDetectedPrefixAndRetainsSource() throws IOException {
        byte[] bytes = {
                0x1f, (byte) 0x8b, 0x08, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55
        };
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(bytes));
        CompressionProbeResult probe = CompressionCodecs.probe(source, 8, ChannelOwnership.RETAIN);

        assertTrue(probe.detected());
        assertNotNull(probe.codec());
        assertEquals("gzip", probe.codec().name());
        byte[] prefix = new byte[8];
        probe.prefix().get(prefix);
        assertArrayEquals(new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, 0x11, 0x22, 0x33, 0x44}, prefix);
        assertEquals(0, probe.prefix().position());
        assertArrayEquals(bytes, readInSmallChunks(probe.channel()));
        probe.channel().close();
        assertTrue(source.isOpen());
        source.close();
    }

    /// Verifies unmatched data is replayed and explicit ownership closes the source.
    @Test
    void replaysUnmatchedPrefixAndClosesOwnedSource() throws IOException {
        byte[] bytes = {1, 2, 3, 4, 5, 6, 7};
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(bytes));
        CompressionProbeResult probe = CompressionCodecs.probe(source, ChannelOwnership.CLOSE);

        assertFalse(probe.detected());
        assertNull(probe.codec());
        assertArrayEquals(bytes, readInSmallChunks(probe.channel()));
        probe.channel().close();
        assertFalse(source.isOpen());
    }

    /// Verifies replay closure retries the owned source after a first close failure.
    @Test
    void retriesOwnedSourceClosure() throws IOException {
        CloseFailingOnceChannel source = new CloseFailingOnceChannel(new byte[]{1, 2, 3});
        CompressionProbeResult probe = CompressionCodecs.probe(source, ChannelOwnership.CLOSE);

        assertThrows(IOException.class, probe.channel()::close);
        assertEquals(1, source.closeCount());
        assertTrue(probe.channel().isOpen());
        probe.channel().close();
        assertEquals(2, source.closeCount());
        assertFalse(probe.channel().isOpen());
    }

    /// Verifies invalid arguments do not transfer ownership of the source.
    @Test
    void validatesMinimumPrefixBeforeTakingOwnership() {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompressionCodecs.probe(source, -1L, ChannelOwnership.CLOSE)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompressionCodecs.probe(
                        source,
                        (long) Integer.MAX_VALUE + 1L,
                        ChannelOwnership.CLOSE
                )
        );
        assertTrue(source.isOpen());
    }

    /// Verifies an owned source is closed when probing cannot make progress.
    @Test
    void closesOwnedSourceAfterProbeFailure() {
        ZeroProgressChannel source = new ZeroProgressChannel();
        IOException exception = assertThrows(
                IOException.class,
                () -> CompressionCodecs.probe(source, ChannelOwnership.CLOSE)
        );
        assertTrue(exception.getMessage().contains("made no progress"));
        assertFalse(source.isOpen());
    }

    /// Reads all channel bytes through one-byte target buffers.
    private static byte[] readInSmallChunks(ReadableByteChannel source) throws IOException {
        ByteBuffer result = ByteBuffer.allocate(64);
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            int read = source.read(one);
            if (read < 0) {
                break;
            }
            assertEquals(1, read);
            one.flip();
            result.put(one);
        }
        result.flip();
        byte[] bytes = new byte[result.remaining()];
        result.get(bytes);
        return bytes;
    }

    /// Implements a byte-array channel that fails its first close call.
    @NotNullByDefault
    private static final class CloseFailingOnceChannel implements ReadableByteChannel {
        /// The readable byte-array delegate.
        private final ReadableByteChannel delegate;

        /// The number of close attempts.
        private int closeCount;

        /// Creates a close-failing channel over bytes.
        private CloseFailingOnceChannel(byte[] bytes) {
            delegate = Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        /// Reads from the byte-array delegate.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails the first close attempt and closes the delegate on the second.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Implements a channel that never reports progress.
    @NotNullByDefault
    private static final class ZeroProgressChannel implements ReadableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Returns zero without modifying the target.
        @Override
        public int read(ByteBuffer target) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }
    }
}

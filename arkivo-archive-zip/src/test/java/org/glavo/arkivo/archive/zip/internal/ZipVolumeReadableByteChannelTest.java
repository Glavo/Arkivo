// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP volume concatenation, construction ownership, and retryable cleanup behavior.
@NotNullByDefault
final class ZipVolumeReadableByteChannelTest {
    /// Verifies fragmented reads cross physical volume boundaries and successful close owns every resource.
    @Test
    void readsAcrossVolumesAndOwnsSource() throws IOException {
        TrackingChannel first = new TrackingChannel(new byte[]{1, 2, 3}, 1);
        TrackingChannel second = new TrackingChannel(new byte[]{4, 5, 6}, 2);
        TrackingVolumeSource source = new TrackingVolumeSource(List.of(first, second));
        ZipVolumeReadableByteChannel channel = new ZipVolumeReadableByteChannel(source);

        ByteBuffer destination = ByteBuffer.allocate(6);
        assertEquals(6, channel.read(destination));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, destination.array());
        assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));

        channel.close();
        channel.close();
        assertFalse(channel.isOpen());
        assertFalse(first.isOpen());
        assertFalse(second.isOpen());
        assertTrue(source.closed);
        assertEquals(1, first.closeAttempts);
        assertEquals(1, second.closeAttempts);
        assertEquals(1, source.closeAttempts);
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
    }

    /// Verifies setup failure closes obtained channels while leaving the volume source caller-owned.
    @Test
    void constructionFailureLeavesSourceCallerOwned() throws IOException {
        TrackingChannel first = new TrackingChannel(new byte[]{1}, 1);
        TrackingChannel second = new TrackingChannel(new byte[]{2}, 1);
        second.sizeFailures = 1;
        TrackingVolumeSource source = new TrackingVolumeSource(List.of(first, second));

        IOException failure = assertThrows(
                IOException.class,
                () -> new ZipVolumeReadableByteChannel(source)
        );

        assertEquals("size failure", failure.getMessage());
        assertFalse(first.isOpen());
        assertFalse(second.isOpen());
        assertFalse(source.closed);
        assertEquals(0, source.closeAttempts);
        source.close();
        assertTrue(source.closed);
    }

    /// Verifies channel and source close failures are ordered, aggregated, and retried independently.
    @Test
    void aggregatesAndRetriesIncompleteCleanup() throws IOException {
        TrackingChannel first = new TrackingChannel(new byte[]{1}, 1);
        first.closeFailures = 1;
        TrackingChannel second = new TrackingChannel(new byte[]{2}, 1);
        TrackingVolumeSource source = new TrackingVolumeSource(List.of(first, second));
        source.closeFailures = 1;
        ZipVolumeReadableByteChannel channel = new ZipVolumeReadableByteChannel(source);

        IOException failure = assertThrows(IOException.class, channel::close);
        assertEquals("channel close failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("source close failure", failure.getSuppressed()[0].getMessage());
        assertFalse(channel.isOpen());
        assertTrue(first.isOpen());
        assertFalse(second.isOpen());
        assertFalse(source.closed);
        assertEquals(1, first.closeAttempts);
        assertEquals(1, second.closeAttempts);
        assertEquals(1, source.closeAttempts);
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));

        channel.close();
        channel.close();
        assertFalse(first.isOpen());
        assertTrue(source.closed);
        assertEquals(2, first.closeAttempts);
        assertEquals(1, second.closeAttempts);
        assertEquals(2, source.closeAttempts);
    }

    /// Supplies a fixed finite volume sequence and supports source-close failure injection.
    @NotNullByDefault
    private static final class TrackingVolumeSource implements ArkivoVolumeSource {
        /// Physical volume channels in logical order.
        private final @Unmodifiable List<TrackingChannel> channels;

        /// Number of source-close failures still scheduled.
        private int closeFailures;

        /// Number of source-close attempts.
        private int closeAttempts;

        /// Whether source cleanup completed.
        private boolean closed;

        /// Creates a source exposing the supplied test channels.
        private TrackingVolumeSource(List<TrackingChannel> channels) {
            this.channels = List.copyOf(channels);
        }

        /// Opens the requested test volume, or returns `null` after the finite sequence.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            if (closed) {
                throw new IOException("source is closed");
            }
            return index >= 0L && index < channels.size() ? channels.get((int) index) : null;
        }

        /// Closes source-owned discovery state or performs one scheduled failure.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closed) {
                return;
            }
            if (closeFailures > 0) {
                closeFailures--;
                throw new IOException("source close failure");
            }
            closed = true;
        }
    }

    /// Provides one fragmented read-only physical volume with configurable metadata and close failures.
    @NotNullByDefault
    private static final class TrackingChannel implements SeekableByteChannel {
        /// Immutable volume bytes.
        private final byte @Unmodifiable [] bytes;

        /// Maximum bytes returned by one positive read.
        private final int maximumReadSize;

        /// Number of size failures still scheduled.
        private int sizeFailures;

        /// Number of close failures still scheduled.
        private int closeFailures;

        /// Number of close attempts.
        private int closeAttempts;

        /// Current physical position.
        private long position;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a channel over a private copy of the supplied bytes.
        private TrackingChannel(byte @Unmodifiable [] bytes, int maximumReadSize) {
            this.bytes = bytes.clone();
            this.maximumReadSize = maximumReadSize;
        }

        /// Reads one bounded fragment from the current position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }
            int bytePosition = Math.toIntExact(position);
            int count = Math.min(
                    Math.min(destination.remaining(), maximumReadSize),
                    bytes.length - bytePosition
            );
            destination.put(bytes, bytePosition, count);
            position += count;
            return count;
        }

        /// Rejects writes because test volumes are read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current physical position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current physical position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the volume size or performs one scheduled metadata failure.
        @Override
        public long size() throws IOException {
            ensureOpen();
            if (sizeFailures > 0) {
                sizeFailures--;
                throw new IOException("size failure");
            }
            return bytes.length;
        }

        /// Rejects truncation because test volumes are read-only.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the volume or performs one scheduled failure while leaving it open.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (!open) {
                return;
            }
            if (closeFailures > 0) {
                closeFailures--;
                throw new IOException("channel close failure");
            }
            open = false;
        }

        /// Requires the physical volume to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests bounded 7z archive slice channel behavior.
@NotNullByDefault
public final class SevenZipFileSliceChannelTest {
    /// Verifies that slice-relative positions cannot overflow absolute archive offsets.
    @Test
    public void rejectsOverflowingAbsoluteReadPosition() throws IOException {
        try (SevenZipFileSliceChannel channel = new SevenZipFileSliceChannel(
                new ReadOnlyByteArrayChannel(new byte[]{1}),
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE
        )) {
            channel.position(2);

            IOException exception = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));

            assertEquals(true, exception.getMessage().contains("7z slice offset is too large"));
        }
    }

    /// Verifies that an empty destination buffer reports no bytes read even at slice EOF.
    @Test
    public void emptyReadAtEndOfSliceReturnsZero() throws IOException {
        try (SevenZipFileSliceChannel channel = new SevenZipFileSliceChannel(
                new ReadOnlyByteArrayChannel(new byte[]{1}),
                0,
                1
        )) {
            channel.position(1);

            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        }
    }

    /// Verifies that operations on a closed slice channel report closed state.
    @Test
    public void operationsAfterCloseAreRejectedAsClosed() throws IOException {
        SevenZipFileSliceChannel channel = new SevenZipFileSliceChannel(
                new ReadOnlyByteArrayChannel(new byte[]{1, 2, 3}),
                0,
                3
        );

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

    /// Verifies that a delegate close failure still leaves the slice wrapper closed and allows cleanup retry.
    @Test
    public void closeFailureAllowsDelegateCleanupRetry() throws IOException {
        FailingCloseSeekableByteChannel delegate = new FailingCloseSeekableByteChannel(new byte[]{1, 2, 3});
        SevenZipFileSliceChannel channel = new SevenZipFileSliceChannel(delegate, 0, 3);

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals(true, exception.getMessage().contains("close failed"));
        assertEquals(false, channel.isOpen());
        assertEquals(true, delegate.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
        assertEquals(1, delegate.closeCount());

        channel.close();
        channel.close();

        assertEquals(false, delegate.isOpen());
        assertEquals(2, delegate.closeCount());
    }

    /// Provides an in-memory channel whose close operation can fail.
    @NotNullByDefault
    private static final class FailingCloseSeekableByteChannel implements SeekableByteChannel {
        /// The channel content.
        private final byte[] content;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// The number of close calls that should fail without closing this channel.
        private int closeFailures = 1;

        /// The number of close calls.
        private int closeCount;

        /// Creates a channel over the given content.
        private FailingCloseSeekableByteChannel(byte[] content) {
            this.content = Objects.requireNonNull(content, "content");
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }

            int count = Math.min(destination.remaining(), content.length - position);
            destination.put(content, position, count);
            position += count;
            return count;
        }

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition is out of range");
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Fails without closing this channel.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailures > 0) {
                closeFailures--;
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}

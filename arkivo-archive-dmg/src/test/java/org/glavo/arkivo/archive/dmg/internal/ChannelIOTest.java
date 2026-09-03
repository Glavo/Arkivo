// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exact disk-image reads and overflow-safe parser arithmetic.
@NotNullByDefault
final class ChannelIOTest {
    /// Verifies fragmented positional reads fill only the target's remaining region.
    @Test
    void readsFragmentedRangesWithoutTouchingTargetGuards() throws IOException {
        FragmentingSeekableByteChannel source = new FragmentingSeekableByteChannel(
                new byte[]{10, 11, 12, 13, 14, 15, 16, 17},
                2
        );
        ByteBuffer target = ByteBuffer.allocate(8);
        target.put(new byte[]{99, 99, 99, 99, 99, 99, 99, 99});
        target.position(2);
        target.limit(6);

        ChannelIO.readFully(source, 3L, target);

        assertEquals(6, target.position());
        assertEquals(6, target.limit());
        assertArrayEquals(new byte[]{99, 99, 13, 14, 15, 16, 99, 99}, target.array());
        assertEquals(7L, source.position());

        assertArrayEquals(new byte[]{11, 12, 13}, ChannelIO.readBytes(source, 1L, 3));
        assertArrayEquals(new byte[0], ChannelIO.readBytes(source, source.size(), 0));
    }

    /// Verifies truncation and zero progress report the exact progress already reflected in the target.
    @Test
    void reportsPartialProgressForTruncationAndStalls() throws IOException {
        FragmentingSeekableByteChannel truncated = new FragmentingSeekableByteChannel(
                new byte[]{1, 2, 3},
                1
        );
        ByteBuffer truncatedTarget = ByteBuffer.allocate(4);
        EOFException endOfFile = assertThrows(
                EOFException.class,
                () -> ChannelIO.readFully(truncated, 1L, truncatedTarget)
        );
        assertEquals(2, truncatedTarget.position());
        assertEquals(3L, truncated.position());
        assertTrue(endOfFile.getMessage().contains("offset 3"));

        FragmentingSeekableByteChannel stalled = new FragmentingSeekableByteChannel(
                new byte[]{4, 5, 6, 7, 8},
                2
        );
        stalled.returnZeroOnRead(2);
        ByteBuffer stalledTarget = ByteBuffer.allocate(5);
        IOException noProgress = assertThrows(
                IOException.class,
                () -> ChannelIO.readFully(stalled, 1L, stalledTarget)
        );
        assertEquals(2, stalledTarget.position());
        assertEquals(3L, stalled.position());
        assertTrue(noProgress.getMessage().contains("made no progress at offset 3"));
    }

    /// Verifies invalid read arguments fail before changing source or target state.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesReadArgumentsBeforeAccess() throws IOException {
        FragmentingSeekableByteChannel source = new FragmentingSeekableByteChannel(
                new byte[]{1, 2, 3},
                2
        );
        source.position(2L);
        ByteBuffer target = ByteBuffer.allocate(1);

        assertThrows(IllegalArgumentException.class, () -> ChannelIO.readFully(source, -1L, target));
        assertEquals(2L, source.position());
        assertEquals(0, target.position());
        assertThrows(IllegalArgumentException.class, () -> ChannelIO.readBytes(source, 0L, -1));
        assertEquals(2L, source.position());
        assertThrows(NullPointerException.class, () -> ChannelIO.readFully(null, 0L, target));
        assertThrows(NullPointerException.class, () -> ChannelIO.readFully(source, 0L, null));
    }

    /// Verifies addition, multiplication, and enclosing-range checks at signed-long boundaries.
    @Test
    void rejectsInvalidOrOverflowingParserArithmetic() throws IOException {
        assertEquals(Long.MAX_VALUE, ChannelIO.add(Long.MAX_VALUE - 1L, 1L, "sum"));
        assertEquals(Long.MAX_VALUE, ChannelIO.multiply(Long.MAX_VALUE, 1L, "product"));
        assertEquals(0L, ChannelIO.multiply(Long.MAX_VALUE, 0L, "product"));
        ChannelIO.requireRange(4L, 6L, 10L, "extent");

        assertArithmeticFailure(() -> ChannelIO.add(-1L, 0L, "sum"), "sum");
        assertArithmeticFailure(() -> ChannelIO.add(Long.MAX_VALUE, 1L, "sum"), "sum");
        assertArithmeticFailure(() -> ChannelIO.multiply(0L, -1L, "product"), "product");
        assertArithmeticFailure(() -> ChannelIO.multiply(Long.MAX_VALUE, 2L, "product"), "product");
        assertArithmeticFailure(() -> ChannelIO.requireRange(4L, 7L, 10L, "extent"), "extent");
        assertArithmeticFailure(
                () -> ChannelIO.requireRange(Long.MAX_VALUE, 1L, Long.MAX_VALUE, "extent"),
                "extent"
        );
        assertArithmeticFailure(() -> ChannelIO.requireRange(0L, 0L, -1L, "extent"), "extent");
    }

    /// Verifies one malformed arithmetic operation fails with its caller-provided description.
    private static void assertArithmeticFailure(CheckedOperation operation, String description) {
        IOException failure = assertThrows(IOException.class, operation::run);
        assertTrue(failure.getMessage().contains(description));
    }

    /// Executes one parser operation that may report malformed input.
    @FunctionalInterface
    @NotNullByDefault
    private interface CheckedOperation {
        /// Executes the parser operation.
        void run() throws IOException;
    }

    /// Provides a read-only in-memory channel with controlled fragmentation and stalls.
    @NotNullByDefault
    private static final class FragmentingSeekableByteChannel implements SeekableByteChannel {
        /// Immutable source bytes.
        private final byte @Unmodifiable [] bytes;

        /// Maximum bytes returned by one positive read.
        private final int maximumReadSize;

        /// One-based read call that returns zero, or zero when disabled.
        private int zeroRead;

        /// Number of attempted reads.
        private int readCount;

        /// Current absolute position.
        private long position;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a channel over a private copy of the supplied bytes.
        private FragmentingSeekableByteChannel(byte @Unmodifiable [] bytes, int maximumReadSize) {
            this.bytes = bytes.clone();
            this.maximumReadSize = maximumReadSize;
        }

        /// Configures one read call to return zero.
        private void returnZeroOnRead(int read) {
            zeroRead = read;
        }

        /// Reads one configured fragment or reports the configured stall.
        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            readCount++;
            if (readCount == zeroRead) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }
            int arrayPosition = Math.toIntExact(position);
            int length = Math.min(
                    Math.min(target.remaining(), maximumReadSize),
                    bytes.length - arrayPosition
            );
            target.put(bytes, arrayPosition, length);
            position += length;
            return length;
        }

        /// Rejects writes because the source is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current absolute position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Changes the current absolute position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the immutable source size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Rejects truncation because the source is read-only.
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

        /// Closes the channel.
        @Override
        public void close() {
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the bounded-read, extent-mapping, capability, and cleanup contracts of DMG channel views.
@NotNullByDefault
final class DMGChannelViewTest {
    /// Combines failure kinds, partial-byte counts, and destination storage kinds.
    private static Stream<Arguments> readFailures() {
        return Stream.of(
                new IOException("physical read failure"),
                new IllegalStateException("physical read failure"),
                new AssertionError("physical read failure")
        ).flatMap(failure -> Stream.of(0, 2).flatMap(count -> Stream.of(false, true)
                .map(direct -> Arguments.of(failure, count, direct))));
    }

    /// Verifies a slice retains bytes delivered by a failed physical read and resumes at the next byte.
    @ParameterizedTest
    @MethodSource("readFailures")
    void slicedChannelPreservesFailedReadProgress(Throwable failure, int count, boolean direct) throws IOException {
        TestSeekableByteChannel source = new TestSeekableByteChannel(sequence(16), 8);
        source.failedRead = 1;
        source.readFailure = failure;
        source.bytesBeforeFailure = count;
        try (SeekableByteChannel channel = SlicedSeekableByteChannel.open(source, 3L, 7L)) {
            assertReadFailureProgress(channel, failure, count, new byte[]{3, 4, 5, 6, 7, 8, 9}, direct);
        }
        assertFalse(source.isOpen());
    }

    /// Verifies a failure inside the second extent preserves progress from both physical reads.
    @ParameterizedTest
    @MethodSource("readFailures")
    void forkChannelPreservesFailedReadProgress(Throwable failure, int count, boolean direct) throws IOException {
        TestSeekableByteChannel source = new TestSeekableByteChannel(sequence(16), 8);
        source.failedRead = 2;
        source.readFailure = failure;
        source.bytesBeforeFailure = count;
        HFSPlusFork fork = new HFSPlusFork(7L, 2L, List.of(
                new HFSPlusExtent(2L, 1L), new HFSPlusExtent(0L, 1L)
        ));
        try (SeekableByteChannel channel = HFSPlusForkChannel.open(source, fork, 4)) {
            assertReadFailureProgress(channel, failure, 4 + count, new byte[]{8, 9, 10, 11, 0, 1, 2}, direct);
        }
        assertFalse(source.isOpen());
    }

    /// Verifies a failed raw run retains synthesized gap bytes and all bytes delivered from earlier runs.
    @ParameterizedTest
    @MethodSource("readFailures")
    void udifChannelPreservesFailedReadProgress(Throwable failure, int count, boolean direct) throws IOException {
        TestSeekableByteChannel source = new TestSeekableByteChannel(sequence(16), 8);
        source.failedRead = 2;
        source.readFailure = failure;
        source.bytesBeforeFailure = count;
        UDIFLayout layout = new UDIFLayout(8L, List.of(
                new UDIFRun(UDIFConstants.BLOCK_RAW, 2L, 3L, 9L, 3L),
                new UDIFRun(UDIFConstants.BLOCK_RAW, 5L, 3L, 3L, 3L)
        ));
        try (SeekableByteChannel channel = UDIFBlockChannel.open(source, layout, ArchiveReadLimits.UNLIMITED)) {
            assertReadFailureProgress(channel, failure, 5 + count, new byte[]{0, 0, 9, 10, 11, 3, 4, 5}, direct);
        }
        assertFalse(source.isOpen());
    }

    /// Verifies partial progress propagates through the complete UDIF, partition, and fork channel stack.
    @ParameterizedTest
    @MethodSource("readFailures")
    void layeredForkPreservesFailedReadProgress(Throwable failure, int count, boolean direct) throws IOException {
        TestSeekableByteChannel source = new TestSeekableByteChannel(sequence(32), 8);
        source.failedRead = 2;
        source.readFailure = failure;
        source.bytesBeforeFailure = count;
        SeekableByteChannel disk = UDIFBlockChannel.open(source, rawLayout(32L), ArchiveReadLimits.UNLIMITED);
        SeekableByteChannel partition = SlicedSeekableByteChannel.open(disk, 4L, 16L);
        HFSPlusFork fork = new HFSPlusFork(7L, 2L, List.of(
                new HFSPlusExtent(2L, 1L), new HFSPlusExtent(0L, 1L)
        ));
        try (SeekableByteChannel channel = HFSPlusForkChannel.open(partition, fork, 4)) {
            assertReadFailureProgress(channel, failure, 4 + count, new byte[]{12, 13, 14, 15, 4, 5, 6}, direct);
        }
        assertFalse(partition.isOpen());
        assertFalse(disk.isOpen());
        assertFalse(source.isOpen());
        assertEquals(1, source.closeAttempts);
    }

    /// Verifies observable failed-read progress, unchanged buffer bounds, and exact continuation content.
    private static void assertReadFailureProgress(
            SeekableByteChannel channel,
            Throwable failure,
            int completed,
            byte[] expected,
            boolean direct
    ) throws IOException {
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(expected.length + 4)
                : ByteBuffer.allocate(expected.length + 4);
        for (int index = 0; index < target.capacity(); index++) {
            target.put(index, (byte) 99);
        }
        target.position(2).limit(2 + expected.length).mark();
        assertSame(failure, assertThrows(failure.getClass(), () -> channel.read(target)));
        assertEquals(2 + completed, target.position());
        assertEquals(2 + expected.length, target.limit());
        assertEquals(completed, channel.position());
        assertTrue(channel.isOpen());
        ByteBuffer prefix = target.duplicate().flip().position(2);
        byte[] delivered = new byte[prefix.remaining()];
        prefix.get(delivered);
        assertArrayEquals(Arrays.copyOf(expected, completed), delivered);

        while (target.hasRemaining()) {
            assertTrue(channel.read(target) > 0);
        }
        assertEquals(expected.length, channel.position());
        assertEquals(0, channel.read(target));
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        assertEquals(2, target.reset().position());
        byte[] actual = new byte[expected.length];
        target.get(actual);
        assertArrayEquals(expected, actual);
        target.clear();
        assertEquals((byte) 99, target.get(0));
        assertEquals((byte) 99, target.get(1));
        assertEquals((byte) 99, target.get(expected.length + 2));
        assertEquals((byte) 99, target.get(expected.length + 3));
    }

    /// Verifies a slice ignores the source position, restricts reads to its range, and owns the source.
    @Test
    void slicedChannelBoundsReadsAndPreservesInterruptibility() throws IOException {
        InterruptibleTestChannel source = new InterruptibleTestChannel(sequence(10), 10);
        source.position(9L);
        SeekableByteChannel channel = SlicedSeekableByteChannel.open(source, 2L, 5L);

        assertInstanceOf(InterruptibleChannel.class, channel);
        assertEquals(5L, channel.size());
        ByteBuffer destination = ByteBuffer.allocate(8);
        Arrays.fill(destination.array(), (byte) 99);
        destination.position(1);
        destination.limit(7);

        assertEquals(5, channel.read(destination));
        assertEquals(6, destination.position());
        assertArrayEquals(new byte[]{99, 2, 3, 4, 5, 6, 99, 99}, destination.array());
        assertEquals(5L, channel.position());
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        assertThrows(ReadOnlyBufferException.class, () -> channel.read(ByteBuffer.allocate(1).asReadOnlyBuffer()));

        ByteBuffer writeSource = ByteBuffer.wrap(new byte[]{1});
        assertThrows(NonWritableChannelException.class, () -> channel.write(writeSource));
        assertEquals(0, writeSource.position());
        assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));

        channel.close();
        assertFalse(source.isOpen());
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, channel::position);
    }

    /// Verifies an HFS Plus fork reads a logical byte sequence across noncontiguous allocation extents.
    @Test
    void forkChannelMapsNoncontiguousExtents() throws IOException {
        InterruptibleTestChannel partition = new InterruptibleTestChannel(sequence(32), 3);
        HFSPlusFork fork = new HFSPlusFork(
                10L,
                3L,
                List.of(new HFSPlusExtent(2L, 1L), new HFSPlusExtent(0L, 2L))
        );
        SeekableByteChannel channel = HFSPlusForkChannel.open(partition, fork, 4);

        assertInstanceOf(InterruptibleChannel.class, channel);
        ByteBuffer destination = ByteBuffer.allocate(10);
        assertEquals(10, channel.read(destination));
        assertArrayEquals(new byte[]{8, 9, 10, 11, 0, 1, 2, 3, 4, 5}, destination.array());
        assertEquals(10L, channel.position());
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));

        channel.position(3L);
        ByteBuffer tail = ByteBuffer.allocate(4);
        assertEquals(4, channel.read(tail));
        assertArrayEquals(new byte[]{11, 0, 1, 2}, tail.array());

        channel.close();
        assertFalse(partition.isOpen());
        assertThrows(ClosedChannelException.class, channel::size);
    }

    /// Verifies HFS Plus fork failures preserve bytes completed before an extent gap, truncation, or stall.
    @Test
    void forkChannelReportsPartialProgress() throws IOException {
        TestSeekableByteChannel incompleteSource = new TestSeekableByteChannel(sequence(4), 4);
        try (SeekableByteChannel incomplete = HFSPlusForkChannel.open(
                incompleteSource,
                new HFSPlusFork(5L, 1L, List.of(new HFSPlusExtent(0L, 1L))),
                4
        )) {
            ByteBuffer target = ByteBuffer.allocate(5);
            IOException failure = assertThrows(IOException.class, () -> incomplete.read(target));
            assertEquals("HFS Plus fork extents do not cover the declared logical size", failure.getMessage());
            assertEquals(4, target.position());
            assertEquals(4L, incomplete.position());
        }

        TestSeekableByteChannel truncatedSource = new TestSeekableByteChannel(sequence(2), 2);
        try (SeekableByteChannel truncated = HFSPlusForkChannel.open(
                truncatedSource,
                new HFSPlusFork(4L, 1L, List.of(new HFSPlusExtent(0L, 1L))),
                4
        )) {
            ByteBuffer target = ByteBuffer.allocate(4);
            IOException failure = assertThrows(IOException.class, () -> truncated.read(target));
            assertEquals("Unexpected end of HFS Plus allocation extent", failure.getMessage());
            assertEquals(2, target.position());
            assertEquals(2L, truncated.position());
        }

        TestSeekableByteChannel stalledSource = new TestSeekableByteChannel(sequence(4), 4);
        stalledSource.zeroRead = 1;
        try (SeekableByteChannel stalled = HFSPlusForkChannel.open(
                stalledSource,
                new HFSPlusFork(4L, 1L, List.of(new HFSPlusExtent(0L, 1L))),
                4
        )) {
            ByteBuffer target = ByteBuffer.allocate(4);
            IOException failure = assertThrows(IOException.class, () -> stalled.read(target));
            assertEquals("HFS Plus extent read made no progress", failure.getMessage());
            assertEquals(0, target.position());
            assertEquals(0L, stalled.position());
        }
    }

    /// Verifies a UDIF view fills unmapped gaps and sparse runs while reading raw runs from physical offsets.
    @Test
    void udifChannelReadsGapsSparseAndRawRuns() throws IOException {
        TestSeekableByteChannel source = new InterruptibleTestChannel(
                new byte[]{50, 51, 52, 53, 54, 55, 56},
                2
        );
        UDIFLayout layout = new UDIFLayout(12L, List.of(
                new UDIFRun(UDIFConstants.BLOCK_RAW, 2L, 3L, 1L, 3L),
                new UDIFRun(UDIFConstants.BLOCK_IGNORE, 7L, 2L, 0L, 0L),
                new UDIFRun(UDIFConstants.BLOCK_RAW, 9L, 3L, 4L, 3L)
        ));
        SeekableByteChannel channel = UDIFBlockChannel.open(source, layout, ArchiveReadLimits.UNLIMITED);

        assertInstanceOf(InterruptibleChannel.class, channel);
        ByteBuffer destination = ByteBuffer.allocate(14);
        Arrays.fill(destination.array(), (byte) 99);
        destination.position(1);
        destination.limit(13);

        assertEquals(12, channel.read(destination));
        assertEquals(13, destination.position());
        assertArrayEquals(
                new byte[]{99, 0, 0, 51, 52, 53, 0, 0, 0, 0, 54, 55, 56, 99},
                destination.array()
        );
        assertEquals(12L, channel.position());
        assertTrue(source.readCount > 2);

        channel.close();
        assertFalse(source.isOpen());
    }

    /// Verifies raw UDIF read failures retain only progress completed before truncation or a stall.
    @Test
    void udifRawRunReportsPartialProgress() throws IOException {
        TestSeekableByteChannel stalledSource = new TestSeekableByteChannel(new byte[]{1, 2}, 2);
        stalledSource.zeroRead = 1;
        try (SeekableByteChannel stalled = UDIFBlockChannel.open(
                stalledSource,
                rawLayout(2L),
                ArchiveReadLimits.UNLIMITED
        )) {
            ByteBuffer target = ByteBuffer.allocate(3);
            target.position(1);
            IOException failure = assertThrows(IOException.class, () -> stalled.read(target));
            assertEquals("Raw UDIF run read made no progress", failure.getMessage());
            assertEquals(1, target.position());
            assertEquals(0L, stalled.position());
        }

        TestSeekableByteChannel truncatedSource = new TestSeekableByteChannel(new byte[]{7}, 1);
        try (SeekableByteChannel truncated = UDIFBlockChannel.open(
                truncatedSource,
                rawLayout(2L),
                ArchiveReadLimits.UNLIMITED
        )) {
            ByteBuffer target = ByteBuffer.allocate(2);
            IOException failure = assertThrows(IOException.class, () -> truncated.read(target));
            assertEquals("Unexpected end of raw UDIF run", failure.getMessage());
            assertEquals(1, target.position());
            assertEquals(1L, truncated.position());
        }
    }

    /// Verifies closing a UDIF view retries an encoded source left open by an earlier close failure.
    @Test
    void udifChannelRetriesIncompleteSourceClose() throws IOException {
        TestSeekableByteChannel retryingSource = new TestSeekableByteChannel(new byte[0], 1);
        retryingSource.closeFailures = 1;
        SeekableByteChannel retrying = UDIFBlockChannel.open(
                retryingSource,
                new UDIFLayout(0L, List.of()),
                ArchiveReadLimits.UNLIMITED
        );

        IOException failure = assertThrows(IOException.class, retrying::close);
        assertEquals("close failure", failure.getMessage());
        assertFalse(retrying.isOpen());
        assertTrue(retryingSource.isOpen());
        assertEquals(1, retryingSource.closeAttempts);
        assertThrows(ClosedChannelException.class, () -> retrying.read(ByteBuffer.allocate(1)));

        retrying.close();
        retrying.close();
        assertFalse(retryingSource.isOpen());
        assertEquals(2, retryingSource.closeAttempts);

        TestSeekableByteChannel closedOnFailure = new TestSeekableByteChannel(new byte[0], 1);
        closedOnFailure.closeFailures = 1;
        closedOnFailure.closeBeforeFailure = true;
        SeekableByteChannel completed = UDIFBlockChannel.open(
                closedOnFailure,
                new UDIFLayout(0L, List.of()),
                ArchiveReadLimits.UNLIMITED
        );

        assertThrows(IOException.class, completed::close);
        completed.close();
        assertFalse(closedOnFailure.isOpen());
        assertEquals(1, closedOnFailure.closeAttempts);
    }

    /// Returns one raw UDIF layout with matching logical and physical sizes.
    private static UDIFLayout rawLayout(long size) {
        return new UDIFLayout(size, List.of(
                new UDIFRun(UDIFConstants.BLOCK_RAW, 0L, size, 0L, size)
        ));
    }

    /// Returns increasing byte values from zero through `size - 1`.
    private static byte[] sequence(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) index;
        }
        return bytes;
    }

    /// Provides a read-only seekable byte channel with controllable fragmentation and close failures.
    @NotNullByDefault
    private static class TestSeekableByteChannel implements SeekableByteChannel {
        /// Immutable channel bytes.
        private final byte @Unmodifiable [] bytes;

        /// Maximum positive read size.
        private final int maximumReadSize;

        /// One-based read call that returns zero, or zero when disabled.
        private int zeroRead;

        /// Number of read calls.
        private int readCount;

        /// One-based physical read that throws after partial progress, or zero when disabled.
        private int failedRead;

        /// Failure reported by the selected read, or `null` when no failure is scheduled.
        private @Nullable Throwable readFailure;

        /// Maximum bytes transferred by the selected failing read.
        private int bytesBeforeFailure;

        /// Number of close failures still scheduled.
        private int closeFailures;

        /// Whether a scheduled close failure also closes the channel.
        private boolean closeBeforeFailure;

        /// Number of close calls received.
        private int closeAttempts;

        /// Current absolute byte position.
        private long position;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a channel over a private copy of the supplied bytes.
        private TestSeekableByteChannel(byte @Unmodifiable [] bytes, int maximumReadSize) {
            this.bytes = bytes.clone();
            this.maximumReadSize = maximumReadSize;
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
            @Nullable Throwable failure = readCount == failedRead ? readFailure : null;
            if (failure != null) {
                length = Math.min(length, bytesBeforeFailure);
            }
            target.put(bytes, arrayPosition, length);
            position += length;
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return length;
        }

        /// Rejects writes because this test channel is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current absolute byte position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current absolute byte position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the immutable byte size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Rejects truncation because this test channel is read-only.
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

    /// Marks the controllable test channel as interruptible.
    @NotNullByDefault
    private static final class InterruptibleTestChannel
            extends TestSeekableByteChannel implements InterruptibleChannel {
        /// Creates an interruptible channel over a private byte copy.
        private InterruptibleTestChannel(byte @Unmodifiable [] bytes, int maximumReadSize) {
            super(bytes, maximumReadSize);
        }
    }
}

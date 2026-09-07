// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests bounded archive slice channel behavior.
@NotNullByDefault
public final class ArchiveSliceChannelTest {
    /// Holds files used to verify interruption of an owned file channel.
    @TempDir
    private Path temporaryDirectory;

    /// Supplies checked, unchecked, and error failures independently for each test invocation.
    private static Stream<Throwable> failures() {
        return Stream.of(new IOException("source failure"), new IllegalStateException("source failure"),
                new AssertionError("source failure"));
    }

    /// Combines source failures with progress counts and destination storage kinds.
    private static Stream<Arguments> readFailures() {
        return failures().flatMap(failure -> Stream.of(0, 2, 5).flatMap(count -> Stream.of(false, true)
                .map(direct -> Arguments.of(failure, count, direct))));
    }

    /// Verifies a range never exposes adjacent bytes and seeking does not eagerly access its source.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void boundsReadsAndPreservesBufferState(boolean direct) throws IOException {
        TestChannel source = new TestChannel(new byte[]{9, 8, 1, 2, 3, 4, 5, 7, 6});
        source.position(8);
        try (SeekableByteChannel channel = ArchiveSliceChannel.open(source, 2, 5)) {
            assertFalse(channel instanceof InterruptibleChannel);
            assertEquals(8, source.position());
            assertEquals(0, channel.position());
            assertEquals(5, channel.size());
            assertSame(channel, channel.position(1));
            assertEquals(8, source.position());

            ByteBuffer storage = direct ? ByteBuffer.allocateDirect(12) : ByteBuffer.allocate(12);
            for (int i = 0; i < storage.capacity(); i++) {
                storage.put(i, (byte) 99);
            }
            ByteBuffer destination = storage.slice(2, 8).position(1).mark().limit(7);
            assertEquals(4, channel.read(destination));
            assertEquals(5, destination.position());
            assertEquals(7, destination.limit());
            destination.reset();
            assertEquals(1, destination.position());
            byte[] actual = new byte[12];
            storage.get(actual);
            assertArrayEquals(new byte[]{99, 99, 99, 2, 3, 4, 5, 99, 99, 99, 99, 99}, actual);
            assertEquals(5, channel.position());
            assertEquals(-1, channel.read(destination));
            assertEquals(7, source.position());
            channel.position(0);
            assertEquals(1, channel.read(ByteBuffer.allocate(1)));
            assertEquals(1, channel.position());
        }
        assertEquals(1, source.closeCount());
    }

    /// Verifies failed physical reads retain exactly the bytes already delivered, including a complete slice.
    @ParameterizedTest
    @MethodSource("readFailures")
    public void retainsPartialReadProgress(Throwable failure, int count, boolean direct) throws IOException {
        TestChannel source = new TestChannel(new byte[]{9, 8, 1, 2, 3, 4, 5, 7, 6});
        source.readFailure = failure;
        source.bytesBeforeFailure = count;
        try (SeekableByteChannel channel = ArchiveSliceChannel.open(source, 2, 5)) {
            ByteBuffer target = direct ? ByteBuffer.allocateDirect(9) : ByteBuffer.allocate(9);
            for (int i = 0; i < target.capacity(); i++) {
                target.put(i, (byte) 99);
            }
            target.position(2).mark().limit(8);
            assertSame(failure, assertThrows(failure.getClass(), () -> channel.read(target)));
            assertEquals(2 + count, target.position());
            assertEquals(8, target.limit());
            assertEquals(count, channel.position());
            target.reset();
            assertEquals(2, target.position());
            target.position(2 + count);
            if (count < 5) {
                assertEquals(5 - count, channel.read(target));
            }
            assertEquals(-1, channel.read(target));
            assertEquals(7, source.position());
            target.clear();
            byte[] actual = new byte[9];
            target.get(actual);
            assertArrayEquals(new byte[]{99, 99, 1, 2, 3, 4, 5, 99, 99}, actual);
        }
    }

    /// Verifies truncated ranges report an error without changing the declared size or losing delivered bytes.
    @Test
    public void distinguishesTruncationFromRangeEnd() throws IOException {
        try (SeekableByteChannel channel = ArchiveSliceChannel.open(
                new ReadOnlyByteArrayChannel(new byte[]{9, 8, 1, 2, 3}), 2, 5)) {
            ByteBuffer target = ByteBuffer.allocate(7).position(1).mark().limit(6);
            assertEquals(3, channel.read(target));
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(EOFException.class, () -> channel.read(target));
                assertEquals(4, target.position());
                assertEquals(6, target.limit());
                assertEquals(3, channel.position());
                assertEquals(5, channel.size());
                assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            }
            target.reset();
            byte[] actual = new byte[3];
            target.get(actual);
            assertArrayEquals(new byte[]{1, 2, 3}, actual);
            channel.position(5);
            assertEquals(-1, channel.read(target));
        }
    }

    /// Verifies failed seeks and zero-byte physical reads do not consume destination or slice positions.
    @ParameterizedTest
    @MethodSource("failures")
    public void retainsStateWithoutReadProgress(Throwable failure) throws IOException {
        TestChannel source = new TestChannel(new byte[]{9, 8, 1, 2, 3});
        source.seekFailure = failure;
        try (SeekableByteChannel channel = ArchiveSliceChannel.open(source, 2, 3)) {
            ByteBuffer target = ByteBuffer.allocate(7).position(1).mark().limit(6);
            assertSame(failure, assertThrows(failure.getClass(), () -> channel.read(target)));
            assertEquals(0, source.position());
            assertEquals(0, source.readCount);
            assertEquals(0, channel.position());
            assertEquals(1, target.position());
            assertEquals(6, target.limit());
            source.zeroRead = true;
            assertEquals(0, channel.read(target));
            assertEquals(0, channel.position());
            assertEquals(1, target.position());
            assertEquals(3, channel.read(target));
            target.reset();
            byte[] actual = new byte[3];
            target.get(actual);
            assertArrayEquals(new byte[]{1, 2, 3}, actual);
        }
    }

    /// Verifies validation and empty ranges do not reposition or read the source.
    @Test
    public void validatesBeforeSourceAccess() throws IOException {
        TestChannel source = new TestChannel(new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> ArchiveSliceChannel.open(source, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> ArchiveSliceChannel.open(source, 0, -1));
        assertTrue(source.isOpen());
        assertEquals(0, source.closeCount());
        try (SeekableByteChannel channel = ArchiveSliceChannel.open(source, Long.MAX_VALUE, 0)) {
            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertThrows(ReadOnlyBufferException.class,
                    () -> channel.read(ByteBuffer.allocate(0).asReadOnlyBuffer()));
            assertThrows(ReadOnlyBufferException.class,
                    () -> channel.read(ByteBuffer.allocate(1).asReadOnlyBuffer()));
            assertEquals(0, source.position());
            assertEquals(0, source.readCount);
        }
    }

    /// Verifies nested slices translate positions once at each layer and close the complete owned chain once.
    @Test
    public void composesNestedRangesAndClosure() throws IOException {
        TestChannel source = new TestChannel(new byte[]{9, 8, 1, 2, 3, 4, 5, 7, 6});
        SeekableByteChannel outer = ArchiveSliceChannel.open(source, 2, 5);
        SeekableByteChannel inner = ArchiveSliceChannel.open(outer, 1, 3);
        ByteBuffer target = ByteBuffer.allocate(8);
        assertEquals(3, inner.read(target));
        assertArrayEquals(new byte[]{2, 3, 4}, Arrays.copyOf(target.array(), 3));
        assertEquals(3, inner.position());
        assertEquals(4, outer.position());
        assertEquals(6, source.position());
        assertEquals(-1, inner.read(target));
        inner.close();
        outer.close();
        inner.close();
        assertFalse(outer.isOpen());
        assertEquals(1, source.closeCount());
    }

    /// Verifies a file-backed slice retains interruptible closure through nested range views.
    @Test
    public void preservesFileChannelInterruption() throws IOException {
        Path path = temporaryDirectory.resolve("slice.bin");
        Files.write(path, new byte[]{1, 2, 3});
        FileChannel source = FileChannel.open(path, StandardOpenOption.READ);
        try (SeekableByteChannel outer = ArchiveSliceChannel.open(source, 0, 3);
             SeekableByteChannel inner = ArchiveSliceChannel.open(outer, 1, 1)) {
            assertInstanceOf(InterruptibleChannel.class, outer);
            assertInstanceOf(InterruptibleChannel.class, inner);
            ByteBuffer target = ByteBuffer.allocate(1);
            Thread.currentThread().interrupt();
            try {
                assertThrows(ClosedByInterruptException.class, () -> inner.read(target));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(0, target.position());
            assertFalse(source.isOpen());
            assertFalse(outer.isOpen());
            assertFalse(inner.isOpen());
            assertThrows(ClosedChannelException.class, inner::position);
        }
    }

    /// Verifies that slice-relative positions cannot overflow absolute archive offsets.
    @Test
    public void rejectsOverflowingAbsoluteReadPosition() throws IOException {
        try (SeekableByteChannel channel = ArchiveSliceChannel.open(
                new ReadOnlyByteArrayChannel(new byte[]{1}),
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE
        )) {
            channel.position(2);

            IOException exception = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));

            assertEquals(true, exception.getMessage().contains("Archive slice offset is too large"));
        }
    }

    /// Verifies that an empty destination buffer reports no bytes read even at slice EOF.
    @Test
    public void emptyReadAtEndOfSliceReturnsZero() throws IOException {
        try (SeekableByteChannel channel = ArchiveSliceChannel.open(
                new ReadOnlyByteArrayChannel(new byte[]{1}),
                0,
                1
        )) {
            assertSame(channel, channel.position(Long.MAX_VALUE));

            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertEquals(Long.MAX_VALUE, channel.position());
        }
    }

    /// Verifies that operations on a closed slice channel report closed state.
    @Test
    public void operationsAfterCloseAreRejectedAsClosed() throws IOException {
        SeekableByteChannel channel = ArchiveSliceChannel.open(
                new ReadOnlyByteArrayChannel(new byte[]{1, 2, 3}),
                0,
                3
        );

        assertEquals(true, channel.isOpen());
        ByteBuffer writeSource = ByteBuffer.wrap(new byte[]{4});
        assertThrows(NonWritableChannelException.class, () -> channel.write(writeSource));
        assertEquals(0, writeSource.position());
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));

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
    @ParameterizedTest
    @MethodSource("failures")
    public void closeFailureAllowsDelegateCleanupRetry(Throwable failure) throws IOException {
        TestChannel delegate = new TestChannel(new byte[]{1, 2, 3});
        delegate.closeFailure = failure;
        SeekableByteChannel channel = ArchiveSliceChannel.open(delegate, 0, 3);

        assertSame(failure, assertThrows(failure.getClass(), channel::close));
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

    /// Provides an in-memory source with one-shot read, seek, and close failures.
    @NotNullByDefault
    private static final class TestChannel implements SeekableByteChannel {
        /// The channel content.
        private final byte @Unmodifiable [] content;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// The next read failure, cleared before it is thrown.
        private @Nullable Throwable readFailure;

        /// The number of bytes delivered by a failing read.
        private int bytesBeforeFailure;

        /// The next seek failure, cleared before it is thrown.
        private @Nullable Throwable seekFailure;

        /// The next close failure, cleared before it is thrown.
        private @Nullable Throwable closeFailure;

        /// Whether the next read returns zero without consuming content.
        private boolean zeroRead;

        /// The number of source read attempts.
        private int readCount;

        /// The number of close calls.
        private int closeCount;

        /// Creates a channel over the given content.
        private TestChannel(byte[] content) {
            this.content = content.clone();
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            readCount++;
            if (zeroRead) {
                zeroRead = false;
                return 0;
            }
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }

            int count = Math.min(destination.remaining(), content.length - position);
            @Nullable Throwable failure = readFailure;
            if (failure != null) {
                readFailure = null;
                count = Math.min(count, bytesBeforeFailure);
            }
            destination.put(content, position, count);
            position += count;
            if (failure != null) {
                throwFailure(failure);
            }
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
            if (seekFailure != null) {
                Throwable failure = seekFailure;
                seekFailure = null;
                throwFailure(failure);
            }
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

        /// Throws an armed failure or marks the source closed.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailure != null) {
                Throwable failure = closeFailure;
                closeFailure = null;
                throwFailure(failure);
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

        /// Rethrows a configured checked exception, runtime exception, or error unchanged.
        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }
    }
}

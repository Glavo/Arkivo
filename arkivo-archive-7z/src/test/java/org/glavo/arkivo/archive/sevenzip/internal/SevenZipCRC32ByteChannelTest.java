// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests CRC-32 validating 7z entry byte channels.
@NotNullByDefault
public final class SevenZipCRC32ByteChannelTest {
    /// Combines failures, delivered-byte counts, and direct versus bounded archive sources.
    private static Stream<Arguments> partialReadCases() {
        return Stream.of(
                new IOException("partial read failed"),
                new IllegalStateException("partial read failed"),
                new AssertionError("partial read failed")
        ).flatMap(failure -> Stream.of(0, 2, 4).flatMap(count -> Stream.of(false, true)
                .map(sliced -> Arguments.of(failure, count, sliced))));
    }

    /// Verifies partial physical failures do not silently disable sequential CRC validation.
    @ParameterizedTest
    @MethodSource("partialReadCases")
    public void validatesAfterPartialReadFailure(Throwable failure, int count, boolean sliced) throws IOException {
        byte[] content = {1, 2, 3, 4};
        for (boolean corrupt : new boolean[]{false, true}) {
            for (boolean closeImmediately : new boolean[]{false, true}) {
                MemorySeekableByteChannel physical = new MemorySeekableByteChannel(
                        sliced ? new byte[]{9, 8, 1, 2, 3, 4, 7, 6} : content
                );
                physical.partialReadFailure = failure;
                physical.bytesBeforeFailure = count;
                SeekableByteChannel body = sliced ? new SevenZipFileSliceChannel(physical, 2, 4) : physical;
                SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(
                        body, content.length, crc32(content) ^ (corrupt ? 1L : 0L)
                );
                try {
                    ByteBuffer target = ByteBuffer.allocateDirect(6).position(1).limit(5);
                    assertSame(failure, assertThrows(failure.getClass(), () -> channel.read(target)));
                    assertEquals(1 + count, target.position());
                    assertEquals(5, target.limit());
                    assertEquals(count, channel.position());
                    assertEquals(0, failure.getSuppressed().length);

                    if (closeImmediately) {
                        if (corrupt) {
                            IOException mismatch = assertThrows(IOException.class, channel::close);
                            assertEquals("7z entry data does not match CRC-32", mismatch.getMessage());
                        } else {
                            channel.close();
                        }
                        assertFalse(physical.isOpen());
                    } else {
                        ByteBuffer remaining = count < content.length ? target : ByteBuffer.allocate(1);
                        if (corrupt) {
                            IOException mismatch = assertThrows(IOException.class, () -> channel.read(remaining));
                            assertEquals("7z entry data does not match CRC-32", mismatch.getMessage());
                        } else {
                            assertEquals(count < content.length ? content.length - count : -1, channel.read(remaining));
                        }
                        assertEquals(content.length, channel.position());
                        assertEquals(5, target.position());
                        target.position(1);
                        byte[] actual = new byte[content.length];
                        target.get(actual);
                        assertArrayEquals(content, actual);
                    }
                } finally {
                    channel.close();
                }
                assertEquals(1, physical.closeCount());
            }
        }
    }

    /// Verifies that closing a partially consumed channel drains and validates the remaining bytes.
    @Test
    public void closeDrainsAndValidatesRemainingBytes() throws IOException {
        byte[] content = "validated 7z content".getBytes(StandardCharsets.UTF_8);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(
                new MemorySeekableByteChannel(content),
                content.length,
                crc32(content)
        );
        ByteBuffer buffer = ByteBuffer.allocate(4);

        assertEquals(4, channel.read(buffer));
        channel.close();
        channel.close();
    }

    /// Verifies that an empty destination buffer reports no bytes read even at EOF.
    @Test
    public void emptyReadAtEndOfChannelReturnsZero() throws IOException {
        byte[] content = "validated 7z content".getBytes(StandardCharsets.UTF_8);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(
                new MemorySeekableByteChannel(content),
                content.length,
                crc32(content)
        );

        assertEquals(content.length, channel.read(ByteBuffer.allocate(content.length)));
        assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        channel.close();
    }

    /// Verifies the validating wrapper preserves standard read-only seekable-channel behavior.
    @Test
    public void preservesReadOnlySeekableChannelContract() throws IOException {
        byte[] content = "read-only entry".getBytes(StandardCharsets.UTF_8);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(
                new MemorySeekableByteChannel(content),
                content.length,
                crc32(content)
        );

        channel.position(content.length + 17L);
        assertEquals(content.length + 17L, channel.position());
        assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));

        ByteBuffer source = ByteBuffer.wrap(new byte[]{1});
        assertThrows(NonWritableChannelException.class, () -> channel.write(source));
        assertEquals(0, source.position());
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));
        channel.close();
    }

    /// Verifies that closing reports CRC-32 mismatches after draining the full channel.
    @Test
    public void closeRejectsCrc32Mismatch() {
        byte[] content = "bad crc".getBytes(StandardCharsets.UTF_8);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(
                new MemorySeekableByteChannel(content),
                content.length,
                crc32(content) ^ 1L
        );

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals(true, exception.getMessage().contains("7z entry data does not match CRC-32"));
    }

    /// Verifies that a close failure is stable and later close calls are ignored.
    @Test
    public void closeIsIdempotentAfterTruncatedDrain() throws IOException {
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(
                new MemorySeekableByteChannel(content),
                content.length + 1L,
                crc32(content)
        );

        EOFException exception = assertThrows(EOFException.class, channel::close);
        assertEquals(true, exception.getMessage().contains("Unexpected end of 7z entry body"));
        channel.close();
    }

    /// Verifies that a delegate close failure still leaves the wrapper closed and allows cleanup retry.
    @Test
    public void closeFailureAllowsDelegateCleanupRetry() throws IOException {
        byte[] content = "close failure".getBytes(StandardCharsets.UTF_8);
        MemorySeekableByteChannel delegate = new MemorySeekableByteChannel(content, true);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(delegate, content.length, crc32(content));

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals(true, exception.getMessage().contains("close failed"));
        assertEquals(false, channel.isOpen());
        assertEquals(true, delegate.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
        assertThrows(ClosedChannelException.class, channel::size);
        assertEquals(1, delegate.closeCount());

        channel.close();
        channel.close();

        assertEquals(false, delegate.isOpen());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that runtime drain failures still close the wrapped channel.
    @Test
    public void runtimeDrainFailureClosesDelegate() throws IOException {
        byte[] content = "runtime drain failure".getBytes(StandardCharsets.UTF_8);
        RuntimeReadFailingSeekableByteChannel delegate = new RuntimeReadFailingSeekableByteChannel(content, 0);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(delegate, content.length, crc32(content));

        RuntimeException exception = assertThrows(RuntimeException.class, channel::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(false, delegate.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        channel.close();
    }

    /// Verifies close preserves one shared drain and delegate-close failure without self-suppression.
    @Test
    public void preservesSharedDrainAndDelegateCloseFailure() throws IOException {
        byte[] content = "shared channel failure".getBytes(StandardCharsets.UTF_8);
        IOException sharedFailure = new IOException("shared channel failure");
        MemorySeekableByteChannel delegate = new MemorySeekableByteChannel(
                content,
                sharedFailure,
                sharedFailure
        );
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(delegate, content.length, crc32(content));

        IOException failure = assertThrows(IOException.class, channel::close);

        assertSame(sharedFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(false, channel.isOpen());
        assertEquals(true, delegate.isOpen());
        assertEquals(1, delegate.closeCount());

        channel.close();
        assertEquals(false, delegate.isOpen());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies close suppresses a distinct delegate-close failure behind the drain failure.
    @Test
    public void suppressesDistinctDelegateCloseFailureAfterDrainFailure() throws IOException {
        byte[] content = "distinct channel failures".getBytes(StandardCharsets.UTF_8);
        IOException drainFailure = new IOException("channel drain failure");
        IOException closeFailure = new IOException("channel close failure");
        MemorySeekableByteChannel delegate = new MemorySeekableByteChannel(
                content,
                drainFailure,
                closeFailure
        );
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(delegate, content.length, crc32(content));

        IOException failure = assertThrows(IOException.class, channel::close);

        assertSame(drainFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(closeFailure, failure.getSuppressed()[0]);
        assertEquals(false, channel.isOpen());
        assertEquals(true, delegate.isOpen());
        assertEquals(1, delegate.closeCount());

        channel.close();
        assertEquals(false, delegate.isOpen());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that sequential reads report truncation as soon as the wrapped channel ends early.
    @Test
    public void readRejectsTruncatedBodyAtEndOfChannel() throws IOException {
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);
        SevenZipCRC32ByteChannel channel = new SevenZipCRC32ByteChannel(
                new MemorySeekableByteChannel(content),
                content.length + 1L,
                crc32(content)
        );
        ByteBuffer buffer = ByteBuffer.allocate(content.length);

        assertEquals(content.length, channel.read(buffer));
        EOFException exception = assertThrows(EOFException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertEquals(true, exception.getMessage().contains("Unexpected end of 7z entry body"));
    }

    /// Returns the unsigned CRC-32 value for the given bytes.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Provides an in-memory read-only seekable byte channel for tests.
    @NotNullByDefault
    private static final class MemorySeekableByteChannel implements SeekableByteChannel {
        /// The channel content.
        private final byte[] bytes;

        /// The current channel position.
        private int position;

        /// Failure reported by reads, or `null` for ordinary reads.
        private final @Nullable IOException readFailure;

        /// Failure thrown once after delivering the configured byte count.
        private @Nullable Throwable partialReadFailure;

        /// Maximum bytes delivered before the pending partial read failure.
        private int bytesBeforeFailure;

        /// Failure reported by the first scheduled close failure, or `null` for successful close.
        private final @Nullable IOException closeFailure;

        /// Whether this channel is open.
        private boolean open = true;

        /// The number of close calls that should fail without closing this channel.
        private int closeFailures;

        /// The number of close calls.
        private int closeCount;

        /// Creates an in-memory channel for the given bytes.
        private MemorySeekableByteChannel(byte[] bytes) {
            this(bytes, null, null);
        }

        /// Creates an in-memory channel for the given bytes and close behavior.
        private MemorySeekableByteChannel(byte[] bytes, boolean failClose) {
            this(bytes, null, failClose ? new IOException("close failed") : null);
        }

        /// Creates an in-memory channel with configurable read and close failures.
        private MemorySeekableByteChannel(
                byte[] bytes,
                @Nullable IOException readFailure,
                @Nullable IOException closeFailure
        ) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
            this.closeFailures = closeFailure != null ? 1 : 0;
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            if (readFailure != null) {
                throw readFailure;
            }
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }

            int length = Math.min(destination.remaining(), bytes.length - position);
            @Nullable Throwable failure = partialReadFailure;
            if (failure != null) {
                partialReadFailure = null;
                length = Math.min(length, bytesBeforeFailure);
            }
            destination.put(bytes, position, length);
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

        /// Returns the number of bytes in this channel.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
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

        /// Closes this channel.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailures > 0) {
                closeFailures--;
                throw Objects.requireNonNull(closeFailure, "closeFailure");
            }
            open = false;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires the channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Provides an in-memory channel that fails reads at a configured offset.
    @NotNullByDefault
    private static final class RuntimeReadFailingSeekableByteChannel implements SeekableByteChannel {
        /// The channel content.
        private final byte[] bytes;

        /// The first byte offset where reads fail.
        private final int failOffset;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates an in-memory channel for the given bytes.
        private RuntimeReadFailingSeekableByteChannel(byte[] bytes, int failOffset) {
            if (failOffset < 0 || failOffset > bytes.length) {
                throw new IllegalArgumentException("failOffset is out of range");
            }
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.failOffset = failOffset;
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= bytes.length) {
                return -1;
            }

            int length = Math.min(destination.remaining(), Math.min(bytes.length, failOffset) - position);
            if (length == 0) {
                throw new IllegalStateException("read failed");
            }
            destination.put(bytes, position, length);
            position += length;
            return length;
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

        /// Returns the number of bytes in this channel.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
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

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires the channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}

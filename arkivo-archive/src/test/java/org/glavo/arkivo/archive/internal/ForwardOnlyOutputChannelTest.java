// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies buffer progress, positioning, and retryable ownership for sequential entry channels.
@NotNullByDefault
final class ForwardOnlyOutputChannelTest {
    /// Verifies heap and direct writes plus forward-only positioning constraints.
    @Test
    void adaptsOutputStreamsToForwardOnlyChannels() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ForwardOnlyOutputChannel channel = new ForwardOnlyOutputChannel(output);

        assertEquals(3, channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        ByteBuffer direct = ByteBuffer.allocateDirect(2).put(new byte[]{4, 5}).flip();
        assertEquals(2, channel.write(direct));
        assertEquals(0, channel.write(ByteBuffer.allocate(0)));
        assertEquals(5L, channel.position());
        assertEquals(5L, channel.size());
        assertEquals(channel, channel.position(5L));
        assertEquals(channel, channel.truncate(5L));
        assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
        assertEquals(5L, channel.position());
        assertThrows(NonReadableChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(UnsupportedOperationException.class, () -> channel.position(4L));
        assertThrows(UnsupportedOperationException.class, () -> channel.truncate(4L));

        channel.close();
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, output.toByteArray());
        assertThrows(ClosedChannelException.class, channel::position);
    }

    /// Verifies a failed forward-only output close remains retryable.
    @Test
    void retriesForwardOnlyOutputClose() throws IOException {
        FailingCloseOutputStream output = new FailingCloseOutputStream();
        ForwardOnlyOutputChannel channel = new ForwardOnlyOutputChannel(output);

        IOException failure = assertThrows(IOException.class, channel::close);

        assertEquals("close failed", failure.getMessage());
        assertEquals(1, output.closeAttempts());
        assertTrue(channel.isOpen());
        channel.write(ByteBuffer.wrap(new byte[]{1}));

        channel.close();
        channel.close();

        assertEquals(2, output.closeAttempts());
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
    }

    /// Verifies all buffer forms preserve limits, marks, and selected bytes across transfer boundaries.
    @ParameterizedTest
    @MethodSource("bufferSizes")
    void writesSelectedBufferRange(BufferKind kind, int size) throws IOException {
        ByteBuffer source = kind.buffer(size);
        byte @Unmodifiable [] expected = bytes(source);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (ForwardOnlyOutputChannel channel = new ForwardOnlyOutputChannel(target)) {
            int start = source.position();
            int limit = source.limit();
            assertEquals(size, channel.write(source));
            assertEquals(limit, source.position());
            assertEquals(limit, source.limit());
            assertEquals(size, channel.position());
            assertEquals(size, channel.size());
            source.reset();
            assertEquals(start, source.position());
            assertArrayEquals(expected, bytes(source));
            assertArrayEquals(expected, target.toByteArray());
        }
    }

    /// Verifies only completed stream writes advance either position when a later write fails.
    @ParameterizedTest
    @MethodSource("writeFailures")
    void retainsCompletedChunks(BufferKind kind, FailureKind failureKind, int failAt, boolean partial)
            throws IOException {
        ByteBuffer source = kind.buffer(20_000);
        byte @Unmodifiable [] expected = bytes(source);
        Throwable failure = failureKind.failure();
        FailingWriteOutputStream target = new FailingWriteOutputStream(failAt, partial, failure);
        try (ForwardOnlyOutputChannel channel = new ForwardOnlyOutputChannel(target)) {
            int start = source.position();
            int limit = source.limit();
            assertSame(failure, assertThrows(failure.getClass(), () -> channel.write(source)));
            int completed = (failAt - 1) * 8192;
            assertEquals(start + completed, source.position());
            assertEquals(limit, source.limit());
            assertEquals(completed, channel.position());
            assertEquals(completed, channel.size());
            assertSame(channel, channel.position(completed));
            assertSame(channel, channel.truncate(completed));
            assertEquals(completed + (partial ? 1 : 0), target.bytes.size());
            assertArrayEquals(Arrays.copyOf(expected, target.bytes.size()), target.bytes.toByteArray());
            if (!partial) {
                assertEquals(expected.length - completed, channel.write(source));
                assertEquals(expected.length, channel.position());
                assertArrayEquals(expected, target.bytes.toByteArray());
            }
        }
    }

    /// Verifies empty writes never reach an endpoint even if its next write would fail.
    @ParameterizedTest
    @MethodSource("bufferKinds")
    void doesNotSendEmptyWrites(BufferKind kind) throws IOException {
        FailingWriteOutputStream target = new FailingWriteOutputStream(1, false, new IOException("write failed"));
        try (ForwardOnlyOutputChannel channel = new ForwardOnlyOutputChannel(target)) {
            assertEquals(0, channel.write(kind.buffer(0)));
            assertEquals(0, target.attempts);
            assertEquals(0L, channel.position());
        }
    }

    /// Returns selected buffer contents without changing its state.
    private static byte @Unmodifiable [] bytes(ByteBuffer source) {
        byte[] bytes = new byte[source.remaining()];
        source.get(source.position(), bytes);
        return bytes;
    }

    /// Supplies empty, exact-boundary, and multi-chunk source ranges.
    private static Stream<Arguments> bufferSizes() {
        return Stream.of(BufferKind.values()).flatMap(kind -> Stream.of(0, 1, 8191, 8192, 8193, 20_000)
                .map(size -> Arguments.of(kind, size)));
    }

    /// Supplies failures at every stream-write boundary for each buffer representation.
    private static Stream<Arguments> writeFailures() {
        return Stream.of(BufferKind.values()).flatMap(kind -> Stream.of(FailureKind.values())
                .flatMap(failure -> (kind == BufferKind.HEAP || kind == BufferKind.HEAP_SLICE
                        ? Stream.of(1) : Stream.of(1, 2, 3))
                        .flatMap(attempt -> Stream.of(false, true)
                                .map(partial -> Arguments.of(kind, failure, attempt, partial)))));
    }

    /// Supplies each source buffer representation.
    private static Stream<BufferKind> bufferKinds() {
        return Stream.of(BufferKind.values());
    }

    /// Source representations with array offsets and read-only or off-heap storage.
    @NotNullByDefault
    private enum BufferKind {
        /// A writable array-backed range.
        HEAP,
        /// A range in a slice with a nonzero array offset.
        HEAP_SLICE,
        /// A writable off-heap range.
        DIRECT,
        /// A read-only array-backed range.
        READ_ONLY_HEAP,
        /// A read-only off-heap range.
        READ_ONLY_DIRECT;

        /// Creates a marked range surrounded by bytes excluded from the transfer.
        private ByteBuffer buffer(int size) {
            ByteBuffer buffer = this == DIRECT || this == READ_ONLY_DIRECT
                    ? ByteBuffer.allocateDirect(size + 16) : ByteBuffer.allocate(size + 16);
            if (this == HEAP_SLICE) {
                buffer.position(5);
                buffer = buffer.slice();
            }
            for (int index = 0; index < buffer.capacity(); index++) {
                buffer.put(index, (byte) (index * 37));
            }
            buffer.limit(size + 3).position(3).mark();
            return this == READ_ONLY_HEAP || this == READ_ONLY_DIRECT ? buffer.asReadOnlyBuffer() : buffer;
        }
    }

    /// Failure categories that must retain the same partial-progress accounting.
    @NotNullByDefault
    private enum FailureKind {
        /// A checked output failure.
        IO,
        /// An unchecked output failure.
        RUNTIME,
        /// An output error.
        ERROR;

        /// Creates a distinct failure for one test.
        private Throwable failure() {
            return switch (this) {
                case IO -> new IOException("write failed");
                case RUNTIME -> new IllegalStateException("write failed");
                case ERROR -> new AssertionError("write failed");
            };
        }
    }

    /// Writes complete chunks except for a single optionally partial failed call.
    @NotNullByDefault
    private static final class FailingWriteOutputStream extends OutputStream {
        /// Successfully emitted bytes, including the selected partial failed write.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// One-based call to fail.
        private final int failAt;

        /// Whether the failing call emits one byte.
        private final boolean partial;

        /// Failure thrown once by the selected call.
        private final Throwable failure;

        /// Number of target write attempts.
        private int attempts;

        /// Configures one failed target write.
        private FailingWriteOutputStream(int failAt, boolean partial, Throwable failure) {
            this.failAt = failAt;
            this.partial = partial;
            this.failure = failure;
        }

        /// Routes single-byte writes through the same injection point.
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        /// Emits bytes or throws the configured failure after its optional prefix.
        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (++attempts == failAt) {
                if (partial && length > 0) {
                    bytes.write(source[offset]);
                }
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            bytes.write(source, offset, length);
        }
    }

    /// Implements an output stream whose first close attempt fails without closing it.
    @NotNullByDefault
    private static final class FailingCloseOutputStream extends OutputStream {
        /// Whether the stream remains open.
        private boolean open = true;

        /// Number of close attempts made while open.
        private int closeAttempts;

        /// Accepts one byte while the stream is open.
        @Override
        public void write(int value) throws IOException {
            if (!open) {
                throw new IOException("stream closed");
            }
        }

        /// Fails the first close attempt and closes on the second.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeAttempts++;
            if (closeAttempts == 1) {
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Returns the number of close attempts made while open.
        private int closeAttempts() {
            return closeAttempts;
        }
    }
}

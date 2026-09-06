// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies temporary archive materialization, ownership transfer, and cleanup failures.
@NotNullByDefault
final class TemporaryArchiveSourceTest {
    /// Verifies empty and multi-buffer bodies survive short reads at transfer-buffer boundaries.
    @ParameterizedTest
    @CsvSource({"0, 1", "1, 1", "65535, 8191", "65536, 65536", "65537, 65537", "131073, 32767"})
    void materializesTransferBoundaries(int size, int chunkSize) throws IOException {
        byte[] expected = new byte[size];
        for (int index = 0; index < size; index++) {
            expected[index] = (byte) (index * 31 + (index >>> 8));
        }
        ScriptedReadableByteChannel input = new ScriptedReadableByteChannel(expected, chunkSize, true);
        try (TemporaryArchiveSource source = TemporaryArchiveSource.materialize(input, size);
             SeekableByteChannel channel = source.openChannel()) {
            assertFalse(input.isOpen());
            assertEquals(1, input.closeCalls);
            assertEquals(size, input.transferredBytes());
            assertEquals(size, channel.size());
            ByteBuffer actual = ByteBuffer.allocate(size + 2);
            actual.position(1).limit(size + 1);
            while (actual.hasRemaining()) {
                int read = channel.read(actual);
                assertTrue(read > 0);
            }
            assertEquals(size + 1, actual.position());
            assertEquals(size + 1, actual.limit());
            assertEquals(ByteBuffer.wrap(expected), actual.flip().position(1).slice());
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            ByteBuffer rejected = ByteBuffer.wrap(new byte[]{1});
            assertThrows(NonWritableChannelException.class, () -> channel.write(rejected));
            assertEquals(0, rejected.position());
            assertThrows(NonWritableChannelException.class, () -> channel.truncate(0));
            assertEquals(size, channel.size());
        }
        assertEquals(1, input.closeCalls);
    }

    /// Verifies a size violation consumes exactly one probe byte across multiple transfer chunks.
    @ParameterizedTest
    @CsvSource({"0, 1", "1, 1", "65535, 8191", "65536, 65536", "65537, 65537", "131073, 32767"})
    void limitsTransferBoundaries(int maximum, int chunkSize) {
        ScriptedReadableByteChannel input = new ScriptedReadableByteChannel(
                new byte[maximum + 2], chunkSize, true
        );
        ArkivoReadLimitException failure = assertThrows(
                ArkivoReadLimitException.class,
                () -> TemporaryArchiveSource.materialize(input, maximum)
        );
        assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, failure.kind());
        assertEquals(maximum, failure.maximum());
        assertEquals(maximum + 1L, failure.actual());
        assertEquals(maximum + 1, input.transferredBytes());
        assertEquals(1, input.closeCalls);
        assertFalse(input.isOpen());
    }

    /// Returns distinct checked, unchecked, and error failures for each invocation.
    private static Stream<Throwable> failures() {
        return Stream.of(new IOException("I/O failure"), new IllegalStateException("runtime failure"),
                new AssertionError("error failure"));
    }

    /// Verifies zero-progress input is tolerated and each opened channel has an independent position.
    @Test
    void materializesRepeatableContentAndOwnsInput() throws IOException {
        ScriptedReadableByteChannel input = new ScriptedReadableByteChannel(
                new byte[]{1, 2, 3, 4, 5},
                2,
                true
        );
        TemporaryArchiveSource source = TemporaryArchiveSource.materialize(input, 5L);

        assertFalse(input.isOpen());
        assertEquals(5, input.transferredBytes());
        try {
            try (SeekableByteChannel first = source.openChannel();
                 SeekableByteChannel second = source.openChannel()) {
                ByteBuffer prefix = ByteBuffer.allocate(2);
                assertEquals(2, first.read(prefix));
                assertEquals(2L, first.position());
                assertEquals(0L, second.position());
                assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, readAll(second));
                assertArrayEquals(new byte[]{3, 4, 5}, readAll(first));
            }

            source.close();
            source.close();
            IOException closed = assertThrows(IOException.class, source::openChannel);
            assertEquals("Temporary archive source is closed", closed.getMessage());
        } finally {
            source.close();
        }
    }

    /// Verifies the decoded-size limit closes the input and reports the first byte beyond the allowance.
    @Test
    void rejectsMaterializedContentBeyondLimit() {
        ScriptedReadableByteChannel input = new ScriptedReadableByteChannel(
                new byte[]{10, 11, 12, 13, 14},
                Integer.MAX_VALUE,
                false
        );

        ArkivoReadLimitException exception = assertThrows(
                ArkivoReadLimitException.class,
                () -> TemporaryArchiveSource.materialize(input, 3L)
        );

        assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, exception.kind());
        assertEquals(3L, exception.maximum());
        assertEquals(4L, exception.actual());
        assertFalse(input.isOpen());
        assertEquals(4, input.transferredBytes());
    }

    /// Verifies input cleanup failures are suppressed behind a transfer failure.
    @ParameterizedTest
    @MethodSource("failures")
    void suppressesInputCleanupFailureBehindReadFailure(Throwable readFailure) {
        FailingReadableByteChannel input = new FailingReadableByteChannel(readFailure);

        Throwable exception = assertThrows(
                readFailure.getClass(),
                () -> TemporaryArchiveSource.materialize(input, -1L)
        );

        assertSame(readFailure, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close failure", exception.getSuppressed()[0].getMessage());
        assertEquals(1, input.closeCalls());
        assertFalse(input.isOpen());
    }

    /// Verifies a shared read and close failure remains primary without self-suppression.
    @ParameterizedTest
    @MethodSource("failures")
    void preservesSharedReadAndCloseFailure(Throwable sharedFailure) {
        FailingReadableByteChannel input = new FailingReadableByteChannel(sharedFailure, sharedFailure);

        Throwable exception = assertThrows(
                sharedFailure.getClass(),
                () -> TemporaryArchiveSource.materialize(input, -1L)
        );

        assertSame(sharedFailure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertEquals(1, input.closeCalls());
        assertFalse(input.isOpen());
    }

    /// Verifies successful EOF does not hide a subsequent input-close failure or close the input twice.
    @ParameterizedTest
    @MethodSource("failures")
    void propagatesCloseFailureAfterEndOfInput(Throwable closeFailure) {
        FailingReadableByteChannel input = new FailingReadableByteChannel(null, closeFailure);
        assertSame(closeFailure, assertThrows(
                closeFailure.getClass(), () -> TemporaryArchiveSource.materialize(input, 0L)
        ));
        assertEquals(0, closeFailure.getSuppressed().length);
        assertEquals(1, input.closeCalls());
        assertFalse(input.isOpen());
    }

    /// Verifies unchecked cleanup failures do not replace a checked read failure.
    @ParameterizedTest
    @MethodSource("failures")
    void suppressesEachCleanupFailureKind(Throwable closeFailure) {
        IOException readFailure = new IOException("read failure");
        FailingReadableByteChannel input = new FailingReadableByteChannel(readFailure, closeFailure);
        assertSame(readFailure, assertThrows(
                IOException.class, () -> TemporaryArchiveSource.materialize(input, 0L)
        ));
        assertArrayEquals(new Throwable[]{closeFailure}, readFailure.getSuppressed());
        assertEquals(1, input.closeCalls());
        assertFalse(input.isOpen());
    }

    /// Reads every remaining byte from a seekable channel.
    private static byte @Unmodifiable [] readAll(SeekableByteChannel channel) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(3);
        while (true) {
            buffer.clear();
            int read = channel.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            output.write(buffer.array(), 0, read);
        }
    }

    /// Supplies fixed bytes in bounded chunks and optionally reports one initial zero-progress read.
    @NotNullByDefault
    private static final class ScriptedReadableByteChannel implements ReadableByteChannel {
        /// Remaining immutable test content.
        private final @UnmodifiableView ByteBuffer content;

        /// Maximum bytes returned by one read.
        private final int maximumChunkSize;

        /// Whether the next read should report zero progress.
        private boolean zeroPending;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Number of content bytes transferred.
        private int transferredBytes;

        /// Number of close calls received.
        private int closeCalls;

        /// Creates a channel with the requested read schedule.
        private ScriptedReadableByteChannel(byte[] content, int maximumChunkSize, boolean zeroPending) {
            this.content = ByteBuffer.wrap(content.clone()).asReadOnlyBuffer();
            this.maximumChunkSize = maximumChunkSize;
            this.zeroPending = zeroPending;
        }

        /// Returns the next scheduled content chunk.
        @Override
        public int read(ByteBuffer target) throws IOException {
            ensureOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            if (zeroPending) {
                zeroPending = false;
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }
            int count = Math.min(Math.min(target.remaining(), content.remaining()), maximumChunkSize);
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
            transferredBytes += count;
            return count;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            closeCalls++;
            open = false;
        }

        /// Returns the number of content bytes transferred.
        private int transferredBytes() {
            return transferredBytes;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Reports a configured read failure or EOF, then fails after completing input closure.
    @NotNullByDefault
    private static final class FailingReadableByteChannel implements ReadableByteChannel {
        /// Failure reported by reads, or `null` to report EOF.
        private final @Nullable Throwable readFailure;

        /// Failure reported by close.
        private final Throwable closeFailure;

        /// Number of close calls received.
        private int closeCalls;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a channel that reports the given read failure.
        ///
        /// @param readFailure the failure reported by reads
        private FailingReadableByteChannel(Throwable readFailure) {
            this(readFailure, new IOException("close failure"));
        }

        /// Creates a channel that reports the given read and close failures.
        ///
        /// @param readFailure the failure reported by reads, or `null` for EOF
        /// @param closeFailure the failure reported by close
        private FailingReadableByteChannel(@Nullable Throwable readFailure, Throwable closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        /// Reports the configured read failure, or EOF when no read failure was supplied.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (readFailure != null) {
                throwFailure(readFailure);
            }
            return -1;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Completes closure and then reports a cleanup failure.
        @Override
        public void close() throws IOException {
            closeCalls++;
            open = false;
            throwFailure(closeFailure);
        }

        /// Throws a configured failure without wrapping or changing its identity.
        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Unsupported test failure", failure);
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }
    }
}

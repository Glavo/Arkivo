// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies temporary archive materialization, ownership transfer, and cleanup failures.
@NotNullByDefault
final class TemporaryArchiveSourceTest {
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
    @Test
    void suppressesInputCleanupFailureBehindReadFailure() throws IOException {
        IOException readFailure = new IOException("read failure");
        FailingReadableByteChannel input = new FailingReadableByteChannel(readFailure);

        IOException exception = assertThrows(
                IOException.class,
                () -> TemporaryArchiveSource.materialize(input, -1L)
        );

        assertSame(readFailure, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close failure", exception.getSuppressed()[0].getMessage());
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

    /// Fails every read and reports one failure after completing input closure.
    @NotNullByDefault
    private static final class FailingReadableByteChannel implements ReadableByteChannel {
        /// Failure reported by reads.
        private final IOException readFailure;

        /// Number of close calls received.
        private int closeCalls;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a channel that reports the given read failure.
        private FailingReadableByteChannel(IOException readFailure) {
            this.readFailure = readFailure;
        }

        /// Reports the configured read failure.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            throw readFailure;
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
            throw new IOException("close failure");
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }
    }
}

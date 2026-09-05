// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests bounded 7z entry input stream behavior.
@NotNullByDefault
public final class SevenZipBoundedInputStreamTest {
    /// Verifies that bounded reads stop exactly after the configured byte count.
    @Test
    public void readsBoundedBody() throws IOException {
        byte[] content = "hello bounded 7z".getBytes(StandardCharsets.UTF_8);

        try (SevenZipBoundedInputStream input =
                     new SevenZipBoundedInputStream(new ByteArrayInputStream(content), content.length)) {
            byte[] output = input.readAllBytes();

            assertArrayEquals(content, output);
            assertEquals(0, input.read(new byte[0]));
            assertEquals(-1, input.read());
        }
    }

    /// Verifies that early EOF while reading one byte is reported as archive truncation.
    @Test
    public void rejectsTruncatedSingleByteRead() {
        SevenZipBoundedInputStream input = new SevenZipBoundedInputStream(new ByteArrayInputStream(new byte[0]), 1);

        EOFException exception = assertThrows(EOFException.class, input::read);
        assertEquals(true, exception.getMessage().contains("Unexpected end of 7z entry body"));
    }

    /// Verifies that early EOF while reading a byte array is reported as archive truncation.
    @Test
    public void rejectsTruncatedArrayRead() throws IOException {
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);
        SevenZipBoundedInputStream input =
                new SevenZipBoundedInputStream(new ByteArrayInputStream(content), content.length + 1L);

        byte[] buffer = new byte[content.length + 1];
        assertEquals(content.length, input.read(buffer));

        EOFException exception = assertThrows(EOFException.class, () -> input.read(buffer));
        assertEquals(true, exception.getMessage().contains("Unexpected end of 7z entry body"));
    }

    /// Verifies that a close failure is stable and later close calls are ignored.
    @Test
    public void closeIsIdempotentAfterTruncatedDrain() throws IOException {
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);
        SevenZipBoundedInputStream input =
                new SevenZipBoundedInputStream(new ClosingByteArrayInputStream(content), content.length + 1L);

        EOFException exception = assertThrows(EOFException.class, input::close);
        assertEquals(true, exception.getMessage().contains("Unexpected end of 7z entry body"));
        input.close();
    }

    /// Verifies that a delegate close failure still leaves the wrapper closed and allows cleanup retry.
    @Test
    public void closeFailureAllowsDelegateCleanupRetry() throws IOException {
        byte[] content = "close failure".getBytes(StandardCharsets.UTF_8);
        ClosingByteArrayInputStream delegate = new ClosingByteArrayInputStream(content, true);
        SevenZipBoundedInputStream input =
                new SevenZipBoundedInputStream(delegate, content.length);

        IOException exception = assertThrows(IOException.class, input::close);
        assertEquals(true, exception.getMessage().contains("close failed"));
        assertThrows(IOException.class, input::read);
        assertThrows(IOException.class, () -> input.read(new byte[1]));
        assertEquals(false, delegate.closed());
        assertEquals(1, delegate.closeCount());

        input.close();
        input.close();

        assertEquals(true, delegate.closed());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that runtime drain failures still close the underlying stream.
    @Test
    public void runtimeDrainFailureClosesDelegate() throws IOException {
        byte[] content = "runtime drain failure".getBytes(StandardCharsets.UTF_8);
        RuntimeReadFailingInputStream delegate = new RuntimeReadFailingInputStream(content, 0);
        SevenZipBoundedInputStream input = new SevenZipBoundedInputStream(delegate, content.length);

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(true, delegate.closed());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies close preserves one shared drain and delegate-close failure without self-suppression.
    @Test
    public void preservesSharedDrainAndDelegateCloseFailure() throws IOException {
        IOException sharedFailure = new IOException("shared drain failure");
        FailingInputStream delegate = new FailingInputStream(sharedFailure, sharedFailure);
        SevenZipBoundedInputStream input = new SevenZipBoundedInputStream(delegate, 1L);

        IOException failure = assertThrows(IOException.class, input::close);

        assertSame(sharedFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(true, delegate.closed());
        assertEquals(1, delegate.closeCount());

        input.close();
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies close suppresses a distinct delegate-close failure behind the drain failure.
    @Test
    public void suppressesDistinctDelegateCloseFailureAfterDrainFailure() throws IOException {
        IOException drainFailure = new IOException("drain failure");
        IOException closeFailure = new IOException("close failure");
        FailingInputStream delegate = new FailingInputStream(drainFailure, closeFailure);
        SevenZipBoundedInputStream input = new SevenZipBoundedInputStream(delegate, 1L);

        IOException failure = assertThrows(IOException.class, input::close);

        assertSame(drainFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(closeFailure, failure.getSuppressed()[0]);
        assertEquals(true, delegate.closed());
        assertEquals(1, delegate.closeCount());

        input.close();
        assertEquals(2, delegate.closeCount());
    }

    /// Provides an in-memory stream that rejects reads after close.
    @NotNullByDefault
    private static final class ClosingByteArrayInputStream extends InputStream {
        /// The stream content.
        private final byte[] bytes;

        /// The current stream position.
        private int position;

        /// Whether this stream has been closed.
        private boolean closed;

        /// The number of close calls that should fail without closing this stream.
        private int closeFailures;

        /// The number of close calls.
        private int closeCount;

        /// Creates a stream over the given bytes.
        private ClosingByteArrayInputStream(byte[] bytes) {
            this(bytes, false);
        }

        /// Creates a stream over the given bytes and close behavior.
        private ClosingByteArrayInputStream(byte[] bytes, boolean failClose) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.closeFailures = failClose ? 1 : 0;
        }

        /// Reads one byte.
        @Override
        public int read() throws IOException {
            ensureOpen();
            if (position >= bytes.length) {
                return -1;
            }
            return Byte.toUnsignedInt(bytes[position++]);
        }

        /// Reads bytes into the target array.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            ensureOpen();
            if (length == 0) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }

            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, buffer, offset, count);
            position += count;
            return count;
        }

        /// Closes this stream.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailures > 0) {
                closeFailures--;
                throw new IOException("close failed");
            }
            closed = true;
        }

        /// Returns whether this stream is closed.
        private boolean closed() {
            return closed;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("stream is closed");
            }
        }
    }

    /// Provides an in-memory stream that fails reads at a configured offset.
    @NotNullByDefault
    private static final class RuntimeReadFailingInputStream extends InputStream {
        /// The stream content.
        private final byte[] bytes;

        /// The first byte offset where reads fail.
        private final int failOffset;

        /// The current stream position.
        private int position;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a stream over the given bytes.
        private RuntimeReadFailingInputStream(byte[] bytes, int failOffset) {
            if (failOffset < 0 || failOffset > bytes.length) {
                throw new IllegalArgumentException("failOffset is out of range");
            }
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.failOffset = failOffset;
        }

        /// Reads one byte.
        @Override
        public int read() throws IOException {
            ensureOpen();
            if (position >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= bytes.length) {
                return -1;
            }
            return Byte.toUnsignedInt(bytes[position++]);
        }

        /// Reads bytes into the target array.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            ensureOpen();
            if (length == 0) {
                return 0;
            }
            if (position >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= bytes.length) {
                return -1;
            }

            int count = Math.min(length, Math.min(bytes.length, failOffset) - position);
            if (count == 0) {
                throw new IllegalStateException("read failed");
            }
            System.arraycopy(bytes, position, buffer, offset, count);
            position += count;
            return count;
        }

        /// Closes this stream.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this stream has been closed.
        private boolean closed() {
            return closed;
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("stream is closed");
            }
        }
    }

    /// Reports configurable read and close failures while recording physical closure.
    @NotNullByDefault
    private static final class FailingInputStream extends InputStream {
        /// Failure reported by every read while open.
        private final IOException readFailure;

        /// Failure reported by the first close call.
        private final IOException closeFailure;

        /// Whether the stream has been physically closed.
        private boolean closed;

        /// Number of close calls.
        private int closeCount;

        /// Creates a stream with the supplied read and close failures.
        private FailingInputStream(IOException readFailure, IOException closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        /// Reports the configured read failure while open.
        @Override
        public int read() throws IOException {
            ensureOpen();
            throw readFailure;
        }

        /// Reports the configured read failure while open.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            ensureOpen();
            throw readFailure;
        }

        /// Physically closes the stream and reports the configured failure once.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (!closed) {
                closed = true;
                throw closeFailure;
            }
        }

        /// Returns whether the stream has been physically closed.
        private boolean closed() {
            return closed;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this stream to remain open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("stream is closed");
            }
        }
    }
}

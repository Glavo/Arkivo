// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests stream-oriented byte-transform progress, failure, and lifecycle contracts.
@NotNullByDefault
public final class TransformingStreamContractsTest {
    /// A transform that immediately commits every supplied byte without changing it.
    private static final ByteTransform IDENTITY = (buffer, offset, length) -> length;

    /// Verifies deferred tails round-trip and finish remains distinct from downstream closure.
    @Test
    public void deferredTailRoundTripsAcrossFinishAndClose() throws IOException {
        byte[] expected = new byte[20_003];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 29 + index / 7);
        }

        TrackingOutputStream target = new TrackingOutputStream();
        TransformingOutputStream output = new TransformingOutputStream(target, new DeferredXorTransform());
        output.write(Byte.toUnsignedInt(expected[0]));
        output.write(expected, 1, expected.length - 1);
        output.flush();
        assertEquals(1, target.flushCount());
        assertFalse(target.isClosed());

        output.finish();
        byte[] transformed = target.bytes();
        output.finish();
        assertArrayEquals(transformed, target.bytes());
        assertFalse(target.isClosed());
        assertThrows(IOException.class, () -> output.write(1));
        output.flush();
        assertEquals(2, target.flushCount());
        output.close();
        output.close();
        assertTrue(target.isClosed());

        TrackingInputStream source = new TrackingInputStream(transformed);
        TransformingInputStream input = new TransformingInputStream(source, new DeferredXorTransform());
        assertEquals(0, input.available());
        assertEquals(Byte.toUnsignedInt(expected[0]), input.read());
        assertTrue(input.available() > 0);
        assertTrue(input.available() < expected.length - 1);
        byte[] actual = new byte[expected.length];
        actual[0] = expected[0];
        assertEquals(expected.length - 1, input.readNBytes(actual, 1, actual.length - 1));
        assertArrayEquals(expected, actual);
        assertEquals(-1, input.read());
        assertEquals(0, input.available());
        input.close();
        input.close();
        assertTrue(source.isClosed());
    }

    /// Verifies a zero-length bulk read falls back to a single-byte read and handles immediate end of input.
    @Test
    @Timeout(5)
    public void inputMakesProgressAfterZeroLengthBulkRead() throws IOException {
        byte[] expected = {1, 2, 3, 4};
        try (TransformingInputStream input = new TransformingInputStream(
                new ZeroFirstBulkInputStream(expected),
                IDENTITY
        )) {
            byte[] actual = new byte[expected.length];
            assertEquals(expected.length, input.read(actual, 0, actual.length));
            assertArrayEquals(expected, actual);
            assertEquals(-1, input.read(actual, 0, actual.length));
        }

        try (TransformingInputStream input = new TransformingInputStream(
                new ZeroFirstBulkInputStream(new byte[0]),
                IDENTITY
        )) {
            assertEquals(-1, input.read(new byte[1], 0, 1));
        }
    }

    /// Verifies a short suffix retained by the transform is returned unchanged when input ends in the same read.
    @Test
    public void inputReturnsUncommittedSuffixBeforeEndOfInput() throws IOException {
        try (TransformingInputStream input = new TransformingInputStream(
                new ByteArrayInputStream(new byte[]{1, 2}),
                (buffer, offset, length) -> 0
        )) {
            byte[] target = new byte[3];
            assertEquals(2, input.read(target));
            assertArrayEquals(new byte[]{1, 2, 0}, target);
            assertEquals(-1, input.read(target));
        }
    }

    /// Verifies stream constructors, ranges, empty operations, and closed-state checks are deterministic.
    @Test
    public void validatesArgumentsAndClosedState() throws IOException {
        assertThrows(NullPointerException.class, () -> new TransformingInputStream(null, IDENTITY));
        assertThrows(
                NullPointerException.class,
                () -> new TransformingInputStream(new ByteArrayInputStream(new byte[0]), null)
        );
        assertThrows(NullPointerException.class, () -> new TransformingOutputStream(null, IDENTITY));
        assertThrows(
                NullPointerException.class,
                () -> new TransformingOutputStream(new ByteArrayOutputStream(), null)
        );

        TransformingInputStream input = new TransformingInputStream(
                new ByteArrayInputStream(new byte[]{1}),
                IDENTITY
        );
        assertEquals(0, input.read(new byte[1], 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[1], 2, 0));
        assertEquals(1, input.read());
        assertEquals(-1, input.read());
        input.close();
        assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[1], 2, 0));
        assertThrows(ClosedChannelException.class, () -> input.read(new byte[1], 0, 0));
        assertThrows(ClosedChannelException.class, input::available);

        TransformingOutputStream output = new TransformingOutputStream(new ByteArrayOutputStream(), IDENTITY);
        output.write(new byte[]{1}, 1, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[1], 2, 0));
        output.close();
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[1], 2, 0));
        assertThrows(ClosedChannelException.class, () -> output.write(new byte[0], 0, 0));
        assertThrows(ClosedChannelException.class, output::flush);
        assertThrows(ClosedChannelException.class, output::finish);
    }

    /// Verifies invalid transform result counts fail rather than corrupting buffered state.
    @Test
    public void rejectsInvalidTransformCounts() throws IOException {
        for (ByteTransform transform : new ByteTransform[]{
                (buffer, offset, length) -> -1,
                (buffer, offset, length) -> length + 1
        }) {
            TransformingInputStream input = new TransformingInputStream(
                    new ByteArrayInputStream(new byte[]{1}),
                    transform
            );
            IOException inputFailure = assertThrows(IOException.class, () -> input.read(new byte[1]));
            assertEquals("Byte filter returned an invalid transformed byte count", inputFailure.getMessage());
            assertSame(inputFailure, assertThrows(IOException.class, () -> input.read(new byte[1])));
            assertSame(inputFailure, assertThrows(IOException.class, input::available));

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            TransformingOutputStream output = new TransformingOutputStream(bytes, transform);
            IOException outputFailure = assertThrows(IOException.class, () -> output.write(new byte[]{1}));
            assertEquals("Byte filter returned an invalid transformed byte count", outputFailure.getMessage());
            assertSame(outputFailure, assertThrows(IOException.class, output::finish));
            assertSame(outputFailure, assertThrows(IOException.class, output::flush));
            assertSame(outputFailure, assertThrows(IOException.class, output::close));
            output.close();
            assertEquals(0, bytes.size());
        }
    }

    /// Verifies transforms that retain a complete bounded buffer fail promptly on both stream directions.
    @Test
    @Timeout(5)
    public void rejectsTransformsThatNeverCommit() throws IOException {
        ByteTransform noProgress = (buffer, offset, length) -> 0;
        byte[] fullBuffer = new byte[8_192];

        TransformingInputStream input = new TransformingInputStream(
                new ByteArrayInputStream(fullBuffer),
                noProgress
        );
        IOException inputFailure = assertThrows(IOException.class, () -> input.read(new byte[1]));
        assertEquals("Byte filter made no progress with a full buffer", inputFailure.getMessage());

        TransformingOutputStream output = new TransformingOutputStream(new ByteArrayOutputStream(), noProgress);
        IOException outputFailure = assertThrows(IOException.class, () -> output.write(fullBuffer));
        assertEquals("Byte filter made no progress with a full buffer", outputFailure.getMessage());
        assertSame(outputFailure, assertThrows(IOException.class, output::finish));
        assertSame(outputFailure, assertThrows(IOException.class, output::close));
        output.close();
    }

    /// Verifies a partial downstream failure prevents subsequent output and still permits ownership cleanup.
    @Test
    public void partialOutputFailuresDoNotReplayBytes() throws IOException {
        for (Throwable failure : List.of(new IOException("partial output failure"),
                new IllegalStateException("partial output failure"), new AssertionError("partial output failure"))) {
            for (boolean finishing : new boolean[]{false, true}) {
                TrackingOutputStream target = new TrackingOutputStream();
                target.writeFailure = failure;
                TransformingOutputStream output = new TransformingOutputStream(
                        target, finishing ? (buffer, offset, length) -> 0 : IDENTITY
                );
                if (finishing) {
                    output.write(new byte[]{1, 2, 3});
                    assertSame(failure, assertThrows(failure.getClass(), output::finish));
                } else {
                    assertSame(failure, assertThrows(failure.getClass(), () -> output.write(new byte[]{1, 2, 3})));
                }
                assertSame(failure, assertThrows(failure.getClass(), () -> output.write(7)));
                assertSame(failure, assertThrows(failure.getClass(), output::flush));
                assertSame(failure, assertThrows(failure.getClass(), output::finish));
                assertSame(failure, assertThrows(failure.getClass(), output::close));
                output.close();
                assertArrayEquals(new byte[]{1}, target.bytes());
                assertEquals(0, target.flushCount());
                assertTrue(target.isClosed());
            }
        }
    }

    /// Verifies an unchecked transform failure is retained without transforming or publishing buffered bytes again.
    @Test
    public void retainsUncheckedTransformFailures() throws IOException {
        for (Throwable failure : List.of(new IllegalStateException("transform failed"), new AssertionError("transform failed"))) {
            int[] calls = {0};
            ByteTransform transform = (buffer, offset, length) -> {
                calls[0]++;
                buffer[offset] ^= 0x5a;
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            };
            TrackingOutputStream target = new TrackingOutputStream();
            TransformingOutputStream output = new TransformingOutputStream(target, transform);
            assertSame(failure, assertThrows(failure.getClass(), () -> output.write(1)));
            assertSame(failure, assertThrows(failure.getClass(), () -> output.write(2)));
            assertSame(failure, assertThrows(failure.getClass(), output::close));
            output.close();
            assertEquals(1, calls[0]);
            assertEquals(0, target.bytes().length);
            assertTrue(target.isClosed());
        }
    }

    /// Verifies failed input transforms cannot publish mutated bytes or be invoked again.
    @Test
    public void inputRetainsUncheckedTransformFailures() throws IOException {
        for (Throwable failure : List.of(new IllegalStateException("transform failed"), new AssertionError("transform failed"))) {
            int[] calls = {0};
            TrackingInputStream source = new TrackingInputStream(new byte[]{1, 2});
            TransformingInputStream input = new TransformingInputStream(source, (buffer, offset, length) -> {
                calls[0]++;
                buffer[offset] ^= 0x5a;
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            });
            byte[] target = {9, 8, 7, 6};
            assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target, 1, 2)));
            assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target, 1, 2)));
            assertSame(failure, assertThrows(failure.getClass(), input::read));
            assertSame(failure, assertThrows(failure.getClass(), input::available));
            assertEquals(0, input.read(target, 1, 0));
            assertArrayEquals(new byte[]{9, 8, 7, 6}, target);
            assertEquals(1, source.readCount);
            assertEquals(1, calls[0]);
            input.close();
            input.close();
            assertTrue(source.isClosed());
        }
    }

    /// Verifies partial upstream failures preserve the delivered prefix but prevent further physical reads.
    @Test
    public void partialInputFailuresAreTerminal() throws IOException {
        for (Throwable failure : List.of(new IOException("partial input failure"),
                new IllegalStateException("partial input failure"), new AssertionError("partial input failure"))) {
            TrackingInputStream source = new TrackingInputStream(new byte[]{1, 2, 3});
            source.maximumReadSize = 1;
            source.readFailure = failure;
            source.failingRead = 2;
            try (TransformingInputStream input = new TransformingInputStream(source, IDENTITY)) {
                byte[] target = {9, 8, 7, 6, 5};
                assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target, 1, 3)));
                assertArrayEquals(new byte[]{9, 1, 7, 6, 5}, target);
                assertSame(failure, assertThrows(failure.getClass(), () -> input.read(target, 2, 2)));
                assertSame(failure, assertThrows(failure.getClass(), input::available));
                assertEquals(2, source.readCount);
                assertArrayEquals(new byte[]{9, 1, 7, 6, 5}, target);
            }
            assertTrue(source.isClosed());
        }
    }

    /// Verifies upstream and downstream I/O failures are retained and rethrown by later operations.
    @Test
    public void retainsTransportFailures() {
        IOException readFailure = new IOException("injected input failure");
        TransformingInputStream input = new TransformingInputStream(
                new FailingInputStream(readFailure),
                IDENTITY
        );
        assertSame(readFailure, assertThrows(IOException.class, () -> input.read(new byte[1])));
        assertSame(readFailure, assertThrows(IOException.class, () -> input.read(new byte[1])));
        assertSame(readFailure, assertThrows(IOException.class, input::available));

        IOException writeFailure = new IOException("injected output failure");
        TransformingOutputStream output = new TransformingOutputStream(
                new FailingOutputStream(writeFailure),
                IDENTITY
        );
        assertSame(writeFailure, assertThrows(IOException.class, () -> output.write(new byte[]{1})));
        assertSame(writeFailure, assertThrows(IOException.class, () -> output.write(new byte[]{2})));
        assertSame(writeFailure, assertThrows(IOException.class, output::flush));
        assertSame(writeFailure, assertThrows(IOException.class, output::finish));
    }

    /// Verifies close preserves a finish failure and suppresses a simultaneous downstream close failure.
    @Test
    public void closeCombinesFinishAndEndpointFailures() throws IOException {
        FinishAndCloseFailingOutputStream target = new FinishAndCloseFailingOutputStream();
        TransformingOutputStream output = new TransformingOutputStream(
                target,
                (buffer, offset, length) -> 0
        );
        output.write(1);

        IOException failure = assertThrows(IOException.class, output::close);
        assertSame(target.writeFailure(), failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(target.closeFailure(), failure.getSuppressed()[0]);
        assertEquals(1, target.closeCount());

        output.close();
        assertEquals(2, target.closeCount());
        assertTrue(target.isClosed());
        assertThrows(ClosedChannelException.class, () -> output.write(2));
    }

    /// Verifies unchecked downstream close failures retain their original runtime type.
    @Test
    public void closePreservesUncheckedEndpointFailures() {
        RuntimeException runtimeFailure = new IllegalStateException("runtime close failure");
        TransformingOutputStream runtimeOutput = new TransformingOutputStream(
                new UncheckedCloseOutputStream(runtimeFailure),
                IDENTITY
        );
        assertSame(runtimeFailure, assertThrows(RuntimeException.class, runtimeOutput::close));

        Error errorFailure = new AssertionError("error close failure");
        TransformingOutputStream errorOutput = new TransformingOutputStream(
                new UncheckedCloseOutputStream(errorFailure),
                IDENTITY
        );
        assertSame(errorFailure, assertThrows(Error.class, errorOutput::close));
    }

    /// Holds one trailing byte for lookahead and XORs every committed byte.
    @NotNullByDefault
    private static final class DeferredXorTransform implements ByteTransform {
        /// Transforms every byte except the final pending byte.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int transformed = Math.max(0, length - 1);
            for (int index = 0; index < transformed; index++) {
                buffer[offset + index] ^= 0x5a;
            }
            return transformed;
        }
    }

    /// Records output, flushes, and closure for lifecycle assertions.
    @NotNullByDefault
    private static final class TrackingOutputStream extends OutputStream {
        /// Collected bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// A failure thrown once after accepting one byte of a nonempty bulk write.
        private @Nullable Throwable writeFailure;

        /// Number of flush calls.
        private int flushCount;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Writes one byte to the collected output.
        @Override
        public void write(int value) {
            bytes.write(value);
        }

        /// Writes a byte range to the collected output.
        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            @Nullable Throwable failure = writeFailure;
            if (failure != null && length > 0) {
                writeFailure = null;
                bytes.write(source[offset]);
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

        /// Records one flush call.
        @Override
        public void flush() {
            flushCount++;
        }

        /// Marks this stream closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns a copy of collected bytes.
        private byte[] bytes() {
            return bytes.toByteArray();
        }

        /// Returns the number of flush calls.
        private int flushCount() {
            return flushCount;
        }

        /// Returns whether this stream has been closed.
        private boolean isClosed() {
            return closed;
        }
    }

    /// Wraps a byte-array stream and records closure.
    @NotNullByDefault
    private static final class TrackingInputStream extends InputStream {
        /// Readable byte-array delegate.
        private final ByteArrayInputStream delegate;

        /// Failure thrown once after transferring bytes on the selected bulk read.
        private @Nullable Throwable readFailure;

        /// One-based physical read on which to inject the failure.
        private int failingRead = 1;

        /// Maximum number of bytes returned by one bulk read.
        private int maximumReadSize = Integer.MAX_VALUE;

        /// Number of physical read attempts.
        private int readCount;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a tracked source over the supplied bytes.
        private TrackingInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        /// Reads one byte from the delegate.
        @Override
        public int read() {
            readCount++;
            return delegate.read();
        }

        /// Reads a byte range from the delegate.
        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            readCount++;
            int count = delegate.read(target, offset, Math.min(length, maximumReadSize));
            @Nullable Throwable failure = readFailure;
            if (failure != null && readCount == failingRead) {
                readFailure = null;
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            return count;
        }

        /// Marks this stream closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this stream has been closed.
        private boolean isClosed() {
            return closed;
        }
    }

    /// Returns zero from its first bulk read and then exposes fixed bytes normally.
    @NotNullByDefault
    private static final class ZeroFirstBulkInputStream extends InputStream {
        /// Readable byte-array delegate.
        private final ByteArrayInputStream delegate;

        /// Whether the artificial zero result has been returned.
        private boolean returnedZero;

        /// Creates a source over the supplied bytes.
        private ZeroFirstBulkInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        /// Reads one byte from the delegate.
        @Override
        public int read() {
            return delegate.read();
        }

        /// Returns zero once before delegating subsequent bulk reads.
        @Override
        public int read(byte[] target, int offset, int length) {
            if (!returnedZero && length > 0) {
                returnedZero = true;
                return 0;
            }
            return delegate.read(target, offset, length);
        }
    }

    /// Throws one stable checked failure from every read operation.
    @NotNullByDefault
    private static final class FailingInputStream extends InputStream {
        /// The stable failure returned to callers.
        private final IOException failure;

        /// Creates a source that throws the supplied failure.
        private FailingInputStream(IOException failure) {
            this.failure = failure;
        }

        /// Throws the configured failure.
        @Override
        public int read() throws IOException {
            throw failure;
        }

        /// Throws the configured failure.
        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            throw failure;
        }
    }

    /// Throws one stable checked failure from every write operation.
    @NotNullByDefault
    private static final class FailingOutputStream extends OutputStream {
        /// The stable failure returned to callers.
        private final IOException failure;

        /// Creates a target that throws the supplied failure.
        private FailingOutputStream(IOException failure) {
            this.failure = failure;
        }

        /// Throws the configured failure.
        @Override
        public void write(int value) throws IOException {
            throw failure;
        }

        /// Throws the configured failure.
        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            throw failure;
        }
    }

    /// Fails transform-tail output and the first close attempt, then closes successfully.
    @NotNullByDefault
    private static final class FinishAndCloseFailingOutputStream extends OutputStream {
        /// Failure thrown while writing the transform tail.
        private final IOException writeFailure = new IOException("tail write failed");

        /// Failure thrown by the first close attempt.
        private final IOException closeFailure = new IOException("target close failed");

        /// Number of close attempts.
        private int closeCount;

        /// Whether a close attempt has succeeded.
        private boolean closed;

        /// Throws the stable tail-write failure.
        @Override
        public void write(int value) throws IOException {
            throw writeFailure;
        }

        /// Fails once and records successful closure on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw closeFailure;
            }
            closed = true;
        }

        /// Returns the tail-write failure.
        private IOException writeFailure() {
            return writeFailure;
        }

        /// Returns the first-close failure.
        private IOException closeFailure() {
            return closeFailure;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether closure eventually succeeded.
        private boolean isClosed() {
            return closed;
        }
    }

    /// Throws a configured unchecked failure when closed.
    @NotNullByDefault
    private static final class UncheckedCloseOutputStream extends OutputStream {
        /// Runtime exception or error thrown by [#close()].
        private final Throwable failure;

        /// Creates a target that throws the supplied unchecked failure.
        private UncheckedCloseOutputStream(Throwable failure) {
            this.failure = failure;
        }

        /// Accepts one byte without retaining it.
        @Override
        public void write(int value) {
        }

        /// Throws the configured unchecked failure.
        @Override
        public void close() {
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }
    }
}

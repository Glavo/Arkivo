// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compression flush propagation, close retries, and failure suppression.
@NotNullByDefault
final class CompressionOutputStreamTest {
    /// Scalar writes reuse their private source while ranged bulk writes tolerate partial channel progress.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 7, 8193})
    void mixesScalarAndPartialBulkWrites(int length) throws IOException {
        var target = new TrackingCompressingWritableByteChannel();
        target.maximumWriteSize = 3;
        target.checkScalarReuse = true;
        byte[] content = new byte[length + 4];
        for (int index = 0; index < content.length; index++) content[index] = (byte) (index * 29);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        OutputStream output = new CompressionOutputStream(target, new ByteArrayOutputStream(), ResourceOwnership.OWNED);
        try (output) {
            for (int value : new int[]{-1, 0, 128, 255, 256, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                output.write(value);
                expected.write(value);
                output.write(content, 2, length);
                expected.write(content, 2, length);
                int calls = target.writeCount;
                output.write(content, content.length, 0);
                assertEquals(calls, target.writeCount);
            }
            assertArrayEquals(expected.toByteArray(), target.bytes.toByteArray());
            assertEquals(expected.size(), target.inputBytes());
            assertThrows(IndexOutOfBoundsException.class, () -> output.write(content, content.length + 1, 0));
        }
        int calls = target.writeCount;
        assertThrows(IOException.class, () -> output.write(42));
        assertThrows(IOException.class, () -> output.write(content, 0, 0));
        assertEquals(calls, target.writeCount);
        assertEquals(1, target.finishCount());
    }

    /// Scalar and bulk target failures retain their type and allow automatic cleanup without self-suppression.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writeFailurePreservesPrimaryDuringClose(boolean scalar) throws IOException {
        for (Throwable failure : List.of(new IOException("write failed"),
                new IllegalStateException("write failed"), new AssertionError("write failed"))) {
            var target = new FailingCloseWritableChannel();
            target.closeFailure = failure;
            target.writeFailure = failure;
            OutputStream output = new CompressionOutputStream(target, new ByteArrayOutputStream(), ResourceOwnership.OWNED);
            Throwable actual = assertThrows(failure.getClass(), () -> {
                try (output) {
                    if (scalar) output.write(255);
                    else output.write(new byte[]{1, 2, 3});
                }
            });
            assertSame(failure, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(failure, actual.getSuppressed()[0].getCause());
            output.close();
            output.close();
            assertEquals(2, target.closeCount());
        }
    }

    /// Preserves earlier failures even when a different retry failure precedes automatic resource closure.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void alternatingFailuresDoNotSelfSuppress(boolean explicitClose) throws IOException {
        for (Throwable primary : List.of(new IOException("first failure"),
                new IllegalStateException("first failure"), new AssertionError("first failure"))) {
            IOException secondary = new IOException("second failure");
            var downstream = new ByteArrayOutputStream() {
                /// The number of flush attempts, including the final successful retry.
                private int flushCount;

                /// Alternates two failure instances before allowing cleanup to complete.
                @Override
                public void flush() throws IOException {
                    int attempt = ++flushCount;
                    if (attempt == 2) throw secondary;
                    if (attempt == 1 || attempt == 3) {
                        if (primary instanceof IOException exception) throw exception;
                        if (primary instanceof RuntimeException exception) throw exception;
                        throw (Error) primary;
                    }
                }
            };
            OutputStream output = new CompressionOutputStream(
                    new TrackingCompressingWritableByteChannel(), downstream, ResourceOwnership.BORROWED);
            Throwable failure = assertThrows(primary.getClass(), () -> {
                try (output) {
                    try {
                        if (explicitClose) output.close();
                        else output.flush();
                    } catch (Throwable original) {
                        assertSame(primary, original);
                        assertThrows(Throwable.class, () -> {
                            if (explicitClose) output.close();
                            else output.flush();
                        });
                        throw original;
                    }
                }
            });
            assertSame(primary, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(primary, failure.getSuppressed()[0].getCause());
            assertEquals(3, downstream.flushCount);
            output.close();
            output.close();
            assertEquals(4, downstream.flushCount);
        }
    }

    /// Preserves a repeated downstream failure as the primary exception of try-with-resources.
    @Test
    void repeatedFlushFailureDoesNotSelfSuppress() throws IOException {
        IOException expected = new IOException("Persistent flush failure");
        OutputStream downstream = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                throw expected;
            }
        };
        OutputStream output = new CompressionOutputStream(
                new TrackingCompressingWritableByteChannel(), downstream, ResourceOwnership.BORROWED);
        IOException failure = assertThrows(IOException.class, () -> {
            try (output) {
                output.flush();
            }
        });
        assertSame(expected, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(expected, failure.getSuppressed()[0].getCause());
        assertSame(expected, assertThrows(IOException.class, output::close).getCause());
    }

    /// Preserves an explicitly failed close when automatic resource cleanup retries the same downstream failure.
    @Test
    void repeatedCloseFailureDoesNotSelfSuppress() {
        IOException expected = new IOException("Persistent close failure");
        OutputStream downstream = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                throw expected;
            }
        };
        IOException failure = assertThrows(IOException.class, () -> {
            try (OutputStream output = new CompressionOutputStream(
                    new TrackingCompressingWritableByteChannel(), downstream, ResourceOwnership.BORROWED)) {
                output.close();
            }
        });
        assertSame(expected, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(expected, failure.getSuppressed()[0].getCause());
    }

    /// Verifies plain channel targets retain zero-progress rejection and closed-stream checks.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsZeroProgressAndClosedOperations(boolean scalar) throws IOException {
        OutputStream output = new CompressionOutputStream(
                new ZeroProgressWritableChannel(), new ByteArrayOutputStream(), ResourceOwnership.OWNED);
        assertThrows(IOException.class, () -> {
            if (scalar) output.write(1);
            else output.write(new byte[]{1});
        });
        output.write(new byte[0]);
        output.close();
        output.close();
        assertThrows(IOException.class, () -> output.write(1));
        assertThrows(IOException.class, output::flush);
    }

    /// Verifies output-stream flush reaches a channel-first compression encoder.
    @Test
    void flushesCompressingWritableByteChannelThroughOutputStream() throws IOException {
        TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
        try (OutputStream output = new CompressionOutputStream(
                encoder, new ByteArrayOutputStream(), ResourceOwnership.OWNED)) {
            output.write(new byte[]{1, 2, 3});
            output.flush();
            assertEquals(1, encoder.flushCount());
            assertEquals(3L, encoder.inputBytes());
            assertTrue(encoder.isOpen());
        }
        assertFalse(encoder.isOpen());
    }

    /// Verifies an explicit flush reaches a borrowed downstream stream and remains retryable after failure.
    @Test
    void flushesBorrowedDownstreamAndRetainsStreamAfterFailure() throws IOException {
        TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
        FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
        OutputStream output = new CompressionOutputStream(
                encoder,
                downstream,
                ResourceOwnership.BORROWED
        );

        IOException failure = assertThrows(IOException.class, output::flush);
        assertEquals("flush failed", failure.getMessage());
        assertEquals(1, encoder.flushCount());
        assertEquals(1, downstream.flushCount());

        output.flush();
        assertEquals(2, encoder.flushCount());
        assertEquals(2, downstream.flushCount());
        output.close();
        assertEquals(1, encoder.finishCount());
        assertEquals(3, downstream.flushCount());
    }

    /// Verifies codec flush failures retain their identity while downstream flush is attempted and remains retryable.
    @Test
    void composesCheckedAndUncheckedFlushFailures() throws IOException {
        for (ResourceOwnership ownership : ResourceOwnership.values()) {
            for (boolean sharedFailure : new boolean[]{false, true}) {
                for (Throwable primary : List.of(new IOException("encoder flush failed"),
                        new IllegalStateException("encoder flush failed"), new AssertionError("encoder flush failed"))) {
                    TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
                    encoder.flushFailure = primary;
                    FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
                    if (sharedFailure) {
                        downstream.flushFailure = primary;
                    }
                    Throwable secondary = downstream.flushFailure;
                    OutputStream output = new CompressionOutputStream(encoder, downstream, ownership);
                    assertSame(primary, assertThrows(primary.getClass(), output::flush));
                    assertEquals(1, encoder.flushCount());
                    assertEquals(1, downstream.flushCount());
                    assertEquals(sharedFailure ? 0 : 1, primary.getSuppressed().length);
                    if (!sharedFailure) {
                        assertSame(secondary, primary.getSuppressed()[0]);
                    }
                    output.write(7);
                    output.flush();
                    assertEquals(1, encoder.inputBytes());
                    assertEquals(2, encoder.flushCount());
                    assertEquals(2, downstream.flushCount());
                    output.close();
                    output.close();
                    assertEquals(1, encoder.finishCount());
                    assertEquals(ownership == ResourceOwnership.BORROWED ? 3 : 2, downstream.flushCount());
                }
            }
        }
    }

    /// Verifies close retains checked or unchecked target failures without suppressing an exception onto itself.
    @Test
    void composesTargetCloseAndBorrowedDownstreamFlushFailures() throws IOException {
        for (boolean sharedFailure : new boolean[]{false, true}) {
            for (Throwable primary : List.of(new IOException("close failed"),
                    new IllegalStateException("close failed"), new AssertionError("close failed"))) {
                FailingCloseWritableChannel target = new FailingCloseWritableChannel();
                target.closeFailure = primary;
                FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
                if (sharedFailure) {
                    downstream.flushFailure = primary;
                }
                Throwable secondary = downstream.flushFailure;
                OutputStream output = new CompressionOutputStream(target, downstream, ResourceOwnership.BORROWED);

                assertSame(primary, assertThrows(primary.getClass(), output::close));
                assertEquals(sharedFailure ? 0 : 1, primary.getSuppressed().length);
                if (!sharedFailure) {
                    assertSame(secondary, primary.getSuppressed()[0]);
                }
                assertEquals(1, target.closeCount());
                assertEquals(1, downstream.flushCount());

                output.close();
                assertEquals(2, target.closeCount());
                assertEquals(2, downstream.flushCount());
                output.close();
                assertEquals(2, target.closeCount());
                assertEquals(2, downstream.flushCount());
                assertThrows(IOException.class, () -> output.write(1));
                assertThrows(IOException.class, output::flush);
            }
        }
    }

    /// Verifies close retries only a failed downstream flush after codec finalization has completed.
    @Test
    void retriesBorrowedDownstreamFlushWithoutRefinalizingCodec() throws IOException {
        TrackingCompressingWritableByteChannel encoder = new TrackingCompressingWritableByteChannel();
        FailingOnceFlushOutputStream downstream = new FailingOnceFlushOutputStream();
        OutputStream output = new CompressionOutputStream(
                encoder,
                downstream,
                ResourceOwnership.BORROWED
        );
        output.write(new byte[]{1, 2, 3});

        IOException failure = assertThrows(IOException.class, output::close);
        assertEquals("flush failed", failure.getMessage());
        assertEquals(1, encoder.finishCount());
        assertEquals(1, downstream.flushCount());

        output.close();
        assertEquals(1, encoder.finishCount());
        assertEquals(2, downstream.flushCount());
        output.close();
        assertEquals(2, downstream.flushCount());
    }

    /// Records calls made through a stream view of a compression encoder.
    @NotNullByDefault
    private static final class TrackingCompressingWritableByteChannel implements CompressingWritableByteChannel.Flushable {
        /// Captured channel input, used to verify scalar and ranged writes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Maximum input accepted in one channel call.
        private int maximumWriteSize = Integer.MAX_VALUE;

        /// Number of channel write attempts.
        private int writeCount;

        /// Whether one-byte sources must be the same private buffer throughout this session.
        private boolean checkScalarReuse;

        /// First scalar source observed by the reuse check.
        private @Nullable ByteBuffer scalarSource;

        /// The number of accepted source bytes.
        private long inputBytes;

        /// The number of flush calls.
        private int flushCount;

        /// Failure thrown once by the next compression flush, or `null` when disabled.
        private @Nullable Throwable flushFailure;

        /// Number of encoder finalizations.
        private int finishCount;

        /// Whether this encoder remains open.
        private boolean open = true;

        /// Captures a bounded prefix and optionally checks reuse of the internal scalar source.
        @Override
        public int write(ByteBuffer source) {
            writeCount++;
            if (checkScalarReuse && source.capacity() == 1) {
                assertEquals(0, source.position());
                assertEquals(1, source.limit());
                if (scalarSource == null) scalarSource = source;
                else assertSame(scalarSource, source);
            }
            int count = Math.min(source.remaining(), maximumWriteSize);
            for (int index = 0; index < count; index++) bytes.write(source.get());
            inputBytes += count;
            return count;
        }

        /// Records one compression flush.
        @Override
        public void flush() throws IOException {
            flushCount++;
            @Nullable Throwable failure = flushFailure;
            flushFailure = null;
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        /// Finishes this encoder.
        @Override
        public void finish() {
            if (open) {
                finishCount++;
                open = false;
            }
        }

        /// Returns the number of accepted source bytes.
        @Override
        public long inputBytes() {
            return inputBytes;
        }

        /// Returns zero because this test encoder emits no bytes.
        @Override
        public long outputBytes() {
            return 0L;
        }

        /// Returns whether this encoder remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Finishes this encoder.
        @Override
        public void close() {
            finish();
        }

        /// Returns the number of recorded flush calls.
        private int flushCount() {
            return flushCount;
        }

        /// Returns the number of encoder finalizations.
        private int finishCount() {
            return finishCount;
        }
    }

    /// Fails its first flush while accepting all writes.
    @NotNullByDefault
    private static final class FailingOnceFlushOutputStream extends OutputStream {
        /// Failure thrown by the first flush attempt.
        private Throwable flushFailure = new IOException("flush failed");

        /// Number of flush attempts.
        private int flushCount;

        /// Accepts one byte.
        @Override
        public void write(int value) {
        }

        /// Fails the first flush attempt.
        @Override
        public void flush() throws IOException {
            flushCount++;
            if (flushCount == 1) {
                if (flushFailure instanceof IOException exception) {
                    throw exception;
                }
                if (flushFailure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) flushFailure;
            }
        }

        /// Returns the number of flush attempts.
        private int flushCount() {
            return flushCount;
        }
    }

    /// Fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Optional failure emitted after consuming one source byte.
        private @Nullable Throwable writeFailure;

        /// Failure thrown by the first close attempt.
        private Throwable closeFailure = new IOException("close failed");

        /// Number of close attempts.
        private int closeCount;

        /// Consumes all source bytes, or emits the configured failure after accepting one byte.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (writeFailure != null && source.hasRemaining()) {
                source.get();
                if (writeFailure instanceof IOException exception) throw exception;
                if (writeFailure instanceof RuntimeException exception) throw exception;
                throw (Error) writeFailure;
            }
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether closure has not succeeded.
        @Override
        public boolean isOpen() {
            return closeCount < 2;
        }

        /// Fails the first close attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                if (closeFailure instanceof IOException exception) {
                    throw exception;
                }
                if (closeFailure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) closeFailure;
            }
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Returns zero progress for every channel write.
    @NotNullByDefault
    private static final class ZeroProgressWritableChannel implements WritableByteChannel {
        /// Returns zero progress.
        @Override
        public int write(ByteBuffer source) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Closes this channel.
        @Override
        public void close() {
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compression flush propagation, close retries, and failure suppression.
@NotNullByDefault
final class CompressionOutputStreamTest {
    /// Verifies plain channel targets retain zero-progress rejection and closed-stream checks.
    @Test
    void rejectsZeroProgressAndClosedOperations() throws IOException {
        OutputStream output = new CompressionOutputStream(
                new ZeroProgressWritableChannel(), new ByteArrayOutputStream(), ResourceOwnership.OWNED);
        assertThrows(IOException.class, () -> output.write(new byte[]{1}));
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

        /// Consumes all source bytes.
        @Override
        public int write(ByteBuffer source) {
            int count = source.remaining();
            source.position(source.limit());
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
        /// Failure thrown by the first close attempt.
        private Throwable closeFailure = new IOException("close failed");

        /// Number of close attempts.
        private int closeCount;

        /// Consumes all source bytes.
        @Override
        public int write(ByteBuffer source) {
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

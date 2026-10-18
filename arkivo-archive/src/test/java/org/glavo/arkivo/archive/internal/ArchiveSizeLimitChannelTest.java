// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies decoded archive size enforcement, probe boundaries, and channel ownership.
@NotNullByDefault
final class ArchiveSizeLimitChannelTest {
    /// Preserves interruption and asynchronous-close failures when partial output also exceeds the limit.
    @ParameterizedTest
    @CsvSource({"true,0", "true,1", "false,0", "false,1"})
    void preservesCancellationAfterPartialProgress(boolean interrupt, long maximum) throws Exception {
        BlockingPartialChannel delegate = new BlockingPartialChannel();
        ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, maximum);
        ByteBuffer target = ByteBuffer.allocateDirect(8).position(2).limit(6).mark();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                limited.read(target);
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "archive-limit-reader");
        reader.start();
        try {
            assertTrue(delegate.started.await(5L, TimeUnit.SECONDS));
            if (interrupt) {
                reader.interrupt();
            } else {
                limited.close();
            }
            reader.join(5_000L);
            assertFalse(reader.isAlive());
            AsynchronousCloseException cancellation = assertInstanceOf(AsynchronousCloseException.class, failure.get());
            assertEquals(interrupt ? ClosedByInterruptException.class : AsynchronousCloseException.class,
                    cancellation.getClass());
            assertEquals(interrupt, interrupted.get());
            assertEquals(3, target.position());
            assertEquals(6, target.limit());
            assertEquals(1, target.get(2));
            assertEquals(2, target.reset().position());
            assertFalse(limited.isOpen());
            assertFalse(delegate.isOpen());
            if (maximum == 0L) {
                assertEquals(1, cancellation.getSuppressed().length);
                ArkivoReadLimitException limitFailure = assertInstanceOf(
                        ArkivoReadLimitException.class, cancellation.getSuppressed()[0]
                );
                assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, limitFailure.kind());
                assertEquals(0L, limitFailure.maximum());
                assertEquals(1L, limitFailure.actual());
            } else {
                assertEquals(0, cancellation.getSuppressed().length);
            }
            assertThrows(ClosedChannelException.class, () -> limited.read(ByteBuffer.allocate(0)));
            assertThrows(ClosedChannelException.class, () -> limited.read(ByteBuffer.allocate(1)));
        } finally {
            limited.close();
            reader.interrupt();
            reader.join(5_000L);
            assertFalse(reader.isAlive());
        }
    }

    /// Blocks after producing one byte until interrupted or closed by another thread.
    @NotNullByDefault
    private static final class BlockingPartialChannel extends AbstractInterruptibleChannel
            implements ReadableByteChannel {
        /// Signals that the read has produced its partial output.
        private final CountDownLatch started = new CountDownLatch(1);

        /// Releases the blocked read when the channel closes.
        private final CountDownLatch closed = new CountDownLatch(1);

        /// Produces one byte and lets the JDK report cancellation from the unfinished operation.
        @Override
        public int read(ByteBuffer target) throws IOException {
            begin();
            try {
                target.put((byte) 1);
                started.countDown();
                try {
                    closed.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            } finally {
                end(false);
            }
        }

        /// Unblocks the active read without modifying its buffer.
        @Override
        protected void implCloseChannel() {
            closed.countDown();
        }
    }

    /// Distinguishes an exact maximum-long count from one additional byte without transferring exabytes of data.
    @Test
    void rejectsBytesBeyondMaximumLongCount() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3});
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(Channels.newChannel(input), Long.MAX_VALUE)) {
            var count = ArchiveSizeLimitChannel.class.getDeclaredField("count");
            count.setAccessible(true);
            count.setLong(limited, Long.MAX_VALUE - 1L);
            assertEquals(1, limited.read(ByteBuffer.allocate(1)));
            ByteBuffer target = ByteBuffer.allocate(4).position(1).mark();
            ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class, () -> limited.read(target));
            assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, failure.kind());
            assertEquals(Long.MAX_VALUE, failure.maximum());
            assertEquals(Long.MAX_VALUE, failure.actual());
            assertEquals(2, target.position());
            assertEquals(4, target.limit());
            assertEquals(1, target.reset().position());
            assertEquals(1, input.available());
            assertThrows(ArkivoReadLimitException.class, () -> limited.read(target));
            assertEquals(1, input.available());
        }
    }

    /// Preserves empty-read and closed-channel behavior after the archive allowance has been exceeded.
    @Test
    void preservesChannelStateAfterLimitFailure() throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1, 2});
        ReadableByteChannel delegate = Channels.newChannel(input);
        ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 0L);
        try (limited) {
            ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class,
                    () -> limited.read(ByteBuffer.allocate(1)));
            assertEquals(1, input.available());
            assertEquals(0, limited.read(ByteBuffer.allocate(0)));
            assertEquals(1, input.available());
            assertSame(failure, assertThrows(ArkivoReadLimitException.class,
                    () -> limited.read(ByteBuffer.allocate(1))));
            delegate.close();
            assertFalse(limited.isOpen());
            assertThrows(ClosedChannelException.class, () -> limited.read(ByteBuffer.allocate(0)));
            assertThrows(ClosedChannelException.class, () -> limited.read(ByteBuffer.allocate(1)));
            assertEquals(1, input.available());
        }
    }

    /// Verifies the maximum long allowance does not overflow when reserving its probe byte.
    @Test
    void acceptsMaximumAllowanceWithDirectBufferWindow() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, Long.MAX_VALUE)) {
            ByteBuffer parent = ByteBuffer.allocateDirect(12);
            parent.position(3).limit(10);
            ByteBuffer target = parent.slice().position(2).limit(5).mark();
            assertEquals(3, limited.read(target));
            assertEquals(5, target.position());
            assertEquals(5, target.limit());
            assertEquals(3, parent.position());
            assertEquals(10, parent.limit());
            assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3}), target.reset().slice());
            target.position(5).limit(7);
            assertEquals(-1, limited.read(target));
            assertEquals(-1, limited.read(target));
            assertEquals(5, target.position());
        }
    }

    /// Verifies a rejected read-only destination neither consumes input nor spends the size allowance.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedTargetLeavesAllowanceAndInputUnchanged(boolean direct) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3});
        ReadableByteChannel delegate = Channels.newChannel(input);
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 2L)) {
            ByteBuffer storage = direct ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
            ByteBuffer rejected = storage.position(2).limit(7).asReadOnlyBuffer().mark();
            assertThrows(IllegalArgumentException.class, () -> limited.read(rejected));
            assertEquals(3, input.available());
            assertEquals(2, rejected.position());
            assertEquals(7, rejected.limit());
            assertEquals(2, rejected.reset().position());
            ByteBuffer permitted = ByteBuffer.allocate(2);
            assertEquals(2, limited.read(permitted));
            assertEquals(ByteBuffer.wrap(new byte[]{1, 2}), permitted.flip());
            ByteBuffer probe = ByteBuffer.allocateDirect(8).position(2).limit(7).mark();
            ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class, () -> limited.read(probe));
            assertEquals(3L, failure.actual());
            assertEquals(3, probe.position());
            assertEquals(7, probe.limit());
            assertEquals(3, probe.get(2));
            assertEquals(2, probe.reset().position());
        }
    }

    /// Supplies unchecked failures that must remain primary after a size violation.
    private static Stream<Throwable> uncheckedFailures() {
        return Stream.of(new IllegalStateException("partial runtime failure"), new AssertionError("partial error"));
    }

    /// Verifies a read stops at the first byte beyond the configured maximum.
    @Test
    void readsOnlyOneProbeByteBeyondLimit() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(
                new byte[]{10, 11, 12, 13, 14, 15, 16}
        ));
        ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 3L);
        assertTrue(limited.isOpen());
        ByteBuffer target = ByteBuffer.allocate(12);
        target.position(2);
        target.limit(10);

        ArkivoReadLimitException failure = assertThrows(
                ArkivoReadLimitException.class,
                () -> limited.read(target)
        );

        assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, failure.kind());
        assertEquals(3L, failure.maximum());
        assertEquals(4L, failure.actual());
        assertEquals(6, target.position());
        assertEquals(10, target.limit());
        assertEquals(10, target.get(2));
        assertEquals(13, target.get(5));
        assertSame(failure, assertThrows(ArkivoReadLimitException.class, () -> limited.read(target)));

        limited.close();
        assertFalse(limited.isOpen());
        assertFalse(delegate.isOpen());
    }

    /// Verifies exact-limit reads succeed and an empty target does not trigger a probe.
    @Test
    void permitsExactLimitAndDefersProbeForEmptyTarget() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 3L)) {
            ByteBuffer exact = ByteBuffer.allocate(3);
            assertEquals(3, limited.read(exact));
            assertEquals(0, limited.read(ByteBuffer.allocate(0)));

            ByteBuffer probe = ByteBuffer.allocate(8);
            ArkivoReadLimitException failure = assertThrows(
                    ArkivoReadLimitException.class,
                    () -> limited.read(probe)
            );
            assertEquals(4L, failure.actual());
            assertEquals(1, probe.position());
            assertEquals(8, probe.limit());
        }
    }

    /// Verifies disabled limits preserve channel identity and enabled wrappers preserve interruptibility.
    @Test
    void preservesIdentityAndInterruptibility() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));

        assertSame(delegate, ArchiveSizeLimitChannel.wrap(delegate, -1L));

        ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 0L);
        assertInstanceOf(InterruptibleChannel.class, delegate);
        assertInstanceOf(InterruptibleChannel.class, limited);
        limited.close();
        assertFalse(delegate.isOpen());
    }

    /// Verifies the concrete limiter rejects a negative maximum.
    @Test
    void rejectsNegativeMaximum() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        try (delegate) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ArchiveSizeLimitChannel(delegate, -1L)
            );
        }
    }

    /// Verifies bytes delivered before a delegate exception consume allowance on the next read.
    @Test
    void accountsPartialProgressBeforeDelegateFailure() throws IOException {
        IOException readFailure = new IOException("partial read failure");
        PartiallyFailingReadableByteChannel delegate = new PartiallyFailingReadableByteChannel(
                new byte[]{1, 2, 3},
                2,
                readFailure
        );
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 2L)) {
            ByteBuffer first = ByteBuffer.allocate(8);
            first.position(1);
            first.limit(7);

            assertSame(readFailure, assertThrows(IOException.class, () -> limited.read(first)));
            assertEquals(3, first.position());
            assertEquals(7, first.limit());
            assertEquals(1, first.get(1));
            assertEquals(2, first.get(2));

            ByteBuffer probe = ByteBuffer.allocate(8);
            ArkivoReadLimitException limitFailure = assertThrows(
                    ArkivoReadLimitException.class,
                    () -> limited.read(probe)
            );
            assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, limitFailure.kind());
            assertEquals(2L, limitFailure.maximum());
            assertEquals(3L, limitFailure.actual());
            assertEquals(1, probe.position());
        }
    }

    /// Verifies a partial delegate failure crossing the limit is retained as suppressed context.
    @Test
    void prioritizesLimitFailureAfterPartialDelegateFailure() throws IOException {
        IOException readFailure = new IOException("partial read failure");
        PartiallyFailingReadableByteChannel delegate = new PartiallyFailingReadableByteChannel(
                new byte[]{1, 2},
                2,
                readFailure
        );
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 1L)) {
            ByteBuffer target = ByteBuffer.allocate(8);
            ArkivoReadLimitException limitFailure = assertThrows(
                    ArkivoReadLimitException.class,
                    () -> limited.read(target)
            );

            assertEquals(2L, limitFailure.actual());
            assertEquals(2, target.position());
            assertEquals(1, limitFailure.getSuppressed().length);
            assertSame(readFailure, limitFailure.getSuppressed()[0]);
            assertSame(
                    limitFailure,
                    assertThrows(ArkivoReadLimitException.class, () -> limited.read(ByteBuffer.allocate(1)))
            );
        }
    }

    /// Verifies unchecked delegate failures remain primary while the exceeded limit becomes sticky.
    @ParameterizedTest
    @MethodSource("uncheckedFailures")
    void preservesUncheckedFailureAfterPartialLimitViolation(Throwable readFailure) throws IOException {
        PartiallyFailingReadableByteChannel delegate = new PartiallyFailingReadableByteChannel(
                new byte[]{1, 2},
                2,
                readFailure
        );
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 1L)) {
            ByteBuffer target = ByteBuffer.allocate(8);

            assertSame(readFailure, assertThrows(readFailure.getClass(), () -> limited.read(target)));
            assertEquals(2, target.position());
            assertEquals(1, readFailure.getSuppressed().length);
            ArkivoReadLimitException limitFailure = assertInstanceOf(
                    ArkivoReadLimitException.class,
                    readFailure.getSuppressed()[0]
            );
            assertEquals(1L, limitFailure.maximum());
            assertEquals(2L, limitFailure.actual());
            assertSame(
                    limitFailure,
                    assertThrows(ArkivoReadLimitException.class, () -> limited.read(ByteBuffer.allocate(1)))
            );
        }
    }

    /// Verifies plain delegates remain non-interruptible and null arguments fail before I/O.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void preservesPlainChannelTypeAndValidatesNulls() throws IOException {
        PartiallyFailingReadableByteChannel delegate = new PartiallyFailingReadableByteChannel(
                new byte[]{1},
                1,
                new IOException("unused")
        );
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 1L)) {
            assertFalse(limited instanceof InterruptibleChannel);
            assertThrows(NullPointerException.class, () -> limited.read(null));
        }
        assertThrows(NullPointerException.class, () -> ArchiveSizeLimitChannel.wrap(null, 1L));
    }

    /// Delivers a configured prefix before failing once, then returns the remaining bytes.
    @NotNullByDefault
    private static final class PartiallyFailingReadableByteChannel implements ReadableByteChannel {
        /// Remaining source bytes.
        private final ByteBuffer source;

        /// Number of bytes delivered before the first failure.
        private final int partialReadSize;

        /// Checked or unchecked failure reported after the partial read.
        private final Throwable failure;

        /// Whether the partial failure remains pending.
        private boolean failurePending = true;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a partially failing source over copied bytes.
        private PartiallyFailingReadableByteChannel(
                byte @Unmodifiable [] source,
                int partialReadSize,
                Throwable failure
        ) {
            this.source = ByteBuffer.wrap(source.clone());
            this.partialReadSize = partialReadSize;
            this.failure = failure;
        }

        /// Delivers the scheduled bytes and reports the first-read failure.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!open) {
                throw new java.nio.channels.ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!source.hasRemaining()) {
                return -1;
            }
            int count = failurePending
                    ? Math.min(partialReadSize, Math.min(target.remaining(), source.remaining()))
                    : Math.min(target.remaining(), source.remaining());
            ByteBuffer chunk = source.slice();
            chunk.limit(count);
            target.put(chunk);
            source.position(source.position() + count);
            if (failurePending) {
                failurePending = false;
                if (failure instanceof IOException ioException) {
                    throw ioException;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            return count;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source.
        @Override
        public void close() {
            open = false;
        }
    }
}

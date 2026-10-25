// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.internal.ForceCloseable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies prefix replay behavior and conditional interruption support.
@NotNullByDefault
final class PrefixReplayReadableByteChannelTest {
    /// Maximum time allowed for a blocking test operation.
    private static final long TIMEOUT_SECONDS = 5L;

    /// Supplies failures shared by a completed read and a still-pending graceful close.
    private static Stream<Arguments> concurrentCleanupFailures() {
        return Stream.of(false, true).flatMap(interruptible -> Stream.of(
                new IOException("source failure"), new IllegalStateException("source failure"),
                new AssertionError("source failure")
        ).map(failure -> Arguments.of(interruptible, failure)));
    }

    /// Retains the read failure when successful cancellation overtakes automatic graceful cleanup.
    @ParameterizedTest
    @MethodSource("concurrentCleanupFailures")
    void preservesFailureWhenForceCloseOvertakesGracefulClose(boolean interruptible, Throwable failure)
            throws Exception {
        var source = interruptible ? new InterruptiblePendingCloseChannel(failure) : new PendingCloseChannel(failure);
        var replay = PrefixReplayReadableByteChannel.create(ByteBuffer.allocate(0), source, ResourceOwnership.OWNED);
        var pending = new FutureTask<Throwable>(() -> assertThrows(failure.getClass(), () -> {
            try (replay) {
                replay.read(ByteBuffer.allocate(1));
            }
        }));
        Thread worker = new Thread(pending, "prefix-replay-pending-close");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(source.closeEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            replay.forceClose();
            source.releaseClose.countDown();
            assertSame(failure, pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
            assertSame(failure, failure.getSuppressed()[0].getCause());
            assertFalse(replay.isOpen());
            assertFalse(source.isOpen());
            replay.close();
        } finally {
            source.releaseClose.countDown();
            replay.forceClose();
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(worker.isAlive(), "cleanup worker leaked");
        }
    }

    /// Allows cancellation to succeed while a graceful close is waiting to repeat a read failure.
    @NotNullByDefault
    private static class PendingCloseChannel implements ReadableByteChannel, ForceCloseable {
        /// The exception delivered by both the read and graceful cleanup.
        private final Throwable failure;
        /// Signals that graceful cleanup has started.
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        /// Allows graceful cleanup to report its failure after cancellation completes.
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        /// Whether cancellation has not yet completed.
        private volatile boolean open = true;

        /// Creates a channel with one shared failure identity.
        private PendingCloseChannel(Throwable failure) { this.failure = failure; }

        /// Fails without delivering bytes.
        @Override
        public int read(ByteBuffer target) throws IOException { throwFailure(); return -1; }

        /// Returns whether cancellation has completed.
        @Override
        public boolean isOpen() { return open; }

        /// Waits for cancellation, then repeats the original read failure.
        @Override
        public void close() throws IOException {
            closeEntered.countDown();
            try {
                assertTrue(releaseClose.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            throwFailure();
        }

        /// Completes cancellation without waiting for graceful cleanup.
        @Override
        public void forceClose() { open = false; }

        /// Throws the configured checked exception, runtime exception, or error.
        private void throwFailure() throws IOException {
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw (Error) failure;
        }
    }

    /// Exercises the interruptible replay wrapper with the same deterministic close schedule.
    @NotNullByDefault
    private static final class InterruptiblePendingCloseChannel extends PendingCloseChannel
            implements InterruptibleChannel {
        /// Creates an interruptible source with one shared failure identity.
        private InterruptiblePendingCloseChannel(Throwable failure) { super(failure); }
    }

    /// Supplies fresh failure identities for read, close, and forced-close cleanup paths.
    private static Stream<Arguments> repeatedFailures() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (boolean interruptible : new boolean[]{false, true}) {
            for (boolean nested : new boolean[]{false, true}) {
                for (ResourceOwnership ownership : ResourceOwnership.values()) {
                    for (int operation = 0; operation < 3; operation++) {
                        if (operation == 1 && ownership == ResourceOwnership.BORROWED) continue;
                        for (Throwable failure : List.of(new IOException("source failure"),
                                new IllegalStateException("source failure"), new AssertionError("source failure"))) {
                            cases.add(Arguments.of(interruptible, nested, ownership, operation, failure));
                        }
                    }
                }
            }
        }
        return cases.build();
    }

    /// Preserves the primary failure when automatic cleanup repeats an endpoint exception.
    @ParameterizedTest
    @MethodSource("repeatedFailures")
    void preservesRepeatedEndpointFailure(boolean interruptible, boolean nested, ResourceOwnership ownership,
                                         int operation, Throwable failure) throws IOException {
        TrackingReadableByteChannel source = interruptible
                ? new InterruptibleTrackingReadableByteChannel(new byte[]{3, 4})
                : new TrackingReadableByteChannel(new byte[]{3, 4});
        source.closeFailure = failure;
        source.nextReadFailure = failure;
        source.bytesBeforeReadFailure = 1;
        PrefixReplayReadableByteChannel inner = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0), source, ownership);
        PrefixReplayReadableByteChannel replay = nested ? PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0), inner, ownership) : inner;
        ByteBuffer target = ByteBuffer.allocateDirect(5).position(1).limit(4).mark();
        assertSame(failure, assertThrows(failure.getClass(), () -> {
            try (replay) {
                switch (operation) {
                    case 0 -> replay.read(target);
                    case 1 -> replay.close();
                    case 2 -> replay.forceClose();
                    default -> throw new AssertionError("Unknown operation");
                }
            }
        }));
        boolean borrowedRead = operation == 0 && ownership == ResourceOwnership.BORROWED;
        assertEquals(borrowedRead ? 0 : 1, failure.getSuppressed().length);
        if (!borrowedRead) {
            assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
            assertSame(failure, failure.getSuppressed()[0].getCause());
        }
        assertEquals(operation == 0 ? 2 : 1, target.position());
        assertEquals(4, target.limit());
        assertEquals(1, target.reset().position());
        assertFalse(replay.isOpen());
        assertTrue(source.isOpen());
        assertEquals(borrowedRead ? 0 : operation == 0 ? 1 : 2, source.closeCalls);
        source.closeFailure = null;
        int closeCalls = source.closeCalls;
        replay.close();
        replay.close();
        assertEquals(borrowedRead ? closeCalls : closeCalls + 1, source.closeCalls);
        assertEquals(borrowedRead, source.isOpen());
        source.close();
    }

    /// Retains identities across distinct failed cleanup attempts without wrapping a new endpoint failure.
    @Test
    void retainsNonconsecutiveCleanupFailures() throws IOException {
        for (boolean forced : new boolean[]{false, true}) {
            TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[0]);
            PrefixReplayReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                    ByteBuffer.allocate(0), source, ResourceOwnership.OWNED);
            IOException first = new IOException("first cleanup failure");
            IllegalStateException second = new IllegalStateException("second cleanup failure");
            Throwable @Unmodifiable [] failures = {first, second, first, second};
            for (int attempt = 0; attempt < failures.length; attempt++) {
                source.closeFailure = failures[attempt];
                Throwable actual = assertThrows(Exception.class, () -> {
                    if (forced) replay.forceClose();
                    else replay.close();
                });
                if (attempt < 2) {
                    assertSame(failures[attempt], actual);
                } else {
                    assertInstanceOf(IOException.class, actual);
                    assertSame(failures[attempt], actual.getCause());
                }
            }
            source.closeFailure = null;
            replay.close();
            replay.close();
            assertEquals(5, source.closeCalls);
            assertFalse(source.isOpen());
        }
    }

    /// Verifies a plain source produces a plain replay channel without changing replay order.
    @Test
    void keepsPlainSourcesNonInterruptible() throws IOException {
        TrackingReadableByteChannel source = new TrackingReadableByteChannel(new byte[]{3});
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.wrap(new byte[]{1, 2}),
                source,
                ResourceOwnership.BORROWED
        );
        ByteBuffer target = ByteBuffer.allocate(4);

        assertFalse(replay instanceof InterruptibleChannel);
        assertEquals(2, replay.read(target));
        assertEquals(1, replay.read(target));
        assertEquals(-1, replay.read(target));
        target.flip();
        assertEquals(1, target.get());
        assertEquals(2, target.get());
        assertEquals(3, target.get());

        replay.close();
        assertTrue(source.isOpen());
    }

    /// Verifies replay owns its cursor and does not read the source until the complete selected prefix is delivered.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaysSelectedPrefixWithoutChangingCallerCursor(boolean directTarget) throws IOException {
        for (boolean interruptible : new boolean[]{false, true}) {
            for (boolean direct : new boolean[]{false, true}) {
                TrackingReadableByteChannel source = interruptible
                        ? new InterruptibleTrackingReadableByteChannel(new byte[]{5, 6})
                        : new TrackingReadableByteChannel(new byte[]{5, 6});
                ByteBuffer storage = direct ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
                storage.put(new byte[]{9, 8, 1, 2, 3, 4, 7, 6}).position(1).limit(7);
                ByteBuffer prefix = storage.slice().asReadOnlyBuffer().position(1).limit(5).mark();
                try (ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(prefix, source, ResourceOwnership.OWNED)) {
                    assertEquals(1, prefix.position());
                    assertEquals(5, prefix.limit());
                    // Caller cursor changes must not move the replay channel's independent view.
                    prefix.position(3).limit(4);
                    assertEquals(0, replay.read(ByteBuffer.allocate(0)));
                    assertEquals(0, source.readCalls());
                    ByteBuffer target = directTarget ? ByteBuffer.allocateDirect(9) : ByteBuffer.allocate(9);
                    target.put(new byte[]{9, 8, 8, 8, 8, 8, 8, 7, 6}).position(1).limit(3).mark();
                    assertEquals(2, replay.read(target));
                    assertEquals(0, source.readCalls());
                    target.limit(8);
                    assertEquals(2, replay.read(target));
                    assertEquals(5, target.position());
                    assertEquals(0, source.readCalls());
                    assertEquals(2, replay.read(target));
                    assertEquals(1, source.readCalls());
                    assertEquals(-1, replay.read(target));
                    assertEquals(7, target.position());
                    assertEquals(8, target.limit());
                    assertEquals(1, target.reset().position());
                    assertEquals(3, prefix.position());
                    assertEquals(4, prefix.limit());
                    assertEquals(1, prefix.reset().position());
                    byte[] actual = new byte[9];
                    target.clear().get(actual);
                    assertArrayEquals(new byte[]{9, 1, 2, 3, 4, 5, 6, 7, 6}, actual);
                }
                assertFalse(source.isOpen());
            }
        }
    }

    /// Verifies rejected read-only targets preserve the replay prefix and do not touch the source.
    @Test
    void rejectedTargetsDoNotConsumePrefix() throws IOException {
        for (boolean interruptible : new boolean[]{false, true}) {
            TrackingReadableByteChannel source = interruptible
                    ? new InterruptibleTrackingReadableByteChannel(new byte[]{3})
                    : new TrackingReadableByteChannel(new byte[]{3});
            try (ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                    ByteBuffer.wrap(new byte[]{1, 2}), source, ResourceOwnership.OWNED)) {
                ByteBuffer target = ByteBuffer.allocate(3);
                ByteBuffer rejected = target.asReadOnlyBuffer().mark();
                assertThrows(IllegalArgumentException.class, () -> replay.read(rejected));
                assertEquals(0, rejected.position());
                assertEquals(0, rejected.reset().position());
                assertEquals(0, source.readCalls());
                assertThrows(IllegalArgumentException.class,
                        () -> replay.read(ByteBuffer.allocate(0).asReadOnlyBuffer()));
                assertEquals(2, replay.read(target));
                assertEquals(0, source.readCalls());
                assertThrows(IllegalArgumentException.class, () -> replay.read(rejected));
                assertEquals(0, source.readCalls());
                assertEquals(1, replay.read(target));
                assertArrayEquals(new byte[]{1, 2, 3}, target.array());
                assertThrows(IllegalArgumentException.class, () -> replay.read(rejected));
                assertEquals(1, source.readCalls());
            }
        }
    }

    /// Verifies source failures preserve partial progress and neither replay the prefix nor force-close borrowed sources.
    @Test
    void preservesPartialSourceFailuresAfterPrefix() throws IOException {
        for (boolean interruptible : new boolean[]{false, true}) {
            for (ResourceOwnership ownership : ResourceOwnership.values()) {
                for (int transferred : new int[]{0, 1, 2}) {
                    for (Throwable failure : List.of(new IOException("partial read failed"),
                            new IllegalStateException("partial read failed"), new AssertionError("partial read failed"))) {
                        TrackingReadableByteChannel source = interruptible
                                ? new InterruptibleTrackingReadableByteChannel(new byte[]{3, 4})
                                : new TrackingReadableByteChannel(new byte[]{3, 4});
                        source.nextReadFailure = failure;
                        source.bytesBeforeReadFailure = transferred;
                        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                                ByteBuffer.wrap(new byte[]{1, 2}), source, ownership);
                        ByteBuffer target = ByteBuffer.allocateDirect(7);
                        target.put(new byte[]{9, 6, 6, 6, 6, 8, 7}).position(1).limit(6).mark();
                        assertEquals(2, replay.read(target));
                        assertEquals(0, source.readCalls());
                        assertSame(failure, assertThrows(failure.getClass(), () -> replay.read(target)));
                        assertEquals(3 + transferred, target.position());
                        assertEquals(6, target.limit());
                        assertTrue(replay.isOpen());
                        assertEquals(transferred == 2 ? -1 : 2 - transferred, replay.read(target));
                        assertEquals(-1, replay.read(target));
                        assertEquals(5, target.position());
                        assertEquals(1, target.reset().position());
                        byte[] actual = new byte[7];
                        target.clear().get(actual);
                        assertArrayEquals(new byte[]{9, 1, 2, 3, 4, 8, 7}, actual);
                        replay.close();
                        replay.close();
                        assertEquals(ownership == ResourceOwnership.BORROWED, source.isOpen());
                        assertFalse(replay.isOpen());
                        source.close();
                    }
                }
            }
        }
    }

    /// Verifies an interruptible source produces an interruptible replay channel.
    @Test
    void preservesInterruptibleSourceCapability() throws IOException {
        InterruptibleTrackingReadableByteChannel source =
                new InterruptibleTrackingReadableByteChannel(new byte[0]);
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );

        assertInstanceOf(InterruptibleChannel.class, replay);
        replay.close();
        assertTrue(source.isOpen());
    }

    /// Verifies an ordinary source failure is propagated while the replay channel remains usable for closure.
    @Test
    void propagatesOrdinarySourceFailureWhileOpen() throws IOException {
        IOException failure = new IOException("read failure");
        ScriptedFailureInterruptibleReadableByteChannel source =
                new ScriptedFailureInterruptibleReadableByteChannel(failure, null);
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );

        IOException thrown = assertThrows(IOException.class, () -> replay.read(ByteBuffer.allocate(1)));

        assertSame(failure, thrown);
        assertEquals(1, source.scriptedReadCalls());
        assertTrue(replay.isOpen());
        assertTrue(source.isOpen());

        source.close();
        assertFalse(replay.isOpen());
        assertThrows(ClosedChannelException.class, () -> replay.read(ByteBuffer.allocate(1)));
        replay.close();
    }

    /// Verifies an interrupted read and forced close may report the same exception without self-suppression.
    @Test
    void preservesSharedInterruptAndCloseFailure() throws IOException {
        ClosedByInterruptException failure = new ClosedByInterruptException();
        ScriptedFailureInterruptibleReadableByteChannel source =
                new ScriptedFailureInterruptibleReadableByteChannel(failure, failure);
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );

        ClosedByInterruptException thrown = assertThrows(
                ClosedByInterruptException.class,
                () -> replay.read(ByteBuffer.allocate(1))
        );

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(replay.isOpen());
        assertTrue(source.isOpen());
        assertEquals(1, source.closeCalls());

        source.allowClose();
        replay.close();
        assertFalse(source.isOpen());
        assertEquals(2, source.closeCalls());
    }

    /// Verifies interruption raised during a successful source read closes the complete replay chain.
    @Test
    void closesWhenSourceInterruptsReadingThread() throws IOException {
        InterruptAfterReadChannel source = new InterruptAfterReadChannel(new byte[]{7});
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );
        ByteBuffer target = ByteBuffer.allocate(1);

        try {
            assertThrows(ClosedByInterruptException.class, () -> replay.read(target));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, target.position());
            target.flip();
            assertEquals(7, Byte.toUnsignedInt(target.get()));
            assertFalse(replay.isOpen());
            assertFalse(source.isOpen());
        } finally {
            assertTrue(Thread.interrupted());
            replay.close();
        }
    }

    /// Verifies pre-interruption closes both the replay channel and its borrowed source before copying prefix bytes.
    @Test
    void closesInterruptibleSourceWhenPreInterruptedDuringPrefixReplay() {
        InterruptibleTrackingReadableByteChannel source =
                new InterruptibleTrackingReadableByteChannel(new byte[]{3});
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.wrap(new byte[]{1, 2}),
                source,
                ResourceOwnership.BORROWED
        );

        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    ClosedByInterruptException.class,
                    () -> replay.read(ByteBuffer.allocate(1))
            );
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(replay.isOpen());
            assertFalse(source.isOpen());
            assertEquals(0, source.readCalls());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    /// Verifies abortive closure crosses multiple borrowed replay decorators.
    @Test
    void forceCloseTraversesNestedBorrowedReplays() {
        InterruptibleTrackingReadableByteChannel source =
                new InterruptibleTrackingReadableByteChannel(new byte[]{3});
        ReadableByteChannel inner = PrefixReplayReadableByteChannel.create(
                ByteBuffer.wrap(new byte[]{2}),
                source,
                ResourceOwnership.BORROWED
        );
        ReadableByteChannel outer = PrefixReplayReadableByteChannel.create(
                ByteBuffer.wrap(new byte[]{1}),
                inner,
                ResourceOwnership.BORROWED
        );

        Thread.currentThread().interrupt();
        try {
            assertThrows(ClosedByInterruptException.class, () -> outer.read(ByteBuffer.allocate(1)));
            assertFalse(outer.isOpen());
            assertFalse(inner.isOpen());
            assertFalse(source.isOpen());
            assertEquals(0, source.readCalls());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    /// Verifies concurrent close aborts an active read even when the source was borrowed.
    @Test
    void closesBorrowedSourceToAbortActiveRead() throws Exception {
        BlockingInterruptibleReadableByteChannel source = new BlockingInterruptibleReadableByteChannel();
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );
        CompletableFuture<Throwable> completion = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                replay.read(ByteBuffer.allocate(1));
                completion.complete(new AssertionError("read completed normally"));
            } catch (Throwable failure) {
                completion.complete(failure);
            }
        }, "prefix-replay-reader");
        reader.start();

        try {
            assertTrue(source.awaitBlocked(), "read did not reach the borrowed source");
            replay.close();

            Throwable failure = completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertInstanceOf(AsynchronousCloseException.class, failure);
            assertFalse(failure instanceof ClosedByInterruptException);
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(reader.isAlive());
            assertFalse(replay.isOpen());
            assertFalse(source.isOpen());
        } finally {
            source.close();
            if (reader.isAlive()) {
                reader.interrupt();
                reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }
            assertFalse(reader.isAlive(), "read worker leaked");
        }
    }

    /// Verifies a nonstandard interruptible source failure is translated after concurrent replay closure.
    @Test
    void convertsOrdinaryFailureAfterConcurrentClose() throws Exception {
        IOException sourceFailure = new IOException("source closed");
        CloseReleasedInterruptibleReadableByteChannel source =
                new CloseReleasedInterruptibleReadableByteChannel(sourceFailure);
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );
        CompletableFuture<Throwable> completion = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                replay.read(ByteBuffer.allocate(1));
                completion.complete(new AssertionError("read completed normally"));
            } catch (Throwable failure) {
                completion.complete(failure);
            }
        }, "prefix-replay-ordinary-close-reader");
        reader.start();

        try {
            assertTrue(source.awaitBlocked(), "read did not reach the borrowed source");
            replay.close();

            Throwable failure = completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertInstanceOf(AsynchronousCloseException.class, failure);
            assertFalse(failure instanceof ClosedByInterruptException);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(sourceFailure, failure.getSuppressed()[0]);
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(reader.isAlive());
            assertFalse(replay.isOpen());
            assertFalse(source.isOpen());
        } finally {
            source.close();
            if (reader.isAlive()) {
                reader.interrupt();
                reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }
            assertFalse(reader.isAlive(), "read worker leaked");
        }
    }

    /// Verifies data returned after concurrent replay closure is rejected with asynchronous-close semantics.
    @Test
    void rejectsSuccessfulReadAfterConcurrentClose() throws Exception {
        CloseReleasedInterruptibleReadableByteChannel source =
                new CloseReleasedInterruptibleReadableByteChannel(null);
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );
        ByteBuffer target = ByteBuffer.allocate(1);
        CompletableFuture<Throwable> completion = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                replay.read(target);
                completion.complete(new AssertionError("read completed normally"));
            } catch (Throwable failure) {
                completion.complete(failure);
            }
        }, "prefix-replay-late-success-reader");
        reader.start();

        try {
            assertTrue(source.awaitBlocked(), "read did not reach the borrowed source");
            replay.close();

            Throwable failure = completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertInstanceOf(AsynchronousCloseException.class, failure);
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(1, target.position());
            target.flip();
            assertEquals(42, Byte.toUnsignedInt(target.get()));
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(reader.isAlive());
            assertFalse(replay.isOpen());
            assertFalse(source.isOpen());
        } finally {
            source.close();
            if (reader.isAlive()) {
                reader.interrupt();
                reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }
            assertFalse(reader.isAlive(), "read worker leaked");
        }
    }

    /// Verifies ordinary owned-source close failures remain retryable without making replay bytes readable again.
    @Test
    void retriesOrdinaryOwnedCloseFailures() throws IOException {
        for (Throwable failure : List.of(new IOException("close failed"),
                new IllegalStateException("close failed"), new AssertionError("close failed"))) {
            ScriptedFailureInterruptibleReadableByteChannel source = new ScriptedFailureInterruptibleReadableByteChannel(
                    new IOException("unexpected source read"), failure);
            ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                    ByteBuffer.wrap(new byte[]{1, 2}), source, ResourceOwnership.OWNED);
            assertSame(failure, assertThrows(failure.getClass(), replay::close));
            assertFalse(replay.isOpen());
            assertTrue(source.isOpen());
            assertEquals(1, source.closeCalls());
            assertThrows(ClosedChannelException.class, () -> replay.read(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> replay.read(ByteBuffer.allocate(0)));
            assertEquals(0, source.scriptedReadCalls());
            source.allowClose();
            replay.close();
            replay.close();
            assertFalse(source.isOpen());
            assertEquals(2, source.closeCalls());
        }
    }

    /// Verifies a failed source close after interruption remains retryable.
    @Test
    void retriesInterruptedSourceCloseFailure() throws IOException {
        FailingCloseInterruptibleReadableByteChannel source =
                new FailingCloseInterruptibleReadableByteChannel();
        ReadableByteChannel replay = PrefixReplayReadableByteChannel.create(
                ByteBuffer.wrap(new byte[]{1}),
                source,
                ResourceOwnership.BORROWED
        );

        Thread.currentThread().interrupt();
        try {
            ClosedByInterruptException failure = assertThrows(
                    ClosedByInterruptException.class,
                    () -> replay.read(ByteBuffer.allocate(1))
            );
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(source.isOpen());
            assertFalse(replay.isOpen());
        } finally {
            assertTrue(Thread.interrupted());
        }

        replay.close();
        assertFalse(source.isOpen());
        assertEquals(2, source.closeCalls());
    }

    /// Verifies retrying ordinary close preserves a failed force-close through nested borrowed replay channels.
    @Test
    void retriesNestedForceCloseFailure() throws IOException {
        FailingCloseInterruptibleReadableByteChannel source =
                new FailingCloseInterruptibleReadableByteChannel();
        ReadableByteChannel inner = PrefixReplayReadableByteChannel.create(
                ByteBuffer.allocate(0),
                source,
                ResourceOwnership.BORROWED
        );
        ReadableByteChannel outer = PrefixReplayReadableByteChannel.create(
                ByteBuffer.wrap(new byte[]{1}),
                inner,
                ResourceOwnership.BORROWED
        );

        Thread.currentThread().interrupt();
        try {
            assertThrows(ClosedByInterruptException.class, () -> outer.read(ByteBuffer.allocate(1)));
            assertTrue(source.isOpen());
            assertFalse(inner.isOpen());
            assertFalse(outer.isOpen());
        } finally {
            assertTrue(Thread.interrupted());
        }

        outer.close();
        assertFalse(source.isOpen());
        assertEquals(2, source.closeCalls());
    }

    /// Supplies fixed bytes while tracking reads and lifecycle state.
    @NotNullByDefault
    private static class TrackingReadableByteChannel implements ReadableByteChannel {
        /// The bytes returned by this source.
        private final ByteBuffer content;

        /// The number of read calls received.
        private int readCalls;

        /// Number of source close attempts.
        private int closeCalls;

        /// Failure repeated by source closure until cleared.
        private @Nullable Throwable closeFailure;

        /// Failure thrown once after transferring a configured prefix of the next source read.
        private @Nullable Throwable nextReadFailure;

        /// Number of bytes consumed inside the next failing read.
        private int bytesBeforeReadFailure;

        /// Whether this source remains open.
        protected boolean open = true;

        /// Creates a source over fixed bytes.
        protected TrackingReadableByteChannel(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content"));
        }

        /// Copies fixed bytes into the target.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            readCalls++;
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }
            int count = Math.min(target.remaining(), content.remaining());
            @Nullable Throwable failure = nextReadFailure;
            if (failure != null) {
                count = Math.min(count, bytesBeforeReadFailure);
            }
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
            nextReadFailure = null;
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return count;
        }

        /// Returns the number of source read calls.
        protected final int readCalls() {
            return readCalls;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFailure instanceof IOException failure) throw failure;
            if (closeFailure instanceof RuntimeException failure) throw failure;
            if (closeFailure instanceof Error failure) throw failure;
            open = false;
        }
    }

    /// Supplies fixed bytes while advertising interruption support.
    @NotNullByDefault
    private static class InterruptibleTrackingReadableByteChannel
            extends TrackingReadableByteChannel
            implements InterruptibleChannel {
        /// Creates an interruptible source over fixed bytes.
        protected InterruptibleTrackingReadableByteChannel(byte[] content) {
            super(content);
        }
    }

    /// Reports configured failures from reads and optionally from closure.
    @NotNullByDefault
    private static final class ScriptedFailureInterruptibleReadableByteChannel
            extends InterruptibleTrackingReadableByteChannel {
        /// Failure reported by every read.
        private final IOException readFailure;

        /// Failure reported by closure until explicitly cleared.
        private @Nullable Throwable closeFailure;

        /// Number of read calls received.
        private int readCalls;

        /// Number of close calls received while open.
        private int closeCalls;

        /// Creates an interruptible source with configured read and close failures.
        private ScriptedFailureInterruptibleReadableByteChannel(
                IOException readFailure,
                @Nullable Throwable closeFailure
        ) {
            super(new byte[0]);
            this.readFailure = Objects.requireNonNull(readFailure, "readFailure");
            this.closeFailure = closeFailure;
        }

        /// Reports the configured read failure while open.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            readCalls++;
            throw readFailure;
        }

        /// Reports the configured close failure or closes the source.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCalls++;
            if (closeFailure instanceof IOException exception) {
                throw exception;
            }
            if (closeFailure instanceof RuntimeException exception) {
                throw exception;
            }
            if (closeFailure instanceof Error error) {
                throw error;
            }
            super.close();
        }

        /// Allows the next close attempt to complete.
        private void allowClose() {
            closeFailure = null;
        }

        /// Returns the number of read calls received.
        private int scriptedReadCalls() {
            return readCalls;
        }

        /// Returns the number of close calls received while open.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Supplies one byte and interrupts the reading thread before returning it.
    @NotNullByDefault
    private static final class InterruptAfterReadChannel extends InterruptibleTrackingReadableByteChannel {
        /// Creates an interrupting source over fixed bytes.
        private InterruptAfterReadChannel(byte[] content) {
            super(content);
        }

        /// Reads normally and then interrupts the current thread.
        @Override
        public int read(ByteBuffer target) throws IOException {
            int count = super.read(target);
            Thread.currentThread().interrupt();
            return count;
        }
    }

    /// Blocks one read until closure and then reports a configured failure or late byte.
    @NotNullByDefault
    private static final class CloseReleasedInterruptibleReadableByteChannel
            implements ReadableByteChannel, InterruptibleChannel {
        /// Signals that a read reached its blocking section.
        private final CountDownLatch blocked = new CountDownLatch(1);

        /// Failure reported after closure, or `null` to return one late byte.
        private final @Nullable IOException failureAfterClose;

        /// Thread currently blocked in a read.
        private volatile @Nullable Thread blockedThread;

        /// Whether this source remains open.
        private volatile boolean open = true;

        /// Creates a source with the requested post-close result.
        private CloseReleasedInterruptibleReadableByteChannel(@Nullable IOException failureAfterClose) {
            this.failureAfterClose = failureAfterClose;
        }

        /// Waits until the read reaches its blocking section.
        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Waits for closure and then reports the configured result.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            blockedThread = Thread.currentThread();
            blocked.countDown();
            try {
                while (open) {
                    LockSupport.park(this);
                }
            } finally {
                blockedThread = null;
            }
            if (failureAfterClose != null) {
                throw failureAfterClose;
            }
            target.put((byte) 42);
            return 1;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source and releases its blocked reader.
        @Override
        public void close() {
            open = false;
            @Nullable Thread reader = blockedThread;
            if (reader != null) {
                LockSupport.unpark(reader);
            }
        }
    }

    /// Fails its first close attempt before completing a retry.
    @NotNullByDefault
    private static final class FailingCloseInterruptibleReadableByteChannel
            extends InterruptibleTrackingReadableByteChannel {
        /// The number of close calls received.
        private int closeCalls;

        /// Creates an empty interruptible source.
        private FailingCloseInterruptibleReadableByteChannel() {
            super(new byte[0]);
        }

        /// Fails the first close and completes subsequent attempts.
        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeCalls == 1) {
                throw new IOException("close failure");
            }
            super.close();
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Blocks reads until channel closure and then reports asynchronous close.
    @NotNullByDefault
    private static final class BlockingInterruptibleReadableByteChannel
            extends AbstractInterruptibleChannel
            implements ReadableByteChannel {
        /// Signals that a read entered its blocking section.
        private final CountDownLatch blocked = new CountDownLatch(1);

        /// The thread currently blocked in a read.
        private volatile @Nullable Thread blockedThread;

        /// Waits until a read reaches the blocking section.
        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Blocks until interruption or asynchronous close terminates the read.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!target.hasRemaining()) {
                return 0;
            }
            begin();
            try {
                blockedThread = Thread.currentThread();
                blocked.countDown();
                while (isOpen()) {
                    LockSupport.park(this);
                }
            } finally {
                blockedThread = null;
                end(false);
            }
            throw new ClosedChannelException();
        }

        /// Unparks the blocked reader after close changes channel state.
        @Override
        protected void implCloseChannel() {
            @Nullable Thread reader = blockedThread;
            if (reader != null) {
                LockSupport.unpark(reader);
            }
        }
    }
}

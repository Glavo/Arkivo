// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

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
    @Test
    void replaysSelectedPrefixWithoutChangingCallerCursor() throws IOException {
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
                    ByteBuffer target = direct ? ByteBuffer.allocateDirect(9) : ByteBuffer.allocate(9);
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
                assertThrows(ReadOnlyBufferException.class, () -> replay.read(rejected));
                assertEquals(0, rejected.position());
                assertEquals(0, rejected.reset().position());
                assertEquals(0, source.readCalls());
                assertEquals(0, replay.read(ByteBuffer.allocate(0).asReadOnlyBuffer()));
                assertEquals(2, replay.read(target));
                assertEquals(0, source.readCalls());
                assertEquals(1, replay.read(target));
                assertArrayEquals(new byte[]{1, 2, 3}, target.array());
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

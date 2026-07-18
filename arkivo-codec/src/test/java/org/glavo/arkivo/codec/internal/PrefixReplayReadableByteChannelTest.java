// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
            ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
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

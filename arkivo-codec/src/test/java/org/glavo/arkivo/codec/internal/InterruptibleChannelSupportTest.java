// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies interruption, concurrent-close, retry, and failure propagation for channel lifecycle coordination.
@NotNullByDefault
final class InterruptibleChannelSupportTest {
    /// Maximum time allowed for one blocking test operation.
    private static final long TIMEOUT_SECONDS = 5L;

    /// An endpoint may reuse the cancellation exception for the first explicit cleanup attempt.
    @Test
    void preservesCancellationFailureDuringFirstClose() throws Exception {
        for (IOException failure : new IOException[]{new ClosedByInterruptException(), new AsynchronousCloseException()}) {
            var endpoint = new TestInterruptibleChannel();
            endpoint.closeFailure = failure;
            var support = new InterruptibleChannelSupport(endpoint);
            AutoCloseable wrapper = () -> support.close(endpoint::close, () -> {});
            Throwable reported = assertThrows(failure.getClass(), () -> {
                try (wrapper) {
                    support.execute((InterruptibleChannelSupport.IOAction) () -> { throw failure; }, () -> {});
                }
            });
            assertSame(failure, reported);
            assertEquals(1, reported.getSuppressed().length);
            assertSame(failure, reported.getSuppressed()[0].getCause());
            endpoint.closeFailure = null;
            wrapper.close();
            assertFalse(endpoint.isOpen());
        }
    }

    /// Successful recovery does not make a previously delivered operation failure safe to throw from close again.
    @Test
    void preservesRecoveredOperationFailureDuringClose() throws Exception {
        for (Throwable failure : new Throwable[]{new IOException("operation"),
                new IllegalStateException("operation"), new AssertionError("operation")}) {
            var endpoint = new TestInterruptibleChannel();
            endpoint.closeFailure = failure;
            var support = new InterruptibleChannelSupport(endpoint);
            AutoCloseable wrapper = () -> support.close(endpoint::close, () -> {});
            Throwable reported = assertThrows(failure.getClass(), () -> {
                try (wrapper) {
                    assertSame(failure, assertThrows(failure.getClass(), () -> support.execute(
                            (InterruptibleChannelSupport.IOAction) endpoint::close, () -> {})));
                    assertEquals("recovered", support.execute(() -> "recovered", () -> {}));
                    throw failure;
                }
            });
            assertSame(failure, reported);
            assertEquals(1, reported.getSuppressed().length);
            assertSame(failure, reported.getSuppressed()[0].getCause());
            endpoint.closeFailure = null;
            wrapper.close();
            assertFalse(endpoint.isOpen());
        }
    }

    /// Retained normal and abort cleanup failures remain safe for automatic close suppression.
    @Test
    void preservesRetainedCloseFailureAcrossRetries() throws Exception {
        for (boolean abortive : new boolean[]{false, true}) {
            for (Throwable failure : new Throwable[]{new IOException("cleanup"),
                    new IllegalStateException("cleanup"), new AssertionError("cleanup")}) {
                var endpoint = new TestInterruptibleChannel();
                endpoint.closeFailure = failure;
                var support = new InterruptibleChannelSupport(endpoint);
                if (abortive) {
                    Thread.currentThread().interrupt();
                    try {
                        assertThrows(ClosedByInterruptException.class,
                                () -> support.execute(() -> "unused", () -> { }));
                    } finally {
                        Thread.interrupted();
                    }
                }
                AutoCloseable wrapper = () -> support.close(endpoint::close, () -> { });
                Class<? extends Throwable> expected = abortive ? IOException.class : failure.getClass();
                Throwable reported = assertThrows(expected, () -> {
                    try (wrapper) {
                        wrapper.close();
                    }
                });
                assertSame(failure, abortive ? reported.getCause() : reported);
                assertSame(failure, reported.getSuppressed()[0].getCause());
                endpoint.closeFailure = null;
                wrapper.close();
                wrapper.close();
                assertFalse(endpoint.isOpen());
            }
        }
    }

    /// Verifies constructor and operation contracts while preserving unchecked failure identity.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArgumentsAndUncheckedFailurePropagation() throws IOException {
        assertThrows(NullPointerException.class, () -> new InterruptibleChannelSupport(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InterruptibleChannelSupport(new PlainChannel())
        );

        TestInterruptibleChannel endpoint = new TestInterruptibleChannel();
        InterruptibleChannelSupport support = new InterruptibleChannelSupport(endpoint);
        assertThrows(
                NullPointerException.class,
                () -> support.execute((InterruptibleChannelSupport.IOOperation<Object>) null, () -> {
                })
        );
        assertThrows(NullPointerException.class, () -> support.execute(() -> "value", null));
        assertThrows(NullPointerException.class, () -> support.close(null, () -> {
        }));
        assertThrows(NullPointerException.class, () -> support.close(() -> {
        }, null));

        AssertionError failure = new AssertionError("operation failure");
        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> support.execute(
                        (InterruptibleChannelSupport.IOOperation<Object>) () -> {
                            throw failure;
                        },
                        () -> {
                        }
                )
        );
        assertSame(failure, thrown);
        assertTrue(support.isOpen());

        endpoint.close();
        assertFalse(support.isOpen());
        assertThrows(
                ClosedChannelException.class,
                () -> support.execute(() -> "value", () -> {
                })
        );
    }

    /// Verifies a second operation waits and observes interruption only after gaining operation access.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializesConcurrentOperations(boolean interruptWaiting) throws Exception {
        TestInterruptibleChannel endpoint = new TestInterruptibleChannel();
        InterruptibleChannelSupport support = new InterruptibleChannelSupport(endpoint);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                completion.complete(support.execute(() -> {
                    entered.countDown();
                    await(release, "active operation was not released");
                    return "completed";
                }, () -> {
                }));
            } catch (Throwable failure) {
                completion.complete(failure);
            }
        }, "interruptible-support-operation");
        worker.start();

        CompletableFuture<Object> secondCompletion = new CompletableFuture<>();
        AtomicInteger secondCalls = new AtomicInteger();
        Thread second = new Thread(() -> {
            try {
                secondCompletion.complete(support.execute(() -> {
                    secondCalls.incrementAndGet();
                    return "second";
                }, () -> {}));
            } catch (Throwable failure) {
                assertEquals(interruptWaiting, Thread.currentThread().isInterrupted());
                secondCompletion.complete(failure);
            }
        }, "interruptible-support-queued-operation");
        second.setDaemon(true);
        try {
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            second.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (second.getState() != Thread.State.BLOCKED) {
                assertTrue(second.isAlive(), "queued operation returned before the active operation");
                assertTrue(System.nanoTime() < deadline, "queued operation did not block");
                Thread.sleep(1L);
            }
            if (interruptWaiting) second.interrupt();

            release.countDown();
            assertEquals("completed", completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Object secondResult = secondCompletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(interruptWaiting ? 0 : 1, secondCalls.get());
            if (interruptWaiting) {
                assertInstanceOf(ClosedByInterruptException.class, secondResult);
                assertFalse(endpoint.isOpen());
            } else {
                assertEquals("second", secondResult);
            }
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(worker.isAlive());

            support.close(endpoint::close, () -> {
            });
            assertFalse(endpoint.isOpen());
        } finally {
            release.countDown();
            endpoint.close();
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            second.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(worker.isAlive(), "operation worker leaked");
            assertFalse(second.isAlive(), "queued worker leaked");
        }
    }

    /// Rejects same-thread reentry without disturbing the surrounding operation.
    @Test
    void rejectsReentrantOperations() throws IOException {
        var endpoint = new TestInterruptibleChannel();
        var support = new InterruptibleChannelSupport(endpoint);
        assertEquals("outer", support.execute(() -> {
            assertThrows(IllegalStateException.class, () -> support.execute(() -> "inner", () -> {}));
            return "outer";
        }, () -> {}));
        assertTrue(support.isOpen());
        support.close(endpoint::close, () -> {});
    }

    /// Verifies a close nested inside its own graceful action is a no-op and later close retries remain possible.
    @Test
    void toleratesReentrantGracefulCloseAndRetries() throws IOException {
        TestInterruptibleChannel endpoint = new TestInterruptibleChannel();
        InterruptibleChannelSupport support = new InterruptibleChannelSupport(endpoint);
        AtomicInteger normalCalls = new AtomicInteger();
        AtomicInteger abortCalls = new AtomicInteger();

        support.close(() -> {
            normalCalls.incrementAndGet();
            support.close(
                    () -> {
                        throw new AssertionError("nested normal close action ran");
                    },
                    abortCalls::incrementAndGet
            );
        }, abortCalls::incrementAndGet);

        assertEquals(1, normalCalls.get());
        assertEquals(0, abortCalls.get());
        assertFalse(support.isOpen());
        assertTrue(endpoint.isOpen());

        support.close(endpoint::close, abortCalls::incrementAndGet);
        assertFalse(endpoint.isOpen());
        assertEquals(0, abortCalls.get());
    }

    /// Verifies an interrupted close waiter restores its status after the active graceful close completes.
    @Test
    void restoresInterruptionWhileWaitingForGracefulClose() throws Exception {
        TestInterruptibleChannel endpoint = new TestInterruptibleChannel();
        InterruptibleChannelSupport support = new InterruptibleChannelSupport(endpoint);
        CountDownLatch closeActionEntered = new CountDownLatch(1);
        CountDownLatch releaseCloseAction = new CountDownLatch(1);
        AtomicInteger retryCalls = new AtomicInteger();
        CompletableFuture<Object> firstResult = new CompletableFuture<>();
        CompletableFuture<Object> secondResult = new CompletableFuture<>();

        Thread firstCloser = new Thread(() -> {
            try {
                support.close(() -> {
                    closeActionEntered.countDown();
                    await(releaseCloseAction, "graceful close action was not released");
                    endpoint.close();
                }, () -> {
                    throw new AssertionError("abort action ran during graceful close");
                });
                firstResult.complete(Boolean.TRUE);
            } catch (Throwable failure) {
                firstResult.complete(failure);
            }
        }, "interruptible-support-first-close");
        firstCloser.start();

        Thread secondCloser = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                support.close(retryCalls::incrementAndGet, () -> {
                    throw new AssertionError("abort action ran during graceful close retry");
                });
                secondResult.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                secondResult.complete(failure);
            }
        }, "interruptible-support-second-close");

        try {
            assertTrue(closeActionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            secondCloser.start();
            awaitWaiting(secondCloser);
            releaseCloseAction.countDown();

            assertEquals(Boolean.TRUE, firstResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, secondResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, retryCalls.get());
            assertFalse(support.isOpen());
            assertFalse(endpoint.isOpen());

            firstCloser.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            secondCloser.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(firstCloser.isAlive());
            assertFalse(secondCloser.isAlive());
        } finally {
            releaseCloseAction.countDown();
            endpoint.close();
            if (firstCloser.isAlive()) {
                firstCloser.interrupt();
            }
            if (secondCloser.isAlive()) {
                secondCloser.interrupt();
            }
            firstCloser.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            secondCloser.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(firstCloser.isAlive(), "first close worker leaked");
            assertFalse(secondCloser.isAlive(), "second close worker leaked");
        }
    }

    /// Verifies an interrupted concurrent closer waits for cleanup and maps a late generic operation failure.
    @Test
    void restoresConcurrentCloserInterruptionAndMapsOperationFailure() throws Exception {
        TestInterruptibleChannel endpoint = new TestInterruptibleChannel();
        InterruptibleChannelSupport support = new InterruptibleChannelSupport(endpoint);
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        AtomicInteger abortCalls = new AtomicInteger();
        IOException operationFailure = new IOException("operation failed after close");
        CompletableFuture<Throwable> operationResult = new CompletableFuture<>();
        CompletableFuture<Object> closeResult = new CompletableFuture<>();

        Thread operationThread = new Thread(() -> {
            try {
                support.execute((InterruptibleChannelSupport.IOOperation<Object>) () -> {
                    operationEntered.countDown();
                    await(releaseOperation, "active operation was not released");
                    throw operationFailure;
                }, abortCalls::incrementAndGet);
                operationResult.complete(new AssertionError("operation completed normally"));
            } catch (Throwable failure) {
                operationResult.complete(failure);
            }
        }, "interruptible-support-active-operation");
        operationThread.start();

        Thread closeThread = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                support.close(
                        () -> {
                            throw new AssertionError("normal close action ran during abort");
                        },
                        () -> {
                        }
                );
                closeResult.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                closeResult.complete(failure);
            }
        }, "interruptible-support-concurrent-close");

        try {
            assertTrue(operationEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            closeThread.start();
            assertTrue(endpoint.awaitClosed(), "concurrent close did not reach the endpoint");
            awaitWaiting(closeThread);
            releaseOperation.countDown();

            Throwable failure = operationResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertInstanceOf(java.nio.channels.AsynchronousCloseException.class, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(operationFailure, failure.getSuppressed()[0]);
            assertEquals(Boolean.TRUE, closeResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, abortCalls.get());
            assertFalse(support.isOpen());
            assertFalse(endpoint.isOpen());

            operationThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            closeThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(operationThread.isAlive());
            assertFalse(closeThread.isAlive());
        } finally {
            releaseOperation.countDown();
            endpoint.close();
            if (operationThread.isAlive()) {
                operationThread.interrupt();
            }
            if (closeThread.isAlive()) {
                closeThread.interrupt();
            }
            operationThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            closeThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(operationThread.isAlive(), "operation worker leaked");
            assertFalse(closeThread.isAlive(), "close worker leaked");
        }
    }

    /// Verifies shared interruption failures avoid self-suppression and checked abort cleanup remains retryable.
    @Test
    void preservesSharedInterruptionFailureAndRetriesAbortCleanup() throws IOException {
        TestInterruptibleChannel endpoint = new TestInterruptibleChannel();
        InterruptibleChannelSupport support = new InterruptibleChannelSupport(endpoint);
        ClosedByInterruptException sharedFailure = new ClosedByInterruptException();

        ClosedByInterruptException thrown = assertThrows(
                ClosedByInterruptException.class,
                () -> support.execute(
                        (InterruptibleChannelSupport.IOOperation<Object>) () -> {
                            throw sharedFailure;
                        },
                        () -> {
                            throw sharedFailure;
                        }
                )
        );

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(support.isOpen());
        assertFalse(endpoint.isOpen());

        IOException retryFailure = new IOException("abort cleanup retry failed");
        IOException retryThrown = assertThrows(
                IOException.class,
                () -> support.close(
                        () -> {
                            throw new AssertionError("normal close action ran during abort retry");
                        },
                        () -> {
                            throw retryFailure;
                        }
                )
        );
        assertSame(retryFailure, retryThrown.getCause());

        support.close(
                () -> {
                    throw new AssertionError("normal close action ran during abort retry");
                },
                () -> {
                }
        );
    }

    /// Waits for one latch or reports a bounded test failure as an I/O exception.
    private static void await(CountDownLatch latch, String failureMessage) throws IOException {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException(failureMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("test worker interrupted", exception);
        }
    }

    /// Waits until a worker is blocked in the lifecycle monitor.
    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (thread.getState() != Thread.State.WAITING) {
            if (!thread.isAlive()) {
                throw new AssertionError("worker terminated before waiting");
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("worker did not enter the waiting state");
            }
            Thread.sleep(1L);
        }
    }

    /// Implements a noninterruptible channel for constructor validation.
    @NotNullByDefault
    private static final class PlainChannel implements Channel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates an open plain channel.
        private PlainChannel() {
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
    }

    /// Implements a close-observable interruptible channel.
    @NotNullByDefault
    private static final class TestInterruptibleChannel implements Channel, InterruptibleChannel {
        /// An optional persistent physical cleanup failure.
        private @Nullable Throwable closeFailure;

        /// Signals the first successful transition to closed.
        private final CountDownLatch closed = new CountDownLatch(1);

        /// Whether this channel remains open.
        private volatile boolean open = true;

        /// Creates an open interruptible channel.
        private TestInterruptibleChannel() {
        }

        /// Waits until this channel has been closed.
        private boolean awaitClosed() throws InterruptedException {
            return closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel and releases close observers.
        @Override
        public void close() throws IOException {
            if (closeFailure instanceof IOException exception) throw exception;
            if (closeFailure instanceof RuntimeException exception) throw exception;
            if (closeFailure instanceof Error exception) throw exception;
            if (open) {
                open = false;
                closed.countDown();
            }
        }
    }
}

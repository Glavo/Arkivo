// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.internal.ForceCloseableChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.util.Objects;

/// Coordinates interrupt and concurrent-close semantics around one interruptible endpoint.
@NotNullByDefault
final class InterruptibleChannelSupport {
    /// Identifies why this adapter became terminal.
    @NotNullByDefault
    private enum Termination {
        /// The adapter still accepts operations.
        NONE,

        /// An idle close selected ordinary codec finalization or release.
        GRACEFUL,

        /// The operation thread was interrupted.
        INTERRUPTED,

        /// Another thread closed the adapter while an operation was active.
        ASYNCHRONOUS
    }

    /// Selects the work required by one close invocation.
    @NotNullByDefault
    private enum CloseMode {
        /// A reentrant close is already being performed by this thread.
        NONE,

        /// Run ordinary codec finalization or release.
        NORMAL,

        /// Close the endpoint and wait for an active operation to abort.
        ABORT_ACTIVE,

        /// Retry an incomplete ordinary close action.
        RETRY_NORMAL,

        /// Retry an incomplete abort action.
        RETRY_ABORT
    }

    /// Executes one non-null-returning I/O operation.
    @FunctionalInterface
    @NotNullByDefault
    interface IOOperation<T> {
        /// Executes the operation.
        ///
        /// @return the non-null operation result
        /// @throws IOException if the operation fails
        T run() throws IOException;
    }

    /// Executes one void I/O action.
    @FunctionalInterface
    @NotNullByDefault
    interface IOAction {
        /// Executes the action.
        ///
        /// @throws IOException if the action fails
        void run() throws IOException;
    }

    /// Backing endpoint closed to interrupt an active operation, regardless of ordinary ownership.
    private final Channel endpoint;

    /// Monitor protecting operation and terminal state.
    private final Object lock = new Object();

    /// Whether another operation may begin.
    private volatile boolean open = true;

    /// Thread currently executing an adapter operation or graceful close.
    private @Nullable Thread activeThread;

    /// Whether the active thread is performing closure rather than a codec data operation.
    private boolean closeActionActive;

    /// Current terminal reason.
    private Termination termination = Termination.NONE;

    /// An abort cleanup failure that cannot be recovered by invoking a terminal delegate again.
    private @Nullable Throwable terminalAbortFailure;

    /// Creates lifecycle state for an interruptible backing endpoint.
    ///
    /// @param endpoint the endpoint whose close unblocks active operations
    InterruptibleChannelSupport(Channel endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (!(endpoint instanceof InterruptibleChannel)) {
            throw new IllegalArgumentException("endpoint does not implement InterruptibleChannel");
        }
    }

    /// Executes a non-null-returning operation with terminal interruption handling.
    ///
    /// @param operation   the operation to execute
    /// @param abortAction the codec cleanup performed after terminal cancellation
    /// @param <T>         the operation result type
    /// @return the non-null operation result
    /// @throws IOException if the operation, cancellation, endpoint close, or cleanup fails
    <T> T execute(IOOperation<T> operation, IOAction abortAction) throws IOException {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(abortAction, "abortAction");
        boolean preInterrupted;
        synchronized (lock) {
            ensureOpen();
            if (activeThread != null) {
                throw new IllegalStateException("Concurrent codec channel operations are not supported");
            }
            activeThread = Thread.currentThread();
            closeActionActive = false;
            preInterrupted = activeThread.isInterrupted();
            if (preInterrupted) {
                open = false;
                termination = Termination.INTERRUPTED;
            }
        }

        @Nullable T result = null;
        @Nullable Throwable failure = null;
        if (!preInterrupted) {
            try {
                result = operation.run();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
        }
        rethrow(completeActiveOperation(failure, abortAction, false));
        return Objects.requireNonNull(result, "operation result");
    }

    /// Executes a void operation with terminal interruption handling.
    ///
    /// @param operation   the operation to execute
    /// @param abortAction the codec cleanup performed after terminal cancellation
    /// @throws IOException if the operation, cancellation, endpoint close, or cleanup fails
    void execute(IOAction operation, IOAction abortAction) throws IOException {
        execute(() -> {
            operation.run();
            return Boolean.TRUE;
        }, abortAction);
    }

    /// Gracefully closes an idle adapter or aborts an operation active on another thread.
    ///
    /// @param normalCloseAction the ordinary codec finalization or release action
    /// @param abortAction       the codec cleanup performed after terminal cancellation
    /// @throws IOException if terminal processing, endpoint close, or cleanup fails
    void close(IOAction normalCloseAction, IOAction abortAction) throws IOException {
        Objects.requireNonNull(normalCloseAction, "normalCloseAction");
        Objects.requireNonNull(abortAction, "abortAction");
        CloseMode mode;
        Thread current = Thread.currentThread();
        boolean restoreInterrupt = false;
        synchronized (lock) {
            while (activeThread != null
                    && activeThread != current
                    && closeActionActive) {
                try {
                    lock.wait();
                } catch (InterruptedException ignored) {
                    restoreInterrupt = true;
                }
            }
            if (open) {
                open = false;
                if (activeThread == null) {
                    activeThread = current;
                    closeActionActive = true;
                    termination = Termination.GRACEFUL;
                    mode = CloseMode.NORMAL;
                } else {
                    termination = Termination.ASYNCHRONOUS;
                    mode = CloseMode.ABORT_ACTIVE;
                }
            } else if (activeThread != null) {
                mode = closeActionActive ? CloseMode.NONE : CloseMode.ABORT_ACTIVE;
            } else if (termination == Termination.GRACEFUL) {
                activeThread = current;
                closeActionActive = true;
                mode = CloseMode.RETRY_NORMAL;
            } else {
                activeThread = current;
                closeActionActive = true;
                mode = CloseMode.RETRY_ABORT;
            }
        }
        if (restoreInterrupt) {
            current.interrupt();
        }

        switch (mode) {
            case NONE -> {
            }
            case NORMAL -> closeNormally(normalCloseAction, abortAction);
            case ABORT_ACTIVE -> abortActiveOperation(current);
            case RETRY_NORMAL -> retryClose(normalCloseAction, false);
            case RETRY_ABORT -> retryClose(abortAction, true);
        }
    }

    /// Returns whether another adapter operation may begin.
    ///
    /// @return whether the adapter is open for another operation
    boolean isOpen() {
        return open && endpoint.isOpen();
    }

    /// Runs ordinary terminal processing and accounts for interruption or concurrent close.
    private void closeNormally(IOAction normalCloseAction, IOAction abortAction) throws IOException {
        @Nullable Throwable failure = null;
        try {
            normalCloseAction.run();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        rethrow(completeActiveOperation(failure, abortAction, true));
    }

    /// Forces the endpoint closed and waits for another thread's active operation to release codec state.
    private void abortActiveOperation(Thread current) throws IOException {
        @Nullable Throwable failure = closeEndpoint();
        boolean restoreInterrupt = false;
        synchronized (lock) {
            if (failure == null && activeThread != current) {
                while (activeThread != null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                        restoreInterrupt = true;
                    }
                }
            }
        }
        if (restoreInterrupt) {
            current.interrupt();
        }
        failure = mergeIfPresent(failure, terminalAbortFailure());
        rethrow(failure);
    }

    /// Performs and completes one serialized close retry.
    private void retryClose(IOAction action, boolean abortive) throws IOException {
        @Nullable Throwable failure = null;
        if (abortive) {
            failure = closeEndpoint();
        }
        @Nullable Throwable actionFailure = runAction(action);
        if (abortive) {
            recordAbortFailure(actionFailure);
            if (actionFailure instanceof IOException) {
                failure = mergeIfPresent(failure, actionFailure);
            }
            failure = mergeIfPresent(failure, terminalAbortFailure());
        } else {
            failure = actionFailure;
        }
        synchronized (lock) {
            activeThread = null;
            closeActionActive = false;
            lock.notifyAll();
        }
        rethrow(failure);
    }

    /// Completes the active operation, aborting codec state when its terminal reason requires it.
    private @Nullable Throwable completeActiveOperation(
            @Nullable Throwable operationFailure,
            IOAction abortAction,
            boolean closeOperation
    ) {
        Termination completedTermination;
        boolean abortive;
        synchronized (lock) {
            boolean interrupted = operationFailure instanceof ClosedByInterruptException
                    || (Thread.currentThread().isInterrupted()
                        && (!closeOperation || operationFailure != null));
            if (interrupted) {
                if (termination != Termination.ASYNCHRONOUS) {
                    termination = Termination.INTERRUPTED;
                }
            } else if (operationFailure instanceof AsynchronousCloseException) {
                if (termination != Termination.INTERRUPTED) {
                    termination = Termination.ASYNCHRONOUS;
                }
            }
            abortive = termination == Termination.INTERRUPTED
                    || termination == Termination.ASYNCHRONOUS;
            if (abortive) {
                open = false;
            } else {
                completedTermination = termination;
                activeThread = null;
                closeActionActive = false;
                lock.notifyAll();
                return terminalFailure(completedTermination, operationFailure, null);
            }
        }

        @Nullable Throwable cleanupFailure = closeEndpoint();
        @Nullable Throwable abortFailure = runAction(abortAction);
        recordAbortFailure(abortFailure);
        cleanupFailure = mergeIfPresent(cleanupFailure, abortFailure);
        synchronized (lock) {
            completedTermination = termination;
            activeThread = null;
            closeActionActive = false;
            lock.notifyAll();
        }
        return terminalFailure(completedTermination, operationFailure, cleanupFailure);
    }

    /// Closes the endpoint and captures any checked or unchecked failure.
    private @Nullable Throwable closeEndpoint() {
        try {
            ForceCloseableChannel.forceClose(endpoint);
            return null;
        } catch (IOException | RuntimeException | Error exception) {
            return exception;
        }
    }

    /// Executes an action and captures any checked or unchecked failure.
    private static @Nullable Throwable runAction(IOAction action) {
        try {
            action.run();
            return null;
        } catch (IOException | RuntimeException | Error exception) {
            return exception;
        }
    }

    /// Records an abort failure that a terminal delegate cannot retry.
    private void recordAbortFailure(@Nullable Throwable failure) {
        if (failure == null || failure instanceof IOException) {
            return;
        }
        synchronized (lock) {
            terminalAbortFailure = mergeIfPresent(terminalAbortFailure, failure);
        }
    }

    /// Returns the persistent abort cleanup failure, if any.
    private @Nullable Throwable terminalAbortFailure() {
        synchronized (lock) {
            return terminalAbortFailure;
        }
    }

    /// Adds a secondary failure when present.
    private static @Nullable Throwable mergeIfPresent(
            @Nullable Throwable primary,
            @Nullable Throwable secondary
    ) {
        return secondary == null ? primary : mergeFailure(primary, secondary);
    }

    /// Converts terminal state into the exception observed by the active operation.
    private static @Nullable Throwable terminalFailure(
            Termination termination,
            @Nullable Throwable operationFailure,
            @Nullable Throwable cleanupFailure
    ) {
        @Nullable Throwable primary;
        if (termination == Termination.INTERRUPTED) {
            if (operationFailure instanceof ClosedByInterruptException) {
                primary = operationFailure;
            } else {
                primary = new ClosedByInterruptException();
                if (operationFailure != null) {
                    primary.addSuppressed(operationFailure);
                }
            }
        } else if (termination == Termination.ASYNCHRONOUS) {
            if (operationFailure instanceof AsynchronousCloseException
                    && !(operationFailure instanceof ClosedByInterruptException)) {
                primary = operationFailure;
            } else {
                primary = new AsynchronousCloseException();
                if (operationFailure != null) {
                    primary.addSuppressed(operationFailure);
                }
            }
        } else {
            primary = operationFailure;
        }
        return cleanupFailure == null ? primary : mergeFailure(primary, cleanupFailure);
    }

    /// Requires this adapter to remain open for another operation.
    private void ensureOpen() throws ClosedChannelException {
        if (!open || !endpoint.isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Rethrows a captured lifecycle failure with its original checked or unchecked type.
    private static void rethrow(@Nullable Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    /// Combines lifecycle failures while preserving the first failure as primary.
    private static Throwable mergeFailure(@Nullable Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }
}

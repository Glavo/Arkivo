// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.internal.ForceCloseable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compression-format probing preserves primary and cleanup failures.
@NotNullByDefault
final class CompressionFormatsFailureContractTest {
    /// Verifies failed probing and format lookup abort owned sources but leave borrowed sources untouched.
    @ParameterizedTest
    @CsvSource({
            "probe,true,true", "probe,true,false", "probe,false,true", "probe,false,false",
            "named,true,true", "named,true,false", "named,false,true", "named,false,false",
            "detected,true,true", "detected,true,false", "detected,false,true", "detected,false,false"
    })
    void abortsOwnedSourcesAfterFailedOpen(String operation, boolean owned, boolean failAbort) {
        IOException primary = new IOException("probe failure");
        IOException cleanup = new IOException("abort failure");
        AbortTrackingChannel source = new AbortTrackingChannel(primary, failAbort ? cleanup : null);
        ResourceOwnership ownership = owned ? ResourceOwnership.OWNED : ResourceOwnership.BORROWED;
        Class<? extends Exception> expected = operation.equals("named")
                ? IllegalArgumentException.class : IOException.class;
        Throwable failure = assertThrows(expected, () -> {
            switch (operation) {
                case "probe" -> CompressionFormats.probe(source, 1L, ownership);
                case "named" -> CompressionFormats.newReadableByteChannel("missing-test-format", source, ownership);
                case "detected" -> CompressionFormats.newReadableByteChannel(source, ownership);
                default -> throw new AssertionError(operation);
            }
        });
        if (operation.equals("probe")) assertSame(primary, failure);
        assertEquals(0, source.closeCount());
        assertEquals(owned ? 1 : 0, source.abortCount);
        assertEquals(owned && failAbort ? 1 : 0, failure.getSuppressed().length);
        if (owned && failAbort) assertSame(cleanup, failure.getSuppressed()[0]);
        assertEquals(!owned || failAbort, source.isOpen());
    }

    /// Makes graceful cleanup observable separately from cancellation.
    @NotNullByDefault
    private static final class AbortTrackingChannel extends FailingReadableByteChannel implements ForceCloseable {
        /// Failure delivered by cancellation, or `null` for successful cleanup.
        private final @Nullable IOException abortFailure;
        /// Number of cancellation attempts.
        private int abortCount;
        /// Whether cancellation completed successfully.
        private boolean aborted;

        /// Creates a source with independent operation and cancellation failures.
        private AbortTrackingChannel(IOException readFailure, @Nullable IOException abortFailure) {
            super(readFailure, new IOException("unexpected graceful close"));
            this.abortFailure = abortFailure;
        }

        /// Reports cancellation without invoking ordinary close.
        @Override
        public void forceClose() throws IOException {
            abortCount++;
            if (abortFailure != null) throw abortFailure;
            aborted = true;
        }

        /// Returns whether cancellation remains incomplete.
        @Override
        public boolean isOpen() {
            return !aborted;
        }
    }

    /// Creates fresh failures so suppressed exceptions cannot leak between invocations.
    private static Stream<Throwable> failures() {
        return Stream.of(new IOException("I/O failure"), new IllegalStateException("runtime failure"),
                new AssertionError("error failure"));
    }

    /// Verifies a distinct owned-source cleanup failure is suppressed behind the read failure.
    @ParameterizedTest
    @MethodSource("failures")
    void suppressesDistinctOwnedProbeCleanupFailure(Throwable readFailure) {
        IOException closeFailure = new IOException("close failure");
        FailingReadableByteChannel source = new FailingReadableByteChannel(readFailure, closeFailure);

        Throwable exception = assertThrows(
                readFailure.getClass(),
                () -> CompressionFormats.probe(source, 1L, ResourceOwnership.OWNED)
        );

        assertSame(readFailure, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertSame(closeFailure, exception.getSuppressed()[0]);
        assertEquals(1, source.readCount());
        assertEquals(1, source.closeCount());
        assertTrue(source.isOpen());
    }

    /// Verifies one shared read and cleanup failure is propagated without illegal self-suppression.
    @ParameterizedTest
    @MethodSource("failures")
    void preservesSharedOwnedProbeFailure(Throwable failure) {
        FailingReadableByteChannel source = new FailingReadableByteChannel(failure, failure);

        Throwable exception = assertThrows(
                failure.getClass(),
                () -> CompressionFormats.probe(source, 1L, ResourceOwnership.OWNED)
        );

        assertSame(failure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertEquals(1, source.readCount());
        assertEquals(1, source.closeCount());
        assertTrue(source.isOpen());
    }

    /// Verifies partially consumed borrowed sources are not closed when probing fails.
    @ParameterizedTest
    @MethodSource("failures")
    void leavesBorrowedSourceOpenAfterPartialProbeFailure(Throwable failure) {
        FailingReadableByteChannel source = new FailingReadableByteChannel(failure, new IOException("close failure"));
        source.bytesBeforeFailure = 2;
        assertSame(failure, assertThrows(failure.getClass(), () -> CompressionFormats.probe(
                source, 4L, ResourceOwnership.BORROWED
        )));
        assertEquals(2, source.transferredBytes);
        assertEquals(1, source.readCount());
        assertEquals(0, source.closeCount());
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(source.isOpen());
    }

    /// Verifies cleanup failures of every supported throwable kind retain a partially consumed probe failure.
    @ParameterizedTest
    @MethodSource("failures")
    void suppressesCleanupFailureAfterPartialProbe(Throwable closeFailure) {
        IOException failure = new IOException("read failure");
        FailingReadableByteChannel source = new FailingReadableByteChannel(failure, closeFailure);
        source.bytesBeforeFailure = 2;
        assertSame(failure, assertThrows(IOException.class, () -> CompressionFormats.probe(
                source, 4L, ResourceOwnership.OWNED
        )));
        assertEquals(2, source.transferredBytes);
        assertEquals(1, source.readCount());
        assertEquals(1, source.closeCount());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(closeFailure, failure.getSuppressed()[0]);
    }

    /// Verifies failed argument validation does not transfer ownership or read the source.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesBeforeTakingSourceOwnership() {
        FailingReadableByteChannel source = new FailingReadableByteChannel(
                new IOException("read failure"), new IOException("close failure")
        );
        assertThrows(IllegalArgumentException.class, () -> CompressionFormats.probe(source, -1L, ResourceOwnership.OWNED));
        assertThrows(IllegalArgumentException.class, () -> CompressionFormats.probe(
                source, Integer.MAX_VALUE + 1L, ResourceOwnership.OWNED
        ));
        assertThrows(NullPointerException.class, () -> CompressionFormats.probe(source, 1L, null));
        assertEquals(0, source.readCount());
        assertEquals(0, source.closeCount());
        assertTrue(source.isOpen());
    }

    /// Implements a source that reports configured failures from reads and closure.
    @NotNullByDefault
    private static class FailingReadableByteChannel implements ReadableByteChannel {
        /// The read failure.
        private final Throwable readFailure;

        /// The close failure.
        private final Throwable closeFailure;

        /// Number of bytes written into the probe buffer before throwing.
        private int bytesBeforeFailure;

        /// Total bytes consumed from this source.
        private int transferredBytes;

        /// Number of read attempts.
        private int readCount;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a channel with the requested failures.
        private FailingReadableByteChannel(Throwable readFailure, Throwable closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        /// Records one read attempt and reports the configured failure.
        @Override
        public int read(ByteBuffer target) throws IOException {
            readCount++;
            int count = Math.min(bytesBeforeFailure, target.remaining());
            for (int index = 0; index < count; index++) {
                target.put((byte) index);
            }
            transferredBytes += count;
            throwFailure(readFailure);
            throw new AssertionError("Configured read failure was not thrown");
        }

        /// Returns whether cleanup has completed successfully.
        @Override
        public boolean isOpen() {
            return true;
        }

        /// Records one close attempt and reports the configured failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            throwFailure(closeFailure);
        }

        /// Throws the configured checked or unchecked failure without wrapping it.
        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Unsupported test failure", failure);
        }

        /// Returns the number of read attempts.
        private int readCount() {
            return readCount;
        }

        /// Returns the number of close attempts.
        final int closeCount() {
            return closeCount;
        }
    }
}

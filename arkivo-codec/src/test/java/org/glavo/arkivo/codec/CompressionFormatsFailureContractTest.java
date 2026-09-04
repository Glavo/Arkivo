// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compression-format probing preserves primary and cleanup failures.
@NotNullByDefault
final class CompressionFormatsFailureContractTest {
    /// Verifies a distinct owned-source cleanup failure is suppressed behind the read failure.
    @Test
    void suppressesDistinctOwnedProbeCleanupFailure() {
        IOException readFailure = new IOException("read failure");
        IOException closeFailure = new IOException("close failure");
        FailingReadableByteChannel source = new FailingReadableByteChannel(readFailure, closeFailure);

        IOException exception = assertThrows(
                IOException.class,
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
    @Test
    void preservesSharedOwnedProbeFailure() {
        IOException failure = new IOException("shared failure");
        FailingReadableByteChannel source = new FailingReadableByteChannel(failure, failure);

        IOException exception = assertThrows(
                IOException.class,
                () -> CompressionFormats.probe(source, 1L, ResourceOwnership.OWNED)
        );

        assertSame(failure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertEquals(1, source.readCount());
        assertEquals(1, source.closeCount());
        assertTrue(source.isOpen());
    }

    /// Implements a source that reports configured failures from reads and closure.
    @NotNullByDefault
    private static final class FailingReadableByteChannel implements ReadableByteChannel {
        /// The read failure.
        private final IOException readFailure;

        /// The close failure.
        private final IOException closeFailure;

        /// Number of read attempts.
        private int readCount;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a channel with the requested failures.
        private FailingReadableByteChannel(IOException readFailure, IOException closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        /// Records one read attempt and reports the configured failure.
        @Override
        public int read(ByteBuffer target) throws IOException {
            readCount++;
            throw readFailure;
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
            throw closeFailure;
        }

        /// Returns the number of read attempts.
        private int readCount() {
            return readCount;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}

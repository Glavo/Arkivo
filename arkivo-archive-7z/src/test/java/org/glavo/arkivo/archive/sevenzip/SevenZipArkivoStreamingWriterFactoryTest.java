// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies ownership and failure aggregation across 7z streaming-writer factories.
@NotNullByDefault
final class SevenZipArkivoStreamingWriterFactoryTest {
    /// Verifies one shared setup and target-close failure is not suppressed onto itself.
    @Test
    void avoidsSelfSuppressionAfterPasswordSetupFailure() {
        IOException failure = new IOException("shared setup and close failure");
        SharedFailureChannel output = new SharedFailureChannel(failure);
        SevenZipArchiveOptions.Create options = SevenZipArchiveOptions.CREATE_DEFAULTS
                .withPasswordProvider(request -> {
                    throw failure;
                });

        IOException actual = assertThrows(
                IOException.class,
                () -> SevenZipArkivoStreamingWriter.open(output, options)
        );
        assertSame(failure, actual);
        assertEquals(0, actual.getSuppressed().length);
        assertEquals(1, output.closeCalls());
        assertFalse(output.isOpen());
    }

    /// Verifies an independent close failure remains suppressed behind the setup failure.
    @Test
    void suppressesIndependentCloseFailureAfterPasswordSetupFailure() {
        IOException setupFailure = new IOException("setup failure");
        IOException closeFailure = new IOException("close failure");
        SharedFailureChannel output = new SharedFailureChannel(closeFailure);
        SevenZipArchiveOptions.Create options = SevenZipArchiveOptions.CREATE_DEFAULTS
                .withPasswordProvider(request -> {
                    throw setupFailure;
                });

        IOException actual = assertThrows(
                IOException.class,
                () -> SevenZipArkivoStreamingWriter.open(output, options)
        );
        assertSame(setupFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(closeFailure, actual.getSuppressed()[0]);
        assertEquals(1, output.closeCalls());
        assertFalse(output.isOpen());
    }

    /// Accepts writes while open and reports one caller-supplied failure after physically closing.
    @NotNullByDefault
    private static final class SharedFailureChannel implements WritableByteChannel {
        /// Failure reported by every close call.
        private final IOException failure;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Number of close calls received.
        private int closeCalls;

        /// Creates an open channel with the supplied close failure.
        private SharedFailureChannel(IOException failure) {
            this.failure = failure;
        }

        /// Consumes every source byte while this channel remains open.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Marks this channel closed and reports the configured failure.
        @Override
        public void close() throws IOException {
            closeCalls++;
            open = false;
            throw failure;
        }

        /// Returns the number of close calls received.
        private int closeCalls() {
            return closeCalls;
        }
    }
}

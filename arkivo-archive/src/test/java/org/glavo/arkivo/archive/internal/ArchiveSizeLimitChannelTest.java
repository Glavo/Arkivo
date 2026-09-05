// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies decoded archive size enforcement, probe boundaries, and channel ownership.
@NotNullByDefault
final class ArchiveSizeLimitChannelTest {
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

    /// Verifies runtime delegate failures remain primary while the exceeded limit becomes sticky.
    @Test
    void preservesRuntimeFailureAfterPartialLimitViolation() throws IOException {
        IllegalStateException readFailure = new IllegalStateException("partial runtime failure");
        PartiallyFailingReadableByteChannel delegate = new PartiallyFailingReadableByteChannel(
                new byte[]{1, 2},
                2,
                readFailure
        );
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 1L)) {
            ByteBuffer target = ByteBuffer.allocate(8);

            assertSame(readFailure, assertThrows(IllegalStateException.class, () -> limited.read(target)));
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

        /// Checked or runtime failure reported after the partial read.
        private final Exception failure;

        /// Whether the partial failure remains pending.
        private boolean failurePending = true;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a partially failing source over copied bytes.
        private PartiallyFailingReadableByteChannel(
                byte @Unmodifiable [] source,
                int partialReadSize,
                Exception failure
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
                throw (RuntimeException) failure;
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

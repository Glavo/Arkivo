// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies terminal archive-output failures, exact body transfers, and independent close retries.
@NotNullByDefault
final class ArchiveOutputStreamTest {
    /// Verifies target write and flush failures stop all further output without losing the first cause.
    @ParameterizedTest
    @MethodSource("targetFailures")
    void stopsAfterTargetFailure(Throwable failure, Operation operation, boolean partial) throws IOException {
        Target target = new Target();
        ArchiveOutputStream output = new ArchiveOutputStream(target);
        output.write(new byte[]{1, 2});
        target.failure = failure;
        target.partial = partial;
        assertSame(failure, assertThrows(failure.getClass(), () -> {
            switch (operation) {
                case SINGLE -> output.write(3);
                case BULK -> output.write(new byte[]{3, 4});
                case FLUSH -> output.flush();
            }
        }));
        assertEquals(2L, output.bytesWritten());
        assertEquals(partial && operation != Operation.FLUSH ? 3 : 2, target.bytes.size());
        int attempts = target.operations;
        assertSame(failure, assertThrows(IOException.class, () -> output.write(0)).getCause());
        assertSame(failure, assertThrows(IOException.class, () -> output.write(new byte[0])).getCause());
        assertSame(failure, assertThrows(IOException.class, output::flush).getCause());
        assertEquals(attempts, target.operations);
        output.close();
        output.close();
        assertEquals(1, target.closes);
        assertTrue(output.isClosed());
    }

    /// Verifies body copying neither reads past the declared length nor takes ownership of its input.
    @Test
    void preservesBodyBoundariesAndOwnership() throws IOException {
        Target target = new Target();
        try (ArchiveOutputStream output = new ArchiveOutputStream(target);
             ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(new byte[]{2, 3, 4, 5})) {
            output.write(1);
            output.writeBody(source, 2L);
            assertEquals(2L, source.position());
            output.writeBody(source, 0L);
            assertEquals(2L, source.position());
            output.flush();
            assertEquals(3L, output.bytesWritten());
            output.close();
            assertTrue(source.isOpen());
            assertArrayEquals(new byte[]{1, 2, 3}, target.bytes.toByteArray());
        }
        assertEquals(1, target.closes);
    }

    /// Verifies premature EOF retains copied progress and disables further output.
    @Test
    void rejectsShortBodiesAfterPartialProgress() throws IOException {
        Target target = new Target();
        try (ArchiveOutputStream output = new ArchiveOutputStream(target);
             ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(new byte[]{1, 2})) {
            EOFException failure = assertThrows(EOFException.class, () -> output.writeBody(source, 3L));
            assertEquals(2L, source.position());
            assertEquals(2L, output.bytesWritten());
            assertSame(failure, assertThrows(IOException.class, output::ensureWritable).getCause());
            assertArrayEquals(new byte[]{1, 2}, target.bytes.toByteArray());
        }
    }

    /// Verifies source failures are terminal even when the target itself has not failed.
    @ParameterizedTest
    @MethodSource("failureKinds")
    void stopsAfterSourceFailure(Throwable failure) throws IOException {
        Target target = new Target();
        try (ArchiveOutputStream output = new ArchiveOutputStream(target);
             FailingSource source = new FailingSource(failure)) {
            output.write(7);
            assertSame(failure, assertThrows(failure.getClass(), () -> output.writeBody(source, 2L)));
            assertEquals(2L, output.bytesWritten());
            assertArrayEquals(new byte[]{7, 1}, target.bytes.toByteArray());
            assertSame(failure, assertThrows(IOException.class, () -> output.writeBody(source, 1L)).getCause());
            assertEquals(2, source.reads);
            output.close();
            assertTrue(source.isOpen());
        }
    }

    /// Verifies caller validation does not make an otherwise writable archive unusable.
    @Test
    void permitsWritesAfterInvalidArguments() throws IOException {
        Target target = new Target();
        try (ArchiveOutputStream output = new ArchiveOutputStream(target);
             ReadOnlyByteArrayChannel source = new ReadOnlyByteArrayChannel(new byte[]{3})) {
            assertThrows(NullPointerException.class, () -> output.write(null, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[1], 0, 2));
            assertThrows(IllegalArgumentException.class, () -> output.writeBody(source, -1L));
            assertThrows(NullPointerException.class, () -> output.writeBody(null, 0L));
            output.writeBody(source, 1L);
            assertEquals(1, target.operations);
            assertEquals(1L, output.bytesWritten());
        }
    }

    /// Verifies failed close attempts reject new output but permit another close attempt.
    @ParameterizedTest
    @MethodSource("failureKinds")
    void retriesTargetClosure(Throwable failure) throws IOException {
        Target target = new Target();
        ArchiveOutputStream output = new ArchiveOutputStream(target);
        target.closeFailure = failure;
        assertSame(failure, assertThrows(failure.getClass(), output::close));
        assertFalse(output.isClosed());
        assertThrows(IOException.class, () -> output.write(1));
        assertThrows(IOException.class, output::flush);
        assertEquals(0, target.operations);
        output.close();
        output.close();
        assertTrue(output.isClosed());
        assertEquals(2, target.closes);
    }

    /// Verifies a shared write/close exception cannot cause try-with-resources self-suppression.
    @ParameterizedTest
    @MethodSource("failureKinds")
    void retainsSharedWriteAndCloseFailure(Throwable failure) throws IOException {
        Target target = new Target();
        target.failure = failure;
        target.closeFailure = failure;
        ArchiveOutputStream output = new ArchiveOutputStream(target);
        assertSame(failure, assertThrows(failure.getClass(), () -> {
            try (output) {
                output.write(1);
            }
        }));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(failure, failure.getSuppressed()[0].getCause());
        output.close();
        assertTrue(output.isClosed());
    }

    /// Supplies fresh checked, unchecked, and error failures.
    private static Stream<Throwable> failureKinds() {
        return Stream.of(new IOException("I/O failure"), new IllegalStateException("runtime failure"),
                new AssertionError("error failure"));
    }

    /// Supplies every target operation with failures before or after partial output.
    private static Stream<Arguments> targetFailures() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of(false, true)
                .flatMap(partial -> failureKinds().map(failure -> Arguments.of(failure, operation, partial))));
    }

    /// Target operations whose failures prevent further archive output.
    @NotNullByDefault
    private enum Operation {
        /// Writes one byte.
        SINGLE,
        /// Writes a range of bytes.
        BULK,
        /// Flushes the target.
        FLUSH
    }

    /// Throws one configured failure with its original type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Captures output and injects independent operation and close failures.
    @NotNullByDefault
    private static final class Target extends OutputStream {
        /// Captured bytes, including partial writes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /// The failure for writes and flushes, or `null` to succeed.
        private @Nullable Throwable failure;
        /// The failure consumed by the next close, or `null` to succeed.
        private @Nullable Throwable closeFailure;
        /// Whether a failing write emits one byte first.
        private boolean partial;
        /// Number of target writes and flushes.
        private int operations;
        /// Number of target close attempts.
        private int closes;

        /// Writes one byte with optional partial progress before failure.
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value});
        }

        /// Writes a range with optional partial progress before failure.
        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            operations++;
            if (failure != null && partial && length > 0) {
                bytes.write(source[offset]);
            }
            throwFailure(failure);
            bytes.write(source, offset, length);
        }

        /// Reports an independently observable flush attempt.
        @Override
        public void flush() throws IOException {
            operations++;
            throwFailure(failure);
        }

        /// Consumes the next close failure so a later close can succeed.
        @Override
        public void close() throws IOException {
            closes++;
            @Nullable Throwable exception = closeFailure;
            closeFailure = null;
            throwFailure(exception);
        }
    }

    /// Supplies one byte, then fails the next body read.
    @NotNullByDefault
    private static final class FailingSource implements ReadableByteChannel {
        /// The failure returned by the second read.
        private final Throwable failure;
        /// Number of attempted reads.
        private int reads;
        /// Whether the caller has closed this source.
        private boolean open = true;

        /// Creates a source with one terminal read failure.
        private FailingSource(Throwable failure) {
            this.failure = failure;
        }

        /// Returns one byte on the first read and fails thereafter.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            if (++reads == 1) {
                destination.put((byte) 1);
                return 1;
            }
            throwFailure(failure);
            throw new AssertionError("Unreachable");
        }

        /// Returns whether this source remains caller-owned and open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Records caller-controlled source closure.
        @Override
        public void close() {
            open = false;
        }
    }
}

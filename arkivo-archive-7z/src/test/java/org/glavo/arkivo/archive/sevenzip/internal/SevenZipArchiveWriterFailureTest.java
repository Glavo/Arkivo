// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies output failure is terminal across 7z packed streams, solid folders, and final header replacement.
@NotNullByDefault
final class SevenZipArchiveWriterFailureTest {
    /// Entry metadata that inherits the writer's compression and filters.
    private static final SevenZipEntryWriteMetadata METADATA =
            SevenZipEntryWriteMetadata.withWindowsAttributes(0);

    /// Small x86-like data that exercises BCJ2 branch output without large temporary files.
    private static final byte @Unmodifiable [] BODY = body();

    /// Verifies every target write, seek, size query, and truncation failure stops later archive operations.
    @ParameterizedTest
    @MethodSource("failures")
    void stopsAtEveryTargetBoundary(Encoding encoding, FailureKind kind, boolean partial) throws IOException {
        FaultChannel healthy = new FaultChannel();
        SevenZipArchiveWriter baseline = encoding.writer(healthy);
        healthy.operations.clear();
        writeArchive(baseline);
        @Unmodifiable List<Operation> operations = List.copyOf(healthy.operations);
        for (int index = 0; index < operations.size(); index++) {
            if (operations.get(index) == Operation.CLOSE) {
                continue;
            }
            FaultChannel target = new FaultChannel();
            SevenZipArchiveWriter writer = encoding.writer(target);
            target.operations.clear();
            Throwable failure = kind.failure();
            target.failAt = index;
            target.failure = failure;
            target.partial = partial;
            Throwable thrown = assertThrows(failure.getClass(), () -> writeArchive(writer),
                    encoding + " at " + index + " (" + operations.get(index) + ")");
            assertSame(failure, thrown);
            byte @Unmodifiable [] incomplete = target.delegate.array().clone();
            int attempts = target.archiveOperations();
            assertThrows(IOException.class, () -> writer.write(BODY, 0, BODY.length));
            assertThrows(IOException.class, writer::closeArchiveEntry);
            assertThrows(IOException.class, () -> writer.putArchiveEntry("later", false, METADATA));
            try {
                writer.close();
            } catch (IOException exception) {
                assertTrue(hasCause(exception, failure));
            }
            writer.close();
            assertEquals(attempts, target.archiveOperations());
            assertArrayEquals(incomplete, target.delegate.array());
            assertFalse(target.isOpen());
        }
    }

    /// Verifies destination cleanup can be retried without repeating finalization or changing completed bytes.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void retriesOnlyFailedTargetClose(FailureKind kind) throws IOException {
        FaultChannel target = new FaultChannel();
        SevenZipArchiveWriter writer = Encoding.LZMA2.writer(target);
        writer.putArchiveEntry("entry", false, METADATA);
        writer.write(BODY, 0, BODY.length);
        writer.closeArchiveEntry();
        Throwable failure = kind.failure();
        target.closeFailure = failure;
        assertSame(failure, assertThrows(failure.getClass(), writer::close));
        byte @Unmodifiable [] completed = target.delegate.array().clone();
        int operations = target.archiveOperations();
        assertThrows(ClosedChannelException.class, () -> writer.putArchiveEntry("later", false, METADATA));
        writer.close();
        writer.close();
        assertArrayEquals(completed, target.delegate.array());
        assertEquals(operations, target.archiveOperations());
        assertEquals(2, target.closeAttempts);
    }

    /// Verifies invalid caller ranges and entry-state mistakes do not disable an otherwise usable writer.
    @Test
    void retainsUsabilityAfterPreflightFailures() throws IOException {
        FaultChannel target = new FaultChannel();
        try (SevenZipArchiveWriter writer = Encoding.COPY.writer(target)) {
            assertThrows(IOException.class, () -> writer.write(BODY, 0, 1));
            writer.putArchiveEntry("directory/", true, METADATA);
            assertThrows(IOException.class, () -> writer.write(BODY, 0, 1));
            writer.closeArchiveEntry();
            writer.putArchiveEntry("file", false, METADATA);
            assertThrows(IndexOutOfBoundsException.class, () -> writer.write(BODY, -1, 1));
            assertThrows(IOException.class, () -> writer.putArchiveEntry("other", false, METADATA));
            writer.write(BODY, 0, BODY.length);
            writer.closeArchiveEntry();
        }
        assertEquals('7', target.delegate.array()[0]);
        assertEquals('z', target.delegate.array()[1]);
    }

    /// Verifies the public file-system layer does not retry publication after a supplied destination fails.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void stopsFileSystemAfterPublicationFailure(FailureKind kind) throws IOException {
        FaultChannel target = new FaultChannel();
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target,
                SevenZipArchiveOptions.CREATE_DEFAULTS.withCompression(SevenZipCompression.copy()));
        var body = Files.newOutputStream(fileSystem.getPath("/entry"));
        Throwable failure = kind.failure();
        target.failAt = target.operations.size();
        target.failure = failure;
        target.partial = true;
        body.write(BODY);
        body.close();
        assertSame(failure, assertThrows(failure.getClass(), fileSystem::close));
        byte @Unmodifiable [] incomplete = target.delegate.array().clone();
        int operations = target.archiveOperations();
        fileSystem.close();
        assertEquals(operations, target.archiveOperations());
        assertArrayEquals(incomplete, target.delegate.array());
        assertFalse(target.isOpen());
    }

    /// Verifies a permanently stalled target causes one failed write instead of a retry loop or later output.
    @Test
    void rejectsZeroProgress() throws IOException {
        FaultChannel target = new FaultChannel();
        SevenZipArchiveWriter writer = Encoding.COPY.writer(target);
        target.zeroProgress = true;
        writer.putArchiveEntry("file", false, METADATA);
        IOException failure = assertThrows(IOException.class, () -> writer.write(BODY, 0, BODY.length));
        int operations = target.archiveOperations();
        assertTrue(hasCause(assertThrows(IOException.class, writer::close), failure));
        writer.close();
        assertEquals(operations, target.archiveOperations());
    }

    /// Verifies a repeated target exception cannot replace the original failure with a self-suppression error.
    @Test
    void retainsSameExceptionFromWriteAndClose() throws IOException {
        FaultChannel target = new FaultChannel();
        SevenZipArchiveWriter writer = Encoding.COPY.writer(target);
        IOException failure = new IOException("write and close failed");
        target.failAt = target.operations.size();
        target.failure = failure;
        target.closeFailure = failure;
        writer.putArchiveEntry("file", false, METADATA);
        assertSame(failure, assertThrows(IOException.class, () -> {
            try (writer) {
                writer.write(BODY, 0, BODY.length);
            }
        }));
        assertTrue(failure.getSuppressed().length > 0);
        writer.close();
    }

    /// Verifies AES cleanup cannot flush buffered ciphertext after a destination failure.
    @Test
    void abortsEncryptionWithoutEmittingBufferedBytes() throws IOException {
        FaultChannel target = new FaultChannel();
        SevenZipArchiveWriter writer = new SevenZipArchiveWriter(target, new byte[]{1, 2},
                SevenZipCompression.copy(), SevenZipFilterChain.EMPTY, 1);
        writer.putArchiveEntry("file", false, METADATA);
        IOException failure = new IOException("encrypted output failed");
        target.failAt = target.operations.size();
        target.failure = failure;
        target.partial = true;
        assertSame(failure, assertThrows(IOException.class, () -> writer.write(BODY, 0, BODY.length)));
        byte @Unmodifiable [] incomplete = target.delegate.array().clone();
        int operations = target.archiveOperations();
        assertTrue(hasCause(assertThrows(IOException.class, writer::close), failure));
        writer.close();
        assertEquals(operations, target.archiveOperations());
        assertArrayEquals(incomplete, target.delegate.array());
    }

    /// Writes two substreams and finalizes the archive, allowing solid and non-solid folder transitions.
    private static void writeArchive(SevenZipArchiveWriter writer) throws IOException {
        for (String name : List.of("first", "second")) {
            writer.putArchiveEntry(name, false, METADATA);
            writer.write(BODY, 0, BODY.length);
            writer.closeArchiveEntry();
        }
        writer.close();
    }

    /// Returns whether an exception retains the supplied failure through its cause chain.
    private static boolean hasCause(Throwable exception, Throwable failure) {
        for (@Nullable Throwable current = exception; current != null; current = current.getCause()) {
            if (current == failure) {
                return true;
            }
        }
        return false;
    }

    /// Supplies coder graphs and failures before or after a target write has accepted a prefix.
    private static Stream<Arguments> failures() {
        return Stream.of(Encoding.values()).flatMap(encoding -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(false, true).map(partial -> Arguments.of(encoding, kind, partial))));
    }

    /// Creates deterministic instruction-like source bytes.
    private static byte @Unmodifiable [] body() {
        byte[] bytes = new byte[256];
        for (int index = 0; index < bytes.length; index += 8) {
            bytes[index] = (byte) 0xe8;
            bytes[index + 1] = (byte) index;
            bytes[index + 5] = (byte) 0xe9;
        }
        return bytes;
    }

    /// Coder graphs with direct, buffered, transformed, solid, and staged packed output.
    @NotNullByDefault
    private enum Encoding {
        /// Direct uncompressed output with one folder per entry.
        COPY,
        /// Buffered LZMA2 output.
        LZMA2,
        /// Delta preprocessing followed by LZMA2.
        DELTA_LZMA2,
        /// Four staged BCJ2 branches.
        BCJ2_COPY,
        /// Two files sharing one uncompressed folder.
        SOLID_COPY;

        /// Opens a writer with small dictionaries and no password derivation.
        private SevenZipArchiveWriter writer(FaultChannel channel) throws IOException {
            SevenZipCompression compression = this == LZMA2 || this == DELTA_LZMA2
                    ? SevenZipCompression.lzma2(4096) : SevenZipCompression.copy();
            SevenZipFilterChain filters = switch (this) {
                case DELTA_LZMA2 -> SevenZipFilterChain.of(SevenZipFilter.delta());
                case BCJ2_COPY -> SevenZipFilterChain.of(SevenZipFilter.of(SevenZipFilterMethod.BCJ2));
                default -> SevenZipFilterChain.EMPTY;
            };
            return new SevenZipArchiveWriter(channel, null, compression, filters, this == SOLID_COPY ? 2 : 1);
        }
    }

    /// Observable destination operations whose ordering affects archive finalization.
    @NotNullByDefault
    private enum Operation {
        /// Writes signature, packed, or header bytes.
        WRITE,
        /// Reads the current physical output offset.
        POSITION,
        /// Repositions to rewrite the signature header.
        SEEK,
        /// Sets the final archive extent.
        TRUNCATE,
        /// Releases the destination.
        CLOSE
    }

    /// Checked and unchecked output failures.
    @NotNullByDefault
    private enum FailureKind {
        /// Checked I/O failure.
        IO,
        /// Unchecked endpoint failure.
        RUNTIME,
        /// Endpoint error.
        ERROR;

        /// Creates one failure whose identity is checked throughout cleanup.
        private Throwable failure() {
            return switch (this) {
                case IO -> new IOException("target failed");
                case RUNTIME -> new IllegalStateException("target failed");
                case ERROR -> new AssertionError("target failed");
            };
        }
    }

    /// Records target operations and fails once at a selected operation without losing access to captured bytes.
    @NotNullByDefault
    private static final class FaultChannel implements SeekableByteChannel {
        /// Indicates no numbered operation is armed to fail.
        private static final int NO_FAILURE = -1;

        /// In-memory seekable target with externally inspectable captured bytes.
        private final SeekableInMemoryByteChannel delegate = new SeekableInMemoryByteChannel();

        /// Operations observed in call order.
        private final List<Operation> operations = new ArrayList<>();

        /// Zero-based operation index to fail.
        private int failAt = NO_FAILURE;

        /// Failure injected at the selected operation.
        private @Nullable Throwable failure;

        /// Independent one-shot close failure.
        private @Nullable Throwable closeFailure;

        /// Whether the failed write accepts one byte first.
        private boolean partial;

        /// Whether writes permanently return zero.
        private boolean zeroProgress;

        /// Number of destination close attempts.
        private int closeAttempts;

        /// Records an operation and throws its selected failure.
        private void attempt(Operation operation) throws IOException {
            operations.add(operation);
            if (operations.size() - 1 == failAt) {
                throwFailure(Objects.requireNonNull(failure));
            }
        }

        /// Rethrows an injected failure without changing its identity.
        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        /// Counts operations that could observe or modify archive output, excluding resource cleanup.
        private int archiveOperations() {
            return (int) operations.stream().filter(operation -> operation != Operation.CLOSE).count();
        }

        /// Reads captured data when requested by test support.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        /// Writes fully unless configured to fail after an optional prefix or return zero.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (operations.size() == failAt && partial && source.hasRemaining()) {
                int limit = source.limit();
                source.limit(source.position() + 1);
                try {
                    delegate.write(source);
                } finally {
                    source.limit(limit);
                }
            }
            attempt(Operation.WRITE);
            return zeroProgress ? 0 : delegate.write(source);
        }

        /// Queries the physical output offset.
        @Override
        public long position() throws IOException {
            attempt(Operation.POSITION);
            return delegate.position();
        }

        /// Repositions the destination.
        @Override
        public SeekableByteChannel position(long position) throws IOException {
            attempt(Operation.SEEK);
            delegate.position(position);
            return this;
        }

        /// Returns the stored size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Changes the archive extent.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            attempt(Operation.TRUNCATE);
            delegate.truncate(size);
            return this;
        }

        /// Returns whether the destination remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Retries the underlying close after a one-shot cleanup failure.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            attempt(Operation.CLOSE);
            @Nullable Throwable exception = closeFailure;
            closeFailure = null;
            if (exception != null) {
                throwFailure(exception);
            }
            delegate.close();
        }
    }
}

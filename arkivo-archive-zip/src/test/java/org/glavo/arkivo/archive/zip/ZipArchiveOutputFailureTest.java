// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that failed ZIP output never resumes encoding, writes a directory, or publishes a volume transaction.
@NotNullByDefault
final class ZipArchiveOutputFailureTest {
    /// Deterministic data that fills more than two minimum-size split volumes when stored.
    private static final byte @Unmodifiable [] CONTENT = content();

    /// Creates reproducible incompressible entry data without repository fixtures.
    private static byte[] content() {
        byte[] bytes = new byte[140_000];
        new Random(0x5a4950L).nextBytes(bytes);
        return bytes;
    }

    /// Supplies compression, encryption, failure type, and partial-write combinations.
    private static Stream<Arguments> encodingFailures() {
        return Stream.of(ZipMethod.STORED, ZipMethod.DEFLATED, ZipMethod.BZIP2,
                        ZipMethod.LZMA, ZipMethod.XZ, ZipMethod.ZSTANDARD)
                .flatMap(method -> Stream.of(ZipEncryption.values())
                        .flatMap(encryption -> Stream.of(FailureKind.values())
                                .flatMap(kind -> Stream.of(false, true)
                                        .map(partial -> Arguments.of(method, encryption, kind, partial)))));
    }

    /// Fails the first compressed-data write, including encoders that defer output until body close.
    @ParameterizedTest(name = "{0}, {1}, {2}, partial={3}")
    @MethodSource("encodingFailures")
    void stopsEncodingAfterBodyOutputFailure(
            ZipMethod method, ZipEncryption encryption, FailureKind kind, boolean partial
    ) throws IOException {
        FaultTarget target = new FaultTarget();
        ZipArkivoStreamingWriter writer = open(target, encryption, false);
        OutputStream body = openBody(writer, method);
        Throwable failure = kind.create();
        target.arm(target.operations + 1, failure, partial);

        assertSame(failure, assertThrows(failure.getClass(), () -> {
            body.write(CONTENT);
            body.close();
        }));
        byte[] incomplete = target.bytes();
        int operations = target.operations;
        assertThrows(IOException.class, () -> body.write(1));
        ignoreIOException(body::close);
        assertThrows(IOException.class, writer::close);
        writer.close();

        assertEquals(operations, target.operations);
        assertArrayEquals(incomplete, target.bytes());
        assertEquals(1, target.channelCloses);
    }

    /// Supplies transport, failure type, and partial-write combinations for exhaustive output-position checks.
    private static Stream<Arguments> outputFailures() {
        return Stream.of(false, true).flatMap(split -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(false, true).map(partial -> Arguments.of(split, kind, partial))));
    }

    /// Fails each target write and volume-open operation from the first header through the end directory.
    @ParameterizedTest(name = "split={0}, {1}, partial={2}")
    @MethodSource("outputFailures")
    void stopsAtEveryArchiveOutputPosition(boolean split, FailureKind kind, boolean partial) throws IOException {
        FaultTarget baseline = new FaultTarget();
        writeArchive(baseline, split);
        int totalOperations = baseline.operations;
        assertTrue(totalOperations > 20);

        for (int operation = 1; operation <= totalOperations; operation++) {
            FaultTarget target = new FaultTarget();
            Throwable failure = kind.create();
            target.arm(operation, failure, partial);
            @Nullable ZipArkivoStreamingWriter writer = null;
            try {
                writer = open(target, ZipEncryption.NONE, split);
                OutputStream body = openBody(writer, ZipMethod.STORED);
                body.write(CONTENT);
                body.close();
                writeMetadataEntries(writer);
                writer.close();
                throw new AssertionError("Fault position was not reached: " + operation);
            } catch (IOException | RuntimeException | Error exception) {
                assertSame(failure, exception, "operation " + operation);
            }
            byte[] incomplete = target.bytes();
            if (writer != null) {
                ignoreIOException(writer::close);
                writer.close();
            }
            assertEquals(operation, target.operations, "operation " + operation);
            assertArrayEquals(incomplete, target.bytes(), "operation " + operation);
            assertEquals(0, target.commits);
            assertEquals(target.streams.size(), target.channelCloses);
            if (split) {
                assertEquals(1, target.rollbacks);
                assertEquals(1, target.transactionCloses);
            }
        }
    }

    /// Verifies that a failed volume close delays rollback until the channel is successfully closed.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void retriesVolumeClosureWithoutWritingOrPublishing(FailureKind kind) throws IOException {
        FaultTarget target = new FaultTarget();
        ZipArkivoStreamingWriter writer = open(target, ZipEncryption.NONE, true);
        OutputStream body = openBody(writer, ZipMethod.STORED);
        Throwable failure = kind.create();
        target.closeFailure = failure;
        assertSame(failure, assertThrows(failure.getClass(), () -> body.write(CONTENT)));
        int operations = target.operations;

        assertThrows(IOException.class, writer::close);
        writer.close();
        assertEquals(operations, target.operations);
        assertEquals(0, target.commits);
        assertEquals(1, target.rollbacks);
        assertEquals(1, target.transactionCloses);
        assertEquals(2, target.channelCloses);
    }

    /// Supplies transport and target-close failure combinations.
    private static Stream<Arguments> closeFailures() {
        return Stream.of(false, true).flatMap(split -> Stream.of(FailureKind.values())
                .map(kind -> Arguments.of(split, kind)));
    }

    /// Retries failed final-target closure without rewriting the already emitted directory.
    @ParameterizedTest(name = "split={0}, {1}")
    @MethodSource("closeFailures")
    void retriesFinalTargetCloseWithoutRefinalizing(boolean split, FailureKind kind) throws IOException {
        FaultTarget target = new FaultTarget();
        ZipArkivoStreamingWriter writer = open(target, ZipEncryption.NONE, split);
        try (OutputStream body = openBody(writer, ZipMethod.DEFLATED)) {
            body.write(1);
        }
        Throwable failure = kind.create();
        target.closeFailure = failure;
        assertSame(failure, assertThrows(failure.getClass(), writer::close));
        assertEquals(0, target.commits);
        assertEquals(0, target.rollbacks);
        assertEquals(0, target.transactionCloses);
        int operations = target.operations;
        byte[] incomplete = target.bytes();
        writer.close();
        writer.close();
        assertEquals(operations, target.operations);
        assertArrayEquals(incomplete, target.bytes());
        assertEquals(2, target.channelCloses);
        assertEquals(0, target.commits);
        assertEquals(split ? 1 : 0, target.rollbacks);
        assertEquals(split ? 1 : 0, target.transactionCloses);
    }

    /// Supplies update-output channel and transaction cleanup failures.
    private static Stream<Arguments> updateCloseFailures() {
        return Stream.of(false, true).flatMap(transaction -> Stream.of(FailureKind.values())
                .map(kind -> Arguments.of(transaction, kind)));
    }

    /// Retains the newly assembled output for cleanup retries after an update replaces its staging output.
    @ParameterizedTest(name = "transaction={0}, {1}")
    @MethodSource("updateCloseFailures")
    void updateRetriesReplacementOutputCleanup(boolean transaction, FailureKind kind) throws IOException {
        FaultTarget original = new FaultTarget();
        try (ZipArkivoStreamingWriter writer = open(original, ZipEncryption.NONE, false);
             OutputStream body = openBody(writer, ZipMethod.STORED)) {
            body.write(1);
        }
        byte @Unmodifiable [] archive = original.bytes();
        FaultTarget target = new FaultTarget();
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                index -> index == 0 ? new ReadOnlyByteArrayChannel(archive) : null,
                target, ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
        );
        Throwable failure = kind.create();
        if (transaction) {
            target.transactionCloseFailure = failure;
        } else {
            target.closeFailure = failure;
        }
        assertSame(failure, assertThrows(failure.getClass(), fileSystem::close));
        int operations = target.operations;
        int commits = target.commits;
        fileSystem.close();
        fileSystem.close();
        assertEquals(operations, target.operations);
        assertEquals(commits, target.commits);
        assertEquals(transaction ? 2 : 1, target.transactionCloses);
        assertEquals(transaction ? 0 : 1, target.rollbacks);
        assertEquals(target.streams.size() + (transaction ? 0 : 1), target.channelCloses);
    }

    /// Verifies that failed commit or transaction cleanup is never retried as publication.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void retriesTransactionCleanupWithoutRepeatingCommit(FailureKind kind) throws IOException {
        FaultTarget target = new FaultTarget();
        ZipArkivoStreamingWriter writer = open(target, ZipEncryption.NONE, true);
        OutputStream body = openBody(writer, ZipMethod.STORED);
        body.write(1);
        body.close();
        Throwable commitFailure = kind.create();
        Throwable cleanupFailure = kind.create();
        target.commitFailure = commitFailure;
        target.transactionCloseFailure = cleanupFailure;

        assertSame(commitFailure, assertThrows(commitFailure.getClass(), writer::close));
        assertSame(cleanupFailure, commitFailure.getSuppressed()[0]);
        int operations = target.operations;
        writer.close();
        writer.close();
        assertEquals(operations, target.operations);
        assertEquals(1, target.commits);
        assertEquals(1, target.rollbacks);
        assertEquals(2, target.transactionCloses);
    }

    /// Supplies size and CRC-32 mismatch cases for every encryption setting and transport.
    private static Stream<Arguments> validationFailures() {
        return Stream.of(false, true).flatMap(split -> Stream.of(false, true)
                .flatMap(wrongSize -> Stream.of(ZipEncryption.values())
                        .map(encryption -> Arguments.of(split, wrongSize, encryption))));
    }

    /// Rejects final entry metadata mismatches without adding a central directory or committing volumes.
    @ParameterizedTest(name = "split={0}, wrongSize={1}, {2}")
    @MethodSource("validationFailures")
    void validationFailurePreventsPublication(boolean split, boolean wrongSize, ZipEncryption encryption)
            throws IOException {
        FaultTarget target = new FaultTarget();
        ZipArkivoStreamingWriter writer = open(target, encryption, split);
        var entry = writer.beginFile("invalid.bin");
        @Nullable var attributes = entry.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(attributes);
        CRC32 crc = new CRC32();
        crc.update(1);
        attributes.setUncompressedSizeAndCrc32(wrongSize ? 2 : 1, wrongSize ? crc.getValue() : 0);
        OutputStream body = entry.openOutputStream();
        body.write(1);
        IOException failure = assertThrows(IOException.class, body::close);
        assertTrue(failure.getMessage().contains(wrongSize ? "configured size" : "configured CRC-32"));
        int operations = target.operations;
        IOException closeFailure = assertThrows(IOException.class, writer::close);
        assertSame(failure, closeFailure.getCause());
        writer.close();
        assertEquals(operations, target.operations);
        assertEquals(0, target.commits);
        assertEquals(split ? 1 : 0, target.rollbacks);
    }

    /// Rejects a zero-progress channel both during initial setup and after changing volumes.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void zeroProgressVolumeDisablesOutput(int volume) throws IOException {
        FaultTarget target = new FaultTarget();
        target.zeroProgressVolume = volume;
        if (volume == 0) {
            assertThrows(IOException.class, () -> open(target, ZipEncryption.NONE, true));
        } else {
            ZipArkivoStreamingWriter writer = open(target, ZipEncryption.NONE, true);
            OutputStream body = openBody(writer, ZipMethod.STORED);
            assertThrows(IOException.class, () -> body.write(CONTENT));
            int operations = target.operations;
            assertThrows(IOException.class, writer::close);
            writer.close();
            assertEquals(operations, target.operations);
        }
        assertEquals(0, target.commits);
        assertEquals(1, target.rollbacks);
        assertEquals(target.streams.size(), target.channelCloses);
    }

    /// Rejects invalid caller ranges before changing encoder state or disabling output.
    @Test
    void invalidWriteArgumentsDoNotPoisonArchive() throws IOException {
        FaultTarget target = new FaultTarget();
        try (ZipArkivoStreamingWriter writer = open(target, ZipEncryption.NONE, false)) {
            try (OutputStream body = openBody(writer, ZipMethod.DEFLATED)) {
                assertThrows(IndexOutOfBoundsException.class, () -> body.write(CONTENT, -1, 1));
                assertThrows(NullPointerException.class, () -> body.write(null, 0, 0));
                int operations = target.operations;
                body.write(CONTENT, 0, 0);
                assertEquals(operations, target.operations);
                body.write(CONTENT);
            }
        }
        assertEquals(1, target.channelCloses);
        try (var input = new ZipInputStream(new ByteArrayInputStream(target.bytes()))) {
            assertNotNull(input.getNextEntry());
            assertArrayEquals(CONTENT, input.readAllBytes());
        }
    }

    /// Writes a complete stored entry through the selected target kind.
    private static void writeArchive(FaultTarget target, boolean split) throws IOException {
        try (ZipArkivoStreamingWriter writer = open(target, ZipEncryption.NONE, split)) {
            try (OutputStream body = openBody(writer, ZipMethod.STORED)) {
                body.write(CONTENT);
            }
            writeMetadataEntries(writer);
        }
    }

    /// Exercises directory and fixed-body symbolic-link record serialization.
    private static void writeMetadataEntries(ZipArkivoStreamingWriter writer) throws IOException {
        writer.beginDirectory("directory").close();
        writer.beginSymbolicLink("link", "data.bin").close();
    }

    /// Opens ordinary or split output with a fixed test password when encryption is enabled.
    private static ZipArkivoStreamingWriter open(FaultTarget target, ZipEncryption encryption, boolean split)
            throws IOException {
        var options = ZipArchiveOptions.CREATE_DEFAULTS.withDefaultEncryption(encryption)
                .withPasswordProvider(ArkivoPasswordProvider.fixed(new byte[]{1, 2, 3}));
        return split
                ? ZipArkivoStreamingWriter.open(target, ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE, options)
                : ZipArkivoStreamingWriter.open(target.newStream(), options);
    }

    /// Opens a body configured with the requested compression method.
    private static OutputStream openBody(ZipArkivoStreamingWriter writer, ZipMethod method) throws IOException {
        var entry = writer.beginFile("data.bin");
        @Nullable var attributes = entry.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(attributes);
        attributes.setMethod(method);
        return entry.openOutputStream();
    }

    /// Allows cleanup to report the previously latched I/O failure, but no unchecked cleanup failures.
    private static void ignoreIOException(Executable cleanup) {
        try {
            cleanup.execute();
        } catch (IOException ignored) {
            // The original operation already reported the failure.
        } catch (Throwable failure) {
            throw new AssertionError("Unexpected cleanup failure", failure);
        }
    }

    /// Exception categories accepted from external output implementations.
    @NotNullByDefault
    private enum FailureKind {
        /// Checked output failure.
        IO,
        /// Unchecked output failure.
        RUNTIME,
        /// Serious output failure.
        ERROR;

        /// Creates a distinct failure for each injection.
        Throwable create() {
            return switch (this) {
                case IO -> new IOException("Injected ZIP output failure");
                case RUNTIME -> new IllegalStateException("Injected ZIP output failure");
                case ERROR -> new AssertionError("Injected ZIP output failure");
            };
        }
    }

    /// Records target activity and injects a single failure without making later target operations fail.
    @NotNullByDefault
    private static final class FaultTarget implements ArkivoVolumeTarget, ArkivoVolumeOutput {
        /// Bytes emitted to each opened stream.
        private final List<ByteArrayOutputStream> streams = new ArrayList<>();
        /// Number of writes and volume-open attempts.
        private int operations;
        /// One-based operation to fail, or zero when disabled.
        private int failureOperation;
        /// Failure thrown by the selected operation.
        private @Nullable Throwable failure;
        /// Whether a failed write emits its first byte.
        private boolean partial;
        /// Number of channel close attempts.
        private int channelCloses;
        /// Number of commit attempts.
        private int commits;
        /// Number of effective rollbacks.
        private int rollbacks;
        /// Number of transaction close attempts.
        private int transactionCloses;
        /// Whether the transaction has committed or rolled back.
        private boolean finished;
        /// Failure for the next channel close.
        private @Nullable Throwable closeFailure;
        /// Failure for the next commit.
        private @Nullable Throwable commitFailure;
        /// Failure for the next transaction close.
        private @Nullable Throwable transactionCloseFailure;
        /// Volume whose first write returns zero, or negative when disabled.
        private int zeroProgressVolume = -1;

        /// Selects one target operation, preserving all later operations for detecting unintended retries.
        void arm(int operation, Throwable exception, boolean partialWrite) {
            failureOperation = operation;
            failure = exception;
            partial = partialWrite;
        }

        /// Returns a byte snapshot across every physical output stream.
        byte[] bytes() {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            streams.forEach(stream -> result.writeBytes(stream.toByteArray()));
            return result.toByteArray();
        }

        /// Creates a stream that counts each target call and optionally emits a prefix before failure.
        OutputStream newStream() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            streams.add(bytes);
            return new OutputStream() {
                /// Accounts for a single-byte target write through the bulk fault path.
                @Override
                public void write(int value) throws IOException {
                    write(new byte[]{(byte) value});
                }

                /// Records bytes or emits a prefix and throws at the selected operation.
                @Override
                public void write(byte[] source, int offset, int length) throws IOException {
                    operations++;
                    if (operations == failureOperation) {
                        if (partial && length > 0) {
                            bytes.write(source[offset]);
                        }
                        throwFailure(failure);
                    }
                    bytes.write(source, offset, length);
                }

                /// Records closure and consumes the one-shot close failure.
                @Override
                public void close() throws IOException {
                    channelCloses++;
                    @Nullable Throwable exception = closeFailure;
                    closeFailure = null;
                    throwFailure(exception);
                }
            };
        }

        /// Returns this recording transaction.
        @Override
        public ArkivoVolumeOutput openOutput() {
            return this;
        }

        /// Opens the next volume or injects an open failure or a zero-progress channel.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            operations++;
            if (operations == failureOperation) {
                throwFailure(failure);
            }
            assertEquals(streams.size(), index);
            WritableByteChannel channel = StreamChannelAdapters.writableChannel(newStream());
            if (index != zeroProgressVolume) {
                return channel;
            }
            return new WritableByteChannel() {
                /// Returns zero once, leaving the channel usable to expose unintended write retries.
                @Override
                public int write(ByteBuffer source) throws IOException {
                    if (zeroProgressVolume == index) {
                        zeroProgressVolume = -1;
                        operations++;
                        return 0;
                    }
                    return channel.write(source);
                }

                /// Returns the backing channel's open state.
                @Override
                public boolean isOpen() {
                    return channel.isOpen();
                }

                /// Closes the backing channel.
                @Override
                public void close() throws IOException {
                    channel.close();
                }
            };
        }

        /// Records publication and consumes a one-shot commit failure.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            commits++;
            @Nullable Throwable exception = commitFailure;
            commitFailure = null;
            throwFailure(exception);
            finished = true;
        }

        /// Records at most one effective rollback.
        @Override
        public void rollback() {
            if (!finished) {
                rollbacks++;
                finished = true;
            }
        }

        /// Retries transaction cleanup without changing the result of a successful commit.
        @Override
        public void close() throws IOException {
            transactionCloses++;
            @Nullable Throwable exception = transactionCloseFailure;
            transactionCloseFailure = null;
            throwFailure(exception);
            rollback();
        }

        /// Throws an injected failure with its original identity and category.
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
    }
}

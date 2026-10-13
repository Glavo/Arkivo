// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies transactional cleanup after archive publication and output failures.
@NotNullByDefault
final class ArchiveVolumeTransactionFailureTest {
    /// Contains source archives and unpublished replacement files.
    @TempDir
    private Path temporaryDirectory;

    /// Direct targets must not truncate source-backed bodies before they have been read.
    @ParameterizedTest
    @ValueSource(strings = {"7z", "tar.zst"})
    void assemblesBeforeOpeningDirectTarget(String format) throws IOException {
        for (boolean truncateAtOpen : new boolean[]{false, true}) {
            Path path = temporaryDirectory.resolve(truncateAtOpen + "." + format);
            try (ArkivoFileSystem archive = format.equals("7z")
                    ? ArkivoFormats.createFileSystem("7z", path)
                    : TarArkivoFileSystem.create(path, TarArchiveOptions.CREATE_DEFAULTS
                            .withCompression(ZstdCodec.DEFAULT))) {
                Files.writeString(archive.getPath("retained"), "retained body");
                Files.writeString(archive.getPath("changed"), "old body");
            }
            ArkivoCommitTarget target = truncateAtOpen ? source -> {
                Files.write(path, new byte[0]);
                return ArkivoCommitTarget.writeTo(path).openOutput(source);
            } : ArkivoCommitTarget.replaceOriginal();
            ArchiveUpdateOptions common = ArchiveUpdateOptions.DEFAULT.withCommitTarget(target);
            try (ArkivoFileSystem archive = format.equals("7z")
                    ? ArkivoFormats.updateFileSystem("7z", path, common)
                    : TarArkivoFileSystem.update(path, TarArchiveOptions.UPDATE_DEFAULTS.withCommon(common))) {
                Files.writeString(archive.getPath("changed"), "new body");
            }
            try (ArkivoFileSystem archive = format.equals("7z")
                    ? ArkivoFormats.openFileSystem("7z", path)
                    : TarArkivoFileSystem.open(path)) {
                assertEquals("retained body", Files.readString(archive.getPath("retained")));
                assertEquals("new body", Files.readString(archive.getPath("changed")));
            }
        }
    }

    /// Publication starts only after the owned source closes, and close failures cannot publish an update.
    @ParameterizedTest
    @ValueSource(strings = {"7z", "tar"})
    void closesOwnedSourceBeforeOpeningTarget(String format) throws IOException {
        for (boolean failClose : new boolean[]{false, true}) {
            Path path = temporaryDirectory.resolve(failClose + "." + format);
            try (ArkivoFileSystem archive = ArkivoFormats.createFileSystem(format, path)) {
                Files.writeString(archive.getPath("retained"), "retained body");
                Files.writeString(archive.getPath("changed"), "old body");
            }
            byte[] original = Files.readAllBytes(path);
            ClosingSource source = new ClosingSource(path);
            source.closeBlocked = failClose;
            int[] targetCalls = {0};
            ArchiveUpdateOptions common = ArchiveUpdateOptions.DEFAULT.withCommitTarget(ignored -> {
                targetCalls[0]++;
                assertTrue(source.closed);
                return ArkivoCommitTarget.writeTo(path).openOutput(null);
            });
            ArkivoFileSystem archive = format.equals("7z")
                    ? SevenZipArkivoFileSystem.update(source, SevenZipArchiveOptions.UPDATE_DEFAULTS.withCommon(common))
                    : TarArkivoFileSystem.update(source, TarArchiveOptions.UPDATE_DEFAULTS.withCommon(common));
            try {
                Files.writeString(archive.getPath("changed"), "new body");
                if (failClose) {
                    assertThrows(IOException.class, archive::close);
                    assertThrows(IOException.class, archive::close);
                    assertEquals(0, targetCalls[0]);
                    assertArrayEquals(original, Files.readAllBytes(path));
                    source.closeBlocked = false;
                    archive.close();
                    assertTrue(source.closed);
                    assertEquals(0, targetCalls[0]);
                } else {
                    archive.close();
                    assertEquals(1, targetCalls[0]);
                    try (ArkivoFileSystem reopened = ArkivoFormats.openFileSystem(format, path)) {
                        assertEquals("retained body", Files.readString(reopened.getPath("retained")));
                        assertEquals("new body", Files.readString(reopened.getPath("changed")));
                    }
                }
            } finally {
                source.closeBlocked = false;
                archive.close();
            }
        }
    }

    /// Exposes independent channels and records successful source closure.
    @NotNullByDefault
    private static final class ClosingSource implements ArkivoSeekableChannelSource {
        /// The physical archive opened by each channel.
        private final Path path;
        /// Whether source closure must fail.
        private boolean closeBlocked;
        /// Whether source closure completed.
        private boolean closed;

        /// Creates a source over an existing archive.
        private ClosingSource(Path path) {
            this.path = path;
        }

        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closed) {
                throw new ClosedChannelException();
            }
            return Files.newByteChannel(path);
        }

        @Override
        public void close() throws IOException {
            if (closeBlocked) {
                throw new IOException("Source close failed");
            }
            closed = true;
        }
    }

    /// An unsupported forward hard link is rejected without making the rest of the TAR unreadable.
    @Test
    void rejectsTarHardLinksToSymbolicLinks() throws IOException {
        Path path = temporaryDirectory.resolve("links.tar");
        try (ArkivoFileSystem archive = ArkivoFormats.createFileSystem("tar", path)) {
            Files.writeString(archive.getPath("original"), "body");
            Files.createSymbolicLink(archive.getPath("symbolic"), archive.getPath("original"));
            assertThrows(IOException.class,
                    () -> Files.createLink(archive.getPath("rejected"), archive.getPath("symbolic")));
            Files.createLink(archive.getPath("hard"), archive.getPath("original"));
            Files.createLink(archive.getPath("chain"), archive.getPath("hard"));
        }
        try (ArkivoFileSystem archive = ArkivoFormats.openFileSystem("tar", path)) {
            assertEquals("body", Files.readString(archive.getPath("symbolic")));
            assertEquals("body", Files.readString(archive.getPath("chain")));
            assertFalse(Files.exists(archive.getPath("rejected")));
        }
    }

    /// A failed update retains cleanup state across closes without retrying publication.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void retriesFailedUpdateCleanup(String formatName) throws IOException {
        Path path = temporaryDirectory.resolve("source." + formatName);
        try (ArkivoFileSystem archive = ArkivoFormats.createFileSystem(formatName, path)) {
            Files.writeString(archive.getPath("entry"), "original");
        }
        byte[] original = Files.readAllBytes(path);
        RetryCommitOutput output = new RetryCommitOutput(temporaryDirectory.resolve("staged"));
        ArchiveUpdateOptions options = ArchiveUpdateOptions.DEFAULT.withCommitTarget(ignored -> output);
        ArkivoFileSystem archive = ArkivoFormats.updateFileSystem(formatName, path, options);
        Files.writeString(archive.getPath("entry"), "replacement");
        try {
            IOException failure = assertThrows(IOException.class, archive::close);
            assertEquals("Publication failed", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("Cleanup failed", failure.getSuppressed()[0].getMessage());
            assertFalse(archive.isOpen());
            assertEquals(1, output.closeCalls);
            assertTrue(Files.exists(output.path()));
            assertThrows(IOException.class, archive::close);
            assertEquals(2, output.closeCalls);
            archive.close();
            assertEquals(3, output.closeCalls);
            assertFalse(Files.exists(output.path()));
            archive.close();
            assertEquals(3, output.closeCalls);
            assertEquals(1, output.commitCalls);
            assertArrayEquals(original, Files.readAllBytes(path));
        } finally {
            output.close();
            archive.close();
        }
    }

    /// Split updates retain failed rollback state after the file system becomes closed.
    @ParameterizedTest
    @ValueSource(strings = {"zip", "7z"})
    void retriesSplitUpdateRollback(String formatName) throws IOException {
        Path path = temporaryDirectory.resolve("source." + formatName);
        try (ArkivoFileSystem archive = ArkivoFormats.createFileSystem(formatName, path)) {
            Files.writeString(archive.getPath("entry"), "original");
        }
        byte[] original = Files.readAllBytes(path);
        FailingVolumeOutput output = new FailingVolumeOutput(FailurePoint.COMMIT);
        output.cleanupBlocked = true;
        ArkivoFileSystem archive = ArkivoFormats.updateFileSystem(
                formatName, ArkivoVolumeSource.of(List.of(path)), () -> output,
                SPLIT_SIZE, ArchiveUpdateOptions.DEFAULT);
        Files.writeString(archive.getPath("entry"), "replacement");
        try {
            assertThrows(IOException.class, archive::close);
            assertFalse(archive.isOpen());
            assertFalse(output.finished);
            assertEquals(1, output.commitCalls);
            assertThrows(IOException.class, archive::close);
            assertFalse(output.finished);
            output.cleanupBlocked = false;
            archive.close();
            assertTrue(output.finished);
            assertTrue(output.allVolumeChannelsClosed());
            archive.close();
            assertEquals(1, output.commitCalls);
            assertArrayEquals(original, Files.readAllBytes(path));
        } finally {
            output.cleanupBlocked = false;
            output.close();
            archive.close();
        }
    }

    /// Rejects publication and the first two cleanup attempts.
    @NotNullByDefault
    private static final class RetryCommitOutput implements ArkivoCommitOutput {
        /// The unpublished replacement file.
        private final Path path;
        /// Number of publication attempts.
        private int commitCalls;
        /// Number of cleanup attempts.
        private int closeCalls;

        /// Creates a transaction with independent staged storage.
        private RetryCommitOutput(Path path) {
            this.path = path;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            return Files.newByteChannel(path, options);
        }

        @Override
        public void commit() throws IOException {
            commitCalls++;
            throw new IOException("Publication failed");
        }

        @Override
        public void rollback() throws IOException {
            close();
        }

        @Override
        public void close() throws IOException {
            if (++closeCalls <= 2) {
                throw new IOException("Cleanup failed");
            }
            Files.deleteIfExists(path);
        }
    }
    /// ZIP's minimum split size, which is also sufficient for a single-volume 7z transaction.
    private static final long SPLIT_SIZE = 64L * 1024L;

    /// Entry body used after writer setup succeeds.
    private static final byte @Unmodifiable [] CONTENT =
            "transaction failure matrix".getBytes(StandardCharsets.UTF_8);

    /// Creates a transaction failure-test instance for JUnit.
    ArchiveVolumeTransactionFailureTest() {
    }

    /// Verifies that 7z and ZIP roll back after volume-open, write, close, and commit failures.
    @Test
    void rollsBackEveryExternalVolumeFailureStage() {
        for (String formatName : List.of("7z", "zip")) {
            for (FailurePoint failurePoint : FailurePoint.values()) {
                FailingVolumeTarget target = new FailingVolumeTarget(failurePoint);
                assertThrows(IOException.class, () -> writeArchive(formatName, target),
                        formatName + ':' + failurePoint);

                FailingVolumeOutput output = Objects.requireNonNull(
                        target.output(),
                        formatName + ':' + failurePoint
                );
                assertEquals(1, output.rollbackCount(), formatName + ':' + failurePoint);
                assertFalse(output.committed(), formatName + ':' + failurePoint);
                assertTrue(output.allVolumeChannelsClosed(), formatName + ':' + failurePoint);
            }
        }
    }

    /// Writes one entry and closes the archive so every transaction stage is reachable.
    private static void writeArchive(String formatName, ArkivoVolumeTarget target) throws IOException {
        ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, target, SPLIT_SIZE);
        try (writer) {
            try (OutputStream body = writer.beginFile("failure.txt").openOutputStream()) {
                body.write(CONTENT);
            }
        } catch (IOException exception) {
            // A failed channel close must complete before the archive can roll back its volume transaction.
            try {
                writer.close();
            } catch (IOException cleanupFailure) {
                if (exception != cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    /// Identifies one externally observable output stage that fails once.
    @NotNullByDefault
    private enum FailurePoint {
        /// Opening the first physical volume fails.
        OPEN_VOLUME,

        /// The first physical-volume write fails.
        WRITE_VOLUME,

        /// Closing the physical volume fails after making it closed.
        CLOSE_VOLUME,

        /// Publishing completed physical volumes fails.
        COMMIT
    }

    /// Opens one recording output configured to fail at a selected stage.
    @NotNullByDefault
    private static final class FailingVolumeTarget implements ArkivoVolumeTarget {
        /// Stage rejected by the opened output.
        private final FailurePoint failurePoint;

        /// The single output opened by the writer, or null before setup reaches the target.
        private @Nullable FailingVolumeOutput output;

        /// Creates a target with one selected failure stage.
        private FailingVolumeTarget(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        /// Opens the target's single output transaction.
        @Override
        public ArkivoVolumeOutput openOutput() throws IOException {
            if (output != null) {
                throw new IOException("Failure-matrix output was already opened");
            }
            output = new FailingVolumeOutput(failurePoint);
            return output;
        }

        /// Returns the output opened by the writer, or null before target setup.
        private @Nullable FailingVolumeOutput output() {
            return output;
        }
    }

    /// Records transaction completion while injecting one deterministic stage failure.
    @NotNullByDefault
    private static final class FailingVolumeOutput implements ArkivoVolumeOutput {
        /// Whether every rollback attempt is currently rejected.
        private boolean cleanupBlocked;

        /// Number of publication attempts, including failed attempts.
        private int commitCalls;
        /// Stage rejected once by this output.
        private final FailurePoint failurePoint;

        /// Physical volume channels opened by the writer.
        private final java.util.ArrayList<FailingVolumeChannel> channels = new java.util.ArrayList<>();

        /// Number of effective rollback attempts.
        private int rollbackCount;

        /// Whether publication succeeded.
        private boolean committed;

        /// Whether this transaction has committed or rolled back.
        private boolean finished;

        /// Whether the selected open-volume failure has been injected.
        private boolean openFailureInjected;

        /// Creates one unfinished transaction.
        private FailingVolumeOutput(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        /// Opens the next sequential volume or injects the selected setup failure.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            if (finished) {
                throw new ClosedChannelException();
            }
            if (index != channels.size()) {
                throw new IllegalArgumentException("Unexpected failure-matrix volume index: " + index);
            }
            if (failurePoint == FailurePoint.OPEN_VOLUME && !openFailureInjected) {
                openFailureInjected = true;
                throw new IOException("injected volume-open failure");
            }
            FailingVolumeChannel channel = new FailingVolumeChannel(failurePoint);
            channels.add(channel);
            return channel;
        }

        /// Publishes the transaction or injects the selected commit failure.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            commitCalls++;
            if (failurePoint == FailurePoint.COMMIT) {
                throw new IOException("injected commit failure");
            }
            if (finalVolumeIndex != channels.size() - 1L) {
                throw new IllegalArgumentException("Unexpected final failure-matrix volume index");
            }
            committed = true;
            finished = true;
        }

        /// Records one effective rollback and abandons the transaction.
        @Override
        public void rollback() throws IOException {
            if (finished) {
                return;
            }
            if (cleanupBlocked) {
                throw new IOException("Rollback failed");
            }
            rollbackCount++;
            finished = true;
        }

        /// Rolls back when publication did not complete.
        @Override
        public void close() throws IOException {
            rollback();
        }

        /// Returns the number of effective rollback attempts.
        private int rollbackCount() {
            return rollbackCount;
        }

        /// Returns whether publication completed.
        private boolean committed() {
            return committed;
        }

        /// Returns whether every opened physical-volume channel is closed.
        private boolean allVolumeChannelsClosed() {
            for (FailingVolumeChannel channel : channels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }
    }

    /// Discards physical-volume bytes while injecting one write or close failure.
    @NotNullByDefault
    private static final class FailingVolumeChannel implements WritableByteChannel {
        /// Stage rejected once by this channel.
        private final FailurePoint failurePoint;

        /// Whether this channel accepts further writes.
        private boolean open = true;

        /// Whether the selected write failure has been injected.
        private boolean writeFailureInjected;

        /// Whether the selected close failure has been injected.
        private boolean closeFailureInjected;

        /// Creates one open discarding channel.
        private FailingVolumeChannel(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        /// Consumes source bytes or injects the selected write failure.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            if (failurePoint == FailurePoint.WRITE_VOLUME && !writeFailureInjected) {
                writeFailureInjected = true;
                throw new IOException("injected volume-write failure");
            }
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        /// Returns whether this physical volume remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel and then injects the selected close failure once.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            open = false;
            if (failurePoint == FailurePoint.CLOSE_VOLUME && !closeFailureInjected) {
                closeFailureInjected = true;
                throw new IOException("injected volume-close failure");
            }
        }
    }
}

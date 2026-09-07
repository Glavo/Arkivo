// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.cpio.CPIOArchiveOptions;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingReader;
import org.glavo.arkivo.archive.cpio.CPIOArkivoStreamingWriter;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies staged archive writers retain resources across failures without repeating publication.
@NotNullByDefault
final class ArchiveWriterStorageLifecycleTest {
    /// Small entry bytes shared by all format cases.
    private static final byte @Unmodifiable [] BODY = {1, 2, 3, 4};

    /// Supplies every streaming format and failure category.
    private static Stream<Arguments> streamingFailures() {
        return Stream.of(Format.values()).flatMap(format -> Stream.of(FailureKind.values())
                .map(kind -> Arguments.of(format, kind)));
    }

    /// Supplies target and storage failures after an entry has been fully emitted.
    private static Stream<Arguments> finalCleanupFailures() {
        return streamingFailures().flatMap(arguments -> Stream.of(Stage.TARGET_CLOSE, Stage.STORAGE)
                .map(stage -> Arguments.of(arguments.get()[0], arguments.get()[1], stage)));
    }

    /// Supplies failures while closing either side of a staged-body transfer.
    private static Stream<Arguments> bodyCloseFailures() {
        return streamingFailures().flatMap(arguments -> Stream.of(Stage.WRITE_CLOSE, Stage.READ_CLOSE)
                .map(stage -> Arguments.of(arguments.get()[0], arguments.get()[1], stage)));
    }

    /// Verifies final cleanup is retryable and a retry never emits the archive ending twice.
    @ParameterizedTest
    @MethodSource("finalCleanupFailures")
    void retriesFinalCleanupWithoutRepeatingOutput(Format format, FailureKind kind, Stage stage) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        try (OutputStream body = writer.beginFile("entry").openOutputStream()) {
            body.write(BODY);
        }
        Throwable failure = kind.failure("final cleanup");
        storage.fail(stage, failure);
        assertSame(failure, assertThrows(failure.getClass(), writer::close));
        byte[] finished = target.bytes.toByteArray();
        writer.close();
        writer.close();
        assertArrayEquals(finished, target.bytes.toByteArray());
        assertEquals(stage == Stage.TARGET_CLOSE ? 2 : 1, storage.count(Stage.TARGET_CLOSE));
        assertEquals(stage == Stage.STORAGE ? 2 : 1, storage.count(Stage.STORAGE));
        storage.assertReleased();
        format.verifyArchive(finished, true);
    }

    /// Verifies failed staging-channel closes retain their handles and never permit premature content deletion.
    @ParameterizedTest
    @MethodSource("bodyCloseFailures")
    void retainsFailedBodyChannels(Format format, FailureKind kind, Stage stage) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        OutputStream body = writer.beginFile("entry").openOutputStream();
        body.write(BODY);
        Throwable failure = kind.failure("body close");
        storage.fail(stage, failure);
        assertSame(failure, assertThrows(failure.getClass(), body::close));
        try {
            writer.close();
        } catch (IOException | RuntimeException | Error exception) {
            assertSame(failure, exception);
        }
        byte[] finished = target.bytes.toByteArray();
        writer.close();
        assertArrayEquals(finished, target.bytes.toByteArray());
        assertEquals(2, storage.count(stage));
        storage.assertReleased();
        format.verifyArchive(finished, stage == Stage.READ_CLOSE);
    }

    /// Verifies target, content, and storage failures preserve identity and respect cleanup dependencies.
    @ParameterizedTest
    @MethodSource("streamingFailures")
    void aggregatesFailuresInDependencyOrder(Format format, FailureKind kind) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        Throwable contentFailure = kind.failure("content cleanup");
        storage.fail(Stage.CONTENT, contentFailure, contentFailure);
        try (OutputStream body = writer.beginFile("entry").openOutputStream()) {
            body.write(BODY);
        }
        Throwable targetFailure = kind.failure("target cleanup");
        Throwable storageFailure = kind.failure("storage cleanup");
        storage.fail(Stage.TARGET_CLOSE, targetFailure);
        storage.fail(Stage.STORAGE, storageFailure);
        assertSame(targetFailure, assertThrows(targetFailure.getClass(), writer::close));
        assertArrayEquals(new Throwable[]{contentFailure}, targetFailure.getSuppressed());
        assertEquals(0, storage.count(Stage.STORAGE));
        assertSame(storageFailure, assertThrows(storageFailure.getClass(), writer::close));
        byte[] finished = target.bytes.toByteArray();
        writer.close();
        assertEquals(2, storage.count(Stage.TARGET_CLOSE));
        assertEquals(3, storage.count(Stage.CONTENT));
        assertEquals(2, storage.count(Stage.STORAGE));
        assertArrayEquals(finished, target.bytes.toByteArray());
        storage.assertReleased();
        format.verifyArchive(finished, true);
    }

    /// Verifies finalization failures still close both resources and are never retried as further output.
    @ParameterizedTest
    @MethodSource("streamingFailures")
    void doesNotRetryFailedFinalization(Format format, FailureKind kind) throws IOException {
        TrackingStorage storage = new TrackingStorage();
        TrackingOutput target = new TrackingOutput(storage);
        ArkivoStreamingWriter writer = format.writer(target, storage);
        Throwable failure = kind.failure("archive ending");
        storage.fail(Stage.TARGET_WRITE, failure);
        assertSame(failure, assertThrows(failure.getClass(), writer::close));
        writer.close();
        assertEquals(1, storage.count(Stage.TARGET_WRITE));
        assertEquals(1, storage.count(Stage.TARGET_CLOSE));
        assertEquals(1, storage.count(Stage.STORAGE));
        storage.assertReleased();
    }

    /// Verifies successful ZIP publication is not repeated when only storage closure fails.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void retriesZipStorageAfterPublication(FailureKind kind, @TempDir Path directory) throws IOException {
        Path archive = createZip(directory);
        TrackingStorage storage = new TrackingStorage();
        ZipArkivoFileSystem fileSystem = updateZip(archive, storage);
        Files.write(fileSystem.getPath("/added"), BODY);
        Throwable failure = kind.failure("ZIP storage close");
        storage.fail(Stage.STORAGE, failure);
        assertSame(failure, assertThrows(failure.getClass(), fileSystem::close));
        byte[] published = Files.readAllBytes(archive);
        fileSystem.close();
        fileSystem.close();
        assertEquals(2, storage.count(Stage.STORAGE));
        storage.assertReleased();
        assertArrayEquals(published, Files.readAllBytes(archive));
        try (ZipArkivoFileSystem result = ZipArkivoFileSystem.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/original")));
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/added")));
        }
    }

    /// Verifies ZIP does not publish staged local records whose output channel failed to close.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void discardsZipRewriteAfterStagingCloseFailure(FailureKind kind, @TempDir Path directory) throws IOException {
        Path archive = createZip(directory);
        byte[] original = Files.readAllBytes(archive);
        TrackingStorage storage = new TrackingStorage();
        ZipArkivoFileSystem fileSystem = updateZip(archive, storage);
        Files.write(fileSystem.getPath("/added"), BODY);
        Throwable failure = kind.failure("ZIP records close");
        storage.fail(Stage.WRITE_CLOSE, failure);
        assertSame(failure, assertThrows(failure.getClass(), fileSystem::close));
        fileSystem.close();
        storage.assertReleased();
        assertArrayEquals(original, Files.readAllBytes(archive));
    }

    /// Verifies rejected ZIP entry updates retain failed channel cleanup but do not replace the old body.
    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void discardsZipEntryAfterWriterCloseFailure(FailureKind kind, @TempDir Path directory) throws IOException {
        Path archive = createZip(directory);
        TrackingStorage storage = new TrackingStorage();
        ZipArkivoFileSystem fileSystem = updateZip(archive, storage);
        try (SeekableByteChannel body = Files.newByteChannel(fileSystem.getPath("/original"),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            body.write(ByteBuffer.wrap(new byte[]{9, 8}));
            Throwable failure = kind.failure("ZIP entry close");
            storage.fail(Stage.WRITE_CLOSE, failure);
            assertSame(failure, assertThrows(failure.getClass(), body::close));
        }
        fileSystem.close();
        fileSystem.close();
        storage.assertReleased();
        try (ZipArkivoFileSystem result = ZipArkivoFileSystem.open(archive)) {
            assertArrayEquals(BODY, Files.readAllBytes(result.getPath("/original")));
        }
    }

    /// Creates an independently encoded ZIP source for update tests.
    private static Path createZip(Path directory) throws IOException {
        Path archive = directory.resolve("archive.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("original"));
            output.write(BODY);
            output.closeEntry();
        }
        return archive;
    }

    /// Opens an update session using the fault-injecting storage.
    private static ZipArkivoFileSystem updateZip(Path archive, TrackingStorage storage) throws IOException {
        return ZipArkivoFileSystem.update(archive, ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(
                ArchiveUpdateOptions.DEFAULT.withEditStorageFactory(() -> storage)));
    }

    /// Formats whose unknown-size streaming bodies use configurable staging storage.
    @NotNullByDefault
    private enum Format {
        /// Unix archive members.
        AR,
        /// Tape archive entries.
        TAR,
        /// Copy-in/copy-out entries.
        CPIO;

        /// Opens an owning writer for this format.
        private ArkivoStreamingWriter writer(OutputStream output, ArkivoEditStorage storage) throws IOException {
            return switch (this) {
                case AR -> ArArkivoStreamingWriter.open(output, storage);
                case TAR -> TarArkivoStreamingWriter.open(output, storage);
                case CPIO -> CPIOArkivoStreamingWriter.open(output, CPIOArchiveOptions.CREATE_DEFAULTS.withCommon(
                        ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage)));
            };
        }

        /// Opens the corresponding reader without retaining its input beyond reader closure.
        private ArkivoStreamingReader reader(InputStream input) throws IOException {
            return switch (this) {
                case AR -> ArArkivoStreamingReader.open(input);
                case TAR -> TarArkivoStreamingReader.open(input);
                case CPIO -> CPIOArkivoStreamingReader.open(input);
            };
        }

        /// Verifies exactly one committed body, or an empty archive when body staging failed.
        private void verifyArchive(byte[] bytes, boolean hasEntry) throws IOException {
            try (ArkivoStreamingReader reader = reader(new ByteArrayInputStream(bytes))) {
                assertEquals(hasEntry, reader.next());
                if (hasEntry) {
                    try (InputStream input = reader.openInputStream()) {
                        assertArrayEquals(BODY, input.readAllBytes());
                    }
                    assertFalse(reader.next());
                }
            }
        }
    }

    /// Exception categories accepted from user-provided storage and output implementations.
    @NotNullByDefault
    private enum FailureKind {
        /// A checked I/O failure.
        IO,
        /// An unchecked storage failure.
        RUNTIME,
        /// An unrecoverable failure whose identity must still be preserved during cleanup.
        ERROR;

        /// Creates a fresh failure for one operation boundary.
        private Throwable failure(String message) {
            return switch (this) {
                case IO -> new IOException(message);
                case RUNTIME -> new IllegalStateException(message);
                case ERROR -> new AssertionError(message);
            };
        }
    }

    /// Independently observable staging and output operations.
    @NotNullByDefault
    private enum Stage {
        /// Closes a staging writer.
        WRITE_CLOSE,
        /// Closes a body reader.
        READ_CLOSE,
        /// Releases retained content.
        CONTENT,
        /// Closes the storage container.
        STORAGE,
        /// Writes archive bytes to the final target.
        TARGET_WRITE,
        /// Closes the final target.
        TARGET_CLOSE
    }

    /// Tracks resource dependencies and injects failures without allocating external temporary content.
    @NotNullByDefault
    private static final class TrackingStorage implements ArkivoEditStorage {
        /// In-memory backing storage used for byte operations.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Pending failures at each operation boundary.
        private final EnumMap<Stage, ArrayDeque<Throwable>> failures = new EnumMap<>(Stage.class);

        /// Attempt counts, including failed operations.
        private final EnumMap<Stage, Integer> counts = new EnumMap<>(Stage.class);

        /// Number of content objects whose deletion has not succeeded.
        private int liveContents;

        /// Whether storage cleanup has succeeded.
        private boolean closed;

        /// Arms failures consumed in order at one boundary.
        private void fail(Stage stage, Throwable... values) {
            ArrayDeque<Throwable> queue = failures.computeIfAbsent(stage, ignored -> new ArrayDeque<>());
            for (Throwable value : values) {
                queue.add(value);
            }
        }

        /// Records an operation and reports its next queued failure.
        private void attempt(Stage stage) throws IOException {
            counts.merge(stage, 1, Integer::sum);
            @Nullable ArrayDeque<Throwable> queue = failures.get(stage);
            @Nullable Throwable failure = queue == null ? null : queue.poll();
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        /// Returns the number of attempts at one boundary.
        private int count(Stage stage) {
            return counts.getOrDefault(stage, 0);
        }

        /// Allocates content with independently tracked read and write channels.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            ArkivoStoredContent content = delegate.createContent(path, expectedSize);
            liveContents++;
            return new Content(content);
        }

        /// Rejects premature storage closure and otherwise releases the backing storage.
        @Override
        public void close() throws IOException {
            assertEquals(0, liveContents, "Storage closed before content cleanup");
            attempt(Stage.STORAGE);
            delegate.close();
            closed = true;
        }

        /// Verifies all owned resources were released successfully.
        private void assertReleased() {
            assertEquals(0, liveContents);
            assertTrue(closed);
        }

        /// Keeps channel ownership separate from content deletion.
        @NotNullByDefault
        private final class Content implements ArkivoStoredContent {
            /// Backing content whose lifetime is being tracked.
            private final ArkivoStoredContent delegate;

            /// Channels that have not closed successfully.
            private int liveChannels;

            /// Whether this content has been released.
            private boolean closed;

            /// Takes ownership of one backing body.
            private Content(ArkivoStoredContent delegate) {
                this.delegate = delegate;
            }

            /// Opens and tracks one independently positioned channel.
            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                SeekableByteChannel channel = delegate.openChannel(options);
                liveChannels++;
                return new ContentChannel(channel, this, options.contains(StandardOpenOption.WRITE)
                        ? Stage.WRITE_CLOSE : Stage.READ_CLOSE);
            }

            /// Returns the current body size.
            @Override
            public long size() throws IOException {
                return delegate.size();
            }

            /// Rejects deletion before channel closure and tracks successful release exactly once.
            @Override
            public void close() throws IOException {
                assertEquals(0, liveChannels, "Content deleted before channel cleanup");
                attempt(Stage.CONTENT);
                delegate.close();
                if (!closed) {
                    liveContents--;
                    closed = true;
                }
            }
        }

        /// Forwards byte operations and tracks whether a failed close still owns a channel.
        @NotNullByDefault
        private final class ContentChannel implements SeekableByteChannel {
            /// Backing channel retained until successful closure.
            private final SeekableByteChannel delegate;

            /// Content whose deletion depends on this channel.
            private final Content content;

            /// Close boundary selected from the requested access mode.
            private final Stage closeStage;

            /// Whether this channel remains owned.
            private boolean open = true;

            /// Takes ownership of an independently opened backing channel.
            private ContentChannel(SeekableByteChannel delegate, Content content, Stage closeStage) {
                this.delegate = delegate;
                this.content = content;
                this.closeStage = closeStage;
            }

            /// Reads bytes from the delegate.
            @Override
            public int read(ByteBuffer target) throws IOException {
                return delegate.read(target);
            }

            /// Writes bytes to the delegate.
            @Override
            public int write(ByteBuffer source) throws IOException {
                return delegate.write(source);
            }

            /// Returns the delegate position.
            @Override
            public long position() throws IOException {
                return delegate.position();
            }

            /// Changes the delegate position.
            @Override
            public SeekableByteChannel position(long position) throws IOException {
                delegate.position(position);
                return this;
            }

            /// Returns the current body size.
            @Override
            public long size() throws IOException {
                return delegate.size();
            }

            /// Truncates the delegate.
            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                delegate.truncate(size);
                return this;
            }

            /// Returns whether channel closure has not yet succeeded.
            @Override
            public boolean isOpen() {
                return open;
            }

            /// Leaves the delegate owned when an injected close failure occurs.
            @Override
            public void close() throws IOException {
                attempt(closeStage);
                delegate.close();
                if (open) {
                    content.liveChannels--;
                    open = false;
                }
            }
        }
    }

    /// Captures final archive bytes while exposing independent write and close failure boundaries.
    @NotNullByDefault
    private static final class TrackingOutput extends OutputStream {
        /// Final bytes emitted by the writer.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Shared operation recorder and fault queues.
        private final TrackingStorage storage;

        /// Whether target closure has completed.
        private boolean closed;

        /// Creates a target controlled by the storage test's fault queues.
        private TrackingOutput(TrackingStorage storage) {
            this.storage = storage;
        }

        /// Writes one byte after checking target state and injected failures.
        @Override
        public void write(int value) throws IOException {
            assertFalse(closed, "Archive output resumed after close");
            storage.attempt(Stage.TARGET_WRITE);
            bytes.write(value);
        }

        /// Writes a byte range after checking target state and injected failures.
        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            assertFalse(closed, "Archive output resumed after close");
            storage.attempt(Stage.TARGET_WRITE);
            bytes.write(source, offset, length);
        }

        /// Closes this target only after any queued failure has been reported.
        @Override
        public void close() throws IOException {
            storage.attempt(Stage.TARGET_CLOSE);
            closed = true;
        }
    }
}

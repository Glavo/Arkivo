// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests indexed TAR content storage ownership and cleanup behavior.
@NotNullByDefault
public final class TarIndexedStorageTest {
    /// Verifies that a streaming writer owns staged body storage and retries a failed body cleanup.
    @Test
    public void streamingWriterOwnsBodyStorage() throws IOException {
        byte[] expected = "streamed-content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TrackingEditStorage storage = new TrackingEditStorage(true);
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive, storage)) {
            var entry = writer.beginFile("file.txt");
            try (OutputStream output = entry.openOutputStream()) {
                output.write(expected);
            }
        }
        assertEquals(1, storage.createdContentCount());
        assertEquals(2, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(archive.toByteArray()))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        }
    }

    /// Verifies the common environment option configures streaming-writer body storage.
    @Test
    public void streamingWriterUsesEnvironmentBodyStorage() throws IOException {
        TrackingEditStorage storage = new TrackingEditStorage(false);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(
                archive,
                TarArchiveOptions.CREATE_DEFAULTS.withCommon(
                        ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage)
                )
        )) {
            var entry = writer.beginFile("file.txt");
            try (OutputStream output = entry.openOutputStream()) {
                output.write("environment-storage".getBytes(StandardCharsets.UTF_8));
            }
        }

        assertEquals(1, storage.createdContentCount());
        assertEquals(1, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
    }

    /// Verifies that a regular file and its hard link share one owned stored body.
    @Test
    public void hardLinksShareOneStoredBody() throws IOException {
        Path archivePath = createArchive(true);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try {
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(
                    archivePath,
                    TarArchiveOptions.READ_DEFAULTS.withCommon(
                            ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                    )
            )) {
                byte[] expected = "shared-content".getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/file.txt")));
                assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/hard.txt")));
            }
            assertEquals(1, storage.createdContentCount());
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that failed stored-content cleanup is retried by a later file-system close.
    @Test
    public void contentCleanupCanBeRetried() throws IOException {
        Path archivePath = createArchive(false);
        TrackingEditStorage storage = new TrackingEditStorage(true);
        TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(
                archivePath,
                TarArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                )
        );
        try {
            IOException failure = assertThrows(IOException.class, fileSystem::close);
            assertEquals("content close failed", failure.getMessage());
            assertEquals(1, storage.contentCloseCount());
            assertEquals(0, storage.closeCount());

            fileSystem.close();
            fileSystem.close();
            assertEquals(2, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
        } finally {
            try {
                fileSystem.close();
            } finally {
                Files.deleteIfExists(archivePath);
            }
        }
    }

    /// Verifies a checked storage-construction failure releases an already transferred archive source.
    @Test
    void closesOwnedSourceAfterStorageConstructionFailure() throws IOException {
        Path archivePath = createArchive(false);
        IOException failure = new IOException("storage construction failed");
        try (SeekableByteChannel channel = Files.newByteChannel(archivePath)) {
            ArkivoSeekableChannelSource source = ArkivoSeekableChannelSource.of(channel);
            assertSame(failure, assertThrows(IOException.class, () -> TarArkivoFileSystem.open(source,
                    TarArchiveOptions.READ_DEFAULTS.withCommon(ArchiveReadOptions.DEFAULT
                            .withEditStorageFactory(() -> {
                                throw failure;
                            })))));
            assertFalse(channel.isOpen());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Supplies checked and unchecked failures for the same backing-channel close boundary.
    private static Stream<Throwable> channelCloseFailures() {
        return Stream.of(new IOException("channel close failed"),
                new IllegalStateException("channel close failed"), new AssertionError("channel close failed"));
    }

    /// Verifies independent readers delay cleanup while coordinated closure rejects further I/O.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesSharedReadersInEitherOrder(boolean closeFileSystemFirst) throws IOException {
        Path archivePath = createArchive(true);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, TarArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                ));
        try (fileSystem;
             SeekableByteChannel first = Files.newByteChannel(fileSystem.getPath("/file.txt"));
             SeekableByteChannel second = Files.newByteChannel(fileSystem.getPath("/hard.txt"))) {
            assertEquals(1, storage.createdContentCount());
            assertEquals(1, first.read(ByteBuffer.allocate(1)));
            assertEquals(0L, second.position());
            if (closeFileSystemFirst) {
                fileSystem.close();
                assertThrows(ClosedChannelException.class, () -> first.read(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, second::position);
            }
            assertEquals(0, storage.contentCloseCount());
            assertEquals(0, storage.closeCount());
            first.close();
            if (!closeFileSystemFirst) {
                assertEquals(1, second.read(ByteBuffer.allocate(1)));
            }
            assertEquals(0, storage.contentCloseCount());
            second.close();
            assertEquals(closeFileSystemFirst ? 1 : 0, storage.contentCloseCount());
            fileSystem.close();
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies a failed reader close remains tracked and blocks content deletion until a retry succeeds.
    @ParameterizedTest
    @MethodSource("channelCloseFailures")
    void retriesBackingChannelCloseBeforeReleasingContent(Throwable failure) throws IOException {
        Path archivePath = createArchive(true);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, TarArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                ));
        try (fileSystem;
             SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/file.txt"))) {
            storage.channelCloseFailure = failure;
            assertSame(failure, assertThrows(failure.getClass(), channel::close));
            assertEquals(0, storage.contentCloseCount());
            assertEquals(0, storage.closeCount());
            fileSystem.close();
            assertEquals(3, storage.channelCloseCount);
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
            channel.close();
            fileSystem.close();
            assertEquals(3, storage.channelCloseCount);
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies strict close releases active readers and still retries a failed backing close before deletion.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictCloseReleasesReadersBeforeStorage(boolean failClose) throws IOException {
        Path archivePath = createArchive(true);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath,
                TarArchiveOptions.READ_DEFAULTS.withCommon(ArchiveReadOptions.DEFAULT
                        .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                        .withEditStorageFactory(() -> storage)));
        try (fileSystem;
             SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/file.txt"))) {
            IOException failure = new IOException("strict channel close failed");
            if (failClose) {
                storage.channelCloseFailure = failure;
                assertSame(failure, assertThrows(IOException.class, fileSystem::close));
            } else {
                fileSystem.close();
            }
            fileSystem.close();
            assertFalse(channel.isOpen());
            assertEquals(failClose ? 3 : 2, storage.channelCloseCount);
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies a failed staging-writer close is retried before its content and storage are released.
    @Test
    void recoversFailedMaterializationWriterClose() throws IOException {
        Path archivePath = createArchive(true);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        IOException failure = new IOException("staging writer close failed");
        storage.channelCloseFailure = failure;
        try {
            assertSame(failure, assertThrows(IOException.class,
                    () -> TarArkivoFileSystem.open(archivePath, TarArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                ))));
            assertEquals(2, storage.channelCloseCount);
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies failed update-channel closure discards the body while successful closure publishes it.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateStoragePublishesOnlySuccessfullyClosedBodies(boolean failClose) throws IOException {
        Path archivePath = createArchive(true);
        byte[] original = Files.readAllBytes(archivePath);
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try {
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archivePath,
                    TarArchiveOptions.UPDATE_DEFAULTS.withCommon(ArchiveUpdateOptions.DEFAULT
                            .withEditStorageFactory(() -> storage)))) {
                try (SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/file.txt"),
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(ByteBuffer.wrap(replacement));
                    if (failClose) {
                        IOException failure = new IOException("update channel close failed");
                        storage.channelCloseFailure = failure;
                        assertSame(failure, assertThrows(IOException.class, channel::close));
                    }
                }
            }
            assertEquals(storage.createdContentCount(), storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
            if (failClose) {
                assertArrayEquals(original, Files.readAllBytes(archivePath));
            } else {
                try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                    assertArrayEquals(replacement, Files.readAllBytes(fileSystem.getPath("/file.txt")));
                    assertArrayEquals(replacement, Files.readAllBytes(fileSystem.getPath("/hard.txt")));
                }
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Creates a small TAR archive with one regular file and an optional hard link.
    private static Path createArchive(boolean includeHardLink) throws IOException {
        Path directory = Path.of("build", "tmp", "arkivo-tar-storage-tests");
        Files.createDirectories(directory);
        Path archivePath = Files.createTempFile(directory, "indexed-storage-", ".tar");
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
            var fileEntry = writer.beginFile("file.txt");
            try (OutputStream output = fileEntry.openOutputStream()) {
                output.write("shared-content".getBytes(StandardCharsets.UTF_8));
            }
            if (includeHardLink) {
                var hardLinkEntry = writer.beginHardLink("hard.txt", "file.txt");
                hardLinkEntry.close();
            }
        }
        return archivePath;
    }

    /// Tracks storage allocation and close calls while delegating content to memory storage.
    @NotNullByDefault
    private static final class TrackingEditStorage implements ArkivoEditStorage {
        /// The delegate memory storage.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Whether the first stored-content close call must fail.
        private final boolean failFirstContentClose;

        /// The number of created content objects.
        private int createdContentCount;

        /// The total number of stored-content close calls.
        private int contentCloseCount;

        /// The failure injected by the next channel close, or null when no failure is armed.
        private @Nullable Throwable channelCloseFailure;

        /// The number of backing-channel close attempts.
        private int channelCloseCount;

        /// The number of storage close calls.
        private int closeCount;

        /// Creates tracking storage with the requested cleanup behavior.
        private TrackingEditStorage(boolean failFirstContentClose) {
            this.failFirstContentClose = failFirstContentClose;
        }

        /// Creates one tracked stored-content object.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createdContentCount++;
            return new TrackingStoredContent(delegate.createContent(path, expectedSize));
        }

        /// Closes the delegate storage and records the call.
        @Override
        public void close() throws IOException {
            closeCount++;
            delegate.close();
        }

        /// Returns the number of created content objects.
        private int createdContentCount() {
            return createdContentCount;
        }

        /// Returns the total number of stored-content close calls.
        private int contentCloseCount() {
            return contentCloseCount;
        }

        /// Returns the number of storage close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Records backing-channel cleanup without changing byte access.
        @NotNullByDefault
        private final class TrackingChannel implements SeekableByteChannel {
            /// The independently owned backing channel.
            private final SeekableByteChannel delegate;

            /// Wraps a channel created by the configured storage.
            private TrackingChannel(SeekableByteChannel delegate) {
                this.delegate = delegate;
            }

            /// Reads from the backing channel.
            @Override
            public int read(ByteBuffer target) throws IOException {
                return delegate.read(target);
            }

            /// Writes to the backing channel.
            @Override
            public int write(ByteBuffer source) throws IOException {
                return delegate.write(source);
            }

            /// Returns the backing position.
            @Override
            public long position() throws IOException {
                return delegate.position();
            }

            /// Changes the backing position.
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

            /// Truncates the backing content.
            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                delegate.truncate(size);
                return this;
            }

            /// Returns whether the backing channel remains open.
            @Override
            public boolean isOpen() {
                return delegate.isOpen();
            }

            /// Reports an armed failure without closing the delegate, or closes it successfully.
            @Override
            public void close() throws IOException {
                channelCloseCount++;
                @Nullable Throwable failure = channelCloseFailure;
                channelCloseFailure = null;
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                delegate.close();
            }
        }

        /// Tracks one delegated stored-content object.
        @NotNullByDefault
        private final class TrackingStoredContent implements ArkivoStoredContent {
            /// The delegated stored content.
            private final ArkivoStoredContent content;

            /// Whether this content has failed its first close call.
            private boolean firstCloseFailed;

            /// Creates tracked stored content.
            private TrackingStoredContent(ArkivoStoredContent content) {
                this.content = content;
            }

            /// Opens a channel over the delegated content.
            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                return new TrackingChannel(content.openChannel(options));
            }

            /// Returns the delegated content size.
            @Override
            public long size() throws IOException {
                return content.size();
            }

            /// Closes the delegated content or injects the configured first failure.
            @Override
            public void close() throws IOException {
                contentCloseCount++;
                if (failFirstContentClose && !firstCloseFailed) {
                    firstCloseFailed = true;
                    throw new IOException("content close failed");
                }
                content.close();
            }
        }
    }
}

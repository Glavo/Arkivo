// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests indexed AR content storage ownership and cleanup behavior.
@NotNullByDefault
public final class ArIndexedStorageTest {
    /// Verifies that a configured member size keeps AR body output on the direct streaming path.
    @Test
    public void knownSizeStreamingBodyDoesNotUseStorage() throws IOException {
        byte[] expected = "direct-content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(archive, storage)) {
            var entry = writer.beginFile("file.txt");
            ArArkivoEntryAttributeView attributes = Objects.requireNonNull(
                    entry.attributeView(ArArkivoEntryAttributeView.class)
            );
            attributes.setSize(expected.length);
            try (OutputStream output = entry.openOutputStream()) {
                output.write(expected);
            }
        }
        assertEquals(0, storage.createdContentCount());
        assertEquals(0, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
    }

    /// Verifies that a streaming writer owns staged body storage and retries a failed body cleanup.
    @Test
    public void streamingWriterOwnsBodyStorage() throws IOException {
        byte[] expected = "streamed-content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TrackingEditStorage storage = new TrackingEditStorage(true);
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(archive, storage)) {
            var entry = writer.beginFile("file.txt");
            try (OutputStream output = entry.openOutputStream()) {
                output.write(expected);
            }
        }
        assertEquals(1, storage.createdContentCount());
        assertEquals(2, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
        try (ArArkivoStreamingReader reader =
                     ArArkivoStreamingReader.open(new ByteArrayInputStream(archive.toByteArray()))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        }
    }

    /// Verifies the format writer contract applies common environment body storage.
    @Test
    public void formatWriterUsesEnvironmentBodyStorage() throws IOException {
        TrackingEditStorage storage = new TrackingEditStorage(false);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        try (ArArkivoStreamingWriter writer = ArArkivoFormat.instance().openStreamingWriter(
                archive,
                ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage)
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

    /// Verifies the path factory transfers explicit body-storage ownership and publishes its staged member.
    @Test
    public void pathFactoryOwnsExplicitBodyStorage() throws IOException {
        Path directory = Path.of("build", "tmp", "arkivo-ar-storage-tests");
        Files.createDirectories(directory);
        Path archivePath = Files.createTempFile(directory, "explicit-storage-", ".a");
        byte[] expected = "path storage".getBytes(StandardCharsets.UTF_8);
        TrackingEditStorage storage = new TrackingEditStorage(false);

        try {
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath, storage)) {
                try (OutputStream output = writer.beginFile("file.txt").openOutputStream()) {
                    output.write(expected);
                }
            }

            assertEquals(1, storage.createdContentCount());
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
            try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(Files.newInputStream(archivePath))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(expected, input.readAllBytes());
                }
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that configured storage owns and releases one indexed member body.
    @Test
    public void configuredStorageOwnsIndexedBody() throws IOException {
        Path archivePath = createArchive();
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try {
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(
                    archivePath,
                    ArArchiveOptions.READ_DEFAULTS.withCommon(
                            ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                    )
            )) {
                assertArrayEquals(
                        "stored-content".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/file.txt"))
                );
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
        Path archivePath = createArchive();
        TrackingEditStorage storage = new TrackingEditStorage(true);
        ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(
                archivePath,
                ArArchiveOptions.READ_DEFAULTS.withCommon(
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

    /// Verifies writer cleanup preserves a shared failure and retries every resource that remains open.
    @Test
    public void writerCleanupAvoidsSelfSuppressionAndRetries() throws IOException {
        IOException sharedFailure = new IOException("shared writer cleanup failure");
        SharedFailureOutputStream output = new SharedFailureOutputStream(sharedFailure);
        SharedFailureStorage storage = new SharedFailureStorage(sharedFailure);
        ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output, storage);

        assertSame(sharedFailure, assertThrows(IOException.class, writer::close));
        assertEquals(0, sharedFailure.getSuppressed().length);
        assertTrue(output.isOpen());
        assertTrue(storage.isOpen());

        assertDoesNotThrow(writer::close);
        assertFalse(output.isOpen());
        assertFalse(storage.isOpen());
        assertDoesNotThrow(writer::close);
    }

    /// Verifies indexed file-system cleanup preserves one shared failure and retries incomplete resources.
    @Test
    public void fileSystemCleanupAvoidsSelfSuppressionAndRetries() throws IOException {
        Path archivePath = createArchive();
        IOException sharedFailure = new IOException("shared file-system cleanup failure");
        SharedContentFailureStorage storage = new SharedContentFailureStorage(sharedFailure);
        ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(
                archivePath,
                ArArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                )
        );

        try {
            assertSame(sharedFailure, assertThrows(IOException.class, fileSystem::close));
            assertEquals(0, sharedFailure.getSuppressed().length);
            assertTrue(storage.isContentOpen());
            assertTrue(storage.isOpen());

            assertSame(sharedFailure, assertThrows(IOException.class, fileSystem::close));
            assertEquals(0, sharedFailure.getSuppressed().length);
            assertFalse(storage.isContentOpen());
            assertTrue(storage.isOpen());
            assertDoesNotThrow(fileSystem::close);
            assertFalse(storage.isContentOpen());
            assertFalse(storage.isOpen());
            assertDoesNotThrow(fileSystem::close);
        } finally {
            try {
                fileSystem.close();
            } finally {
                Files.deleteIfExists(archivePath);
            }
        }
    }

    /// Verifies source cleanup does not replace a shared storage-construction failure with self-suppression.
    @Test
    public void sourceCleanupAvoidsSelfSuppressionAfterStorageFailure() {
        RuntimeException sharedFailure = new IllegalStateException("shared source cleanup failure");
        SharedRuntimeFailureSource source = new SharedRuntimeFailureSource(sharedFailure);
        ArArchiveOptions.Read options = ArArchiveOptions.READ_DEFAULTS.withCommon(
                ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> {
                    throw sharedFailure;
                })
        );

        assertSame(
                sharedFailure,
                assertThrows(RuntimeException.class, () -> ArArkivoFileSystem.open(source, options))
        );
        assertEquals(0, sharedFailure.getSuppressed().length);
        assertEquals(1, source.closeCount());
    }

    /// Verifies a checked storage-construction failure releases an already transferred archive source.
    @Test
    void closesOwnedSourceAfterStorageConstructionFailure() throws IOException {
        Path archivePath = createArchive();
        IOException failure = new IOException("storage construction failed");
        try (SeekableByteChannel channel = Files.newByteChannel(archivePath)) {
            ArkivoSeekableChannelSource source = ArkivoSeekableChannelSource.of(channel);
            assertSame(failure, assertThrows(IOException.class, () -> ArArkivoFileSystem.open(source,
                    ArArchiveOptions.READ_DEFAULTS.withCommon(ArchiveReadOptions.DEFAULT
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
        Path archivePath = createArchive();
        TrackingEditStorage storage = new TrackingEditStorage(false);
        ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, ArArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
                ));
        try (fileSystem;
             SeekableByteChannel first = Files.newByteChannel(fileSystem.getPath("/file.txt"));
             SeekableByteChannel second = Files.newByteChannel(fileSystem.getPath("/file.txt"))) {
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
        Path archivePath = createArchive();
        TrackingEditStorage storage = new TrackingEditStorage(false);
        ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, ArArchiveOptions.READ_DEFAULTS.withCommon(
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
        Path archivePath = createArchive();
        TrackingEditStorage storage = new TrackingEditStorage(false);
        ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath,
                ArArchiveOptions.READ_DEFAULTS.withCommon(ArchiveReadOptions.DEFAULT
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
        Path archivePath = createArchive();
        TrackingEditStorage storage = new TrackingEditStorage(false);
        IOException failure = new IOException("staging writer close failed");
        storage.channelCloseFailure = failure;
        try {
            assertSame(failure, assertThrows(IOException.class,
                    () -> ArArkivoFileSystem.open(archivePath, ArArchiveOptions.READ_DEFAULTS.withCommon(
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
        Path archivePath = createArchive();
        byte[] original = Files.readAllBytes(archivePath);
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try {
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(archivePath,
                    ArArchiveOptions.UPDATE_DEFAULTS.withCommon(ArchiveUpdateOptions.DEFAULT
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
                try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath)) {
                    assertArrayEquals(replacement, Files.readAllBytes(fileSystem.getPath("/file.txt")));
                }
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Creates a small AR archive with one regular member.
    private static Path createArchive() throws IOException {
        Path directory = Path.of("build", "tmp", "arkivo-ar-storage-tests");
        Files.createDirectories(directory);
        Path archivePath = Files.createTempFile(directory, "indexed-storage-", ".a");
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
            var entry = writer.beginFile("file.txt");
            try (OutputStream output = entry.openOutputStream()) {
                output.write("stored-content".getBytes(StandardCharsets.UTF_8));
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

    /// Provides an output stream whose first close reports one shared failure.
    @NotNullByDefault
    private static final class SharedFailureOutputStream extends OutputStream {
        /// Successfully written archive bytes.
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        /// Failure reported by the first close.
        private final IOException failure;

        /// Whether the first close failure has occurred.
        private boolean failed;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Creates an output stream with one close failure.
        private SharedFailureOutputStream(IOException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /// Writes one archive byte while open.
        @Override
        public void write(int value) throws IOException {
            requireOpen();
            delegate.write(value);
        }

        /// Writes one archive byte range while open.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireOpen();
            delegate.write(bytes, offset, length);
        }

        /// Reports the shared failure once and closes successfully on retry.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            if (!failed) {
                failed = true;
                throw failure;
            }
            open = false;
            delegate.close();
        }

        /// Returns whether this output remains open.
        private boolean isOpen() {
            return open;
        }

        /// Requires this output to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("shared-failure output is closed");
            }
        }
    }

    /// Provides otherwise ordinary edit storage whose first close reports one shared failure.
    @NotNullByDefault
    private static final class SharedFailureStorage implements ArkivoEditStorage {
        /// Delegated memory-backed storage.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Failure reported by the first close.
        private final IOException failure;

        /// Whether the first close failure has occurred.
        private boolean failed;

        /// Whether this storage remains open.
        private boolean open = true;

        /// Creates storage with one close failure.
        private SharedFailureStorage(IOException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /// Creates delegated stored content while open.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            if (!open) {
                throw new IOException("shared-failure storage is closed");
            }
            return delegate.createContent(path, expectedSize);
        }

        /// Reports the shared failure once and closes successfully on retry.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            if (!failed) {
                failed = true;
                throw failure;
            }
            open = false;
            delegate.close();
        }

        /// Returns whether this storage remains open.
        private boolean isOpen() {
            return open;
        }
    }

    /// Provides one stored content and its storage that each report the same first close failure.
    @NotNullByDefault
    private static final class SharedContentFailureStorage implements ArkivoEditStorage {
        /// Delegated memory-backed storage.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Failure reported by the first content and storage close calls.
        private final IOException failure;

        /// Whether the content has reported its first close failure.
        private boolean contentFailed;

        /// Whether the storage has reported its first close failure.
        private boolean storageFailed;

        /// Whether the delegated content remains open.
        private boolean contentOpen = true;

        /// Whether this storage remains open.
        private boolean open = true;

        /// Creates storage whose content and container share one close failure.
        private SharedContentFailureStorage(IOException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /// Creates one content object whose first close reports the shared failure.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            ArkivoStoredContent content = delegate.createContent(path, expectedSize);
            return new ArkivoStoredContent() {
                /// Opens a channel over the delegated content.
                @Override
                public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                    return content.openChannel(options);
                }

                /// Returns the delegated content size.
                @Override
                public long size() throws IOException {
                    return content.size();
                }

                /// Reports the shared failure once and closes the delegated content on retry.
                @Override
                public void close() throws IOException {
                    if (!contentOpen) {
                        return;
                    }
                    if (!contentFailed) {
                        contentFailed = true;
                        throw failure;
                    }
                    contentOpen = false;
                    content.close();
                }
            };
        }

        /// Reports the shared failure once and closes the delegated storage on retry.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            if (!storageFailed) {
                storageFailed = true;
                throw failure;
            }
            open = false;
            delegate.close();
        }

        /// Returns whether the delegated content remains open.
        private boolean isContentOpen() {
            return contentOpen;
        }

        /// Returns whether this storage remains open.
        private boolean isOpen() {
            return open;
        }
    }

    /// Provides a channel source whose close repeats a configured unchecked setup failure.
    @NotNullByDefault
    private static final class SharedRuntimeFailureSource implements ArkivoSeekableChannelSource {
        /// Failure reported by source close.
        private final RuntimeException failure;

        /// Number of source close attempts.
        private int closeCount;

        /// Creates a source that reports `failure` from close.
        private SharedRuntimeFailureSource(RuntimeException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /// Rejects channel access because setup must fail before opening source data.
        @Override
        public SeekableByteChannel openChannel() {
            throw new AssertionError("source channel must not be opened");
        }

        /// Reports the configured shared runtime failure.
        @Override
        public void close() {
            closeCount++;
            throw failure;
        }

        /// Returns the number of source close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}

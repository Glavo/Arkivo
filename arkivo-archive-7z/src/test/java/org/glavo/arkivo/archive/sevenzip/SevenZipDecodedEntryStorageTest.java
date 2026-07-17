// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies storage and lifecycle behavior for decoded seekable 7z entry channels.
@NotNullByDefault
final class SevenZipDecodedEntryStorageTest {
    /// Verifies compressed read-only channels use configured storage and defer its close while content remains open.
    @Test
    void stagesCompressedChannelsAndDefersStorageClose(@TempDir Path directory) throws IOException {
        byte[] content = "compressed decoded 7z entry".repeat(64).getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("storage.7z");
        createCompressedArchive(archive, content);
        TrackingStorage storage = new TrackingStorage(directory.resolve("staging"), false);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                archive,
                readOptions(storage)
        );

        try (SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/compressed.bin"))) {
            assertEquals(1, storage.createCount);
            assertEquals(content.length, storage.lastExpectedSize);
            channel.position(57L);
            ByteBuffer actual = ByteBuffer.allocate(91);
            assertEquals(91, channel.read(actual));
            assertArrayEquals(java.util.Arrays.copyOfRange(content, 57, 148), actual.array());
        }
        assertEquals(1, storage.contentCloseCount);

        SeekableByteChannel deferred = Files.newByteChannel(fileSystem.getPath("/compressed.bin"));
        assertEquals(2, storage.createCount);
        fileSystem.close();
        assertEquals(0, storage.storageCloseCount);
        deferred.close();
        assertEquals(2, storage.contentCloseCount);
        assertEquals(1, storage.storageCloseCount);
    }

    /// Verifies a failed read-only open closes configured storage after ownership transfers.
    @Test
    void failedOpenClosesConfiguredStorage(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("invalid.7z");
        Files.write(archive, new byte[32]);
        TrackingStorage storage = new TrackingStorage(directory.resolve("failed-open-staging"), false);

        assertThrows(
                IOException.class,
                () -> SevenZipArkivoFileSystem.open(
                        archive,
                        readOptions(storage)
                )
        );
        assertEquals(1, storage.storageCloseCount);
    }

    /// Verifies failed decoded-content cleanup is retained and retried during file system close.
    @Test
    void retriesDecodedContentCleanup(@TempDir Path directory) throws IOException {
        byte[] content = "retry decoded 7z entry".repeat(32).getBytes(StandardCharsets.UTF_8);
        Path archive = directory.resolve("retry.7z");
        createCompressedArchive(archive, content);
        TrackingStorage storage = new TrackingStorage(directory.resolve("retry-staging"), true);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                archive,
                readOptions(storage)
        );
        SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/compressed.bin"));

        assertThrows(IOException.class, channel::close);
        assertEquals(1, storage.contentCloseAttempts);
        assertEquals(0, storage.storageCloseCount);

        fileSystem.close();
        assertEquals(2, storage.contentCloseAttempts);
        assertEquals(1, storage.contentCloseCount);
        assertEquals(1, storage.storageCloseCount);
    }

    /// Creates one Deflate-compressed 7z entry.
    private static void createCompressedArchive(Path archive, byte[] content) throws IOException {
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.create(
                archive,
                new SevenZipArchiveOptions.Create(
                        org.glavo.arkivo.archive.ArchiveCreateOptions.DEFAULT,
                        null,
                        SevenZipCompression.deflate(1),
                        SevenZipFilterChain.EMPTY,
                        SevenZipArchiveOptions.DEFAULT_SOLID_FILE_COUNT,
                        false
                )
        )) {
            var writerEntry107 = writer.beginFile("compressed.bin");
            try (OutputStream output = writerEntry107.openOutputStream()) {
                output.write(content);
            }
        }
    }

    /// Returns read options using the requested decoded-content storage.
    private static SevenZipArchiveOptions.Read readOptions(ArkivoEditStorage storage) {
        return new SevenZipArchiveOptions.Read(
                ArchiveReadOptions.DEFAULT.withEditStorage(storage),
                null
        );
    }

    /// Records storage allocation and close behavior while delegating bytes to temporary files.
    @NotNullByDefault
    private static final class TrackingStorage implements ArkivoEditStorage {
        /// The temporary-file storage delegate.
        private final ArkivoEditStorage delegate;

        /// Whether the first content close should fail.
        private final boolean failFirstContentClose;

        /// The number of allocated content objects.
        private int createCount;

        /// The last expected decoded size.
        private long lastExpectedSize = UNKNOWN_SIZE;

        /// The number of content close attempts.
        private int contentCloseAttempts;

        /// The number of successful content closes.
        private int contentCloseCount;

        /// The number of storage close calls.
        private int storageCloseCount;

        /// Creates tracking storage backed by temporary files.
        private TrackingStorage(Path directory, boolean failFirstContentClose) {
            this.delegate = ArkivoEditStorage.temporaryFiles(directory);
            this.failFirstContentClose = failFirstContentClose;
        }

        /// Allocates one tracked content object.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createCount++;
            lastExpectedSize = expectedSize;
            return new TrackingContent(delegate.createContent(path, expectedSize), this);
        }

        /// Closes the delegated storage.
        @Override
        public void close() throws IOException {
            storageCloseCount++;
            delegate.close();
        }
    }

    /// Tracks one decoded content body and optionally fails its first close attempt.
    @NotNullByDefault
    private static final class TrackingContent implements ArkivoStoredContent {
        /// The stored-content delegate.
        private final ArkivoStoredContent delegate;

        /// The storage receiving lifecycle counters.
        private final TrackingStorage storage;

        /// Creates tracked stored content.
        private TrackingContent(ArkivoStoredContent delegate, TrackingStorage storage) {
            this.delegate = delegate;
            this.storage = storage;
        }

        /// Opens a delegated channel.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            return delegate.openChannel(options);
        }

        /// Returns the delegated content size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Closes the content or raises the configured first-attempt failure.
        @Override
        public void close() throws IOException {
            storage.contentCloseAttempts++;
            if (storage.failFirstContentClose && storage.contentCloseAttempts == 1) {
                throw new IOException("Injected decoded content close failure");
            }
            delegate.close();
            storage.contentCloseCount++;
        }
    }
}

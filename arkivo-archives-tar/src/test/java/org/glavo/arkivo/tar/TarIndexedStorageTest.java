// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            writer.beginFile("file.txt");
            try (OutputStream output = writer.openOutputStream()) {
                output.write(expected);
            }
        }
        assertEquals(1, storage.createdContentCount());
        assertEquals(2, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(archive.toByteArray()))) {
            assertEquals(true, reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        }
    }

    /// Verifies that a regular file and its hard link share one owned stored body.
    @Test
    public void hardLinksShareOneStoredBody() throws IOException {
        Path archivePath = createArchive(true);
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try {
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(
                    archivePath,
                    Map.of(ArkivoFileSystem.EDIT_STORAGE.key(), storage)
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
                Map.of(ArkivoFileSystem.EDIT_STORAGE.key(), storage)
        );
        try {
            IOException failure = assertThrows(IOException.class, fileSystem::close);
            assertEquals("content close failed", failure.getMessage());
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());

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

    /// Creates a small TAR archive with one regular file and an optional hard link.
    private static Path createArchive(boolean includeHardLink) throws IOException {
        Path directory = Path.of("build", "tmp", "arkivo-tar-storage-tests");
        Files.createDirectories(directory);
        Path archivePath = Files.createTempFile(directory, "indexed-storage-", ".tar");
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
            writer.beginFile("file.txt");
            try (OutputStream output = writer.openOutputStream()) {
                output.write("shared-content".getBytes(StandardCharsets.UTF_8));
            }
            if (includeHardLink) {
                writer.beginHardLink("hard.txt", "file.txt");
                writer.endEntry();
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
                return content.openChannel(options);
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

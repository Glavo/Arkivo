// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies AR file systems backed by repeatable seekable channel sources.
@NotNullByDefault
final class ArArkivoChannelSourceTest {
    /// Verifies indexed reads, source ownership, file store metadata, and URI behavior.
    @Test
    void opensReadOnlyFileSystemFromChannelSource() throws IOException {
        byte[] archive = archiveBytes();
        Path archivePath = Files.createTempFile("arkivo-ar-source-", ".a");
        TrackingSource source = new TrackingSource(archivePath, false);
        try {
            Files.write(archivePath, archive);
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(source)) {
                Path entry = fileSystem.getPath("/value.txt");
                assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(entry));
                assertTrue(source.openedChannelClosed());
                assertEquals(0, source.closeCount());

                FileStore fileStore = Files.getFileStore(entry);
                assertEquals("ar", fileStore.name());
                assertEquals(archive.length, fileStore.getTotalSpace());
                assertThrows(UnsupportedOperationException.class, entry::toUri);
            }

            assertEquals(1, source.closeCount());
            assertTrue(source.closed());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that write options are rejected before a source channel is opened and the source is cleaned up.
    @Test
    void rejectsWriteOptionsAndClosesSource() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-ar-source-options-", ".a");
        TrackingSource source = new TrackingSource(archivePath, false);
        try {
            Files.write(archivePath, archiveBytes());
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            );

            assertThrows(UnsupportedOperationException.class, () -> ArArkivoFileSystem.open(source, environment));
            assertEquals(0, source.openCount());
            assertEquals(1, source.closeCount());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies source cleanup after an open failure and retry after a close failure.
    @Test
    void cleansUpFailedOpenAndRetriesFailedClose() throws IOException {
        Path invalidPath = Files.createTempFile("arkivo-ar-source-invalid-", ".a");
        TrackingSource invalidSource = new TrackingSource(invalidPath, false);
        try {
            Files.write(invalidPath, new byte[]{1, 2, 3});
            assertThrows(IOException.class, () -> ArArkivoFileSystem.open(invalidSource));
            assertTrue(invalidSource.openedChannelClosed());
            assertEquals(1, invalidSource.closeCount());
        } finally {
            Files.deleteIfExists(invalidPath);
        }

        Path archivePath = Files.createTempFile("arkivo-ar-source-close-", ".a");
        TrackingSource source = new TrackingSource(archivePath, true);
        try {
            Files.write(archivePath, archiveBytes());
            ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(source);

            IOException exception = assertThrows(IOException.class, fileSystem::close);
            assertEquals("source close failed", exception.getMessage());
            assertFalse(fileSystem.isOpen());
            assertEquals(1, source.closeCount());

            fileSystem.close();
            fileSystem.close();
            assertEquals(2, source.closeCount());
            assertTrue(source.closed());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Creates an AR archive used by channel-source tests.
    private static byte[] archiveBytes() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writer.beginFile("value.txt");
            try (OutputStream body = writer.openOutputStream()) {
                body.write("value".getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    /// Opens tracked path-backed channels through the channel-source contract.
    @NotNullByDefault
    private static final class TrackingSource implements ArkivoSeekableChannelSource {
        /// The path opened for each channel request.
        private final Path path;

        /// Whether the first source close should fail.
        private final boolean failFirstClose;

        /// The most recently opened channel.
        private @Nullable SeekableByteChannel openedChannel;

        /// The number of opened channels.
        private int openCount;

        /// The number of source close attempts.
        private int closeCount;

        /// Whether the source closed successfully.
        private boolean closed;

        /// Creates a tracked source.
        private TrackingSource(Path path, boolean failFirstClose) {
            this.path = path;
            this.failFirstClose = failFirstClose;
        }

        /// Opens a new readable channel.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closed) {
                throw new IOException("source is closed");
            }
            openedChannel = Files.newByteChannel(path, StandardOpenOption.READ);
            openCount++;
            return openedChannel;
        }

        /// Closes this source, optionally failing the first attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (failFirstClose && closeCount == 1) {
                throw new IOException("source close failed");
            }
            closed = true;
        }

        /// Returns whether the opened channel has been closed.
        private boolean openedChannelClosed() {
            return openedChannel != null && !openedChannel.isOpen();
        }

        /// Returns the number of opened channels.
        private int openCount() {
            return openCount;
        }

        /// Returns the number of source close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether the source closed successfully.
        private boolean closed() {
            return closed;
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies TAR file systems backed by repeatable seekable channel sources.
@NotNullByDefault
final class TarArkivoChannelSourceTest {
    /// Verifies indexed reads, source ownership, file store metadata, and URI behavior.
    @Test
    void opensReadOnlyFileSystemFromChannelSource() throws IOException {
        byte[] archive = archiveBytes();
        Path archivePath = Files.createTempFile("arkivo-tar-source-", ".tar");
        TrackingSource source = new TrackingSource(archivePath, false);
        try {
            Files.write(archivePath, archive);
            try (TarArkivoFileSystem fileSystem = TarArkivoFormat.instance().open(source)) {
                Path entry = fileSystem.getPath("/value.txt");
                assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(entry));
                assertTrue(source.openedChannelClosed());
                assertEquals(0, source.closeCount());

                FileStore fileStore = Files.getFileStore(entry);
                assertEquals("tar", fileStore.name());
                assertEquals(archive.length, fileStore.getTotalSpace());
                assertThrows(UnsupportedOperationException.class, entry::toUri);
            }

            assertEquals(1, source.closeCount());
            assertTrue(source.closed());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that channel-source updates require an explicit publication target before opening a source channel.
    @Test
    void updateRequiresCommitTargetAndClosesSource() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-tar-source-options-", ".tar");
        TrackingSource source = new TrackingSource(archivePath, false);
        try {
            Files.write(archivePath, archiveBytes());
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            );

            assertThrows(IllegalArgumentException.class, () -> TarArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment)));
            assertEquals(0, source.openCount());
            assertEquals(1, source.closeCount());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies complete-rewrite updates from a repeatable source into a derived archive.
    @Test
    void updatesChannelSourceIntoDerivedArchive() throws IOException {
        Path sourcePath = Files.createTempFile("arkivo-tar-source-update-", ".tar");
        Path targetPath = Files.createTempFile("arkivo-tar-source-derived-", ".tar");
        Files.delete(targetPath);
        byte[] original = archiveBytes();
        TrackingSource source = new TrackingSource(sourcePath, false);
        try {
            Files.write(sourcePath, original);
            ArkivoCommitTarget target = (@Nullable Path sourceArchivePath) -> {
                assertNull(sourceArchivePath);
                return ArkivoCommitTarget.writeTo(targetPath).openOutput(sourceArchivePath);
            };
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                    ArkivoFileSystem.COMMIT_TARGET.key(),
                    target
            );

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment))) {
                assertFalse(fileSystem.isReadOnly());
                Files.writeString(fileSystem.getPath("/value.txt"), "updated", StandardCharsets.UTF_8);
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            assertArrayEquals(original, Files.readAllBytes(sourcePath));
            assertEquals(2, source.openCount());
            assertEquals(1, source.closeCount());
            assertTrue(source.closed());
            try (TarArkivoFileSystem derived = TarArkivoFileSystem.open(targetPath)) {
                assertEquals("updated", Files.readString(derived.getPath("/value.txt"), StandardCharsets.UTF_8));
                assertEquals("added", Files.readString(derived.getPath("/added.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(sourcePath);
        }
    }

    /// Verifies complete-rewrite updates from one owned seekable channel.
    @Test
    void updatesOwnedSeekableChannelIntoDerivedArchive() throws IOException {
        Path sourcePath = Files.createTempFile("arkivo-tar-channel-update-", ".tar");
        Path targetPath = Files.createTempFile("arkivo-tar-channel-derived-", ".tar");
        Files.delete(targetPath);
        byte[] original = archiveBytes();
        SeekableByteChannel channel = null;
        try {
            Files.write(sourcePath, original);
            channel = Files.newByteChannel(sourcePath, StandardOpenOption.READ);
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                    ArkivoFileSystem.COMMIT_TARGET.key(),
                    ArkivoCommitTarget.writeTo(targetPath)
            );

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(channel, ArchiveOptions.fromEnvironment(environment))) {
                Files.delete(fileSystem.getPath("/value.txt"));
                Files.writeString(fileSystem.getPath("/replacement.txt"), "replacement", StandardCharsets.UTF_8);
            }

            assertFalse(channel.isOpen());
            assertArrayEquals(original, Files.readAllBytes(sourcePath));
            try (TarArkivoFileSystem derived = TarArkivoFileSystem.open(targetPath)) {
                assertFalse(Files.exists(derived.getPath("/value.txt")));
                assertEquals(
                        "replacement",
                        Files.readString(derived.getPath("/replacement.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            if (channel != null) {
                channel.close();
            }
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(sourcePath);
        }
    }

    /// Verifies cleanup and source preservation when channel-source publication setup fails.
    @Test
    void failedChannelSourceCommitPreservesSourceAndClosesOwnership() throws IOException {
        Path sourcePath = Files.createTempFile("arkivo-tar-source-failed-commit-", ".tar");
        byte[] original = archiveBytes();
        TrackingSource source = new TrackingSource(sourcePath, false);
        try {
            Files.write(sourcePath, original);
            ArkivoCommitTarget failingTarget = (@Nullable Path sourceArchivePath) -> {
                assertNull(sourceArchivePath);
                throw new IOException("channel commit target failed");
            };
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                    ArkivoFileSystem.COMMIT_TARGET.key(),
                    failingTarget
            );

            TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment));
            Files.writeString(fileSystem.getPath("/value.txt"), "changed", StandardCharsets.UTF_8);
            IOException exception = assertThrows(IOException.class, fileSystem::close);

            assertEquals("channel commit target failed", exception.getMessage());
            assertArrayEquals(original, Files.readAllBytes(sourcePath));
            assertFalse(fileSystem.isOpen());
            assertEquals(1, source.closeCount());
            assertTrue(source.closed());
        } finally {
            Files.deleteIfExists(sourcePath);
        }
    }

    /// Verifies that an unchanged channel-source update does not open its commit target.
    @Test
    void unchangedChannelSourceUpdateDoesNotPublish() throws IOException {
        Path sourcePath = Files.createTempFile("arkivo-tar-source-unchanged-", ".tar");
        Path targetPath = Files.createTempFile("arkivo-tar-source-unpublished-", ".tar");
        Files.delete(targetPath);
        TrackingSource source = new TrackingSource(sourcePath, false);
        try {
            Files.write(sourcePath, archiveBytes());
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                    ArkivoFileSystem.COMMIT_TARGET.key(),
                    ArkivoCommitTarget.writeTo(targetPath)
            );

            try (TarArkivoFileSystem ignored = TarArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment))) {
            }

            assertFalse(Files.exists(targetPath));
            assertEquals(1, source.closeCount());
            assertTrue(source.closed());
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(sourcePath);
        }
    }

    /// Verifies source cleanup after an open failure and retry after a close failure.
    @Test
    void cleansUpFailedOpenAndRetriesFailedClose() throws IOException {
        Path invalidPath = Files.createTempFile("arkivo-tar-source-invalid-", ".tar");
        TrackingSource invalidSource = new TrackingSource(invalidPath, false);
        try {
            Files.write(invalidPath, new byte[]{1, 2, 3});
            assertThrows(IOException.class, () -> TarArkivoFileSystem.open(invalidSource));
            assertTrue(invalidSource.openedChannelClosed());
            assertEquals(1, invalidSource.closeCount());
        } finally {
            Files.deleteIfExists(invalidPath);
        }

        Path archivePath = Files.createTempFile("arkivo-tar-source-close-", ".tar");
        TrackingSource source = new TrackingSource(archivePath, true);
        try {
            Files.write(archivePath, archiveBytes());
            TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(source);

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

    /// Creates a TAR archive used by channel-source tests.
    private static byte[] archiveBytes() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
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

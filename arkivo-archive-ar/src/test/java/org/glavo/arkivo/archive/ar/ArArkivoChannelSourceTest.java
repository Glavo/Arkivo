// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            try (ArArkivoFileSystem fileSystem = ArArkivoFormat.instance().open(source)) {
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

    /// Verifies that channel-source updates require an explicit publication target before opening a source channel.
    @Test
    void updateRequiresCommitTargetAndClosesSource() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-ar-source-options-", ".a");
        TrackingSource source = new TrackingSource(archivePath, false);
        try {
            Files.write(archivePath, archiveBytes());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ArArkivoFileSystem.update(source, ArArchiveOptions.UPDATE_DEFAULTS)
            );
            assertEquals(0, source.openCount());
            assertEquals(1, source.closeCount());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies complete-rewrite updates from a repeatable source into a derived archive.
    @Test
    void updatesChannelSourceIntoDerivedArchive() throws IOException {
        Path sourcePath = Files.createTempFile("arkivo-ar-source-update-", ".a");
        Path targetPath = Files.createTempFile("arkivo-ar-source-derived-", ".a");
        Files.delete(targetPath);
        byte[] original = archiveBytes();
        TrackingSource source = new TrackingSource(sourcePath, false);
        try {
            Files.write(sourcePath, original);
            ArkivoCommitTarget target = (@Nullable Path sourceArchivePath) -> {
                assertNull(sourceArchivePath);
                return ArkivoCommitTarget.writeTo(targetPath).openOutput(sourceArchivePath);
            };
            ArArchiveOptions.Update options = ArArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withCommitTarget(target)
            );

            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(source, options)) {
                assertFalse(fileSystem.isReadOnly());
                Files.writeString(fileSystem.getPath("/value.txt"), "updated", StandardCharsets.UTF_8);
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            assertArrayEquals(original, Files.readAllBytes(sourcePath));
            assertEquals(1, source.openCount());
            assertEquals(1, source.closeCount());
            assertTrue(source.closed());
            try (ArArkivoFileSystem derived = ArArkivoFileSystem.open(targetPath)) {
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
        Path sourcePath = Files.createTempFile("arkivo-ar-channel-update-", ".a");
        Path targetPath = Files.createTempFile("arkivo-ar-channel-derived-", ".a");
        Files.delete(targetPath);
        byte[] original = archiveBytes();
        SeekableByteChannel channel = null;
        try {
            Files.write(sourcePath, original);
            channel = Files.newByteChannel(sourcePath, StandardOpenOption.READ);
            ArArchiveOptions.Update options = ArArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath))
            );

            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(channel, options)) {
                Files.delete(fileSystem.getPath("/value.txt"));
                Files.writeString(fileSystem.getPath("/replacement.txt"), "replacement", StandardCharsets.UTF_8);
            }

            assertFalse(channel.isOpen());
            assertArrayEquals(original, Files.readAllBytes(sourcePath));
            try (ArArkivoFileSystem derived = ArArkivoFileSystem.open(targetPath)) {
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
        Path sourcePath = Files.createTempFile("arkivo-ar-source-failed-commit-", ".a");
        byte[] original = archiveBytes();
        TrackingSource source = new TrackingSource(sourcePath, false);
        try {
            Files.write(sourcePath, original);
            ArkivoCommitTarget failingTarget = (@Nullable Path sourceArchivePath) -> {
                assertNull(sourceArchivePath);
                throw new IOException("channel commit target failed");
            };
            ArArchiveOptions.Update options = ArArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withCommitTarget(failingTarget)
            );

            ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(source, options);
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
        Path sourcePath = Files.createTempFile("arkivo-ar-source-unchanged-", ".a");
        Path targetPath = Files.createTempFile("arkivo-ar-source-unpublished-", ".a");
        Files.delete(targetPath);
        TrackingSource source = new TrackingSource(sourcePath, false);
        try {
            Files.write(sourcePath, archiveBytes());
            ArArchiveOptions.Update options = ArArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath))
            );

            try (ArArkivoFileSystem ignored = ArArkivoFileSystem.update(source, options)) {
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
            var writerEntry264 = writer.beginFile("value.txt");
            try (OutputStream body = writerEntry264.openOutputStream()) {
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

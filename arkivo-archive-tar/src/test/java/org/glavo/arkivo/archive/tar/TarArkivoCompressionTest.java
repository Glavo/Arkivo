// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compressed TAR file system creation, detection, reading, and update behavior.
@NotNullByDefault
final class TarArkivoCompressionTest {
    /// Verifies gzip creation, automatic read detection, and compression-preserving update.
    @Test
    void createsReadsAndUpdatesAutoDetectedGzipArchive() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-tar-gzip-", ".tar.gz");
        try {
            createCompressedArchive(archivePath, "gzip");

            @Nullable CompressionFormat detected = CompressionFormats.detect(archivePath);
            assertNotNull(detected);
            assertEquals("gzip", detected.name());
            assertArchiveContent(archivePath);

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archivePath)) {
                Files.writeString(
                        fileSystem.getPath("/value.txt"),
                        "updated",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            @Nullable CompressionFormat updatedFormat = CompressionFormats.detect(archivePath);
            assertNotNull(updatedFormat);
            assertEquals("gzip", updatedFormat.name());
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                assertEquals("updated", Files.readString(fileSystem.getPath("/value.txt")));
                assertEquals("added", Files.readString(fileSystem.getPath("/added.txt")));
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies automatic compression detection through a repeatable channel source.
    @Test
    void opensAutoDetectedGzipArchiveFromChannelSource() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-tar-gzip-source-", ".tar.gz");
        CountingSource source = new CountingSource(archivePath);
        try {
            createCompressedArchive(archivePath, "gzip");
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(source)) {
                assertEquals("initial", Files.readString(fileSystem.getPath("/value.txt")));
                assertEquals(2, source.openCount());
            }
            assertTrue(source.closed());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that an auto-detected compressed channel source keeps its codec in a derived update.
    @Test
    void updatesAutoDetectedGzipChannelSourceWithoutChangingSource() throws IOException {
        Path sourcePath = Files.createTempFile("arkivo-tar-gzip-channel-source-", ".tar.gz");
        Path targetPath = Files.createTempFile("arkivo-tar-gzip-channel-derived-", ".tar.gz");
        Files.delete(targetPath);
        CountingSource source = new CountingSource(sourcePath);
        try {
            createCompressedArchive(sourcePath, "gzip");
            byte[] original = Files.readAllBytes(sourcePath);
            TarArchiveOptions.Update options = TarArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath))
            );

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(source, options)) {
                Files.writeString(fileSystem.getPath("/value.txt"), "updated", StandardCharsets.UTF_8);
            }

            assertArrayEquals(original, Files.readAllBytes(sourcePath));
            assertEquals(2, source.openCount());
            assertTrue(source.closed());
            @Nullable CompressionFormat detected = CompressionFormats.detect(targetPath);
            assertNotNull(detected);
            assertEquals("gzip", detected.name());
            try (TarArkivoFileSystem derived = TarArkivoFileSystem.open(targetPath)) {
                assertEquals("updated", Files.readString(derived.getPath("/value.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(sourcePath);
        }
    }

    /// Verifies channel-source updates with an explicit codec that has no reliable stream signature.
    @Test
    void updatesChannelSourceWithExplicitRawDeflateCompression() throws IOException {
        Path sourcePath = Files.createTempFile("arkivo-tar-deflate-channel-source-", ".tar.deflate");
        Path targetPath = Files.createTempFile("arkivo-tar-deflate-channel-derived-", ".tar.deflate");
        Files.delete(targetPath);
        CountingSource source = new CountingSource(sourcePath);
        try {
            createCompressedArchive(sourcePath, "deflate");
            byte[] original = Files.readAllBytes(sourcePath);
            TarArchiveOptions.Update options = TarArchiveOptions.UPDATE_DEFAULTS
                    .withCommon(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
                    .withCompression(CompressionFormats.require("deflate").defaultCodec());

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(source, options)) {
                Files.writeString(fileSystem.getPath("/value.txt"), "updated", StandardCharsets.UTF_8);
            }

            assertArrayEquals(original, Files.readAllBytes(sourcePath));
            assertEquals(1, source.openCount());
            assertTrue(source.closed());
            try (TarArkivoFileSystem derived = TarArkivoFileSystem.open(
                    targetPath,
                    TarArchiveOptions.READ_DEFAULTS.withCompression(
                            CompressionFormats.require("deflate").defaultCodec()
                    )
            )) {
                assertEquals("updated", Files.readString(derived.getPath("/value.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(sourcePath);
        }
    }

    /// Verifies explicit selection of a codec without a reliable stream signature.
    @Test
    void supportsExplicitRawDeflateCompression() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-tar-deflate-", ".tar.deflate");
        try {
            createCompressedArchive(archivePath, "deflate");

            assertThrows(IOException.class, () -> openAndClose(archivePath, TarArchiveOptions.READ_DEFAULTS));
            TarArchiveOptions.Read options = TarArchiveOptions.READ_DEFAULTS.withCompression(
                    CompressionFormats.require("deflate").defaultCodec()
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, options)) {
                assertEquals("initial", Files.readString(fileSystem.getPath("/value.txt")));
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that a valid zlib prefix in a raw TAR entry name does not cause false compression detection.
    @Test
    void rejectsFalsePositiveCompressionBeforeOpeningRawTar() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-tar-zlib-prefix-", ".tar");
        String entryName = "x\u0001value.txt";
        try {
            Files.deleteIfExists(archivePath);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.create(archivePath)) {
                Files.writeString(fileSystem.getPath("/" + entryName), "raw", StandardCharsets.UTF_8);
            }

            @Nullable CompressionFormat detected = CompressionFormats.detect(archivePath);
            assertNotNull(detected);
            assertEquals("zlib", detected.name());
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                assertEquals("raw", Files.readString(fileSystem.getPath("/" + entryName)));
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Creates a compressed forward-only TAR archive.
    private static void createCompressedArchive(Path archivePath, String formatName) throws IOException {
        Files.deleteIfExists(archivePath);
        TarArchiveOptions.Create options = TarArchiveOptions.CREATE_DEFAULTS.withCompression(
                CompressionFormats.require(formatName).defaultCodec()
        );
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.create(archivePath, options)) {
            Files.writeString(fileSystem.getPath("/value.txt"), "initial", StandardCharsets.UTF_8);
        }
    }

    /// Verifies the initial file in an automatically detected compressed archive.
    private static void assertArchiveContent(Path archivePath) throws IOException {
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
            assertEquals("initial", Files.readString(fileSystem.getPath("/value.txt")));
            assertArrayEquals(
                    "initial".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(fileSystem.getPath("/value.txt"))
            );
        }
    }

    /// Opens and closes a TAR file system so failed exception assertions cannot leak it.
    private static void openAndClose(Path archivePath, TarArchiveOptions.Read options) throws IOException {
        try (TarArkivoFileSystem ignored = TarArkivoFileSystem.open(archivePath, options)) {
            assertTrue(ignored.isOpen());
        }
    }

    /// Opens tracked channels for one path-backed archive.
    @NotNullByDefault
    private static final class CountingSource implements ArkivoSeekableChannelSource {
        /// The archive path.
        private final Path path;

        /// The number of opened channels.
        private int openCount;

        /// Whether the source has closed.
        private boolean closed;

        /// Creates a counting source.
        private CountingSource(Path path) {
            this.path = path;
        }

        /// Opens a fresh readable archive channel.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            openCount++;
            return Files.newByteChannel(path, StandardOpenOption.READ);
        }

        /// Closes this source.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns the number of opened channels.
        private int openCount() {
            return openCount;
        }

        /// Returns whether this source has closed.
        private boolean closed() {
            return closed;
        }
    }
}

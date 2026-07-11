// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

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

            @Nullable CompressionCodec detected = CompressionCodecs.detect(archivePath);
            assertNotNull(detected);
            assertEquals("gzip", detected.name());
            assertArchiveContent(archivePath);

            Map<String, Object> updateEnvironment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, updateEnvironment)) {
                Files.writeString(
                        fileSystem.getPath("/value.txt"),
                        "updated",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            @Nullable CompressionCodec updatedCodec = CompressionCodecs.detect(archivePath);
            assertNotNull(updatedCodec);
            assertEquals("gzip", updatedCodec.name());
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

    /// Verifies explicit selection of a codec without a reliable stream signature.
    @Test
    void supportsExplicitRawDeflateCompression() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-tar-deflate-", ".tar.deflate");
        try {
            createCompressedArchive(archivePath, "deflate");

            assertThrows(IOException.class, () -> openAndClose(archivePath, Map.of()));
            Map<String, Object> environment = Map.of(
                    TarArkivoFileSystem.COMPRESSION.key(),
                    "deflate"
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, environment)) {
                assertEquals("initial", Files.readString(fileSystem.getPath("/value.txt")));
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies that unknown compression names are rejected deterministically.
    @Test
    void rejectsUnknownCompressionCodec() throws IOException {
        Path archivePath = Files.createTempFile("arkivo-tar-codec-", ".tar");
        try {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> openAndClose(
                            archivePath,
                            Map.of(TarArkivoFileSystem.COMPRESSION.key(), "missing-codec")
                    )
            );
            assertTrue(exception.getMessage().contains("missing-codec"));
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
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    )
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, environment)) {
                Files.writeString(fileSystem.getPath("/" + entryName), "raw", StandardCharsets.UTF_8);
            }

            @Nullable CompressionCodec detected = CompressionCodecs.detect(archivePath);
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
    private static void createCompressedArchive(Path archivePath, String codecName) throws IOException {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ),
                TarArkivoFileSystem.COMPRESSION.key(),
                codecName
        );
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath, environment)) {
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
    private static void openAndClose(Path archivePath, Map<String, ?> environment) throws IOException {
        try (TarArkivoFileSystem ignored = TarArkivoFileSystem.open(archivePath, environment)) {
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

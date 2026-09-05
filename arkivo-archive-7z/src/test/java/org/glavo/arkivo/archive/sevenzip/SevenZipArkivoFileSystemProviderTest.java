// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the NIO provider lifecycle and URI mapping for 7z file systems.
@NotNullByDefault
public final class SevenZipArkivoFileSystemProviderTest {
    /// The isolated directory used for archive fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies URI registration, lookup, path resolution, and deregistration.
    @Test
    public void managesUriBackedFileSystemLifecycle() throws IOException {
        Path archivePath = Files.write(
                temporaryDirectory.resolve("minimal.7z"),
                SevenZipTestArchiveFixtures.minimalArchive()
        ).toAbsolutePath().normalize();
        SevenZipArkivoFileSystemProvider provider = new SevenZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(SevenZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI rootUri = URI.create(fileSystemUri + "!/");

        try {
            try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
                assertEquals("/", provider.getPath(rootUri).toString());
                assertEquals(rootUri, fileSystem.getPath("/").toUri());
                assertThrows(
                        FileSystemAlreadyExistsException.class,
                        () -> provider.newFileSystem(fileSystemUri, Map.of())
                );
            }
            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
        } finally {
            try {
                provider.getFileSystem(fileSystemUri).close();
            } catch (FileSystemNotFoundException ignored) {
                // The normal path already deregisters the file system.
            }
        }
    }

    /// Verifies the path-based provider factory and same-provider copy dispatch.
    @Test
    public void opensPathAndCopiesWithinWritableFileSystem() throws IOException {
        Path archivePath = temporaryDirectory.resolve("copy.7z");
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(archivePath)) {
            Files.writeString(fileSystem.getPath("/source.txt"), "payload", StandardCharsets.UTF_8);
        }

        SevenZipArkivoFileSystemProvider provider = new SevenZipArkivoFileSystemProvider();
        assertEquals(SevenZipArkivoFileSystemProvider.SCHEME, provider.getScheme());
        try (SevenZipArkivoFileSystem fileSystem = provider.newFileSystem(archivePath, Map.of())) {
            assertEquals(
                    "payload",
                    Files.readString(fileSystem.getPath("/source.txt"), StandardCharsets.UTF_8)
            );
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.update(archivePath)) {
            Files.copy(fileSystem.getPath("/source.txt"), fileSystem.getPath("/copy.txt"));
            assertTrue(Files.exists(fileSystem.getPath("/copy.txt")));
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
            assertEquals(
                    "payload",
                    Files.readString(fileSystem.getPath("/copy.txt"), StandardCharsets.UTF_8)
            );
        }
    }

    /// Verifies path-backed split factories reject non-positive sizes before touching the archive path.
    @Test
    public void rejectsNonPositivePathSplitSizes() {
        Path archivePath = temporaryDirectory.resolve("invalid-split.7z.001");

        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystem.create(
                        archivePath,
                        0L,
                        SevenZipArchiveOptions.CREATE_DEFAULTS
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystem.update(
                        archivePath,
                        -1L,
                        SevenZipArchiveOptions.UPDATE_DEFAULTS
                )
        );
        assertFalse(Files.exists(archivePath));
    }
}

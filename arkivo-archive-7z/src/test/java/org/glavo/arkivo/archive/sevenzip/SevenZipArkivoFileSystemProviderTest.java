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
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}

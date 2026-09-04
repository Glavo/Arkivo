// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
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
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the NIO provider lifecycle and URI mapping for ZIP file systems.
@NotNullByDefault
public final class ZipArkivoFileSystemProviderTest {
    /// The isolated directory used for archive fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies URI registration, entry resolution, duplicate detection, and deregistration.
    @Test
    public void managesReadOnlyUriFileSystemLifecycle() throws IOException {
        Path archivePath = ZipTestArchiveFixtures.writeDeflatedArchive(
                temporaryDirectory.resolve("archive.zip")
        );
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI entryUri = URI.create(fileSystemUri + "!/dir/hello.txt");

        try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
            assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
            Path entry = provider.getPath(entryUri);
            assertEquals(entryUri, entry.toUri());
            assertEquals("hello", Files.readString(entry, StandardCharsets.UTF_8));
            assertThrows(
                    FileSystemAlreadyExistsException.class,
                    () -> provider.newFileSystem(fileSystemUri, Map.of())
            );
        }

        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
    }

    /// Verifies that closing a writable URI file system deregisters it immediately.
    @Test
    public void deregistersWritableFileSystemOnClose() throws IOException {
        Path archivePath = temporaryDirectory.resolve("writable.zip");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        Map<String, Object> environment = Map.of(
                "arkivo.openOptions",
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );

        try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, environment)) {
            assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
        }

        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
    }
}

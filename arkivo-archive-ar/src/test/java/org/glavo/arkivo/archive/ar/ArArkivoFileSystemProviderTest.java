// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the NIO provider lifecycle and URI mapping for AR file systems.
@NotNullByDefault
public final class ArArkivoFileSystemProviderTest {
    /// The isolated directory used for archive fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies URI registration, entry resolution, duplicate detection, and deregistration.
    @Test
    public void managesUriBackedFileSystemLifecycle() throws IOException {
        byte[] content = "provider".getBytes(StandardCharsets.UTF_8);
        Path archivePath = temporaryDirectory.resolve("archive.a").toAbsolutePath().normalize();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
            try (OutputStream body = writer.beginFile("dir/provider.txt").openOutputStream()) {
                body.write(content);
            }
        }

        ArArkivoFileSystemProvider provider = new ArArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ArArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI entryUri = URI.create(fileSystemUri + "!/dir/provider.txt");

        try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
            assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
            Path entry = provider.getPath(entryUri);
            assertEquals(entryUri, entry.toUri());
            assertArrayEquals(content, Files.readAllBytes(entry));
            assertThrows(
                    FileSystemAlreadyExistsException.class,
                    () -> provider.newFileSystem(fileSystemUri, Map.of())
            );
        }

        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));

        try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
            assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
            assertArrayEquals(content, Files.readAllBytes(provider.getPath(entryUri)));
        }

        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
    }
}

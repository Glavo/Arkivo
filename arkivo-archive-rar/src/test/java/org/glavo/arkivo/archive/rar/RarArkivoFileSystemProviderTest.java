// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the NIO provider lifecycle and URI mapping for RAR file systems.
@NotNullByDefault
public final class RarArkivoFileSystemProviderTest {
    /// The isolated directory used for archive fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Rejects nonexistent and nondirectory components before resolving a parent component.
    @ParameterizedTest
    @CsvSource({"missing/../entry,false", "missing/../entry,true", "entry/../entry,false", "entry/../entry,true"})
    public void validatesComponentsForMetadataQueries(String name, boolean fileStore) throws IOException {
        Path source = Files.write(temporaryDirectory.resolve("components.rar"),
                RarTestArchiveFixtures.storedArchive("entry", new byte[]{1}));
        try (var fileSystem = RarArkivoFileSystem.open(source)) {
            Path path = fileSystem.getPath(name);
            Class<? extends IOException> expected = name.startsWith("missing/")
                    ? NoSuchFileException.class : NotDirectoryException.class;
            assertThrows(expected, () -> {
                if (fileStore) {
                    Files.getFileStore(path);
                } else {
                    Files.isHidden(path);
                }
            });
        }
    }

    /// Preserves source dot components and registers equivalent percent-escaped spellings under one key.
    @Test
    public void preservesSourceUriComponents() throws IOException {
        Files.createDirectory(temporaryDirectory.resolve("sub"));
        Path source = temporaryDirectory.resolve("sub/../source.rar");
        Files.write(source, RarTestArchiveFixtures.storedArchive("entry", new byte[]{1, 2, 3}));
        RarArkivoFileSystemProvider provider = new RarArkivoFileSystemProvider();
        URI literal = URI.create(provider.getScheme() + ':' + source.toUri().toASCIIString() + "!/entry");
        URI encoded = URI.create(literal.toASCIIString().replace("/../", "/%2e%2E/"));
        try (var direct = provider.newFileSystem(source, Map.of())) {
            assertEquals(literal, direct.getPath("/entry").toUri());
        }
        try (var registered = provider.newFileSystem(encoded, Map.of())) {
            Path entry = registered.getPath("/entry");
            assertEquals(entry, provider.getPath(entry.toUri()));
            assertEquals(registered, provider.getFileSystem(literal));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(provider.getPath(entry.toUri())));
            assertThrows(FileSystemAlreadyExistsException.class, () -> provider.newFileSystem(literal, Map.of()));
        }
    }

    /// Verifies URI registration, entry resolution, duplicate detection, and deregistration.
    @Test
    public void managesUriBackedFileSystemLifecycle() throws IOException {
        byte[] content = "provider".getBytes(StandardCharsets.UTF_8);
        Path archivePath = Files.write(
                temporaryDirectory.resolve("archive.rar"),
                RarTestArchiveFixtures.storedArchive("dir/provider.txt", content)
        ).toAbsolutePath().normalize();
        RarArkivoFileSystemProvider provider = new RarArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(RarArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
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

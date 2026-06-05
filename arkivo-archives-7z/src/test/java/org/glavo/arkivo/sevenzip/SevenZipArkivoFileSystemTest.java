// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic 7z Arkivo file system behavior.
@NotNullByDefault
public final class SevenZipArkivoFileSystemTest {
    /// Verifies that a 7z file system can be opened from an archive path.
    @Test
    public void openPath() throws IOException {
        Path archivePath = Path.of("sample.7z");

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
            assertEquals(SevenZipArkivoFileSystemProvider.instance(), fileSystem.provider());
            assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
            assertEquals(true, fileSystem.isOpen());
            assertEquals(true, fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("basic"));
            assertEquals("7z", Files.getFileStore(fileSystem.getPath("/")).type());
        }
    }

    /// Verifies basic 7z path operations.
    @Test
    public void paths() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(Path.of("sample.7z"))) {
            Path path = fileSystem.getPath("/a/b/../c.txt");

            assertEquals("/a/b/../c.txt", path.toString());
            assertEquals("c.txt", path.getFileName().toString());
            assertEquals("/a/b/..", path.getParent().toString());
            assertEquals("/a/c.txt", path.normalize().toString());
            assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
            assertEquals(true, fileSystem.getPathMatcher("glob:**/*.txt").matches(path));
        }
    }

    /// Verifies root directory and basic attributes.
    @Test
    public void rootDirectory() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(Path.of("sample.7z"))) {
            Path root = fileSystem.getPath("/");
            BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class);
            ArrayList<Path> children = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path child : stream) {
                    children.add(child);
                }
            }

            assertEquals(true, attributes.isDirectory());
            assertEquals(List.of(), children);
            assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(
                    fileSystem.getPath("/missing"),
                    BasicFileAttributes.class
            ));
        }
    }

    /// Verifies that entry data reading is explicitly not implemented yet.
    @Test
    public void entryDataReadingIsNotImplementedYet() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(Path.of("sample.7z"))) {
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newInputStream(fileSystem.getPath("/")).close()
            );
        }
    }

    /// Verifies that a 7z file system can be opened and resolved through provider URIs.
    @Test
    public void openUri() throws IOException {
        Path archivePath = Path.of("sample.7z").toAbsolutePath().normalize();
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
                // The test closes the file system in the normal path.
            }
        }
    }
}

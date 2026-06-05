// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic 7z Arkivo file system behavior.
@NotNullByDefault
public final class SevenZipArkivoFileSystemTest {
    /// Verifies that a 7z file system can be opened from an archive path.
    @Test
    public void openPath() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertEquals(SevenZipArkivoFileSystemProvider.instance(), fileSystem.provider());
                assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
                assertEquals(true, fileSystem.isOpen());
                assertEquals(true, fileSystem.isReadOnly());
                assertEquals("/", fileSystem.getSeparator());
                assertEquals(0, fileSystem.majorVersion());
                assertEquals(4, fileSystem.minorVersion());
                assertEquals(0L, fileSystem.nextHeaderOffset());
                assertEquals(0L, fileSystem.nextHeaderSize());
                assertEquals(0L, fileSystem.nextHeaderCrc32());
                assertEquals(true, fileSystem.supportedFileAttributeViews().contains("basic"));
                assertEquals("7z", Files.getFileStore(fileSystem.getPath("/")).type());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies basic 7z path operations.
    @Test
    public void paths() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path path = fileSystem.getPath("/a/b/../c.txt");

                assertEquals("/a/b/../c.txt", path.toString());
                assertEquals("c.txt", path.getFileName().toString());
                assertEquals("/a/b/..", path.getParent().toString());
                assertEquals("/a/c.txt", path.normalize().toString());
                assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
                assertEquals(true, fileSystem.getPathMatcher("glob:**/*.txt").matches(path));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies root directory and basic attributes.
    @Test
    public void rootDirectory() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
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
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that entry data reading is explicitly not implemented yet.
    @Test
    public void entryDataReadingIsNotImplementedYet() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> Files.newInputStream(fileSystem.getPath("/")).close()
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z file system can be opened and resolved through provider URIs.
    @Test
    public void openUri() throws IOException {
        Path archivePath = createMinimalArchive().toAbsolutePath().normalize();
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
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that invalid 7z signatures are rejected.
    @Test
    public void invalidSignature() throws IOException {
        Path archivePath = createTemporaryArchivePath("invalid-");
        Files.write(archivePath, new byte[32]);

        try {
            assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty next header CRC is validated.
    @Test
    public void nonEmptyNextHeader() throws IOException {
        byte[] nextHeader = new byte[]{0};
        Path archivePath = createTemporaryArchivePath("next-header-");
        Files.write(archivePath, archive(nextHeader, crc32(nextHeader)));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertEquals(0L, fileSystem.nextHeaderOffset());
                assertEquals(1L, fileSystem.nextHeaderSize());
                assertEquals(crc32(nextHeader), fileSystem.nextHeaderCrc32());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that invalid next header CRC values are rejected.
    @Test
    public void invalidNextHeaderCrc() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-next-header-");
        Files.write(archivePath, archive(new byte[]{0}, 1L));

        try {
            assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Creates a temporary minimal 7z archive under the module build directory.
    private static Path createMinimalArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("minimal-");
        Files.write(archivePath, minimalArchive());
        return archivePath;
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-7z-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sample.7z");
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// Returns a minimal 7z archive with an empty next header.
    private static byte[] minimalArchive() {
        return archive(new byte[0], 0L);
    }

    /// Returns a 7z archive with the given next header and expected next header CRC-32.
    private static byte[] archive(byte[] nextHeader, long nextHeaderCrc32) {
        ByteBuffer buffer = ByteBuffer.allocate(32 + nextHeader.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(0);
        buffer.putLong(nextHeader.length);
        buffer.putInt((int) nextHeaderCrc32);
        buffer.put(nextHeader);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }

    /// Returns the unsigned CRC-32 value of the given content.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }
}

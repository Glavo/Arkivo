// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the complete generated UDIF-to-HFS-Plus-to-NIO file-system path.
@NotNullByDefault
final class DMGArkivoFileSystemTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Reads files, follows symbolic links, enumerates directories, and exposes HFS Plus attributes.
    @Test
    void readsGeneratedHFSPlusFileSystem() throws IOException {
        Path imagePath = createImage("filesystem.dmg");
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/hello.txt");
            Path link = fileSystem.getPath("/link");

            assertTrue(fileSystem.isReadOnly());
            assertEquals(DMGPartitionScheme.RAW, fileSystem.partition().scheme());
            assertEquals("hello", Files.readString(file));
            assertEquals("hello", Files.readString(link));
            assertEquals(fileSystem.getPath("hello.txt"), Files.readSymbolicLink(link));
            try (var children = Files.list(root)) {
                assertEquals(List.of("hello.txt", "link"), children
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList());
            }

            BasicFileAttributes followed = Files.readAttributes(link, BasicFileAttributes.class);
            BasicFileAttributes notFollowed = Files.readAttributes(
                    link,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            assertTrue(followed.isRegularFile());
            assertEquals(5L, followed.size());
            assertTrue(notFollowed.isSymbolicLink());
            assertEquals(9L, notFollowed.size());

            PosixFileAttributes attributes = Files.readAttributes(file, PosixFileAttributes.class);
            assertEquals("501", attributes.owner().getName());
            assertEquals("20", attributes.group().getName());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), attributes.permissions());
            assertEquals(5L, Files.getAttribute(file, "basic:size"));

            FileStore store = Files.getFileStore(root);
            assertEquals("hfsplus", store.type());
            assertTrue(store.isReadOnly());
            assertEquals(16L * DMGTestFixtures.SECTOR_SIZE, store.getTotalSpace());
            assertEquals(7L * DMGTestFixtures.SECTOR_SIZE, store.getUnallocatedSpace());
            assertEquals(0L, store.getUsableSpace());
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.writeString(file, "changed"));
        }
    }

    /// Selects an explicit partition and rejects an index outside the discovered list.
    @Test
    void appliesPartitionSelection() throws IOException {
        Path imagePath = createImage("partition-selection.dmg");
        DMGArchiveOptions.Read explicit = DMGArchiveOptions.READ_DEFAULTS.withPartitionIndex(0);
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath, explicit)) {
            assertEquals(0, fileSystem.partition().index());
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }

        DMGArchiveOptions.Read outside = explicit.withPartitionIndex(1);
        IOException exception = assertThrows(
                IOException.class,
                () -> DMGArkivoFileSystem.open(imagePath, outside)
        );
        assertTrue(exception.getMessage().contains("outside the discovered partition list"));
    }

    /// Opens an HFSX signature-and-version pair through the same read-only file-system implementation.
    @Test
    void readsGeneratedHFSXFileSystem() throws IOException {
        byte[] disk = createHFSPlusDisk();
        int volumeHeader = 2 * DMGTestFixtures.SECTOR_SIZE;
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader, (short) 0x4858);
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader + 2, (short) 5);
        Path imagePath = writeRawImage(temporaryDirectory.resolve("hfsx.dmg"), disk);

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }
    }

    /// Applies entry-count and entry-size limits while indexing the HFS Plus catalog.
    @Test
    void appliesCatalogEntryLimits() throws IOException {
        Path imagePath = createImage("entry-limits.dmg");
        ArchiveReadLimits countLimits = ArchiveReadLimits.builder().maximumEntryCount(1L).build();
        DMGArchiveOptions.Read countOptions = DMGArchiveOptions.READ_DEFAULTS.withCommon(
                ArchiveReadOptions.DEFAULT.withLimits(countLimits)
        );
        ArkivoReadLimitException countException = assertThrows(
                ArkivoReadLimitException.class,
                () -> DMGArkivoFileSystem.open(imagePath, countOptions)
        );
        assertEquals(ArkivoReadLimitKind.ENTRY_COUNT, countException.kind());
        assertEquals(2L, countException.actual());

        ArchiveReadLimits sizeLimits = ArchiveReadLimits.builder().maximumEntrySize(8L).build();
        DMGArchiveOptions.Read sizeOptions = DMGArchiveOptions.READ_DEFAULTS.withCommon(
                ArchiveReadOptions.DEFAULT.withLimits(sizeLimits)
        );
        ArkivoReadLimitException sizeException = assertThrows(
                ArkivoReadLimitException.class,
                () -> DMGArkivoFileSystem.open(imagePath, sizeOptions)
        );
        assertEquals(ArkivoReadLimitKind.ENTRY_SIZE, sizeException.kind());
        assertEquals("link", sizeException.entryPath());
        assertEquals(9L, sizeException.actual());
    }

    /// Registers provider-URI file systems and unregisters them when closed.
    @Test
    void supportsProviderURILifecycle() throws IOException {
        Path imagePath = createImage("provider-uri.dmg");
        URI fileSystemUri = URI.create(
                DMGArkivoFormat.instance().uriScheme() + ":" + imagePath.toUri().toASCIIString()
        );
        URI entryUri = URI.create(fileSystemUri + "!/hello.txt");

        try (FileSystem fileSystem = FileSystems.newFileSystem(
                fileSystemUri,
                Map.of("arkivo.dmg.partitionIndex", 0)
        )) {
            assertEquals(fileSystem, FileSystems.getFileSystem(fileSystemUri));
            assertEquals("hello", Files.readString(Path.of(entryUri)));
            assertThrows(
                    FileSystemAlreadyExistsException.class,
                    () -> FileSystems.newFileSystem(fileSystemUri, Map.of())
            );
        }
        assertThrows(FileSystemNotFoundException.class, () -> FileSystems.getFileSystem(fileSystemUri));
    }

    /// Closes channels owned by a file system and rejects subsequent path access.
    @Test
    void closesManagedResources() throws IOException {
        Path imagePath = createImage("close.dmg");
        DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath);
        Path file = fileSystem.getPath("/hello.txt");
        SeekableByteChannel channel = Files.newByteChannel(file);

        fileSystem.close();
        fileSystem.close();
        assertFalse(fileSystem.isOpen());
        assertFalse(channel.isOpen());
        assertThrows(ClosedFileSystemException.class, () -> Files.size(file));
    }

    /// Writes the shared generated HFS Plus disk as one flattened UDIF image.
    private Path createImage(String name) throws IOException {
        return writeRawImage(temporaryDirectory.resolve(name), createHFSPlusDisk());
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests file-system-level metadata exposed by indexed 7z archives.
@NotNullByDefault
public final class SevenZipArkivoFileSystemMetadataTest {
    /// The isolated directory used for archive fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies that opening a minimal archive exposes its format and file-store metadata.
    @Test
    public void exposesArchiveAndFileStoreMetadata() throws IOException {
        Path archivePath = writeMinimalArchive();

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
            assertEquals(SevenZipArkivoFileSystemProvider.instance(), fileSystem.provider());
            assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
            assertTrue(fileSystem.isOpen());
            assertTrue(fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(0, fileSystem.majorVersion());
            assertEquals(4, fileSystem.minorVersion());
            assertEquals(0L, fileSystem.nextHeaderOffset());
            assertEquals(0L, fileSystem.nextHeaderSize());
            assertEquals(0L, fileSystem.nextHeaderCrc32());
            assertTrue(fileSystem.supportedFileAttributeViews().containsAll(Set.of("basic", "owner", "posix")));

            var fileStore = Files.getFileStore(fileSystem.getPath("/"));
            assertEquals("7z", fileStore.type());
            assertEquals(fileStore.name(), fileStore.getAttribute("name"));
            assertEquals(fileStore.type(), fileStore.getAttribute("type"));
            assertEquals(fileStore.isReadOnly(), fileStore.getAttribute("basic:readOnly"));
            assertEquals(fileStore.getTotalSpace(), fileStore.getAttribute("totalSpace"));
            assertEquals(fileStore.getUsableSpace(), fileStore.getAttribute("usableSpace"));
            assertEquals(fileStore.getUnallocatedSpace(), fileStore.getAttribute("unallocatedSpace"));
            assertNull(fileStore.getFileStoreAttributeView(FileStoreAttributeView.class));
            assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("7z:type"));
            assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("missing"));
        }
    }

    /// Verifies synthesized owner and group principal lookup.
    @Test
    public void exposesSyntheticPrincipalLookup() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(writeMinimalArchive())) {
            UserPrincipalLookupService lookupService = fileSystem.getUserPrincipalLookupService();
            UserPrincipal owner = lookupService.lookupPrincipalByName("owner");
            GroupPrincipal group = lookupService.lookupPrincipalByGroupName("group");

            assertEquals("owner", owner.getName());
            assertEquals("group", group.getName());
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> lookupService.lookupPrincipalByName("missing")
            );
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> lookupService.lookupPrincipalByGroupName("missing")
            );
        }
    }

    /// Verifies root-directory metadata and directory iteration.
    @Test
    public void exposesEmptyRootDirectory() throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(writeMinimalArchive())) {
            Path root = fileSystem.getPath("/");
            BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class);
            PosixFileAttributes posixAttributes = Files.readAttributes(root, PosixFileAttributes.class);
            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(root, BasicFileAttributeView.class)
            );
            ArrayList<Path> children = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                stream.forEach(children::add);
            }

            assertEquals("basic", basicView.name());
            assertTrue(basicView.readAttributes().isDirectory());
            assertEquals(FileTime.fromMillis(0L), attributes.lastModifiedTime());
            assertEquals(FileTime.fromMillis(0L), attributes.lastAccessTime());
            assertEquals(FileTime.fromMillis(0L), attributes.creationTime());
            assertFalse(attributes.isRegularFile());
            assertTrue(attributes.isDirectory());
            assertFalse(attributes.isSymbolicLink());
            assertFalse(attributes.isOther());
            assertEquals(0L, attributes.size());
            assertNull(attributes.fileKey());
            assertEquals("owner", posixAttributes.owner().getName());
            assertEquals("group", posixAttributes.group().getName());
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            ), posixAttributes.permissions());
            assertEquals(List.of(), children);
            assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(
                    fileSystem.getPath("/missing"),
                    BasicFileAttributes.class
            ));
        }
    }

    /// Writes and returns one minimal archive path in the isolated test directory.
    private Path writeMinimalArchive() throws IOException {
        return Files.write(temporaryDirectory.resolve("minimal.7z"), SevenZipTestArchiveFixtures.minimalArchive());
    }
}

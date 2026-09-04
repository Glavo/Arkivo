// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies named attribute mutation and persistence for complete-rewrite 7z file systems.
@NotNullByDefault
final class SevenZipNamedAttributeContractTest {
    /// Temporary directory used for path-backed 7z archives.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies core NIO infrastructure and provider dispatch for a read-only 7z file system.
    @Test
    void exposesNioInfrastructureAndProviderOperations() throws IOException {
        Path archive = createArchive();
        Path copiedFile = temporaryDirectory.resolve("copied-value.txt");
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive);

        try (fileSystem) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/value.txt");

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(root, roots.next());
            assertFalse(roots.hasNext());

            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertSame(store, Files.getFileStore(file));

            assertFalse(Files.isHidden(file));
            assertTrue(Files.isSameFile(file, fileSystem.getPath("/./value.txt")));
            Files.copy(file, copiedFile);
            assertEquals("value", Files.readString(copiedFile, StandardCharsets.UTF_8));
            assertThrows(UnsupportedOperationException.class, fileSystem::newWatchService);
        }

        assertThrows(ClosedFileSystemException.class, fileSystem::getRootDirectories);
        assertThrows(ClosedFileSystemException.class, fileSystem::getFileStores);
    }

    /// Verifies basic, POSIX, and 7z-specific named attributes share one persistent entry model.
    @Test
    void persistsNamedAttributeMutationsAndOutputSettings() throws IOException {
        Path archive = createArchive();
        FileTime modifiedTime = FileTime.from(Instant.parse("2036-04-05T06:07:08Z"));
        FileTime accessTime = FileTime.from(Instant.parse("2037-05-06T07:08:09Z"));
        FileTime creationTime = FileTime.from(Instant.parse("2038-06-07T08:09:10Z"));
        Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
        );

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/value.txt");
            Files.setAttribute(file, "lastModifiedTime", modifiedTime);
            Files.setAttribute(file, "basic:lastAccessTime", accessTime);
            Files.setAttribute(file, "7z:creationTime", creationTime);
            Files.setAttribute(file, "posix:lastModifiedTime", modifiedTime);
            Files.setAttribute(file, "posix:lastAccessTime", accessTime);
            Files.setAttribute(file, "posix:creationTime", creationTime);

            Files.setAttribute(
                    file,
                    "7z:windowsAttributes",
                    SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
            );
            Files.setAttribute(file, "7z:unixMode", SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE);
            Files.setAttribute(file, "7z:windowsAttributes", 0x20);
            Files.setAttribute(file, "7z:unixMode", 0100000);
            Files.setAttribute(file, "posix:permissions", permissions);

            Files.setAttribute(file, "7z:compression", SevenZipCompression.copy());
            Files.setAttribute(file, "7z:filter", SevenZipFilter.bcjX86());
            Files.setAttribute(file, "7z:filters", SevenZipFilterChain.of(SevenZipFilter.delta()));
            Files.setAttribute(file, "7z:filter", null);

            assertMetadata(file, modifiedTime, accessTime, creationTime, permissions);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Objects.requireNonNull(
                            Files.getFileAttributeView(file, FileOwnerAttributeView.class)
                    ).setOwner((UserPrincipal) () -> "owner")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Objects.requireNonNull(
                            Files.getFileAttributeView(file, PosixFileAttributeView.class)
                    ).setGroup((GroupPrincipal) () -> "group")
            );
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
            Path file = fileSystem.getPath("/value.txt");
            assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
            assertMetadata(file, modifiedTime, accessTime, creationTime, permissions);
            SevenZipCoderGraph coderGraph = Objects.requireNonNull(
                    Files.readAttributes(file, SevenZipArkivoEntryAttributes.class).coderGraph()
            );
            assertEquals(1, coderGraph.coders().size());
            assertEquals(SevenZipCoderMethod.COPY, coderGraph.coders().get(0).method());
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.setAttribute(file, "7z:windowsAttributes", 0x1)
            );
        }
    }

    /// Verifies named attribute dispatch rejects unsupported operations and malformed values precisely.
    @Test
    void validatesNamedAttributeMutations() throws IOException {
        Path archive = createArchive();
        FileTime time = FileTime.fromMillis(1L);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/value.txt");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "basic:lastModifiedTime", "not-a-time")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "basic:size", time)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "owner:owner", (UserPrincipal) () -> "owner")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:owner", "not-a-principal")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "posix:owner", (UserPrincipal) () -> "owner")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:group", "not-a-principal")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "posix:group", (GroupPrincipal) () -> "group")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:permissions", "not-permissions")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "posix:unknown", time)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "7z:windowsAttributes", "not-a-number")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "7z:unixMode", "not-a-number")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "7z:unixMode", -2)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "7z:unixMode", 0x1_0000)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "7z:compression", "copy")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "7z:filter", "delta")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "7z:filters", SevenZipFilter.delta())
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "7z:unknown", 1L)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "unknown:value", 1L)
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.setAttribute(fileSystem.getPath("/"), "7z:windowsAttributes", 0x1)
            );
        }
    }

    /// Verifies one path exposes the expected 7z and POSIX metadata projections.
    private static void assertMetadata(
            Path file,
            FileTime modifiedTime,
            FileTime accessTime,
            FileTime creationTime,
            Set<PosixFilePermission> permissions
    ) throws IOException {
        SevenZipArkivoEntryAttributes attributes = Files.readAttributes(
                file,
                SevenZipArkivoEntryAttributes.class
        );
        assertEquals(modifiedTime, attributes.lastModifiedTime());
        assertEquals(accessTime, attributes.lastAccessTime());
        assertEquals(creationTime, attributes.creationTime());
        assertEquals(0x20, attributes.windowsAttributes() & 0xffff);
        assertEquals(0100421, attributes.unixMode());
        assertEquals(permissions, Files.readAttributes(file, PosixFileAttributes.class).permissions());
    }

    /// Creates an archive containing one nonempty regular file.
    private Path createArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("sample.7z");
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(archive)) {
            Files.writeString(
                    fileSystem.getPath("/value.txt"),
                    "value",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        }
        return archive;
    }
}

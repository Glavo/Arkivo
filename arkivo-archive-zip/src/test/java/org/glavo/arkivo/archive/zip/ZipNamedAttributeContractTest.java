// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies named NIO attribute reads and mutations for writable ZIP file systems.
@NotNullByDefault
final class ZipNamedAttributeContractTest {
    /// Temporary directory used for path-backed ZIP archives.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies wildcard and explicit named reads for existing, written, and synthetic entries.
    @Test
    void readsNamedAttributeViewsDuringUpdate() throws IOException {
        Path archive = createArchive();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archive)) {
            Path existing = fileSystem.getPath("/dir/existing.txt");
            assertNamedAttributeViews(existing);

            Path written = fileSystem.getPath("/written.txt");
            Files.writeString(
                    written,
                    "written",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
            assertNamedAttributeViews(written);

            Map<String, Object> syntheticDirectory = Files.readAttributes(
                    fileSystem.getPath("/dir"),
                    "zip:*"
            );
            assertEquals(true, syntheticDirectory.get("isDirectory"));
            assertEquals("dir", syntheticDirectory.get("path"));

            assertEquals(5L, Files.readAttributes(existing, "size").get("size"));
            assertEquals(
                    Set.of("path", "rawComment"),
                    Files.readAttributes(existing, "zip: path , rawComment ").keySet()
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.readAttributes(existing, "unknown:*")
            );
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "basic:rawPath"));
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "basic:owner"));
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "owner:size"));
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "owner:group"));
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "posix:rawPath"));
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "zip:owner"));
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "zip:missing"));
        }
    }

    /// Verifies the read-only implementation exposes the same complete named-attribute maps.
    @Test
    void readsNamedAttributeViewsWhenReadOnly() throws IOException {
        Path archive = createArchive();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            Path existing = fileSystem.getPath("/dir/existing.txt");
            assertNamedAttributeViews(existing);

            Map<String, Object> directory = Files.readAttributes(fileSystem.getPath("/dir"), "zip:*");
            assertEquals(true, directory.get("isDirectory"));
            assertEquals("dir", directory.get("path"));
            assertEquals(0L, directory.get("size"));

            assertEquals(
                    Set.of("lastModifiedTime", "size", "fileKey"),
                    Files.readAttributes(existing, "basic:lastModifiedTime,size,fileKey").keySet()
            );
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "basic:rawPath"));
            assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(existing, "zip:missing"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.readAttributes(existing, "unknown:*")
            );
        }
    }

    /// Verifies named setters update both source entries and entries written by the current rewrite.
    @Test
    void persistsNamedAttributeMutations() throws IOException {
        Path archive = createArchive();
        FileTime modifiedTime = FileTime.from(Instant.parse("2036-04-05T06:07:08Z"));
        Set<PosixFilePermission> firstPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
        );
        Set<PosixFilePermission> finalPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
        );
        byte[] existingComment = {1, 2, 3};
        byte[] writtenComment = {4, 5, 6};

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archive)) {
            Path existing = fileSystem.getPath("/dir/existing.txt");
            PosixFileAttributes initial = Files.readAttributes(existing, PosixFileAttributes.class);
            Files.setAttribute(existing, "lastModifiedTime", modifiedTime);
            Files.setAttribute(existing, "owner:owner", initial.owner());
            Files.setAttribute(existing, "posix:owner", initial.owner());
            Files.setAttribute(existing, "posix:group", initial.group());
            Files.setAttribute(existing, "zip:externalAttributes", 0x20L);
            assertEquals(
                    0x20L,
                    Files.readAttributes(existing, ZipArkivoEntryAttributes.class).externalAttributes()
            );
            Files.setAttribute(existing, "posix:permissions", firstPermissions);
            Files.setAttribute(existing, "zip:permissions", finalPermissions);
            Files.setAttribute(existing, "zip:internalAttributes", 7);
            Files.setAttribute(existing, "zip:rawComment", null);
            Files.setAttribute(existing, "zip:rawComment", existingComment);
            existingComment[0] = 99;
            assertPersistentMetadata(
                    existing,
                    modifiedTime,
                    finalPermissions,
                    7,
                    new byte[]{1, 2, 3}
            );

            Path written = fileSystem.getPath("/written.txt");
            Files.writeString(
                    written,
                    "written",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
            Files.setAttribute(written, "zip:lastModifiedTime", modifiedTime);
            Files.setAttribute(written, "zip:permissions", finalPermissions);
            Files.setAttribute(written, "zip:internalAttributes", 9);
            Files.setAttribute(written, "zip:rawComment", writtenComment);
            writtenComment[0] = 99;
            assertPersistentMetadata(
                    written,
                    modifiedTime,
                    finalPermissions,
                    9,
                    new byte[]{4, 5, 6}
            );
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertPersistentMetadata(
                    fileSystem.getPath("/dir/existing.txt"),
                    modifiedTime,
                    finalPermissions,
                    7,
                    new byte[]{1, 2, 3}
            );
            assertPersistentMetadata(
                    fileSystem.getPath("/written.txt"),
                    modifiedTime,
                    finalPermissions,
                    9,
                    new byte[]{4, 5, 6}
            );
        }
    }

    /// Verifies typed basic, owner, POSIX, and ZIP views share one persistent metadata model.
    @Test
    void persistsTypedAttributeViewMutations() throws IOException {
        Path archive = createArchive();
        FileTime modifiedTime = FileTime.from(Instant.parse("2040-08-09T10:11:12Z"));
        Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_EXECUTE
        );
        byte[] comment = {7, 8, 9};

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/dir/existing.txt");
            PosixFileAttributes initial = Files.readAttributes(file, PosixFileAttributes.class);

            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, BasicFileAttributeView.class)
            );
            assertEquals("basic", basicView.name());
            basicView.setTimes(modifiedTime, null, null);
            assertEquals(modifiedTime, basicView.readAttributes().lastModifiedTime());

            FileOwnerAttributeView ownerView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, FileOwnerAttributeView.class)
            );
            assertEquals("owner", ownerView.name());
            ownerView.setOwner(initial.owner());
            assertEquals(initial.owner(), ownerView.getOwner());

            PosixFileAttributeView posixView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, PosixFileAttributeView.class)
            );
            assertEquals("posix", posixView.name());
            posixView.setTimes(modifiedTime, null, null);
            posixView.setOwner(initial.owner());
            posixView.setGroup(initial.group());
            posixView.setPermissions(permissions);
            assertEquals(permissions, posixView.readAttributes().permissions());
            assertEquals(initial.owner(), posixView.getOwner());

            ZipArkivoEntryAttributeView zipView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, ZipArkivoEntryAttributeView.class)
            );
            assertEquals("zip", zipView.name());
            zipView.setTimes(modifiedTime, null, null);
            zipView.setPermissions(permissions);
            zipView.setInternalAttributes(11);
            zipView.setRawComment(comment);
            comment[0] = 99;
            assertEquals(11, zipView.readAttributes().internalAttributes());
            assertArrayEquals(new byte[]{7, 8, 9}, zipView.readAttributes().rawComment());
            assertThrows(UnsupportedOperationException.class, () -> zipView.setMethod(ZipMethod.STORED));
            assertThrows(UnsupportedOperationException.class, () -> zipView.setEncryption(ZipEncryption.NONE));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> zipView.setUncompressedSizeAndCrc32(5L, 0L)
            );
            assertThrows(UnsupportedOperationException.class, () -> zipView.setLocalExtraData(new byte[0]));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> zipView.setCentralDirectoryExtraData(new byte[0])
            );
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            Path file = fileSystem.getPath("/dir/existing.txt");
            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, BasicFileAttributeView.class)
            );
            assertEquals("basic", basicView.name());
            assertEquals(modifiedTime, basicView.readAttributes().lastModifiedTime());
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> basicView.setTimes(modifiedTime, null, null)
            );
            ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
            assertEquals(permissions, attributes.permissions());
            assertEquals(11, attributes.internalAttributes());
            assertArrayEquals(new byte[]{7, 8, 9}, attributes.rawComment());
        }
    }

    /// Verifies every typed read-only view exposes its standard name and rejects all supported mutations.
    @Test
    void exposesTypedAttributeViewsWhenReadOnly() throws IOException {
        Path archive = createArchive();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            Path file = fileSystem.getPath("/dir/existing.txt");
            ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

            FileOwnerAttributeView ownerView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, FileOwnerAttributeView.class)
            );
            assertEquals("owner", ownerView.name());
            assertEquals(attributes.owner(), ownerView.getOwner());
            assertThrows(ReadOnlyFileSystemException.class, () -> ownerView.setOwner(attributes.owner()));

            PosixFileAttributeView posixView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, PosixFileAttributeView.class)
            );
            assertEquals("posix", posixView.name());
            PosixFileAttributes posixAttributes = posixView.readAttributes();
            assertEquals(attributes.owner(), posixView.getOwner());
            assertEquals(attributes.owner(), posixAttributes.owner());
            assertEquals(attributes.group(), posixAttributes.group());
            assertEquals(attributes.permissions(), posixAttributes.permissions());
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> posixView.setTimes(attributes.lastModifiedTime(), null, null)
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setOwner(attributes.owner()));
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setGroup(attributes.group()));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> posixView.setPermissions(attributes.permissions())
            );

            ZipArkivoEntryAttributeView zipView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, ZipArkivoEntryAttributeView.class)
            );
            assertEquals("zip", zipView.name());
            assertEquals(attributes.path(), zipView.readAttributes().path());
            assertEquals(attributes.owner(), zipView.getOwner());
            assertThrows(UnsupportedOperationException.class, () -> zipView.setOwner(attributes.owner()));
            assertThrows(UnsupportedOperationException.class, () -> zipView.setGroup(attributes.group()));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> zipView.setTimes(attributes.lastModifiedTime(), null, null)
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> zipView.setMethod(ZipMethod.STORED));
            assertThrows(ReadOnlyFileSystemException.class, () -> zipView.setEncryption(ZipEncryption.NONE));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> zipView.setPermissions(attributes.permissions())
            );
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> zipView.setUncompressedSizeAndCrc32(attributes.size(), attributes.crc32())
            );
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> zipView.setInternalAttributes(attributes.internalAttributes())
            );
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> zipView.setExternalAttributes(attributes.externalAttributes())
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> zipView.setLocalExtraData(new byte[0]));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> zipView.setCentralDirectoryExtraData(new byte[0])
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> zipView.setRawComment(null));
        }
    }

    /// Verifies named setters reject unsupported attributes, malformed values, and immutable sessions.
    @Test
    void validatesNamedAttributeMutations() throws IOException {
        Path archive = createArchive();
        FileTime time = FileTime.fromMillis(1L);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/dir/existing.txt");
            assertThrows(
                    ClassCastException.class,
                    () -> Files.setAttribute(file, "basic:lastModifiedTime", "not-a-time")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "basic:lastAccessTime", time)
            );
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> Files.setAttribute(file, "owner:owner", (UserPrincipal) () -> "other-owner")
            );
            assertThrows(
                    ClassCastException.class,
                    () -> Files.setAttribute(file, "owner:owner", "not-a-principal")
            );
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> Files.setAttribute(file, "posix:group", (GroupPrincipal) () -> "other-group")
            );
            assertThrows(
                    ClassCastException.class,
                    () -> Files.setAttribute(file, "posix:permissions", "not-permissions")
            );
            assertThrows(
                    ClassCastException.class,
                    () -> Files.setAttribute(file, "zip:internalAttributes", 1L)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "zip:internalAttributes", -1)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "zip:internalAttributes", 0x1_0000)
            );
            assertThrows(
                    ClassCastException.class,
                    () -> Files.setAttribute(file, "zip:externalAttributes", 1)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "zip:externalAttributes", -1L)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "zip:externalAttributes", 0x1_0000_0000L)
            );
            assertThrows(
                    ClassCastException.class,
                    () -> Files.setAttribute(file, "zip:rawComment", "not-bytes")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "zip:rawComment", new byte[0x1_0000])
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "zip:compressionMethod", ZipMethod.STORED)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "unknown:value", 1L)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(fileSystem.getPath("/dir"), "zip:internalAttributes", 1)
            );
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.setAttribute(fileSystem.getPath("/dir/existing.txt"), "zip:internalAttributes", 1)
            );
        }
    }

    /// Verifies all wildcard maps and explicit name lists expose the same supported attribute names.
    private static void assertNamedAttributeViews(Path file) throws IOException {
        Set<String> basicNames = Set.of(
                "lastModifiedTime",
                "lastAccessTime",
                "creationTime",
                "size",
                "isRegularFile",
                "isDirectory",
                "isSymbolicLink",
                "isOther",
                "fileKey"
        );
        Set<String> ownerNames = Set.of("owner");
        Set<String> posixNames = Set.of(
                "lastModifiedTime",
                "lastAccessTime",
                "creationTime",
                "size",
                "isRegularFile",
                "isDirectory",
                "isSymbolicLink",
                "isOther",
                "fileKey",
                "owner",
                "group",
                "permissions"
        );
        Set<String> zipNames = Set.of(
                "lastModifiedTime",
                "lastAccessTime",
                "creationTime",
                "size",
                "isRegularFile",
                "isDirectory",
                "isSymbolicLink",
                "isOther",
                "fileKey",
                "rawPath",
                "path",
                "comment",
                "compressedSize",
                "crc32",
                "generalPurposeFlags",
                "versionMadeBy",
                "versionNeededToExtract",
                "internalAttributes",
                "externalAttributes",
                "userId",
                "groupId",
                "compressionMethodId",
                "compressionMethod",
                "encryption",
                "localExtraData",
                "centralDirectoryExtraData",
                "rawComment"
        );

        Map<String, Object> basic = Files.readAttributes(file, "basic:*");
        Map<String, Object> owner = Files.readAttributes(file, "owner:*");
        Map<String, Object> posix = Files.readAttributes(file, "posix:*");
        Map<String, Object> zip = Files.readAttributes(file, "zip:*");
        assertEquals(basicNames, basic.keySet());
        assertEquals(ownerNames, owner.keySet());
        assertEquals(posixNames, posix.keySet());
        assertEquals(zipNames, zip.keySet());
        assertThrows(UnsupportedOperationException.class, () -> basic.put("size", 0L));

        Map<String, Object> namedOwner = Files.readAttributes(file, "owner:" + String.join(",", ownerNames));
        Map<String, Object> namedPosix = Files.readAttributes(file, "posix:" + String.join(",", posixNames));
        Map<String, Object> namedZip = Files.readAttributes(file, "zip:" + String.join(",", zipNames));
        assertEquals(ownerNames, namedOwner.keySet());
        assertEquals(posixNames, namedPosix.keySet());
        assertEquals(zipNames, namedZip.keySet());
        assertArrayEquals((byte[]) zip.get("rawPath"), (byte[]) namedZip.get("rawPath"));
        assertTrue((long) zip.get("compressedSize") >= 0L);
    }

    /// Verifies one entry retains the requested writable metadata.
    private static void assertPersistentMetadata(
            Path file,
            FileTime modifiedTime,
            Set<PosixFilePermission> permissions,
            int internalAttributes,
            byte[] rawComment
    ) throws IOException {
        ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
        assertEquals(modifiedTime, attributes.lastModifiedTime());
        assertEquals(permissions, attributes.permissions());
        assertEquals(internalAttributes, attributes.internalAttributes());
        assertArrayEquals(rawComment, attributes.rawComment());
    }

    /// Creates an archive whose parent directory exists only in the logical ZIP tree.
    private Path createArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("sample.zip");
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archive)) {
            try (OutputStream output = writer.beginFile("dir/existing.txt").openOutputStream()) {
                output.write("value".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archive;
    }
}

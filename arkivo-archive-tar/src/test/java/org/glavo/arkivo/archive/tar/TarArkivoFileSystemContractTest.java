// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the NIO infrastructure and named-attribute contracts of indexed TAR file systems.
@NotNullByDefault
final class TarArkivoFileSystemContractTest {
    /// Temporary directory used for path-backed TAR archives.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies roots, stores, matchers, principals, view discovery, and closed-state behavior.
    @Test
    void exposesNioFileSystemInfrastructure() throws IOException {
        Path archive = createArchive();
        TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive);

        try (fileSystem) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/dir/value.txt");
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(TarArkivoFormat.instance().uriScheme(), fileSystem.provider().getScheme());

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(root, roots.next());
            assertFalse(roots.hasNext());

            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertSame(store, Files.getFileStore(file));
            assertEquals(Files.size(archive), store.getTotalSpace());
            assertEquals(Set.of("basic", "owner", "posix", "tar"), fileSystem.supportedFileAttributeViews());
            assertTrue(store.supportsFileAttributeView("basic"));
            assertTrue(store.supportsFileAttributeView("tar"));
            assertFalse(store.supportsFileAttributeView("dos"));
            assertTrue(store.supportsFileAttributeView(BasicFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView(PosixFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView(TarArkivoEntryAttributeView.class));
            assertFalse(store.supportsFileAttributeView(DosFileAttributeView.class));
            assertNull(store.getFileStoreAttributeView(FileStoreAttributeView.class));

            assertTrue(fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
            assertFalse(fileSystem.getPathMatcher("glob:**/*.bin").matches(file));
            assertTrue(fileSystem.getPathMatcher("regex:.*/value\\.txt").matches(file));
            assertThrows(IllegalArgumentException.class, () -> fileSystem.getPathMatcher("glob"));
            assertThrows(IllegalArgumentException.class, () -> fileSystem.getPathMatcher(":*.txt"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fileSystem.getPathMatcher("literal:value.txt")
            );

            UserPrincipalLookupService principals = fileSystem.getUserPrincipalLookupService();
            assertEquals("archive-user", principals.lookupPrincipalByName("archive-user").getName());
            assertEquals("archive-group", principals.lookupPrincipalByGroupName("archive-group").getName());
            assertThrows(UnsupportedOperationException.class, fileSystem::newWatchService);

            assertFalse(Files.isHidden(file));
            assertTrue(Files.isSameFile(file, fileSystem.getPath("/dir/./value.txt")));
            Path copiedFile = temporaryDirectory.resolve("copied-tar-value.txt");
            Files.copy(file, copiedFile);
            assertEquals("value", Files.readString(copiedFile, StandardCharsets.UTF_8));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.setAttribute(file, "tar:userId", 1L)
            );
        }

        assertThrows(ClosedFileSystemException.class, fileSystem::getRootDirectories);
        assertThrows(ClosedFileSystemException.class, fileSystem::getFileStores);
    }

    /// Verifies raw NIO environments accept every documented metadata-charset representation.
    @Test
    void acceptsMetadataCharsetEnvironmentRepresentations() throws IOException {
        Path archive = createArchive();
        TarArkivoFileSystemProvider provider = new TarArkivoFileSystemProvider();
        Object[] representations = {
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                "UTF-8"
        };

        for (Object representation : representations) {
            try (TarArkivoFileSystem fileSystem = provider.newFileSystem(
                    archive,
                    Map.of("arkivo.tar.metadataCharsetDetector", representation)
            )) {
                assertEquals(
                        "value",
                        Files.readString(fileSystem.getPath("/dir/value.txt"), StandardCharsets.UTF_8)
                );
            }
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of("arkivo.tar.metadataCharsetDetector", 1)
                )
        );
    }

    /// Verifies every writable named attribute view shares one persistent TAR metadata model.
    @Test
    void persistsNamedAttributeMutations() throws IOException {
        Path archive = createArchive();
        FileTime modifiedTime = FileTime.from(Instant.parse("2036-04-05T06:07:08Z"));
        FileTime accessTime = FileTime.from(Instant.parse("2037-05-06T07:08:09Z"));
        FileTime statusChangeTime = FileTime.from(Instant.parse("2038-06-07T08:09:10Z"));
        FileTime creationTime = FileTime.from(Instant.parse("2039-07-08T09:10:11Z"));
        Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
        );

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/dir/value.txt");
            Files.setAttribute(file, "lastModifiedTime", modifiedTime);
            Files.setAttribute(file, "basic:lastAccessTime", accessTime);
            Files.setAttribute(file, "basic:creationTime", creationTime);
            Files.setAttribute(file, "owner:owner", (UserPrincipal) () -> "owner-view-user");
            assertEquals(
                    "owner-view-user",
                    Files.readAttributes(file, PosixFileAttributes.class).owner().getName()
            );
            Files.setAttribute(file, "posix:group", (GroupPrincipal) () -> "posix-view-group");
            Files.setAttribute(file, "tar:mode", 0100000);
            Files.setAttribute(file, "posix:permissions", permissions);
            Files.setAttribute(file, "tar:userId", 1234L);
            Files.setAttribute(file, "tar:groupId", 5678L);
            Files.setAttribute(file, "tar:userName", null);
            Files.setAttribute(file, "tar:groupName", null);
            Files.setAttribute(file, "tar:userName", "tar-user");
            Files.setAttribute(file, "tar:groupName", "tar-group");
            Files.setAttribute(file, "tar:recordedLastAccessTime", null);
            Files.setAttribute(file, "tar:recordedLastAccessTime", accessTime);
            Files.setAttribute(file, "tar:recordedStatusChangeTime", statusChangeTime);
            Files.setAttribute(file, "tar:recordedCreationTime", creationTime);

            assertMetadata(
                    file,
                    modifiedTime,
                    accessTime,
                    statusChangeTime,
                    creationTime,
                    permissions
            );

            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "basic:lastModifiedTime", "not-a-time")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "basic:size", FileTime.fromMillis(1L))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "owner:group", (UserPrincipal) () -> "user")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "owner:owner", "not-a-principal")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:owner", "not-a-principal")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:group", "not-a-principal")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:permissions", "not-permissions")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "tar:userId", "not-a-number")
            );
            assertThrows(IllegalArgumentException.class, () -> Files.setAttribute(file, "tar:userId", -1L));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "tar:mode", "not-a-number")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "tar:userName", 1L)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "tar:recordedCreationTime", "not-a-time")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "tar:unknown", 1L)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "unknown:value", 1L)
            );
            assertThrows(
                    IOException.class,
                    () -> Files.setAttribute(fileSystem.getPath("/"), "tar:userId", 1L)
            );
        }

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive)) {
            assertMetadata(
                    fileSystem.getPath("/dir/value.txt"),
                    modifiedTime,
                    accessTime,
                    statusChangeTime,
                    creationTime,
                    permissions
            );
        }
    }

    /// Verifies typed basic, owner, and POSIX views mutate and read the same persistent metadata snapshot.
    @Test
    void persistsTypedAttributeViewMutations() throws IOException {
        Path archive = createArchive();
        FileTime modifiedTime = FileTime.from(Instant.parse("2040-08-09T10:11:12Z"));
        FileTime accessTime = FileTime.from(Instant.parse("2041-09-10T11:12:13Z"));
        FileTime creationTime = FileTime.from(Instant.parse("2042-10-11T12:13:14Z"));
        UserPrincipal owner = () -> "typed-owner";
        GroupPrincipal group = () -> "typed-group";
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwxr-----");

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/dir/value.txt");
            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, BasicFileAttributeView.class)
            );
            assertEquals("basic", basicView.name());
            basicView.setTimes(modifiedTime, accessTime, creationTime);
            assertEquals(modifiedTime, basicView.readAttributes().lastModifiedTime());
            assertEquals(accessTime, basicView.readAttributes().lastAccessTime());
            assertEquals(creationTime, basicView.readAttributes().creationTime());

            FileOwnerAttributeView ownerView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, FileOwnerAttributeView.class)
            );
            assertEquals("owner", ownerView.name());
            ownerView.setOwner(owner);
            assertEquals(owner.getName(), ownerView.getOwner().getName());

            PosixFileAttributeView posixView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, PosixFileAttributeView.class)
            );
            assertEquals("posix", posixView.name());
            posixView.setTimes(modifiedTime, accessTime, creationTime);
            posixView.setOwner(owner);
            posixView.setGroup(group);
            posixView.setPermissions(permissions);
            PosixFileAttributes attributes = posixView.readAttributes();
            assertEquals(owner.getName(), posixView.getOwner().getName());
            assertEquals(owner.getName(), attributes.owner().getName());
            assertEquals(group.getName(), attributes.group().getName());
            assertEquals(permissions, attributes.permissions());
        }

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive)) {
            PosixFileAttributes attributes = Files.readAttributes(
                    fileSystem.getPath("/dir/value.txt"),
                    PosixFileAttributes.class
            );
            assertEquals(modifiedTime, attributes.lastModifiedTime());
            assertEquals(accessTime, attributes.lastAccessTime());
            assertEquals(creationTime, attributes.creationTime());
            assertEquals(owner.getName(), attributes.owner().getName());
            assertEquals(group.getName(), attributes.group().getName());
            assertEquals(permissions, attributes.permissions());
        }
    }

    /// Verifies update sessions preserve links and directory subtrees while enforcing structural mutation rules.
    @Test
    void updatesStructuralEntriesAndRejectsInvalidTreeMutations() throws IOException {
        Path archive = createArchive();
        Set<PosixFilePermission> directoryPermissions = PosixFilePermissions.fromString("rwxr-x---");

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archive)) {
            Path root = fileSystem.getPath("/");
            Path directory = fileSystem.getPath("/dir");
            Path file = fileSystem.getPath("/dir/value.txt");
            Path emptyDirectory = fileSystem.getPath("/empty");
            Path hardLink = fileSystem.getPath("/hard");
            Path symbolicLink = fileSystem.getPath("/symbolic");

            Files.createDirectory(
                    emptyDirectory,
                    PosixFilePermissions.asFileAttribute(directoryPermissions)
            );
            assertEquals(
                    directoryPermissions,
                    Files.readAttributes(emptyDirectory, PosixFileAttributes.class).permissions()
            );
            Files.createLink(hardLink, file);
            Files.createSymbolicLink(symbolicLink, Path.of("hard"));
            assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(hardLink));
            assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(symbolicLink));

            assertThrows(
                    FileSystemException.class,
                    () -> Files.createLink(fileSystem.getPath("/directory-link"), directory)
            );
            assertThrows(
                    NoSuchFileException.class,
                    () -> Files.createLink(
                            fileSystem.getPath("/missing-link"),
                            fileSystem.getPath("/missing-target")
                    )
            );
            assertThrows(FileSystemException.class, () -> Files.delete(root));
            assertThrows(DirectoryNotEmptyException.class, () -> Files.delete(directory));

            Files.move(file, file);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.move(file, fileSystem.getPath("/unsupported"), LinkOption.NOFOLLOW_LINKS)
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(root, fileSystem.getPath("/root-moved"))
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(directory, directory.resolve("nested"))
            );
            assertThrows(FileAlreadyExistsException.class, () -> Files.move(file, hardLink));
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(file, emptyDirectory, StandardCopyOption.REPLACE_EXISTING)
            );

            Path occupiedDirectory = fileSystem.getPath("/occupied");
            Files.createDirectory(occupiedDirectory);
            Files.writeString(occupiedDirectory.resolve("child.txt"), "child", StandardCharsets.UTF_8);
            assertThrows(
                    DirectoryNotEmptyException.class,
                    () -> Files.move(directory, occupiedDirectory, StandardCopyOption.REPLACE_EXISTING)
            );

            Files.delete(emptyDirectory);
            Files.move(directory, fileSystem.getPath("/moved"), StandardCopyOption.ATOMIC_MOVE);
        }

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive)) {
            assertFalse(Files.exists(fileSystem.getPath("/dir")));
            assertFalse(Files.exists(fileSystem.getPath("/empty")));
            assertTrue(Files.isDirectory(fileSystem.getPath("/moved")));
            assertEquals(
                    "value",
                    Files.readString(fileSystem.getPath("/moved/value.txt"), StandardCharsets.UTF_8)
            );

            Path hardLink = fileSystem.getPath("/hard");
            TarArkivoEntryAttributes hardLinkAttributes = Files.readAttributes(
                    hardLink,
                    TarArkivoEntryAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            assertTrue(hardLinkAttributes.isHardLink());
            assertEquals("moved/value.txt", hardLinkAttributes.linkName());
            assertEquals("value", Files.readString(hardLink, StandardCharsets.UTF_8));

            Path symbolicLink = fileSystem.getPath("/symbolic");
            assertTrue(Files.isSymbolicLink(symbolicLink));
            assertEquals(fileSystem.getPath("hard"), Files.readSymbolicLink(symbolicLink));
            assertEquals("value", Files.readString(symbolicLink, StandardCharsets.UTF_8));
            assertEquals(
                    "child",
                    Files.readString(fileSystem.getPath("/occupied/child.txt"), StandardCharsets.UTF_8)
            );
        }
    }

    /// Verifies update channels validate options, hide pending entries, and persist append and creation metadata.
    @Test
    void enforcesUpdateChannelOptionsAndVisibility() throws IOException {
        Path archive = createArchive();
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archive)) {
            Path root = fileSystem.getPath("/");
            Path directory = fileSystem.getPath("/dir");
            Path file = fileSystem.getPath("/dir/value.txt");
            Path missing = fileSystem.getPath("/missing.txt");

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newInputStream(file, StandardOpenOption.WRITE)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newOutputStream(file, StandardOpenOption.READ)
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newByteChannel(root, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE))
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newByteChannel(directory, Set.of(StandardOpenOption.WRITE))
            );
            assertThrows(
                    NoSuchFileException.class,
                    () -> Files.newByteChannel(missing, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE))
            );
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(missing, Set.of(StandardOpenOption.CREATE))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(file, Set.of(StandardOpenOption.APPEND, StandardOpenOption.READ))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING)
                    )
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)
                    )
            );

            Path created = fileSystem.getPath("/created.bin");
            try (SeekableByteChannel channel = Files.newByteChannel(
                    created,
                    Set.of(
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE
                    ),
                    PosixFilePermissions.asFileAttribute(permissions)
            )) {
                assertEquals(0L, channel.size());
                assertEquals(3, channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
                channel.position(0L);
                ByteBuffer target = ByteBuffer.allocate(3);
                assertEquals(3, channel.read(target));
                assertArrayEquals(new byte[]{1, 2, 3}, target.array());

                assertThrows(FileSystemException.class, () -> Files.readAllBytes(created));
                assertThrows(
                        FileSystemException.class,
                        () -> Files.newByteChannel(file, Set.of(StandardOpenOption.WRITE))
                );
                assertThrows(FileSystemException.class, () -> Files.delete(file));
                assertThrows(
                        FileSystemException.class,
                        () -> Files.setAttribute(file, "tar:userId", 10L)
                );
            }

            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(created));
            assertEquals(
                    permissions,
                    Files.readAttributes(created, PosixFileAttributes.class).permissions()
            );
            try (SeekableByteChannel channel = Files.newByteChannel(
                    created,
                    Set.of(StandardOpenOption.APPEND)
            )) {
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{4})));
            }
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(created));
        }

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive)) {
            Path created = fileSystem.getPath("/created.bin");
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(created));
            assertEquals(
                    permissions,
                    Files.readAttributes(created, PosixFileAttributes.class).permissions()
            );
        }
    }

    /// Verifies file-system commits preserve metadata that requires POSIX PAX records.
    @Test
    void persistsPaxOnlySnapshotFields() throws IOException {
        Path archive = temporaryDirectory.resolve("pax-snapshot.tar");
        String entryName = "entry-" + "n".repeat(110) + ".txt";
        String userName = "user-" + "u".repeat(40);
        String groupName = "group-" + "g".repeat(40);
        long userId = 10_000_000_000L;
        long groupId = 20_000_000_000L;

        try (TarArkivoStreamingWriter ignored = TarArkivoStreamingWriter.create(archive)) {
        }
        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/" + entryName);
            Files.writeString(file, "pax-snapshot", StandardCharsets.UTF_8);
            Files.setAttribute(file, "tar:userId", userId);
            Files.setAttribute(file, "tar:groupId", groupId);
            Files.setAttribute(file, "tar:userName", userName);
            Files.setAttribute(file, "tar:groupName", groupName);
            Files.createSymbolicLink(fileSystem.getPath("/long-link"), Path.of(entryName));
        }

        try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive)) {
            Path file = fileSystem.getPath("/" + entryName);
            assertEquals("pax-snapshot", Files.readString(file, StandardCharsets.UTF_8));
            TarArkivoEntryAttributes attributes = Files.readAttributes(file, TarArkivoEntryAttributes.class);
            assertEquals(userId, attributes.userId());
            assertEquals(groupId, attributes.groupId());
            assertEquals(userName, attributes.userName());
            assertEquals(groupName, attributes.groupName());
            assertEquals(
                    fileSystem.getPath(entryName),
                    Files.readSymbolicLink(fileSystem.getPath("/long-link"))
            );
        }
    }

    /// Verifies one path exposes the expected metadata through TAR and POSIX projections.
    private static void assertMetadata(
            Path file,
            FileTime modifiedTime,
            FileTime accessTime,
            FileTime statusChangeTime,
            FileTime creationTime,
            Set<PosixFilePermission> permissions
    ) throws IOException {
        TarArkivoEntryAttributes attributes = Files.readAttributes(file, TarArkivoEntryAttributes.class);
        assertEquals(modifiedTime, attributes.lastModifiedTime());
        assertEquals(accessTime, attributes.recordedLastAccessTime());
        assertEquals(statusChangeTime, attributes.recordedStatusChangeTime());
        assertEquals(creationTime, attributes.recordedCreationTime());
        assertEquals(1234L, attributes.userId());
        assertEquals(5678L, attributes.groupId());
        assertEquals("tar-user", attributes.userName());
        assertEquals("tar-group", attributes.groupName());
        assertEquals(0100421, attributes.mode());
        PosixFileAttributes posix = Files.readAttributes(file, PosixFileAttributes.class);
        assertEquals("tar-user", posix.owner().getName());
        assertEquals("tar-group", posix.group().getName());
        assertEquals(permissions, posix.permissions());
    }

    /// Creates an archive containing one explicit directory and one regular file.
    private Path createArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("sample.tar");
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archive)) {
            writer.beginDirectory("dir").close();
            try (OutputStream output = writer.beginFile("dir/value.txt").openOutputStream()) {
                output.write("value".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archive;
    }
}

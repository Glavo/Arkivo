// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests TAR streaming reader behavior.
@NotNullByDefault
public final class TarArkivoStreamingReaderTest {
    /// Verifies that regular files, directories, and symbolic links are read in stream order.
    @Test
    public void readsStreamingEntries() throws IOException {
        byte[] archive = tarArchive();
        ArrayList<String> paths = new ArrayList<>();

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            var readerEntry73 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes directory = readerEntry73.attributes(TarArkivoEntryAttributes.class);
            paths.add(directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(0755, directory.mode());

            var readerEntry79 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry79.attributes(TarArkivoEntryAttributes.class);
            BasicFileAttributes basicFile = readerEntry79.attributes(BasicFileAttributes.class);
            paths.add(file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(5L, basicFile.size());
            assertEquals("user", file.userName());
            try (var input = readerEntry79.openInputStream()) {
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }

            var readerEntry90 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry90.attributes(TarArkivoEntryAttributes.class);
            paths.add(link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals("dir/hello.txt", link.linkName());

            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }

        assertEquals(List.of("dir/", "dir/hello.txt", "link"), paths);
    }

    /// Verifies that hard link entries expose link metadata without synthetic stream body data.
    @Test
    public void readsHardLinkMetadata() throws IOException {
        byte[] content = "hard-link-source".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "original.txt", content);
        writeHeader(output, "copy.txt", 0644, 1000, 1000, 0, '1', "original.txt", "user", "group");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry113 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes original = readerEntry113.attributes(TarArkivoEntryAttributes.class);
            assertEquals("original.txt", original.path());
            assertEquals(true, original.isRegularFile());
            assertEquals(false, original.isHardLink());
            try (var input = readerEntry113.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            var readerEntry122 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry122.attributes(TarArkivoEntryAttributes.class);
            BasicFileAttributes basicLink = readerEntry122.attributes(BasicFileAttributes.class);
            assertEquals("copy.txt", link.path());
            assertEquals((byte) '1', link.typeFlag());
            assertEquals(true, link.isHardLink());
            assertEquals(true, link.isRegularFile());
            assertEquals(false, link.isSymbolicLink());
            assertEquals(false, link.isOther());
            assertEquals("original.txt", link.linkName());
            assertEquals(0L, basicLink.size());
            try (var input = readerEntry122.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that the streaming writer creates regular files, directories, symbolic links, and hard links.
    @Test
    public void writesStreamingEntries() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            var writerEntry147 = writer.beginDirectory("dir");
            writerEntry147.close();

            var writerEntry150 = writer.beginFile("dir/hello.txt");
            try (OutputStream body = writerEntry150.openOutputStream()) {
                body.write(content);
            }

            var writerEntry155 = writer.beginSymbolicLink("link", "dir/hello.txt");
            writerEntry155.close();

            var writerEntry158 = writer.beginHardLink("hard-link", "dir/hello.txt");
            writerEntry158.close();
        }

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry164 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes directory = readerEntry164.attributes(TarArkivoEntryAttributes.class);
            assertEquals("dir/", directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(0755, directory.mode());

            var readerEntry170 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry170.attributes(TarArkivoEntryAttributes.class);
            assertEquals("dir/hello.txt", file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(0644, file.mode());
            assertEquals(content.length, file.size());
            try (var input = readerEntry170.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            var readerEntry180 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry180.attributes(TarArkivoEntryAttributes.class);
            assertEquals("link", link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals(0777, link.mode());
            assertEquals("dir/hello.txt", link.linkName());

            var readerEntry187 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes hardLink = readerEntry187.attributes(TarArkivoEntryAttributes.class);
            assertEquals("hard-link", hardLink.path());
            assertEquals(true, hardLink.isHardLink());
            assertEquals(true, hardLink.isRegularFile());
            assertEquals(0644, hardLink.mode());
            assertEquals("dir/hello.txt", hardLink.linkName());
            try (var input = readerEntry187.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that the streaming writer persists metadata configured through standard attribute views.
    @Test
    public void writesConfiguredEntryMetadata() throws IOException {
        byte[] content = "metadata".getBytes(StandardCharsets.UTF_8);
        FileTime lastModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_456_789L));
        FileTime lastAccessTime = FileTime.from(Instant.ofEpochSecond(1_700_000_001L, 500_000_000L));
        FileTime creationTime = FileTime.from(Instant.ofEpochSecond(1_700_000_002L, 750_000_000L));
        String userName = "writer-user-name-that-requires-pax-metadata";
        String groupName = "writer-group-name-that-requires-pax-metadata";
        long userId = 3_000_000L;
        long groupId = 4_000_000L;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            var writerEntry216 = writer.beginFile("meta.txt");
            BasicFileAttributeView basicView =
                    Objects.requireNonNull(writerEntry216.attributeView(BasicFileAttributeView.class));
            basicView.setTimes(lastModifiedTime, lastAccessTime, creationTime);

            PosixFileAttributeView posixView =
                    Objects.requireNonNull(writerEntry216.attributeView(PosixFileAttributeView.class));
            posixView.setPermissions(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ
            ));

            TarArkivoEntryAttributeView tarView =
                    Objects.requireNonNull(writerEntry216.attributeView(TarArkivoEntryAttributeView.class));
            assertEquals("tar", tarView.name());
            tarView.setUserId(userId);
            tarView.setGroupId(groupId);
            tarView.setUserName(userName);
            tarView.setGroupName(groupName);

            try (OutputStream body = writerEntry216.openOutputStream()) {
                body.write(content);
            }
        }

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry244 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes attributes = readerEntry244.attributes(TarArkivoEntryAttributes.class);
            PosixFileAttributes posixAttributes = readerEntry244.attributes(PosixFileAttributes.class);
            assertEquals("meta.txt", attributes.path());
            assertEquals(0640, attributes.mode());
            assertEquals(userId, attributes.userId());
            assertEquals(groupId, attributes.groupId());
            assertEquals(userName, attributes.userName());
            assertEquals(groupName, attributes.groupName());
            assertEquals(userName, posixAttributes.owner().getName());
            assertEquals(groupName, posixAttributes.group().getName());
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ
                    ),
                    posixAttributes.permissions()
            );
            assertEquals(lastModifiedTime.toInstant(), attributes.lastModifiedTime().toInstant());
            assertEquals(lastAccessTime.toInstant(), attributes.lastAccessTime().toInstant());
            assertEquals(creationTime.toInstant(), attributes.creationTime().toInstant());
            try (var input = readerEntry244.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that TAR archives can be opened as read-only file systems.
    @Test
    public void opensEntriesAsReadOnlyFileSystem() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("tar-fs-");
        Path copiedDirectory = archivePath.getParent().resolve("copied-dir");
        Path copiedFile = archivePath.getParent().resolve("copied-file");
        Path existingFile = archivePath.getParent().resolve("existing-file");
        Set<PosixFilePermission> filePermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
        );
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
                var writerEntry288 = writer.beginDirectory("dir");
                writerEntry288.close();

                var writerEntry291 = writer.beginFile("dir/hello.txt");
                BasicFileAttributeView basicView =
                        Objects.requireNonNull(writerEntry291.attributeView(BasicFileAttributeView.class));
                basicView.setTimes(FileTime.fromMillis(1_700_000_000_000L), null, null);

                PosixFileAttributeView posixView =
                        Objects.requireNonNull(writerEntry291.attributeView(PosixFileAttributeView.class));
                posixView.setOwner(() -> "fs-user");
                posixView.setGroup(() -> "fs-group");
                posixView.setPermissions(filePermissions);
                TarArkivoEntryAttributeView tarView =
                        Objects.requireNonNull(writerEntry291.attributeView(TarArkivoEntryAttributeView.class));
                tarView.setUserId(1234L);
                tarView.setGroupId(5678L);
                try (OutputStream body = writerEntry291.openOutputStream()) {
                    body.write(content);
                }

                var writerEntry309 = writer.beginSymbolicLink("link", "dir/hello.txt");
                writerEntry309.close();
            }

            TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath);
            try (fileSystem) {
                assertEquals(true, fileSystem.isReadOnly());

                Path directory = fileSystem.getPath("/dir");
                BasicFileAttributes directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
                assertEquals(true, directoryAttributes.isDirectory());
                Files.copy(directory, copiedDirectory);
                assertEquals(true, Files.isDirectory(copiedDirectory));
                assertThrows(FileAlreadyExistsException.class, () -> Files.copy(directory, copiedDirectory));
                Files.copy(directory, copiedDirectory, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(existingFile, "existing", StandardCharsets.UTF_8);
                Files.copy(directory, existingFile, StandardCopyOption.REPLACE_EXISTING);
                assertEquals(true, Files.isDirectory(existingFile));

                ArrayList<String> children = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        children.add(child.toString());
                    }
                }
                assertEquals(Set.of("/dir", "/link"), Set.copyOf(children));

                Path file = fileSystem.getPath("/dir/hello.txt");
                TarArkivoEntryAttributes fileAttributes = Files.readAttributes(file, TarArkivoEntryAttributes.class);
                assertEquals(true, fileAttributes.isRegularFile());
                assertEquals(0640, fileAttributes.mode());
                assertEquals(1234L, fileAttributes.userId());
                assertEquals(5678L, fileAttributes.groupId());
                assertEquals(content.length, fileAttributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                Files.copy(
                        file,
                        copiedFile,
                        LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                assertArrayEquals(content, Files.readAllBytes(copiedFile));

                PosixFileAttributes posixAttributes = Files.readAttributes(file, PosixFileAttributes.class);
                assertEquals("fs-user", posixAttributes.owner().getName());
                assertEquals("fs-group", posixAttributes.group().getName());
                assertEquals(filePermissions, posixAttributes.permissions());

                FileOwnerAttributeView ownerView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, FileOwnerAttributeView.class));
                assertEquals("owner", ownerView.name());
                assertEquals("fs-user", ownerView.getOwner().getName());
                assertThrows(ReadOnlyFileSystemException.class, () -> ownerView.setOwner(() -> "other-user"));

                PosixFileAttributeView readPosixView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, PosixFileAttributeView.class));
                assertEquals("posix", readPosixView.name());
                assertEquals(filePermissions, readPosixView.readAttributes().permissions());
                assertEquals("fs-user", readPosixView.getOwner().getName());
                assertThrows(ReadOnlyFileSystemException.class, () -> readPosixView.setGroup(() -> "other-group"));
                assertThrows(
                        ReadOnlyFileSystemException.class,
                        () -> readPosixView.setPermissions(Set.<PosixFilePermission>of())
                );

                TarArkivoEntryAttributeView tarView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, TarArkivoEntryAttributeView.class));
                assertEquals("tar", tarView.name());
                TarArkivoEntryAttributes viewAttributes = tarView.readAttributes();
                assertEquals("dir/hello.txt", viewAttributes.path());
                assertEquals(0640, viewAttributes.mode());
                assertEquals(1234L, viewAttributes.userId());
                assertEquals(5678L, viewAttributes.groupId());
                assertThrows(ReadOnlyFileSystemException.class, () -> tarView.setMode(0600));
                var fileStore = Files.getFileStore(file);
                assertEquals(fileStore.name(), fileStore.getAttribute("name"));
                assertEquals(fileStore.type(), fileStore.getAttribute("type"));
                assertEquals(Boolean.valueOf(fileStore.isReadOnly()), fileStore.getAttribute("basic:readOnly"));
                assertEquals(Long.valueOf(fileStore.getTotalSpace()), fileStore.getAttribute("totalSpace"));
                assertEquals(Long.valueOf(fileStore.getUsableSpace()), fileStore.getAttribute("usableSpace"));
                assertEquals(Long.valueOf(fileStore.getUnallocatedSpace()), fileStore.getAttribute("unallocatedSpace"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("tar:type"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("missing"));
                assertEquals(
                        true,
                        fileStore.supportsFileAttributeView(TarArkivoEntryAttributeView.class)
                );
                assertEquals(
                        true,
                        fileStore.supportsFileAttributeView(FileOwnerAttributeView.class)
                );
                assertEquals(
                        true,
                        fileStore.supportsFileAttributeView(PosixFileAttributeView.class)
                );

                Map<String, Object> basicNamedAttributes = Files.readAttributes(file, "basic:size,isRegularFile");
                assertEquals((long) content.length, basicNamedAttributes.get("size"));
                assertEquals(true, basicNamedAttributes.get("isRegularFile"));
                assertEquals(false, basicNamedAttributes.containsKey("mode"));

                Map<String, Object> tarNamedAttributes = Files.readAttributes(
                        file,
                        "tar:path,typeFlag,mode,userId,groupId,userName,groupName,size,linkName"
                );
                assertEquals("dir/hello.txt", tarNamedAttributes.get("path"));
                assertEquals((byte) '0', tarNamedAttributes.get("typeFlag"));
                assertEquals(0640, tarNamedAttributes.get("mode"));
                assertEquals(1234L, tarNamedAttributes.get("userId"));
                assertEquals(5678L, tarNamedAttributes.get("groupId"));
                assertEquals("fs-user", tarNamedAttributes.get("userName"));
                assertEquals("fs-group", tarNamedAttributes.get("groupName"));
                assertEquals((long) content.length, tarNamedAttributes.get("size"));
                assertNull(tarNamedAttributes.get("linkName"));

                Map<String, Object> ownerNamedAttributes = Files.readAttributes(file, "owner:owner");
                assertEquals("fs-user", ((UserPrincipal) ownerNamedAttributes.get("owner")).getName());

                Map<String, Object> posixNamedAttributes =
                        Files.readAttributes(file, "posix:owner,group,permissions,isRegularFile");
                assertEquals("fs-user", ((UserPrincipal) posixNamedAttributes.get("owner")).getName());
                assertEquals("fs-group", ((GroupPrincipal) posixNamedAttributes.get("group")).getName());
                assertEquals(filePermissions, posixNamedAttributes.get("permissions"));
                assertEquals(true, posixNamedAttributes.get("isRegularFile"));

                try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                    assertEquals(content.length, channel.size());
                    channel.position(1);
                    ByteBuffer buffer = ByteBuffer.allocate(2);
                    assertEquals(2, channel.read(buffer));
                    buffer.flip();
                    assertEquals((byte) 'e', buffer.get());
                    assertEquals((byte) 'l', buffer.get());
                }

                Path link = fileSystem.getPath("/link");
                TarArkivoEntryAttributes linkAttributes = Files.readAttributes(
                        link,
                        TarArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));

                Map<String, Object> linkNamedAttributes = Files.readAttributes(
                        link,
                        "tar:isSymbolicLink,typeFlag,linkName",
                        LinkOption.NOFOLLOW_LINKS
                );
                assertEquals(true, linkNamedAttributes.get("isSymbolicLink"));
                assertEquals((byte) '2', linkNamedAttributes.get("typeFlag"));
                assertEquals("dir/hello.txt", linkNamedAttributes.get("linkName"));
                assertArrayEquals(content, Files.readAllBytes(link));

                assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "zip:size"));
                assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
            }

            assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/dir"));
        } finally {
            Files.deleteIfExists(copiedFile);
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(copiedDirectory);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that TAR archives can be created through a forward-only writable file system.
    @Test
    public void createsEntriesAsWritableFileSystem() throws IOException {
        byte[] content = "hello from writable file system".getBytes(StandardCharsets.UTF_8);
        byte[] channelContent = new byte[]{1, 2, 3};
        Path archivePath = createTemporaryArchivePath("tar-writable-fs-");
        Set<PosixFilePermission> directoryPermissions = PosixFilePermissions.fromString("rwxr-x---");
        Set<PosixFilePermission> channelFilePermissions = PosixFilePermissions.fromString("rw-r-----");
        Set<PosixFilePermission> linkPermissions = PosixFilePermissions.fromString("rwxr-xr--");
        try {
            Files.deleteIfExists(archivePath);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.create(archivePath)) {
                assertEquals(false, fileSystem.isReadOnly());

                Path directory = fileSystem.getPath("/dir");
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(directoryPermissions));

                Path file = fileSystem.getPath("/dir/hello.txt");
                Files.write(file, content);
                assertThrows(FileAlreadyExistsException.class, () -> Files.write(file, content));

                Path channelFile = fileSystem.getPath("/channel.bin");
                try (SeekableByteChannel channel =
                             Files.newByteChannel(
                                     channelFile,
                                     Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                                     PosixFilePermissions.asFileAttribute(channelFilePermissions)
                             )) {
                    assertEquals(0L, channel.position());
                    assertEquals(channelContent.length, channel.write(ByteBuffer.wrap(channelContent)));
                    assertEquals(channelContent.length, channel.size());
                    assertThrows(UnsupportedOperationException.class, () -> channel.position(0L));
                }

                Files.createSymbolicLink(
                        fileSystem.getPath("/link"),
                        Path.of("dir/hello.txt"),
                        PosixFilePermissions.asFileAttribute(linkPermissions)
                );
                Files.createLink(fileSystem.getPath("/hard-link"), file);
                assertThrows(UnsupportedOperationException.class, () -> Files.readString(file, StandardCharsets.UTF_8));
            }

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                Path directory = fileSystem.getPath("/dir");
                TarArkivoEntryAttributes directoryAttributes =
                        Files.readAttributes(directory, TarArkivoEntryAttributes.class);
                PosixFileAttributes directoryPosixAttributes =
                        Files.readAttributes(directory, PosixFileAttributes.class);
                assertEquals(true, Files.isDirectory(directory));
                assertEquals(0750, directoryAttributes.mode());
                assertEquals(directoryPermissions, directoryPosixAttributes.permissions());

                Path file = fileSystem.getPath("/dir/hello.txt");
                assertArrayEquals(content, Files.readAllBytes(file));

                Path channelFile = fileSystem.getPath("/channel.bin");
                TarArkivoEntryAttributes channelFileAttributes =
                        Files.readAttributes(channelFile, TarArkivoEntryAttributes.class);
                PosixFileAttributes channelFilePosixAttributes =
                        Files.readAttributes(channelFile, PosixFileAttributes.class);
                assertArrayEquals(channelContent, Files.readAllBytes(channelFile));
                assertEquals(0640, channelFileAttributes.mode());
                assertEquals(channelFilePermissions, channelFilePosixAttributes.permissions());

                Path link = fileSystem.getPath("/link");
                TarArkivoEntryAttributes linkAttributes = Files.readAttributes(
                        link,
                        TarArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                PosixFileAttributes linkPosixAttributes = Files.readAttributes(
                        link,
                        PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(0754, linkAttributes.mode());
                assertEquals(linkPermissions, linkPosixAttributes.permissions());
                TarArkivoEntryAttributeView followedView = Objects.requireNonNull(
                        Files.getFileAttributeView(link, TarArkivoEntryAttributeView.class)
                );
                TarArkivoEntryAttributeView linkView = Objects.requireNonNull(Files.getFileAttributeView(
                        link,
                        TarArkivoEntryAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS
                ));
                assertEquals(true, followedView.readAttributes().isRegularFile());
                assertEquals(true, linkView.readAttributes().isSymbolicLink());
                assertEquals(linkPermissions, Objects.requireNonNull(Files.getFileAttributeView(
                        link,
                        PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS
                )).readAttributes().permissions());
                assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));
                assertArrayEquals(content, Files.readAllBytes(link));

                Path hardLink = fileSystem.getPath("/hard-link");
                TarArkivoEntryAttributes hardLinkAttributes =
                        Files.readAttributes(hardLink, TarArkivoEntryAttributes.class);
                assertEquals(true, hardLinkAttributes.isHardLink());
                assertArrayEquals(content, Files.readAllBytes(hardLink));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that update mode rewrites additions, replacements, deletions, moves, links, and metadata.
    @Test
    public void updatesExistingArchiveThroughCompleteRewrite() throws IOException {
        byte[] keepContent = "abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] linkedContent = "linked-content".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("tar-update-");
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
                writeStreamingFile(writer, "keep.txt", keepContent);
                var writerEntry601 = writer.beginSymbolicLink("link", "keep.txt");
                writerEntry601.close();
                writeStreamingFile(writer, "remove.txt", "remove".getBytes(StandardCharsets.UTF_8));
                var writerEntry604 = writer.beginDirectory("dir");
                writerEntry604.close();
                writeStreamingFile(writer, "dir/child.txt", "child".getBytes(StandardCharsets.UTF_8));
                writeStreamingFile(writer, "source.txt", linkedContent);
                var writerEntry608 = writer.beginHardLink("hard.txt", "source.txt");
                writerEntry608.close();
                writeStreamingFile(writer, "target.txt", "old-target".getBytes(StandardCharsets.UTF_8));
                var writerEntry611 = writer.beginHardLink("target-hard.txt", "target.txt");
                writerEntry611.close();
                writeStreamingFile(writer, "replacement.txt", "new-target".getBytes(StandardCharsets.UTF_8));
            }

            FileTime modifiedTime = FileTime.from(Instant.parse("2032-03-04T05:06:07.125Z"));
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archivePath)) {
                assertEquals(false, fileSystem.isReadOnly());
                Path keep = fileSystem.getPath("/keep.txt");
                assertArrayEquals(keepContent, Files.readAllBytes(keep));
                assertThrows(
                        ProviderMismatchException.class,
                        () -> fileSystem.provider().move(keep, archivePath)
                );
                assertArrayEquals(keepContent, Files.readAllBytes(keep));
                try (SeekableByteChannel channel = Files.newByteChannel(
                        keep,
                        Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                )) {
                    channel.position(2L);
                    assertEquals(2, channel.write(ByteBuffer.wrap("ZZ".getBytes(StandardCharsets.UTF_8))));
                    channel.truncate(5L);
                    channel.position(0L);
                    ByteBuffer updated = ByteBuffer.allocate(5);
                    assertEquals(5, channel.read(updated));
                    assertArrayEquals("abZZe".getBytes(StandardCharsets.UTF_8), updated.array());
                }

                TarArkivoEntryAttributeView tarView =
                        Objects.requireNonNull(Files.getFileAttributeView(keep, TarArkivoEntryAttributeView.class));
                tarView.setTimes(modifiedTime, modifiedTime, modifiedTime);
                tarView.setGroupId(5678L);
                tarView.setMode(0640);
                tarView.setUserName("update-user");
                tarView.setGroupName("update-group");
                Path link = fileSystem.getPath("/link");
                Objects.requireNonNull(Files.getFileAttributeView(link, TarArkivoEntryAttributeView.class))
                        .setUserId(1234L);
                Objects.requireNonNull(Files.getFileAttributeView(
                        link,
                        TarArkivoEntryAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS
                )).setUserId(4321L);
                Files.move(
                        fileSystem.getPath("/replacement.txt"),
                        fileSystem.getPath("/target.txt"),
                        StandardCopyOption.REPLACE_EXISTING
                );

                Files.delete(fileSystem.getPath("/remove.txt"));
                Files.delete(fileSystem.getPath("/source.txt"));
                Files.move(fileSystem.getPath("/dir"), fileSystem.getPath("/renamed"));
                Files.writeString(fileSystem.getPath("/new.txt"), "new", StandardCharsets.UTF_8);
            }

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                Path keep = fileSystem.getPath("/keep.txt");
                assertArrayEquals("abZZe".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(keep));
                TarArkivoEntryAttributes attributes = Files.readAttributes(keep, TarArkivoEntryAttributes.class);
                assertEquals(modifiedTime, attributes.lastModifiedTime());
                assertEquals(1234L, attributes.userId());
                assertEquals(5678L, attributes.groupId());
                assertEquals(0640, attributes.mode());
                assertEquals("update-user", attributes.userName());
                assertEquals("update-group", attributes.groupName());
                assertEquals(4321L, Files.readAttributes(
                        fileSystem.getPath("/link"),
                        TarArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                ).userId());

                assertEquals(false, Files.exists(fileSystem.getPath("/remove.txt")));
                assertEquals(false, Files.exists(fileSystem.getPath("/source.txt")));
                assertArrayEquals(
                        linkedContent,
                        Files.readAllBytes(fileSystem.getPath("/hard.txt"))
                );
                assertEquals(
                        false,
                        Files.readAttributes(
                                fileSystem.getPath("/hard.txt"),
                                TarArkivoEntryAttributes.class
                        ).isHardLink()
                );
                assertEquals(
                        "new-target",
                        Files.readString(fileSystem.getPath("/target.txt"), StandardCharsets.UTF_8)
                );
                Path targetHardLink = fileSystem.getPath("/target-hard.txt");
                assertEquals(
                        "old-target",
                        Files.readString(targetHardLink, StandardCharsets.UTF_8)
                );
                assertEquals(
                        false,
                        Files.readAttributes(targetHardLink, TarArkivoEntryAttributes.class).isHardLink()
                );
                assertEquals(
                        "child",
                        Files.readString(fileSystem.getPath("/renamed/child.txt"), StandardCharsets.UTF_8)
                );
                assertEquals("new", Files.readString(fileSystem.getPath("/new.txt"), StandardCharsets.UTF_8));
            }

            ArrayList<String> physicalEntries = new ArrayList<>();
            try (TarArkivoStreamingReader reader =
                         TarArkivoStreamingReader.open(Files.newInputStream(archivePath))) {
                for (var readerEntry722 = reader.nextEntry(); readerEntry722 != null; readerEntry722 = reader.nextEntry()) {
                    physicalEntries.add(readerEntry722.attributes(TarArkivoEntryAttributes.class).path());
                }
            }
            assertEquals(
                    List.of(
                            "keep.txt",
                            "link",
                            "renamed/",
                            "renamed/child.txt",
                            "hard.txt",
                            "target-hard.txt",
                            "target.txt",
                            "new.txt"
                    ),
                    physicalEntries
            );
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that an explicit commit target can publish an updated derivative without changing the source.
    @Test
    public void updateCommitTargetCanPublishDerivedArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("tar-update-source-");
        Path derivedPath = createTemporaryArchivePath("tar-update-derived-");
        Files.deleteIfExists(derivedPath);
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
                writeStreamingFile(writer, "value.txt", "before".getBytes(StandardCharsets.UTF_8));
            }
            TarArchiveOptions.Update options = TarArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(derivedPath))
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archivePath, options)) {
                Files.writeString(fileSystem.getPath("/value.txt"), "after", StandardCharsets.UTF_8);
            }

            try (TarArkivoFileSystem source = TarArkivoFileSystem.open(archivePath);
                 TarArkivoFileSystem derived = TarArkivoFileSystem.open(derivedPath)) {
                assertEquals(
                        "before",
                        Files.readString(source.getPath("/value.txt"), StandardCharsets.UTF_8)
                );
                assertEquals(
                        "after",
                        Files.readString(derived.getPath("/value.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            Files.deleteIfExists(derivedPath);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that commit setup failure leaves the original TAR bytes untouched.
    @Test
    public void failedUpdateCommitLeavesOriginalArchiveUntouched() throws IOException {
        Path archivePath = createTemporaryArchivePath("tar-update-failure-");
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
                writeStreamingFile(writer, "value.txt", "before".getBytes(StandardCharsets.UTF_8));
            }
            byte[] originalArchive = Files.readAllBytes(archivePath);
            ArkivoCommitTarget failingTarget = sourcePath -> {
                throw new IOException("commit target failed");
            };
            TarArchiveOptions.Update options = TarArchiveOptions.UPDATE_DEFAULTS.withCommon(
                    ArchiveUpdateOptions.DEFAULT.withCommitTarget(failingTarget)
            );

            TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archivePath, options);
            Files.writeString(fileSystem.getPath("/value.txt"), "after", StandardCharsets.UTF_8);
            IOException exception = assertThrows(IOException.class, fileSystem::close);
            assertEquals("commit target failed", exception.getMessage());
            assertArrayEquals(originalArchive, Files.readAllBytes(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that closing an unchanged update session does not rewrite archive bytes.
    @Test
    public void unchangedUpdateLeavesArchiveBytesUntouched() throws IOException {
        Path archivePath = createTemporaryArchivePath("tar-update-unchanged-");
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
                writeStreamingFile(writer, "value.txt", "value".getBytes(StandardCharsets.UTF_8));
            }
            byte[] originalArchive = Files.readAllBytes(archivePath);
            try (TarArkivoFileSystem ignored = TarArkivoFileSystem.update(archivePath)) {
            }
            assertArrayEquals(originalArchive, Files.readAllBytes(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the create lifecycle publishes a valid empty archive for a missing path.
    @Test
    public void createModeCreatesMissingArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("tar-update-create-");
        Files.deleteIfExists(archivePath);
        try {
            try (TarArkivoFileSystem ignored = TarArkivoFileSystem.create(archivePath)) {
            }
            assertEquals(true, Files.exists(archivePath));
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath);
                 DirectoryStream<Path> entries = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                assertEquals(false, entries.iterator().hasNext());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that unknown TAR entry bodies survive a rewrite and remain stream-readable.
    @Test
    public void updatePreservesUnknownEntryBodies() throws IOException {
        byte[] extensionBody = "extension-data".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        writeHeader(archive, "extension.bin", 0600, 1, 2, extensionBody.length, 'V', "", "u", "g");
        writeBody(archive, extensionBody);
        archive.write(new byte[1024]);

        Path archivePath = createTemporaryArchivePath("tar-update-extension-");
        try {
            Files.write(archivePath, archive.toByteArray());
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archivePath)) {
                Files.writeString(fileSystem.getPath("/new.txt"), "new", StandardCharsets.UTF_8);
            }

            try (TarArkivoStreamingReader reader =
                         TarArkivoStreamingReader.open(Files.newInputStream(archivePath))) {
                var readerEntry875 = java.util.Objects.requireNonNull(reader.nextEntry());
                TarArkivoEntryAttributes extension = readerEntry875.attributes(TarArkivoEntryAttributes.class);
                assertEquals(true, extension.isOther());
                assertArrayEquals(extensionBody, readerEntry875.openInputStream().readAllBytes());
                var readerEntry879 = java.util.Objects.requireNonNull(reader.nextEntry());
                assertEquals("new.txt", readerEntry879.attributes(TarArkivoEntryAttributes.class).path());
                org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }


    /// Verifies that provider URI entry points register and unregister TAR file systems.
    @Test
    public void providerUriLifecycleSupportsEntryPaths() throws IOException {
        byte[] content = "provider".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("tar-provider-");
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
                var writerEntry896 = writer.beginFile("dir/provider.txt");
                try (OutputStream body = writerEntry896.openOutputStream()) {
                    body.write(content);
                }
            }

            TarArkivoFileSystemProvider provider = TarArkivoFileSystemProvider.instance();
            URI archiveUri = archivePath.toUri().normalize();
            URI fileSystemUri = URI.create(TarArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString());
            URI entryUri = URI.create(
                    TarArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString() + "!/dir/provider.txt"
            );

            FileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of());
            try (fileSystem) {
                assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
                assertThrows(FileSystemAlreadyExistsException.class, () -> provider.newFileSystem(fileSystemUri, Map.of()));

                Path entry = provider.getPath(entryUri);
                assertEquals(entryUri, entry.toUri());
                assertArrayEquals(content, Files.readAllBytes(entry));
            }

            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));

            try (FileSystem reopenedFileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(reopenedFileSystem, provider.getFileSystem(fileSystemUri));
                assertArrayEquals(content, Files.readAllBytes(provider.getPath(entryUri)));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that TAR hard links resolve to earlier regular file content in the read-only file system.
    @Test
    public void opensHardLinksAsReadOnlyFileSystemEntries() throws IOException {
        byte[] content = "linked content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "original.txt", content);
        writeHeader(output, "copy.txt", 0644, 1000, 1000, 0, '1', "original.txt", "user", "group");
        output.write(new byte[1024]);

        Path archivePath = createTemporaryArchivePath("tar-hard-link-fs-");
        try {
            Files.write(archivePath, output.toByteArray());

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                Path original = fileSystem.getPath("/original.txt");
                Path copy = fileSystem.getPath("/copy.txt");

                assertArrayEquals(content, Files.readAllBytes(original));
                assertArrayEquals(content, Files.readAllBytes(copy));
                assertEquals((long) content.length, Files.size(copy));

                TarArkivoEntryAttributes copyAttributes =
                        Files.readAttributes(copy, TarArkivoEntryAttributes.class);
                assertEquals(true, copyAttributes.isHardLink());
                assertEquals(true, copyAttributes.isRegularFile());
                assertEquals(false, copyAttributes.isSymbolicLink());
                assertEquals(false, copyAttributes.isOther());
                assertEquals((byte) '1', copyAttributes.typeFlag());
                assertEquals("original.txt", copyAttributes.linkName());
                assertEquals(content.length, copyAttributes.size());

                Map<String, Object> namedAttributes = Files.readAttributes(
                        copy,
                        "tar:isHardLink,isRegularFile,isOther,typeFlag,linkName,size"
                );
                assertEquals(true, namedAttributes.get("isHardLink"));
                assertEquals(true, namedAttributes.get("isRegularFile"));
                assertEquals(false, namedAttributes.get("isOther"));
                assertEquals((byte) '1', namedAttributes.get("typeFlag"));
                assertEquals("original.txt", namedAttributes.get("linkName"));
                assertEquals((long) content.length, namedAttributes.get("size"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming writer emits PAX metadata for long paths and link targets.
    @Test
    public void writesPaxMetadataForLongNames() throws IOException {
        byte[] content = "long".getBytes(StandardCharsets.UTF_8);
        String path = "pax/this-file-name-segment-is-long-enough-to-require-pax-metadata-because-ustar-name-fields"
                + "-cannot-store-this-single-segment-without-an-extension.txt";
        String target = "this-link-target-segment-is-long-enough-to-require-pax-metadata-because-ustar-link-fields"
                + "-cannot-store-this-single-segment-without-an-extension.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            var writerEntry987 = writer.beginFile(path);
            try (OutputStream body = writerEntry987.openOutputStream()) {
                body.write(content);
            }

            var writerEntry992 = writer.beginSymbolicLink("long-link", target);
            writerEntry992.close();

            var writerEntry995 = writer.beginHardLink("long-hard-link", path);
            writerEntry995.close();
        }

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1001 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1001.attributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            assertEquals(content.length, file.size());
            try (var input = readerEntry1001.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            var readerEntry1009 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry1009.attributes(TarArkivoEntryAttributes.class);
            assertEquals("long-link", link.path());
            assertEquals(target, link.linkName());

            var readerEntry1014 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes hardLink = readerEntry1014.attributes(TarArkivoEntryAttributes.class);
            assertEquals("long-hard-link", hardLink.path());
            assertEquals(true, hardLink.isHardLink());
            assertEquals(path, hardLink.linkName());

            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that the streaming writer rejects unsafe archive paths before writing them.
    @Test
    public void writerRejectsUnsafeEntryPaths() throws IOException {
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(new ByteArrayOutputStream())) {
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("../evil.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginDirectory("/absolute"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("C:/evil.txt", "target"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginHardLink("copy.txt", "../target.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginHardLink("copy.txt", "/target.txt"));
        }
    }

    /// Verifies that streaming reader operations fail as closed after the reader is closed.
    @Test
    public void readerOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(tarArchive()));
        var readerEntry1040 = java.util.Objects.requireNonNull(reader.nextEntry());

        reader.close();

        assertThrows(ClosedChannelException.class, reader::nextEntry);
        assertThrows(
                ClosedChannelException.class,
                () -> readerEntry1040.attributes(TarArkivoEntryAttributes.class)
        );
        assertThrows(ClosedChannelException.class, readerEntry1040::openChannel);
    }

    /// Verifies that source cleanup can be retried after a close failure.
    @Test
    public void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(tarArchive());
        TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(source);
        var readerEntry1057 = java.util.Objects.requireNonNull(reader.nextEntry());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(ClosedChannelException.class, reader::nextEntry);
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Verifies that a closed entry channel rejects reads and leaves the reader able to advance.
    @Test
    public void entryChannelOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(tarArchive()))) {
            var readerEntry1074 = java.util.Objects.requireNonNull(reader.nextEntry());
            var readerEntry1075 = java.util.Objects.requireNonNull(reader.nextEntry());

            ReadableByteChannel channel = readerEntry1075.openChannel();
            channel.close();

            assertEquals(false, channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            var readerEntry1082 = java.util.Objects.requireNonNull(reader.nextEntry());
            assertEquals("link", readerEntry1082.attributes(TarArkivoEntryAttributes.class).path());
        }
    }

    /// Verifies that per-entry PAX metadata overrides fixed-width USTAR header fields.
    @Test
    public void readsPaxExtendedMetadata() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String path = "this/path/is/long/enough/to/require/pax/metadata/when/stored/by/portable/tar/tools/file.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "atime", "1893456001.5",
                "ctime", "1893456002.75",
                "mtime", "1893456000.25",
                "path", path,
                "size", Long.toString(content.length),
                "uname", "pax-user"
        ));
        writeHeader(output, "short-name", 0644, 1000, 1000, 0, '0', "", "user", "group");
        writeBody(output, content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1107 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1107.attributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            assertEquals(5L, file.size());
            assertEquals("pax-user", file.userName());
            assertEquals(Instant.ofEpochSecond(1_893_456_000L, 250_000_000L), file.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_001L, 500_000_000L), file.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_002L, 750_000_000L), file.creationTime().toInstant());
            try (var input = readerEntry1107.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that a TAR-specific detector receives fixed-header context for an ambiguous entry path.
    @Test
    public void detectsFixedHeaderMetadataCharset() throws IOException {
        Charset gb18030 = Charset.forName("GB18030");
        String path = "目录.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeRawPathHeader(output, path.getBytes(gb18030));
        output.write(new byte[1024]);
        TarMetadataCharsetDetector detector = context -> {
            assertEquals(TarMetadataCharsetDetector.MetadataKind.ENTRY_NAME, context.metadataKind());
            assertEquals(TarMetadataCharsetDetector.Source.HEADER, context.source());
            assertEquals(TarMetadataCharsetDetector.HeaderDialect.USTAR, context.headerDialect());
            assertEquals((int) '0', context.typeFlag());
            assertNull(context.paxKey());
            assertEquals(true, context.bytes().isReadOnly());
            return gb18030;
        };

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(output.toByteArray()),
                TarArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(detector)
        )) {
            var readerEntry1147 = java.util.Objects.requireNonNull(reader.nextEntry());
            assertEquals(path, readerEntry1147.attributes(TarArkivoEntryAttributes.class).path());
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that binary PAX path values receive PAX-specific context and can select a legacy charset.
    @Test
    public void detectsBinaryPaxMetadataCharset() throws IOException {
        Charset gb18030 = Charset.forName("GB18030");
        String path = "二进制目录.txt";
        ByteArrayOutputStream paxBody = new ByteArrayOutputStream();
        paxBody.write(paxRecord("hdrcharset", "BINARY"));
        paxBody.write(paxRecord("path", path.getBytes(gb18030)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMetadataEntry(output, "PaxHeaders/entry", 'x', paxBody.toByteArray());
        writeRawPathHeader(output, "short-name".getBytes(StandardCharsets.UTF_8));
        output.write(new byte[1024]);
        int[] paxDetectorCalls = new int[1];
        TarMetadataCharsetDetector detector = context -> {
            if (context.source() == TarMetadataCharsetDetector.Source.PAX_EXTENDED_HEADER) {
                paxDetectorCalls[0]++;
                assertEquals(TarMetadataCharsetDetector.MetadataKind.ENTRY_NAME, context.metadataKind());
                assertEquals("path", context.paxKey());
                assertEquals((int) 'x', context.typeFlag());
                return gb18030;
            }
            return StandardCharsets.UTF_8;
        };

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(output.toByteArray()),
                TarArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(detector)
        )) {
            var readerEntry1184 = java.util.Objects.requireNonNull(reader.nextEntry());
            assertEquals(path, readerEntry1184.attributes(TarArkivoEntryAttributes.class).path());
            assertEquals(1, paxDetectorCalls[0]);
        }
    }

    /// Verifies that ordinary PAX UTF-8 values bypass the metadata charset detector.
    @Test
    public void ordinaryPaxUtf8BypassesDetector() throws IOException {
        String path = "标准目录.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of("path", path));
        writeRawPathHeader(output, "short-name".getBytes(StandardCharsets.UTF_8));
        output.write(new byte[1024]);
        int[] paxDetectorCalls = new int[1];
        TarMetadataCharsetDetector detector = context -> {
            if (context.source() == TarMetadataCharsetDetector.Source.PAX_EXTENDED_HEADER) {
                paxDetectorCalls[0]++;
            }
            return StandardCharsets.UTF_8;
        };

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(output.toByteArray()),
                TarArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(detector)
        )) {
            var readerEntry1213 = java.util.Objects.requireNonNull(reader.nextEntry());
            assertEquals(path, readerEntry1213.attributes(TarArkivoEntryAttributes.class).path());
            assertEquals(0, paxDetectorCalls[0]);
        }
    }

    /// Verifies PAX bodies are rejected before their variable metadata bytes are buffered.
    @Test
    public void enforcesMetadataLimitBeforePaxBodyAllocation() throws IOException {
        byte[] body = paxRecord("path", "metadata-path.txt");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMetadataEntry(output, "PaxHeaders/entry", 'x', body);
        long maximum = 512L;

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(output.toByteArray()),
                TarArchiveOptions.READ_DEFAULTS.withCommon(
                        ArchiveReadOptions.DEFAULT.withLimits(
                                ArchiveReadLimits.builder().maximumMetadataSize(maximum).build()
                        )
                )
        )) {
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::nextEntry);
            assertEquals(ArkivoReadLimitKind.METADATA_SIZE, exception.kind());
            assertEquals(maximum, exception.maximum());
            assertEquals(maximum + body.length, exception.actual());
            assertEquals("PaxHeaders/entry", exception.entryPath());

            ArkivoReadLimitException repeated = assertThrows(ArkivoReadLimitException.class, reader::nextEntry);
            assertEquals(exception.kind(), repeated.kind());
            assertEquals(exception.maximum(), repeated.maximum());
            assertEquals(exception.actual(), repeated.actual());
            assertEquals(exception.entryPath(), repeated.entryPath());
        }
    }

    /// Verifies that TAR entry paths cannot contain parent-directory segments.
    @Test
    public void rejectsParentSegmentEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "../evil.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("TAR entry path must not contain .."));
        }
    }

    /// Verifies that TAR entry paths must be relative.
    @Test
    public void rejectsAbsoluteEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "/evil.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("TAR entry path must be relative"));
        }
    }

    /// Verifies that backslash-separated parent-directory segments are rejected.
    @Test
    public void rejectsBackslashParentSegmentEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeEntry(output, "..\\evil.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("TAR entry path must not contain .."));
        }
    }

    /// Verifies that PAX path overrides must still contain a usable entry path.
    @Test
    public void rejectsDotOnlyPaxEntryPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of("path", "."));
        writeEntry(output, "fallback.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("TAR entry is missing a path"));
        }
    }

    /// Verifies that global PAX metadata applies until per-entry PAX metadata overrides it.
    @Test
    public void readsGlobalPaxMetadata() throws IOException {
        byte[] firstContent = "first".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGlobalPaxHeader(output, Map.of(
                "atime", "1893456010.25",
                "ctime", "1893456020.5",
                "gid", "222",
                "gname", "global-group",
                "mtime", "1893456000.125",
                "uid", "111",
                "uname", "global-user"
        ));
        writeEntry(output, "first.txt", firstContent);
        writePaxHeader(output, Map.of(
                "mtime", "1893456030.75",
                "uname", "entry-user"
        ));
        writeEntry(output, "second.txt", secondContent);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1330 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes first = readerEntry1330.attributes(TarArkivoEntryAttributes.class);
            assertEquals("first.txt", first.path());
            assertEquals(111L, first.userId());
            assertEquals(222L, first.groupId());
            assertEquals("global-user", first.userName());
            assertEquals("global-group", first.groupName());
            assertEquals(Instant.ofEpochSecond(1_893_456_000L, 125_000_000L), first.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_010L, 250_000_000L), first.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_020L, 500_000_000L), first.creationTime().toInstant());
            try (var input = readerEntry1330.openInputStream()) {
                assertArrayEquals(firstContent, input.readAllBytes());
            }

            var readerEntry1344 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes second = readerEntry1344.attributes(TarArkivoEntryAttributes.class);
            assertEquals("second.txt", second.path());
            assertEquals(111L, second.userId());
            assertEquals(222L, second.groupId());
            assertEquals("entry-user", second.userName());
            assertEquals("global-group", second.groupName());
            assertEquals(Instant.ofEpochSecond(1_893_456_030L, 750_000_000L), second.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_010L, 250_000_000L), second.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_020L, 500_000_000L), second.creationTime().toInstant());
            try (var input = readerEntry1344.openInputStream()) {
                assertArrayEquals(secondContent, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that global PAX paths and sizes apply to subsequent entries.
    @Test
    public void readsGlobalPaxPathAndSize() throws IOException {
        byte[] content = "global size".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGlobalPaxHeader(output, Map.of(
                "path", "global-path.txt",
                "size", Integer.toString(content.length)
        ));
        writeHeader(output, "fallback-path.txt", 0644, 1000, 1000, 0, '0', "", "user", "group");
        writeBody(output, content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1376 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1376.attributes(TarArkivoEntryAttributes.class);
            assertEquals("global-path.txt", file.path());
            assertEquals(content.length, file.size());
            try (var input = readerEntry1376.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that global PAX link paths apply to subsequent symbolic links.
    @Test
    public void readsGlobalPaxLinkPath() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGlobalPaxHeader(output, Map.of("linkpath", "global-target.txt"));
        writeHeader(output, "link", 0777, 0, 0, 0, '2', "fallback-target.txt", "user", "group");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1397 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry1397.attributes(TarArkivoEntryAttributes.class);
            assertEquals(true, link.isSymbolicLink());
            assertEquals("global-target.txt", link.linkName());
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that empty global PAX values remove previously active global values.
    @Test
    public void removesGlobalPaxRecordWithEmptyValue() throws IOException {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGlobalPaxHeader(output, Map.of("uname", "global-user"));
        writeGlobalPaxHeader(output, Map.of("uname", ""));
        writeEntry(output, "file.txt", content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1417 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1417.attributes(TarArkivoEntryAttributes.class);
            assertEquals("file.txt", file.path());
            assertEquals("user", file.userName());
            try (var input = readerEntry1417.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that empty per-entry PAX user and group names delete string metadata.
    @Test
    public void deletesPaxUserAndGroupNamesWithEmptyValues() throws IOException {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGlobalPaxHeader(output, Map.of(
                "gname", "global-group",
                "uname", "global-user"
        ));
        writePaxHeader(output, Map.of(
                "gname", "",
                "uname", ""
        ));
        writeEntry(output, "file.txt", content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1446 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1446.attributes(TarArkivoEntryAttributes.class);
            assertEquals("file.txt", file.path());
            assertNull(file.userName());
            assertNull(file.groupName());
            try (var input = readerEntry1446.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that an empty per-entry PAX link path deletes the active link target.
    @Test
    public void deletesPaxLinkPathWithEmptyValue() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGlobalPaxHeader(output, Map.of("linkpath", "global-target.txt"));
        writePaxHeader(output, Map.of("linkpath", ""));
        writeHeader(output, "link", 0777, 0, 0, 0, '2', "fallback-target.txt", "user", "group");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1469 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry1469.attributes(TarArkivoEntryAttributes.class);
            assertEquals(true, link.isSymbolicLink());
            assertNull(link.linkName());
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that negative fractional PAX timestamps are parsed relative to the Unix epoch.
    @Test
    public void readsNegativePaxTimestamps() throws IOException {
        byte[] content = "negative time".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "atime", "-0.25",
                "ctime", "-2",
                "mtime", "-1.5"
        ));
        writeEntry(output, "negative-time.txt", content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1492 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1492.attributes(TarArkivoEntryAttributes.class);
            assertEquals("negative-time.txt", file.path());
            assertEquals(Instant.ofEpochSecond(-2L, 500_000_000L), file.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(-1L, 750_000_000L), file.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(-2L), file.creationTime().toInstant());
            try (var input = readerEntry1492.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that out-of-range PAX timestamps are reported as I/O errors.
    @Test
    public void rejectsOutOfRangePaxTimestamp() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "mtime", Long.toString(Long.MAX_VALUE)
        ));
        writeEntry(output, "out-of-range-pax-time.txt", new byte[0]);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("TAR PAX mtime is out of range"));
        }
    }

    /// Verifies that malformed PAX record boundaries are rejected.
    @Test
    public void rejectsMalformedPaxRecordBoundary() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMetadataEntry(output, "PaxHeaders/entry", 'x', "100 path=file.txt\n".getBytes(StandardCharsets.UTF_8));
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("Invalid TAR PAX record boundary"));
        }
    }

    /// Verifies that TAR string fields preserve leading and trailing spaces.
    @Test
    public void preservesStringFieldSpaces() throws IOException {
        byte[] content = "spaces".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeHeader(output, " leading-and-trailing.txt ", 0644, 1000, 1000, content.length, '0', "", " user ", " group ");
        writeBody(output, content);
        writeHeader(output, " link-with-spaces ", 0777, 0, 0, 0, '2', " target-with-spaces ", "", "");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1548 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1548.attributes(TarArkivoEntryAttributes.class);
            assertEquals(" leading-and-trailing.txt ", file.path());
            assertEquals(" user ", file.userName());
            assertEquals(" group ", file.groupName());
            try (var input = readerEntry1548.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            var readerEntry1557 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry1557.attributes(TarArkivoEntryAttributes.class);
            assertEquals(" link-with-spaces ", link.path());
            assertEquals(" target-with-spaces ", link.linkName());
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that GNU long path and long link metadata entries are applied to the following entries.
    @Test
    public void readsGnuLongMetadata() throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        String path = "gnu/long/path/that/does/not/fit/in/the/plain/ustar/name/field/without/a/metadata/entry/file.txt";
        String target = "gnu/long/target/that/does/not/fit/in/the/plain/ustar/link/field/without/a/metadata/entry/file.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMetadataEntry(output, "././@LongLink", 'L', (path + "\0").getBytes(StandardCharsets.UTF_8));
        writeEntry(output, "short-name", content);
        writeMetadataEntry(output, "././@LongLink", 'K', (target + "\0").getBytes(StandardCharsets.UTF_8));
        writeHeader(output, "link", 0777, 0, 0, 0, '2', "short-target", "user", "group");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1580 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1580.attributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            try (var input = readerEntry1580.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            var readerEntry1587 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes link = readerEntry1587.attributes(TarArkivoEntryAttributes.class);
            assertEquals(target, link.linkName());
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that base-256 encoded numeric fields are parsed.
    @Test
    public void readsBase256NumericFields() throws IOException {
        byte[] content = "base256".getBytes(StandardCharsets.UTF_8);
        long userId = 10_000_000_000L;
        long groupId = 20_000_000_000L;
        long modificationTime = 4_294_967_296L;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBase256Header(output, "binary-numbers.txt", 0640, userId, groupId, content.length, modificationTime);
        writeBody(output, content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1608 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1608.attributes(TarArkivoEntryAttributes.class);

            assertEquals("binary-numbers.txt", file.path());
            assertEquals(0640, file.mode());
            assertEquals(userId, file.userId());
            assertEquals(groupId, file.groupId());
            assertEquals(content.length, file.size());
            assertEquals(Instant.ofEpochSecond(modificationTime), file.lastModifiedTime().toInstant());
            try (var input = readerEntry1608.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that out-of-range header timestamps are reported as I/O errors.
    @Test
    public void rejectsOutOfRangeHeaderTimestamp() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBase256Header(output, "out-of-range-header-time.txt", 0644, 1000, 1000, 0, Long.MAX_VALUE);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("TAR modification time is out of range"));
        }
    }

    /// Verifies that legacy signed TAR header checksums are accepted.
    @Test
    public void readsSignedChecksumHeader() throws IOException {
        byte[] content = "signed".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeSignedChecksumHeader(output, "signed-checksum.txt", content.length);
        writeBody(output, content);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1649 = java.util.Objects.requireNonNull(reader.nextEntry());
            TarArkivoEntryAttributes file = readerEntry1649.attributes(TarArkivoEntryAttributes.class);

            assertEquals("signed-checksum.txt", file.path());
            assertEquals("u\u00ff", file.userName());
            try (var input = readerEntry1649.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertNull(reader.nextEntry());
        }
    }

    /// Verifies that negative base-256 entry sizes are rejected as invalid TAR metadata.
    @Test
    public void rejectsNegativeBase256Size() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeNegativeBase256SizeHeader(output, "negative-size.txt");
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("entry size must not be negative"));
        }
    }

    /// Verifies that PAX user and group IDs must be non-negative.
    @Test
    public void rejectsNegativePaxUserAndGroupIds() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, Map.of(
                "gid", "-2",
                "uid", "-1"
        ));
        writeEntry(output, "negative-ids.txt", "negative ids".getBytes(StandardCharsets.UTF_8));
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("TAR PAX uid must not be negative"));
        }
    }

    /// Verifies that a huge declared size cannot overflow body and padding skipping.
    @Test
    public void rejectsTruncatedEntryWhosePaddingOverflowsSizeSum() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBase256Header(output, "huge-size.txt", 0644, 1000, 1000, Long.MAX_VALUE, 1_893_456_000L);
        output.write(new byte[1024]);

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            var readerEntry1702 = java.util.Objects.requireNonNull(reader.nextEntry());
            assertEquals(Long.MAX_VALUE, readerEntry1702.attributes(TarArkivoEntryAttributes.class).size());

            IOException exception = assertThrows(IOException.class, reader::nextEntry);
            assertEquals(true, exception.getMessage().contains("Unexpected end of TAR entry body"));
        }
    }

    /// Returns a small TAR archive.
    private static byte[] tarArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeHeader(output, "dir/", 0755, 0, 0, 0, '5', "", "user", "group");
        writeEntry(output, "dir/hello.txt", "hello".getBytes(StandardCharsets.UTF_8));
        writeHeader(output, "link", 0777, 0, 0, 0, '2', "dir/hello.txt", "user", "group");
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    /// Creates a temporary TAR archive path under the build directory.
    /// Writes one regular file through a TAR streaming writer.
    private static void writeStreamingFile(
            TarArkivoStreamingWriter writer,
            String path,
            byte @Unmodifiable [] content
    ) throws IOException {
        var writerEntry1727 = writer.beginFile(path);
        try (OutputStream output = writerEntry1727.openOutputStream()) {
            output.write(content);
        }
    }

    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-tar-tests");
        Files.createDirectories(temporaryRoot);
        return Files.createTempFile(temporaryRoot, prefix, ".tar");
    }

    /// Deletes a temporary TAR archive when it exists.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
    }

    /// Writes a regular file entry.
    private static void writeEntry(ByteArrayOutputStream output, String path, byte[] content) throws IOException {
        writeHeader(output, path, 0644, 1000, 1000, content.length, '0', "", "user", "group");
        writeBody(output, content);
    }

    /// Writes a TAR entry body with record padding.
    private static void writeBody(ByteArrayOutputStream output, byte[] content) throws IOException {
        output.write(content);
        int padding = (int) ((512 - (content.length % 512)) % 512);
        output.write(new byte[padding]);
    }

    /// Writes one metadata entry.
    private static void writeMetadataEntry(
            ByteArrayOutputStream output,
            String path,
            int typeFlag,
            byte[] body
    ) throws IOException {
        writeHeader(output, path, 0644, 0, 0, body.length, typeFlag, "", "", "");
        writeBody(output, body);
    }

    /// Writes one PAX extended header entry.
    private static void writePaxHeader(ByteArrayOutputStream output, Map<String, String> records) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> record : records.entrySet()) {
            body.write(paxRecord(record.getKey(), record.getValue()));
        }
        writeMetadataEntry(output, "PaxHeaders/entry", 'x', body.toByteArray());
    }

    /// Writes one PAX global extended header entry.
    private static void writeGlobalPaxHeader(ByteArrayOutputStream output, Map<String, String> records)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> record : records.entrySet()) {
            body.write(paxRecord(record.getKey(), record.getValue()));
        }
        writeMetadataEntry(output, "GlobalHead/entry", 'g', body.toByteArray());
    }

    /// Returns one encoded PAX key-value record.
    private static byte[] paxRecord(String key, String value) {
        return paxRecord(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /// Returns one encoded PAX key-value record with raw value bytes.
    private static byte[] paxRecord(String key, byte[] value) {
        byte[] prefix = (key + "=").getBytes(StandardCharsets.UTF_8);
        int payloadLength = prefix.length + value.length + 1;
        int digits = 1;
        while (true) {
            int length = digits + 1 + payloadLength;
            int actualDigits = Integer.toString(length).length();
            if (actualDigits == digits) {
                byte[] recordPrefix = (length + " ").getBytes(StandardCharsets.US_ASCII);
                byte[] result = new byte[length];
                int offset = 0;
                System.arraycopy(recordPrefix, 0, result, offset, recordPrefix.length);
                offset += recordPrefix.length;
                System.arraycopy(prefix, 0, result, offset, prefix.length);
                offset += prefix.length;
                System.arraycopy(value, 0, result, offset, value.length);
                result[result.length - 1] = '\n';
                return result;
            }
            digits = actualDigits;
        }
    }

    /// Writes one empty regular-file header whose path field contains caller-supplied bytes.
    private static void writeRawPathHeader(ByteArrayOutputStream output, byte[] pathBytes) throws IOException {
        if (pathBytes.length > 100) {
            throw new IllegalArgumentException("pathBytes is too long");
        }
        byte[] header = new byte[512];
        System.arraycopy(pathBytes, 0, header, 0, pathBytes.length);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 1000);
        writeOctal(header, 116, 8, 1000);
        writeOctal(header, 124, 12, 0);
        writeOctal(header, 136, 12, 1_893_456_000);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = '0';
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one TAR header.
    private static void writeHeader(
            ByteArrayOutputStream output,
            String path,
            int mode,
            int userId,
            int groupId,
            int size,
            int typeFlag,
            String linkName,
            String userName,
            String groupName
    ) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, mode);
        writeOctal(header, 108, 8, userId);
        writeOctal(header, 116, 8, groupId);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 1_893_456_000);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = (byte) typeFlag;
        writeString(header, 157, 100, linkName);
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");
        writeString(header, 265, 32, userName);
        writeString(header, 297, 32, groupName);

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one TAR header whose numeric fields use base-256 encoding.
    private static void writeBase256Header(
            ByteArrayOutputStream output,
            String path,
            int mode,
            long userId,
            long groupId,
            long size,
            long modificationTime
    ) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeBase256Number(header, 100, 8, mode);
        writeBase256Number(header, 108, 8, userId);
        writeBase256Number(header, 116, 8, groupId);
        writeBase256Number(header, 124, 12, size);
        writeBase256Number(header, 136, 12, modificationTime);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = '0';
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one TAR header with a negative base-256 size field.
    private static void writeNegativeBase256SizeHeader(ByteArrayOutputStream output, String path) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 1000);
        writeOctal(header, 116, 8, 1000);
        writeNegativeBase256Number(header, 124, 12);
        writeOctal(header, 136, 12, 1_893_456_000);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = '0';
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one TAR header whose checksum uses signed byte arithmetic.
    private static void writeSignedChecksumHeader(ByteArrayOutputStream output, String path, int size) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 1000);
        writeOctal(header, 116, 8, 1000);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 1_893_456_000);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = '0';
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");
        header[265] = 'u';
        header[266] = (byte) 0xc3;
        header[267] = (byte) 0xbf;

        int checksum = 0;
        for (byte value : header) {
            checksum += value;
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes a null-terminated string field.
    private static void writeString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= length) {
            throw new IllegalArgumentException("value is too long");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    /// Writes a fixed-width string field.
    private static void writeRawString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != length) {
            throw new IllegalArgumentException("value must match the field length");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    /// Writes an octal number field.
    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String text = Long.toOctalString(value);
        int start = offset + length - text.length() - 1;
        for (int index = offset; index < start; index++) {
            header[index] = '0';
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, start, bytes.length);
    }

    /// Writes a positive base-256 TAR number field.
    private static void writeBase256Number(byte[] header, int offset, int length, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
        long remaining = value;
        for (int index = offset + length - 1; index > offset; index--) {
            header[index] = (byte) remaining;
            remaining >>>= 8;
        }
        if (remaining > 0x7f) {
            throw new IllegalArgumentException("value is too large");
        }
        header[offset] = (byte) (0x80 | remaining);
    }

    /// Writes a negative base-256 TAR number field.
    private static void writeNegativeBase256Number(byte[] header, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            header[index] = (byte) 0xff;
        }
    }

    /// Writes the TAR checksum field.
    private static void writeChecksum(byte[] header, int checksum) {
        String text = String.format("%06o", checksum);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, 148, bytes.length);
        header[154] = 0;
        header[155] = (byte) ' ';
    }

    /// Input stream that fails its first close call.
    @NotNullByDefault
    private static final class CloseFailingOnceInputStream extends ByteArrayInputStream {
        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing input stream over the given bytes.
        private CloseFailingOnceInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Fails on the first close call and records all close attempts.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            super.close();
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }
}

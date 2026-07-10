// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoCommitTarget;
import org.glavo.arkivo.ArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests AR streaming reader behavior.
@NotNullByDefault
public final class ArArkivoStreamingReaderTest {
    /// Verifies that ordinary AR members can be streamed with metadata.
    @Test
    public void readsOrdinaryMembers() throws IOException {
        byte[] first = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] second = "world!".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(
                member("hello.txt/", 1_700_000_000L, 1000, 1001, 0100644, first),
                member("dir/file.bin/", 1_700_000_010L, 1002, 1003, 0100600, second)
        );
        ArrayList<String> paths = new ArrayList<>();

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes firstAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            BasicFileAttributes firstBasicAttributes = reader.readAttributes(BasicFileAttributes.class);
            PosixFileAttributes firstPosixAttributes = reader.readAttributes(PosixFileAttributes.class);
            paths.add(firstAttributes.path());
            assertEquals("hello.txt", firstAttributes.path());
            assertEquals("hello.txt/", firstAttributes.identifier());
            assertEquals(1000, firstAttributes.userId());
            assertEquals(1001, firstAttributes.groupId());
            assertEquals(0100644, firstAttributes.mode());
            assertEquals(first.length, firstAttributes.size());
            assertEquals(FileTime.fromMillis(1_700_000_000_000L), firstAttributes.lastModifiedTime());
            assertEquals(true, firstBasicAttributes.isRegularFile());
            assertEquals("1000", firstPosixAttributes.owner().getName());
            assertEquals("1001", firstPosixAttributes.group().getName());
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.OTHERS_READ
                    ),
                    firstPosixAttributes.permissions()
            );
            try (var input = reader.openInputStream()) {
                assertArrayEquals(first, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ArArkivoEntryAttributes secondAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            paths.add(secondAttributes.path());
            assertEquals("dir/file.bin", secondAttributes.path());
            assertEquals(second.length, secondAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(second, input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }

        assertEquals(List.of("hello.txt", "dir/file.bin"), paths);
    }

    /// Verifies that AR POSIX mode type bits are exposed through basic attributes.
    @Test
    public void readsPosixModeFileTypes() throws IOException {
        byte[] target = "dir/hello.txt".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(
                member("link/", 0, 0, 0, 0120777, target),
                member("fifo/", 0, 0, 0, 0010644, new byte[0])
        );

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes linkAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("link", linkAttributes.path());
            assertEquals(false, linkAttributes.isRegularFile());
            assertEquals(false, linkAttributes.isDirectory());
            assertEquals(true, linkAttributes.isSymbolicLink());
            assertEquals(false, linkAttributes.isOther());
            assertEquals(target.length, linkAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(target, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ArArkivoEntryAttributes fifoAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("fifo", fifoAttributes.path());
            assertEquals(false, fifoAttributes.isRegularFile());
            assertEquals(false, fifoAttributes.isDirectory());
            assertEquals(false, fifoAttributes.isSymbolicLink());
            assertEquals(true, fifoAttributes.isOther());

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that the streaming writer creates readable AR members.
    @Test
    public void writesStreamingMembers() throws IOException {
        byte[] first = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        String longPath = "very-long-file-name-that-requires-bsd-inline-name.txt";
        String unicodePath = "unicode/naive-\u00ef.txt";

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writer.beginFile("hello.txt");
            try (OutputStream body = writer.openOutputStream()) {
                body.write(first);
            }

            writer.beginFile(longPath);
            try (OutputStream body = writer.openOutputStream()) {
                body.write(second);
            }

            writer.beginFile(unicodePath);
            writer.endEntry();
        }

        try (ArArkivoStreamingReader reader =
                     ArArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes firstAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("hello.txt", firstAttributes.path());
            assertEquals("hello.txt/", firstAttributes.identifier());
            assertEquals(0, firstAttributes.userId());
            assertEquals(0, firstAttributes.groupId());
            assertEquals(0100644, firstAttributes.mode());
            assertEquals(FileTime.fromMillis(0L), firstAttributes.lastModifiedTime());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(first, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ArArkivoEntryAttributes secondAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals(longPath, secondAttributes.path());
            assertEquals("#1/" + longPath.getBytes(StandardCharsets.UTF_8).length, secondAttributes.identifier());
            assertEquals(second.length, secondAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(second, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ArArkivoEntryAttributes unicodeAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals(unicodePath, unicodeAttributes.path());
            assertEquals("#1/" + unicodePath.getBytes(StandardCharsets.UTF_8).length, unicodeAttributes.identifier());
            assertEquals(0L, unicodeAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that the streaming writer creates symbolic link AR members.
    @Test
    public void writesSymbolicLinkMembers() throws IOException {
        String target = "dir/hello.txt";
        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writer.beginSymbolicLink("link", target);
            ArArkivoEntryAttributeView attributes =
                    Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
            ArArkivoEntryAttributes pendingAttributes = attributes.readAttributes();
            assertEquals("link", pendingAttributes.path());
            assertEquals(0120777, pendingAttributes.mode());
            assertEquals(targetBytes.length, pendingAttributes.size());
            assertEquals(false, pendingAttributes.isRegularFile());
            assertEquals(true, pendingAttributes.isSymbolicLink());
            assertThrows(IllegalStateException.class, writer::openOutputStream);
            writer.endEntry();
        }

        try (ArArkivoStreamingReader reader =
                     ArArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes attributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("link", attributes.path());
            assertEquals("link/", attributes.identifier());
            assertEquals(0120777, attributes.mode());
            assertEquals(targetBytes.length, attributes.size());
            assertEquals(false, attributes.isRegularFile());
            assertEquals(true, attributes.isSymbolicLink());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(targetBytes, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that the streaming writer creates directory AR members.
    @Test
    public void writesDirectoryMembers() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writer.beginDirectory("dir");
            ArArkivoEntryAttributeView attributes =
                    Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
            ArArkivoEntryAttributes pendingAttributes = attributes.readAttributes();
            assertEquals("dir", pendingAttributes.path());
            assertEquals("dir/", pendingAttributes.identifier());
            assertEquals(040755, pendingAttributes.mode());
            assertEquals(0L, pendingAttributes.size());
            assertEquals(false, pendingAttributes.isRegularFile());
            assertEquals(true, pendingAttributes.isDirectory());
            assertEquals(false, pendingAttributes.isSymbolicLink());
            assertThrows(IllegalStateException.class, writer::openOutputStream);
            writer.endEntry();

            writer.beginDirectory("configured");
            ArArkivoEntryAttributeView configuredAttributes =
                    Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
            assertThrows(IllegalArgumentException.class, () -> configuredAttributes.setMode(0100644));
            assertThrows(IOException.class, () -> configuredAttributes.setSize(1L));
            configuredAttributes.setMode(040700);
            configuredAttributes.setSize(0L);
            writer.endEntry();
        }

        try (ArArkivoStreamingReader reader =
                     ArArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes directoryAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("dir", directoryAttributes.path());
            assertEquals("dir/", directoryAttributes.identifier());
            assertEquals(040755, directoryAttributes.mode());
            assertEquals(0L, directoryAttributes.size());
            assertEquals(false, directoryAttributes.isRegularFile());
            assertEquals(true, directoryAttributes.isDirectory());
            assertEquals(false, directoryAttributes.isSymbolicLink());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ArArkivoEntryAttributes configuredAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("configured", configuredAttributes.path());
            assertEquals(040700, configuredAttributes.mode());
            assertEquals(true, configuredAttributes.isDirectory());
            assertEquals(false, configuredAttributes.isRegularFile());
            assertEquals(false, configuredAttributes.isOther());

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that known-size members are written directly to the backing archive stream.
    @Test
    public void writesKnownSizeMembersDirectly() throws IOException {
        byte[] first = "direct".getBytes(StandardCharsets.UTF_8);
        byte[] second = "long-name-body".getBytes(StandardCharsets.UTF_8);
        String longPath = "known-size-long-file-name-that-requires-bsd-inline-name.txt";
        byte[] longPathBytes = longPath.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writer.beginFile("known.txt");
            ArArkivoEntryAttributeView firstAttributes =
                    Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
            firstAttributes.setSize(first.length);
            assertEquals(first.length, firstAttributes.readAttributes().size());
            try (OutputStream body = writer.openOutputStream()) {
                int bodyStart = output.size();
                assertEquals(68, bodyStart);
                body.write(first, 0, 2);
                assertEquals(bodyStart + 2, output.size());
                body.write(first, 2, first.length - 2);
            }

            writer.beginFile(longPath);
            ArArkivoEntryAttributeView secondAttributes =
                    Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
            secondAttributes.setSize(second.length);
            int beforeSecond = output.size();
            try (OutputStream body = writer.openOutputStream()) {
                int bodyStart = output.size();
                assertEquals(beforeSecond + 60 + longPathBytes.length, bodyStart);
                body.write(second[0]);
                assertEquals(bodyStart + 1, output.size());
                body.write(second, 1, second.length - 1);
            }
        }

        try (ArArkivoStreamingReader reader =
                     ArArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes firstAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("known.txt", firstAttributes.path());
            assertEquals(first.length, firstAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(first, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ArArkivoEntryAttributes secondAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals(longPath, secondAttributes.path());
            assertEquals("#1/" + longPathBytes.length, secondAttributes.identifier());
            assertEquals(second.length, secondAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(second, input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that known-size members reject bodies shorter than the configured size.
    @Test
    public void knownSizeMemberRejectsShortBody() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output);
        writer.beginFile("short.txt");
        ArArkivoEntryAttributeView attributes =
                Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
        attributes.setSize(5);
        OutputStream body = writer.openOutputStream();
        body.write(new byte[]{1, 2});

        IOException bodyException = assertThrows(IOException.class, body::close);
        assertEquals(true, bodyException.getMessage().contains("does not match configured size"));

        IOException writerException = assertThrows(IOException.class, writer::close);
        assertEquals(true, writerException.getMessage().contains("does not match configured size"));
    }

    /// Verifies that known-size members reject bodies larger than the configured size.
    @Test
    public void knownSizeMemberRejectsOversizedBody() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writer.beginFile("one-byte.txt");
            ArArkivoEntryAttributeView attributes =
                    Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
            attributes.setSize(1);
            try (OutputStream body = writer.openOutputStream()) {
                body.write('a');
                IOException exception = assertThrows(IOException.class, () -> body.write('b'));
                assertEquals(true, exception.getMessage().contains("exceeds configured size"));
            }
        }

        try (ArArkivoStreamingReader reader =
                     ArArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[]{'a'}, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that the streaming writer persists metadata configured through the AR attribute view.
    @Test
    public void writesConfiguredMemberMetadata() throws IOException {
        byte[] content = "metadata".getBytes(StandardCharsets.UTF_8);
        FileTime lastModifiedTime = FileTime.fromMillis(1_700_000_123_000L);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writer.beginFile("metadata.txt");
            ArArkivoEntryAttributeView attributes =
                    Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
            attributes.setTimes(lastModifiedTime, null, null);
            attributes.setUserId(321);
            attributes.setGroupId(654);
            attributes.setMode(0100600);
            try (OutputStream body = writer.openOutputStream()) {
                body.write(content);
            }
        }

        try (ArArkivoStreamingReader reader =
                     ArArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes attributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("metadata.txt", attributes.path());
            assertEquals("metadata.txt/", attributes.identifier());
            assertEquals(lastModifiedTime, attributes.lastModifiedTime());
            assertEquals(321, attributes.userId());
            assertEquals(654, attributes.groupId());
            assertEquals(0100600, attributes.mode());
            PosixFileAttributes posixAttributes = reader.readAttributes(PosixFileAttributes.class);
            assertEquals("321", posixAttributes.owner().getName());
            assertEquals("654", posixAttributes.group().getName());
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    posixAttributes.permissions()
            );
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that AR archives can be opened as read-only file systems.
    @Test
    public void opensMembersAsReadOnlyFileSystem() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Set<PosixFilePermission> filePermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
        );
        Path archivePath = createTemporaryArchivePath("ar-fs-");
        Path copiedDirectory = archivePath.getParent().resolve("copied-dir");
        Path existingFile = archivePath.getParent().resolve("existing-file");
        try {
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("dir/hello.txt");
                ArArkivoEntryAttributeView attributes =
                        Objects.requireNonNull(writer.attributeView(ArArkivoEntryAttributeView.class));
                attributes.setTimes(FileTime.fromMillis(1_700_000_000_000L), null, null);
                attributes.setUserId(1000);
                attributes.setGroupId(1001);
                attributes.setMode(0100640);
                try (OutputStream body = writer.openOutputStream()) {
                    body.write(content);
                }
                writer.beginFile("root.bin");
                writer.endEntry();
                writer.beginSymbolicLink("dir/link", "hello.txt");
                writer.endEntry();
            }

            ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath);
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
                assertEquals(Set.of("/dir", "/root.bin"), Set.copyOf(children));

                Path file = fileSystem.getPath("/dir/hello.txt");
                ArArkivoEntryAttributes fileAttributes = Files.readAttributes(file, ArArkivoEntryAttributes.class);
                assertEquals(true, fileAttributes.isRegularFile());
                assertEquals(1000, fileAttributes.userId());
                assertEquals(1001, fileAttributes.groupId());
                assertEquals(0100640, fileAttributes.mode());
                assertEquals(content.length, fileAttributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));

                Path link = fileSystem.getPath("/dir/link");
                ArArkivoEntryAttributes linkAttributes = Files.readAttributes(link, ArArkivoEntryAttributes.class);
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(false, linkAttributes.isRegularFile());
                assertEquals(0120777, linkAttributes.mode());
                assertEquals("hello.txt", Files.readString(link));
                assertEquals("hello.txt", Files.readSymbolicLink(link).toString());

                PosixFileAttributes posixAttributes = Files.readAttributes(file, PosixFileAttributes.class);
                assertEquals("1000", posixAttributes.owner().getName());
                assertEquals("1001", posixAttributes.group().getName());
                assertEquals(filePermissions, posixAttributes.permissions());

                FileOwnerAttributeView ownerView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, FileOwnerAttributeView.class));
                assertEquals("owner", ownerView.name());
                assertEquals("1000", ownerView.getOwner().getName());
                assertThrows(ReadOnlyFileSystemException.class, () -> ownerView.setOwner(() -> "other-user"));

                PosixFileAttributeView posixView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, PosixFileAttributeView.class));
                assertEquals("posix", posixView.name());
                assertEquals(filePermissions, posixView.readAttributes().permissions());
                assertEquals("1000", posixView.getOwner().getName());
                assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setGroup(() -> "other-group"));
                assertThrows(
                        ReadOnlyFileSystemException.class,
                        () -> posixView.setPermissions(Set.<PosixFilePermission>of())
                );
                var fileStore = Files.getFileStore(file);
                assertEquals(fileStore.name(), fileStore.getAttribute("name"));
                assertEquals(fileStore.type(), fileStore.getAttribute("type"));
                assertEquals(Boolean.valueOf(fileStore.isReadOnly()), fileStore.getAttribute("basic:readOnly"));
                assertEquals(Long.valueOf(fileStore.getTotalSpace()), fileStore.getAttribute("totalSpace"));
                assertEquals(Long.valueOf(fileStore.getUsableSpace()), fileStore.getAttribute("usableSpace"));
                assertEquals(Long.valueOf(fileStore.getUnallocatedSpace()), fileStore.getAttribute("unallocatedSpace"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("ar:type"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("missing"));
                assertEquals(
                        true,
                        fileStore.supportsFileAttributeView(FileOwnerAttributeView.class)
                );
                assertEquals(
                        true,
                        fileStore.supportsFileAttributeView(PosixFileAttributeView.class)
                );

                Map<String, Object> selectedBasicAttributes = Files.readAttributes(file, "basic:size,isRegularFile");
                assertEquals((long) content.length, selectedBasicAttributes.get("size"));
                assertEquals(true, selectedBasicAttributes.get("isRegularFile"));
                assertEquals(false, selectedBasicAttributes.containsKey("mode"));

                Map<String, Object> selectedArAttributes = Files.readAttributes(
                        file,
                        "ar:path,identifier,mode,userId,groupId,size"
                );
                assertEquals("dir/hello.txt", selectedArAttributes.get("path"));
                assertEquals("dir/hello.txt/", selectedArAttributes.get("identifier"));
                assertEquals(0100640, selectedArAttributes.get("mode"));
                assertEquals(1000L, selectedArAttributes.get("userId"));
                assertEquals(1001L, selectedArAttributes.get("groupId"));
                assertEquals((long) content.length, selectedArAttributes.get("size"));

                Map<String, Object> ownerNamedAttributes = Files.readAttributes(file, "owner:owner");
                assertEquals("1000", ((UserPrincipal) ownerNamedAttributes.get("owner")).getName());

                Map<String, Object> posixNamedAttributes =
                        Files.readAttributes(file, "posix:owner,group,permissions,isRegularFile");
                assertEquals("1000", ((UserPrincipal) posixNamedAttributes.get("owner")).getName());
                assertEquals("1001", ((GroupPrincipal) posixNamedAttributes.get("group")).getName());
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

                Map<String, Object> namedAttributes = Files.readAttributes(file, "ar:*");
                assertEquals(0100640, namedAttributes.get("mode"));
                assertEquals("dir/hello.txt/", namedAttributes.get("identifier"));
                ArArkivoEntryAttributeView arView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, ArArkivoEntryAttributeView.class));
                assertThrows(ReadOnlyFileSystemException.class, () -> arView.setSize(content.length));
                assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "zip:size"));
                assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
            }

            assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/dir"));
        } finally {
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(copiedDirectory);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that AR archives can be created through a forward-only writable file system.
    @Test
    public void createsMembersAsWritableFileSystem() throws IOException {
        byte[] content = "hello from writable file system".getBytes(StandardCharsets.UTF_8);
        byte[] channelContent = new byte[]{1, 2, 3};
        Path archivePath = createTemporaryArchivePath("ar-writable-fs-");
        Set<PosixFilePermission> directoryPermissions = PosixFilePermissions.fromString("rwxr-x---");
        Set<PosixFilePermission> channelFilePermissions = PosixFilePermissions.fromString("rw-r-----");
        Set<PosixFilePermission> linkPermissions = PosixFilePermissions.fromString("rwxr-xr--");
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );

        try {
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, environment)) {
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
                        fileSystem.getPath("/dir/link"),
                        Path.of("hello.txt"),
                        PosixFilePermissions.asFileAttribute(linkPermissions)
                );
                assertThrows(UnsupportedOperationException.class, () -> Files.readString(file, StandardCharsets.UTF_8));
            }

            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath)) {
                Path directory = fileSystem.getPath("/dir");
                ArArkivoEntryAttributes directoryAttributes =
                        Files.readAttributes(directory, ArArkivoEntryAttributes.class);
                PosixFileAttributes directoryPosixAttributes =
                        Files.readAttributes(directory, PosixFileAttributes.class);
                assertEquals(true, Files.isDirectory(directory));
                assertEquals(040750, directoryAttributes.mode());
                assertEquals(directoryPermissions, directoryPosixAttributes.permissions());

                Path file = fileSystem.getPath("/dir/hello.txt");
                assertArrayEquals(content, Files.readAllBytes(file));

                Path channelFile = fileSystem.getPath("/channel.bin");
                ArArkivoEntryAttributes channelFileAttributes =
                        Files.readAttributes(channelFile, ArArkivoEntryAttributes.class);
                PosixFileAttributes channelFilePosixAttributes =
                        Files.readAttributes(channelFile, PosixFileAttributes.class);
                assertArrayEquals(channelContent, Files.readAllBytes(channelFile));
                assertEquals(0100640, channelFileAttributes.mode());
                assertEquals(channelFilePermissions, channelFilePosixAttributes.permissions());

                Path link = fileSystem.getPath("/dir/link");
                ArArkivoEntryAttributes linkAttributes = Files.readAttributes(link, ArArkivoEntryAttributes.class);
                PosixFileAttributes linkPosixAttributes = Files.readAttributes(link, PosixFileAttributes.class);
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(0120754, linkAttributes.mode());
                assertEquals(linkPermissions, linkPosixAttributes.permissions());
                assertEquals("hello.txt", Files.readSymbolicLink(link).toString());
                assertEquals("hello.txt", Files.readString(link));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that provider URI entry points register and unregister AR file systems.
    @Test
    public void providerUriLifecycleSupportsEntryPaths() throws IOException {
        byte[] content = "provider".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("ar-provider-");
        try {
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("dir/provider.txt");
                try (OutputStream body = writer.openOutputStream()) {
                    body.write(content);
                }
            }

            ArArkivoFileSystemProvider provider = ArArkivoFileSystemProvider.instance();
            URI archiveUri = archivePath.toUri().normalize();
            URI fileSystemUri = URI.create(ArArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString());
            URI entryUri = URI.create(
                    ArArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString() + "!/dir/provider.txt"
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

    /// Verifies that update mode rewrites additions, replacements, deletions, moves, links, long names, and metadata.
    @Test
    public void updatesExistingArchiveThroughCompleteRewrite() throws IOException {
        byte[] keepContent = "abcdef".getBytes(StandardCharsets.UTF_8);
        String longName = "this-is-a-very-long-ar-member-name.txt";
        Path archivePath = createTemporaryArchivePath("ar-update-");
        try {
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
                writeStreamingMember(writer, "keep.txt", keepContent);
                writeStreamingMember(writer, "remove.txt", "remove".getBytes(StandardCharsets.UTF_8));
                writer.beginDirectory("dir");
                writer.endEntry();
                writeStreamingMember(writer, "dir/child.txt", "child".getBytes(StandardCharsets.UTF_8));
                writer.beginSymbolicLink("link", "keep.txt");
                writer.endEntry();
                writeStreamingMember(writer, longName, "long".getBytes(StandardCharsets.UTF_8));
                writeStreamingMember(writer, "target.txt", "old-target".getBytes(StandardCharsets.UTF_8));
                writeStreamingMember(writer, "replacement.txt", "new-target".getBytes(StandardCharsets.UTF_8));
                writeStreamingMember(writer, "resize.bin", new byte[]{1, 2, 3});
            }

            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            );
            FileTime modifiedTime = FileTime.from(Instant.parse("2032-03-04T05:06:07Z"));
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, environment)) {
                assertEquals(false, fileSystem.isReadOnly());
                Path keep = fileSystem.getPath("/keep.txt");
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

                ArArkivoEntryAttributeView arView =
                        Objects.requireNonNull(Files.getFileAttributeView(keep, ArArkivoEntryAttributeView.class));
                arView.setTimes(modifiedTime, null, null);
                arView.setUserId(1234L);
                arView.setGroupId(5678L);
                arView.setMode(0100640);

                Files.move(
                        fileSystem.getPath("/replacement.txt"),
                        fileSystem.getPath("/target.txt"),
                        StandardCopyOption.REPLACE_EXISTING
                );
                Files.delete(fileSystem.getPath("/remove.txt"));
                Files.move(fileSystem.getPath("/dir"), fileSystem.getPath("/renamed"));
                Files.writeString(fileSystem.getPath("/new.txt"), "new", StandardCharsets.UTF_8);
                Files.setAttribute(fileSystem.getPath("/resize.bin"), "ar:size", 5L);
            }

            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath)) {
                Path keep = fileSystem.getPath("/keep.txt");
                assertArrayEquals("abZZe".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(keep));
                ArArkivoEntryAttributes attributes = Files.readAttributes(keep, ArArkivoEntryAttributes.class);
                assertEquals(modifiedTime, attributes.lastModifiedTime());
                assertEquals(1234L, attributes.userId());
                assertEquals(5678L, attributes.groupId());
                assertEquals(0100640, attributes.mode());

                assertEquals(false, Files.exists(fileSystem.getPath("/remove.txt")));
                assertEquals(
                        "new-target",
                        Files.readString(fileSystem.getPath("/target.txt"), StandardCharsets.UTF_8)
                );
                assertEquals(
                        "child",
                        Files.readString(fileSystem.getPath("/renamed/child.txt"), StandardCharsets.UTF_8)
                );
                assertEquals("keep.txt", Files.readSymbolicLink(fileSystem.getPath("/link")).toString());
                assertEquals(
                        "long",
                        Files.readString(fileSystem.getPath("/" + longName), StandardCharsets.UTF_8)
                );
                assertEquals("new", Files.readString(fileSystem.getPath("/new.txt"), StandardCharsets.UTF_8));
                assertArrayEquals(new byte[]{1, 2, 3, 0, 0}, Files.readAllBytes(fileSystem.getPath("/resize.bin")));
            }

            ArrayList<String> physicalMembers = new ArrayList<>();
            try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(Files.newInputStream(archivePath))) {
                while (reader.next()) {
                    physicalMembers.add(reader.readAttributes(ArArkivoEntryAttributes.class).path());
                }
            }
            assertEquals(
                    List.of(
                            "keep.txt",
                            "renamed",
                            "renamed/child.txt",
                            "link",
                            longName,
                            "target.txt",
                            "resize.bin",
                            "new.txt"
                    ),
                    physicalMembers
            );
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that an explicit commit target can publish an updated derivative without changing the source.
    @Test
    public void updateCommitTargetCanPublishDerivedArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("ar-update-source-");
        Path derivedPath = createTemporaryArchivePath("ar-update-derived-");
        Files.deleteIfExists(derivedPath);
        try {
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
                writeStreamingMember(writer, "value.txt", "before".getBytes(StandardCharsets.UTF_8));
            }
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                    ArkivoFileSystem.COMMIT_TARGET.key(),
                    ArkivoCommitTarget.writeTo(derivedPath)
            );
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, environment)) {
                Files.writeString(fileSystem.getPath("/value.txt"), "after", StandardCharsets.UTF_8);
            }

            try (ArArkivoFileSystem source = ArArkivoFileSystem.open(archivePath);
                 ArArkivoFileSystem derived = ArArkivoFileSystem.open(derivedPath)) {
                assertEquals("before", Files.readString(source.getPath("/value.txt"), StandardCharsets.UTF_8));
                assertEquals("after", Files.readString(derived.getPath("/value.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(derivedPath);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that commit setup failure leaves the original AR bytes untouched.
    @Test
    public void failedUpdateCommitLeavesOriginalArchiveUntouched() throws IOException {
        Path archivePath = createTemporaryArchivePath("ar-update-failure-");
        try {
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
                writeStreamingMember(writer, "value.txt", "before".getBytes(StandardCharsets.UTF_8));
            }
            byte[] originalArchive = Files.readAllBytes(archivePath);
            ArkivoCommitTarget failingTarget = sourcePath -> {
                throw new IOException("commit target failed");
            };
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                    ArkivoFileSystem.COMMIT_TARGET.key(),
                    failingTarget
            );

            ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, environment);
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
        Path archivePath = createTemporaryArchivePath("ar-update-unchanged-");
        try {
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archivePath)) {
                writeStreamingMember(writer, "value.txt", "value".getBytes(StandardCharsets.UTF_8));
            }
            byte[] originalArchive = Files.readAllBytes(archivePath);
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            );
            try (ArArkivoFileSystem ignored = ArArkivoFileSystem.open(archivePath, environment)) {
            }
            assertArrayEquals(originalArchive, Files.readAllBytes(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that update mode with CREATE publishes a valid empty archive for a missing source.
    @Test
    public void updateCreateModeCreatesMissingArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("ar-update-create-");
        Files.deleteIfExists(archivePath);
        try {
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            );
            try (ArArkivoFileSystem ignored = ArArkivoFileSystem.open(archivePath, environment)) {
            }
            assertEquals(true, Files.exists(archivePath));
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath);
                 DirectoryStream<Path> members = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                assertEquals(false, members.iterator().hasNext());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that BSD symbol tables are skipped and omitted from rewritten archives.
    @Test
    public void updateOmitsStaleBsdSymbolTables() throws IOException {
        byte[] content = "value".getBytes(StandardCharsets.UTF_8);
        byte[] extendedSymbolName = "__.SYMDEF_64 SORTED".getBytes(StandardCharsets.US_ASCII);
        Path archivePath = createTemporaryArchivePath("ar-update-symbols-");
        try {
            Files.write(archivePath, archive(
                    member("__.SYMDEF/", 0, 0, 0, 0, new byte[0]),
                    member("#1/" + extendedSymbolName.length, 0, 0, 0, 0, extendedSymbolName),
                    member("value.txt/", 1, 2, 3, 0100644, content)
            ));
            Map<String, Object> environment = Map.of(
                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            );
            try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath, environment)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/value.txt")));
                Files.writeString(fileSystem.getPath("/new.txt"), "new", StandardCharsets.UTF_8);
            }

            String rewritten = new String(Files.readAllBytes(archivePath), StandardCharsets.ISO_8859_1);
            assertEquals(false, rewritten.contains("__.SYMDEF"));
            try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(Files.newInputStream(archivePath))) {
                assertEquals(true, reader.next());
                assertEquals("value.txt", reader.readAttributes(ArArkivoEntryAttributes.class).path());
                assertEquals(true, reader.next());
                assertEquals("new.txt", reader.readAttributes(ArArkivoEntryAttributes.class).path());
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming writer rejects unsafe paths.
    @Test
    public void writerRejectsUnsafePaths() throws IOException {
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(new ByteArrayOutputStream())) {
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("../evil.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("/absolute.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("C:/evil.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginDirectory("../dir"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("../link", "target"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("link", ""));
        }
    }

    /// Verifies that GNU filename tables and symbol tables are handled.
    @Test
    public void readsGnuLongNamesAndSkipsSpecialMembers() throws IOException {
        byte[] table = "very-long-file-name.txt/\nsecond-long-name.bin/\n".getBytes(StandardCharsets.UTF_8);
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(
                member("/", 0, 0, 0, 0, new byte[]{0, 0, 0, 0}),
                member("//", 0, 0, 0, 0, table),
                member("/0", 11, 1, 2, 0100644, first),
                member("/25", 12, 3, 4, 0100644, second)
        );

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes firstAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("very-long-file-name.txt", firstAttributes.path());
            assertEquals("/0", firstAttributes.identifier());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(first, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ArArkivoEntryAttributes secondAttributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("second-long-name.bin", secondAttributes.path());
            assertEquals("/25", secondAttributes.identifier());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(second, input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that BSD inline long names are resolved and excluded from member data.
    @Test
    public void readsBsdInlineLongNames() throws IOException {
        byte[] path = "bsd-long-name.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "body".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[path.length + content.length];
        System.arraycopy(path, 0, body, 0, path.length);
        System.arraycopy(content, 0, body, path.length, content.length);
        byte[] archive = archive(member("#1/" + path.length, 20, 10, 11, 0100644, body));

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ArArkivoEntryAttributes attributes = reader.readAttributes(ArArkivoEntryAttributes.class);
            assertEquals("bsd-long-name.txt", attributes.path());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that unsafe member paths are rejected.
    @Test
    public void rejectsParentDirectoryMemberPath() throws IOException {
        byte[] archive = archive(member("../evil.txt/", 0, 0, 0, 0100644, new byte[0]));

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);

            assertEquals(true, exception.getMessage().contains("must not contain .."));
        }
    }

    /// Verifies that drive-letter member paths are rejected as non-relative paths.
    @Test
    public void rejectsDriveLetterMemberPath() throws IOException {
        byte[] archive = archive(member("C:/evil.txt/", 0, 0, 0, 0100644, new byte[0]));

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);

            assertEquals(true, exception.getMessage().contains("must be relative"));
        }
    }

    /// Verifies that a member body can only be opened once.
    @Test
    public void entryBodyCanOnlyBeOpenedOnce() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(member("hello.txt/", 0, 0, 0, 0100644, content));

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());

            try (var channel = reader.openChannel()) {
                IOException exception = assertThrows(IOException.class, reader::openChannel);
                assertEquals(true, exception.getMessage().contains("already been opened"));

                ByteBuffer buffer = ByteBuffer.allocate(content.length);
                assertEquals(content.length, channel.read(buffer));
                buffer.flip();
                assertEquals("hello", StandardCharsets.UTF_8.decode(buffer).toString());
            }

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that reader close can retry source cleanup after failure.
    @Test
    public void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(archive(
                member("hello.txt/", 0, 0, 0, 0100644, "hello".getBytes(StandardCharsets.UTF_8))
        ));
        ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(source);
        assertEquals(true, reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, reader::next);
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Writes one streaming AR member with the given body.
    private static void writeStreamingMember(
            ArArkivoStreamingWriter writer,
            String path,
            byte @Unmodifiable [] content
    ) throws IOException {
        writer.beginFile(path);
        try (OutputStream output = writer.openOutputStream()) {
            output.write(content);
        }
    }

    /// Creates an AR archive from the given members.
    private static byte[] archive(Member... members) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
        for (Member member : members) {
            writeField(output, member.identifier(), 16);
            writeField(output, Long.toString(member.timestamp()), 12);
            writeField(output, Long.toString(member.userId()), 6);
            writeField(output, Long.toString(member.groupId()), 6);
            writeField(output, Integer.toOctalString(member.mode()), 8);
            writeField(output, Integer.toString(member.body().length), 10);
            output.write('`');
            output.write('\n');
            output.write(member.body());
            if ((member.body().length & 1) != 0) {
                output.write('\n');
            }
        }
        return output.toByteArray();
    }

    /// Creates one AR archive member.
    private static Member member(
            String identifier,
            long timestamp,
            long userId,
            long groupId,
            int mode,
            byte[] body
    ) {
        return new Member(identifier, timestamp, userId, groupId, mode, body);
    }

    /// Writes a fixed-width AR header field.
    private static void writeField(ByteArrayOutputStream output, String value, int width) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > width) {
            throw new IllegalArgumentException("field is too wide");
        }
        output.write(bytes);
        for (int index = bytes.length; index < width; index++) {
            output.write(' ');
        }
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-ar-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sample.ar");
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// One AR test member.
    ///
    /// @param identifier the raw fixed-width AR identifier
    /// @param timestamp the member modification time in epoch seconds
    /// @param userId the numeric user identifier
    /// @param groupId the numeric group identifier
    /// @param mode the stored POSIX mode bits
    /// @param body the complete stored member body
    private record Member(
            String identifier,
            long timestamp,
            long userId,
            long groupId,
            int mode,
            byte @Unmodifiable [] body
    ) {
        /// Creates a test AR member.
        private Member {
            Objects.requireNonNull(identifier, "identifier");
            body = body.clone();
        }
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

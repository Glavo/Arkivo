// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.jetbrains.annotations.NotNullByDefault;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes directory = reader.readAttributes(TarArkivoEntryAttributes.class);
            paths.add(directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(0755, directory.mode());

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            BasicFileAttributes basicFile = reader.readAttributes(BasicFileAttributes.class);
            paths.add(file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(5L, basicFile.size());
            assertEquals("user", file.userName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            paths.add(link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals("dir/hello.txt", link.linkName());

            assertEquals(false, reader.next());
        }

        assertEquals(List.of("dir/", "dir/hello.txt", "link"), paths);
    }

    /// Verifies that the streaming writer creates regular files, directories, and symbolic links.
    @Test
    public void writesStreamingEntries() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            writer.beginDirectory("dir");
            writer.endEntry();

            writer.beginFile("dir/hello.txt");
            try (OutputStream body = writer.openOutputStream()) {
                body.write(content);
            }

            writer.beginSymbolicLink("link", "dir/hello.txt");
            writer.endEntry();
        }

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes directory = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("dir/", directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(0755, directory.mode());

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("dir/hello.txt", file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(0644, file.mode());
            assertEquals(content.length, file.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("link", link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals(0777, link.mode());
            assertEquals("dir/hello.txt", link.linkName());

            assertEquals(false, reader.next());
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

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            writer.beginFile("meta.txt");
            BasicFileAttributeView basicView =
                    Objects.requireNonNull(writer.attributeView(BasicFileAttributeView.class));
            basicView.setTimes(lastModifiedTime, lastAccessTime, creationTime);

            PosixFileAttributeView posixView =
                    Objects.requireNonNull(writer.attributeView(PosixFileAttributeView.class));
            posixView.setOwner(() -> userName);
            posixView.setGroup(() -> groupName);
            posixView.setPermissions(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ
            ));

            try (OutputStream body = writer.openOutputStream()) {
                body.write(content);
            }
        }

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("meta.txt", attributes.path());
            assertEquals(0640, attributes.mode());
            assertEquals(userName, attributes.userName());
            assertEquals(groupName, attributes.groupName());
            assertEquals(lastModifiedTime.toInstant(), attributes.lastModifiedTime().toInstant());
            assertEquals(lastAccessTime.toInstant(), attributes.lastAccessTime().toInstant());
            assertEquals(creationTime.toInstant(), attributes.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that TAR archives can be opened as read-only file systems.
    @Test
    public void opensEntriesAsReadOnlyFileSystem() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("tar-fs-");
        try {
            try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(archivePath)) {
                writer.beginDirectory("dir");
                writer.endEntry();

                writer.beginFile("dir/hello.txt");
                BasicFileAttributeView basicView =
                        Objects.requireNonNull(writer.attributeView(BasicFileAttributeView.class));
                basicView.setTimes(FileTime.fromMillis(1_700_000_000_000L), null, null);

                PosixFileAttributeView posixView =
                        Objects.requireNonNull(writer.attributeView(PosixFileAttributeView.class));
                posixView.setOwner(() -> "fs-user");
                posixView.setGroup(() -> "fs-group");
                posixView.setPermissions(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ
                ));
                try (OutputStream body = writer.openOutputStream()) {
                    body.write(content);
                }

                writer.beginSymbolicLink("link", "dir/hello.txt");
                writer.endEntry();
            }

            TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath);
            try (fileSystem) {
                assertEquals(true, fileSystem.isReadOnly());

                Path directory = fileSystem.getPath("/dir");
                BasicFileAttributes directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
                assertEquals(true, directoryAttributes.isDirectory());

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
                assertEquals(content.length, fileAttributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));

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
                assertEquals(0L, tarNamedAttributes.get("userId"));
                assertEquals(0L, tarNamedAttributes.get("groupId"));
                assertEquals("fs-user", tarNamedAttributes.get("userName"));
                assertEquals("fs-group", tarNamedAttributes.get("groupName"));
                assertEquals((long) content.length, tarNamedAttributes.get("size"));
                assertNull(tarNamedAttributes.get("linkName"));

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
                TarArkivoEntryAttributes linkAttributes = Files.readAttributes(link, TarArkivoEntryAttributes.class);
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));

                Map<String, Object> linkNamedAttributes = Files.readAttributes(
                        link,
                        "tar:isSymbolicLink,typeFlag,linkName"
                );
                assertEquals(true, linkNamedAttributes.get("isSymbolicLink"));
                assertEquals((byte) '2', linkNamedAttributes.get("typeFlag"));
                assertEquals("dir/hello.txt", linkNamedAttributes.get("linkName"));

                assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "zip:size"));
                assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
            }

            assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/dir"));
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
                writer.beginFile("dir/provider.txt");
                try (OutputStream body = writer.openOutputStream()) {
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

    /// Verifies that the streaming writer emits PAX metadata for long paths and symbolic link targets.
    @Test
    public void writesPaxMetadataForLongNames() throws IOException {
        byte[] content = "long".getBytes(StandardCharsets.UTF_8);
        String path = "pax/this-file-name-segment-is-long-enough-to-require-pax-metadata-because-ustar-name-fields"
                + "-cannot-store-this-single-segment-without-an-extension.txt";
        String target = "this-link-target-segment-is-long-enough-to-require-pax-metadata-because-ustar-link-fields"
                + "-cannot-store-this-single-segment-without-an-extension.txt";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            writer.beginFile(path);
            try (OutputStream body = writer.openOutputStream()) {
                body.write(content);
            }

            writer.beginSymbolicLink("long-link", target);
            writer.endEntry();
        }

        try (TarArkivoStreamingReader reader =
                     TarArkivoStreamingReader.open(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            assertEquals(content.length, file.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("long-link", link.path());
            assertEquals(target, link.linkName());

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that the streaming writer rejects unsafe archive paths before writing them.
    @Test
    public void writerRejectsUnsafeEntryPaths() throws IOException {
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(new ByteArrayOutputStream())) {
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("../evil.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginDirectory("/absolute"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("C:/evil.txt", "target"));
        }
    }

    /// Verifies that streaming reader operations fail as closed after the reader is closed.
    @Test
    public void readerOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(tarArchive()));
        assertEquals(true, reader.next());

        reader.close();

        IOException nextException = assertThrows(IOException.class, reader::next);
        assertEquals(true, nextException.getMessage().contains("closed"));
        IOException attributesException = assertThrows(
                IOException.class,
                () -> reader.readAttributes(TarArkivoEntryAttributes.class)
        );
        assertEquals(true, attributesException.getMessage().contains("closed"));
        IOException openException = assertThrows(IOException.class, reader::openChannel);
        assertEquals(true, openException.getMessage().contains("closed"));
    }

    /// Verifies that source cleanup can be retried after a close failure.
    @Test
    public void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(tarArchive());
        TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(source);
        assertEquals(true, reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        IOException nextException = assertThrows(IOException.class, reader::next);
        assertEquals(true, nextException.getMessage().contains("closed"));
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Verifies that a closed entry channel rejects reads and leaves the reader able to advance.
    @Test
    public void entryChannelOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(tarArchive()))) {
            assertEquals(true, reader.next());
            assertEquals(true, reader.next());

            ReadableByteChannel channel = reader.openChannel();
            channel.close();

            assertEquals(false, channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertEquals(true, reader.next());
            assertEquals("link", reader.readAttributes(TarArkivoEntryAttributes.class).path());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            assertEquals(5L, file.size());
            assertEquals("pax-user", file.userName());
            assertEquals(Instant.ofEpochSecond(1_893_456_000L, 250_000_000L), file.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_001L, 500_000_000L), file.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_002L, 750_000_000L), file.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes first = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("first.txt", first.path());
            assertEquals(111L, first.userId());
            assertEquals(222L, first.groupId());
            assertEquals("global-user", first.userName());
            assertEquals("global-group", first.groupName());
            assertEquals(Instant.ofEpochSecond(1_893_456_000L, 125_000_000L), first.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_010L, 250_000_000L), first.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_020L, 500_000_000L), first.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(firstContent, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes second = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("second.txt", second.path());
            assertEquals(111L, second.userId());
            assertEquals(222L, second.groupId());
            assertEquals("entry-user", second.userName());
            assertEquals("global-group", second.groupName());
            assertEquals(Instant.ofEpochSecond(1_893_456_030L, 750_000_000L), second.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_010L, 250_000_000L), second.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_893_456_020L, 500_000_000L), second.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(secondContent, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("global-path.txt", file.path());
            assertEquals(content.length, file.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(true, link.isSymbolicLink());
            assertEquals("global-target.txt", link.linkName());
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("file.txt", file.path());
            assertEquals("user", file.userName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("file.txt", file.path());
            assertNull(file.userName());
            assertNull(file.groupName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(true, link.isSymbolicLink());
            assertNull(link.linkName());
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("negative-time.txt", file.path());
            assertEquals(Instant.ofEpochSecond(-2L, 500_000_000L), file.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(-1L, 750_000_000L), file.lastAccessTime().toInstant());
            assertEquals(Instant.ofEpochSecond(-2L), file.creationTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(" leading-and-trailing.txt ", file.path());
            assertEquals(" user ", file.userName());
            assertEquals(" group ", file.groupName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(" link-with-spaces ", link.path());
            assertEquals(" target-with-spaces ", link.linkName());
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(path, file.path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            TarArkivoEntryAttributes link = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(target, link.linkName());
            assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);

            assertEquals("binary-numbers.txt", file.path());
            assertEquals(0640, file.mode());
            assertEquals(userId, file.userId());
            assertEquals(groupId, file.groupId());
            assertEquals(content.length, file.size());
            assertEquals(Instant.ofEpochSecond(modificationTime), file.lastModifiedTime().toInstant());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            assertEquals(true, reader.next());
            TarArkivoEntryAttributes file = reader.readAttributes(TarArkivoEntryAttributes.class);

            assertEquals("signed-checksum.txt", file.path());
            assertEquals("u\u00ff", file.userName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            IOException exception = assertThrows(IOException.class, reader::next);
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
            assertEquals(true, reader.next());
            assertEquals(Long.MAX_VALUE, reader.readAttributes(TarArkivoEntryAttributes.class).size());

            IOException exception = assertThrows(IOException.class, reader::next);
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
        String payload = key + "=" + value + "\n";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int digits = 1;
        while (true) {
            int length = digits + 1 + payloadBytes.length;
            int actualDigits = Integer.toString(length).length();
            if (actualDigits == digits) {
                return (length + " " + payload).getBytes(StandardCharsets.UTF_8);
            }
            digits = actualDigits;
        }
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

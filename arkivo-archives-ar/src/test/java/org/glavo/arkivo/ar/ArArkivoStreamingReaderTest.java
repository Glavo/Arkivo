// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
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
            paths.add(firstAttributes.path());
            assertEquals("hello.txt", firstAttributes.path());
            assertEquals("hello.txt/", firstAttributes.identifier());
            assertEquals(1000, firstAttributes.userId());
            assertEquals(1001, firstAttributes.groupId());
            assertEquals(0100644, firstAttributes.mode());
            assertEquals(first.length, firstAttributes.size());
            assertEquals(FileTime.fromMillis(1_700_000_000_000L), firstAttributes.lastModifiedTime());
            assertEquals(true, firstBasicAttributes.isRegularFile());
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
        Path archivePath = createTemporaryArchivePath("ar-fs-");
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
            }

            ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archivePath);
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
                assertEquals(Set.of("/dir", "/root.bin"), Set.copyOf(children));

                Path file = fileSystem.getPath("/dir/hello.txt");
                ArArkivoEntryAttributes fileAttributes = Files.readAttributes(file, ArArkivoEntryAttributes.class);
                assertEquals(true, fileAttributes.isRegularFile());
                assertEquals(1000, fileAttributes.userId());
                assertEquals(1001, fileAttributes.groupId());
                assertEquals(0100640, fileAttributes.mode());
                assertEquals(content.length, fileAttributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));

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
                assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "zip:size"));
                assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
            }

            assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/dir"));
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

    /// Verifies that the streaming writer rejects unsafe paths and unsupported entry types.
    @Test
    public void writerRejectsUnsafePathsAndUnsupportedEntryTypes() throws IOException {
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(new ByteArrayOutputStream())) {
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("../evil.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("/absolute.txt"));
            assertThrows(IllegalArgumentException.class, () -> writer.beginFile("C:/evil.txt"));
            assertThrows(UnsupportedOperationException.class, () -> writer.beginDirectory("dir"));
            assertThrows(UnsupportedOperationException.class, () -> writer.beginSymbolicLink("link", "target"));
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

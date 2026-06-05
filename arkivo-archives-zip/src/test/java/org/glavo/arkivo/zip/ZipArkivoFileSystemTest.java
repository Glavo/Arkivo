// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic ZIP Arkivo file system behavior.
@NotNullByDefault
public final class ZipArkivoFileSystemTest {
    /// Verifies that a ZIP file system can be opened from an archive path.
    @Test
    public void openPath() throws IOException {
        Path archivePath = Path.of("sample.zip");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            assertEquals(ZipArkivoFileSystemProvider.instance(), fileSystem.provider());
            assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
            assertEquals(true, fileSystem.isOpen());
            assertEquals(true, fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("zip"));
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("owner"));
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("posix"));
        }
    }

    /// Verifies that ZIP file systems are read-only.
    @Test
    public void zipFileSystemIsReadOnly() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(Path.of("sample.zip"))) {
            assertEquals(true, fileSystem.isReadOnly());
        }
    }

    /// Verifies that a streaming ZIP writer can write entries in storage order.
    @Test
    public void streamingWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginDirectory("dir");
                writer.endEntry();
                writer.beginFile("dir/hello.txt");
                try (var output = writer.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("dir/empty.txt");
                writer.endEntry();
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/dir/empty.txt")));
                PosixFileAttributes posixAttributes =
                        Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), PosixFileAttributes.class);
                ZipArkivoEntryAttributes zipAttributes =
                        Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), ZipArkivoEntryAttributes.class);
                assertEquals(true, posixAttributes.isRegularFile());
                assertEquals("owner", posixAttributes.owner().getName());
                assertEquals("owner", zipAttributes.owner().getName());
                assertEquals(true, posixAttributes.permissions().contains(PosixFilePermission.OWNER_READ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer exposes pending entry attribute views.
    @Test
    public void streamingWriterEntryAttributeView() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-attrs-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("dir/hello.txt");
                BasicFileAttributeView basicView = writer.attributeView(BasicFileAttributeView.class);
                ZipArkivoEntryAttributeView zipView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                PosixFileAttributeView posixView = writer.attributeView(PosixFileAttributeView.class);
                assertNotNull(basicView);
                assertNotNull(zipView);
                assertNotNull(posixView);
                assertEquals("posix", posixView.name());
                assertThrows(UnsupportedOperationException.class, () -> zipView.setOwner(posixView.getOwner()));
                assertThrows(UnsupportedOperationException.class, () -> zipView.setGroup(posixView.readAttributes().group()));

                zipView.setMethod(ZipMethod.deflated());
                Set<PosixFilePermission> permissions = Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ
                );
                zipView.setPermissions(permissions);
                ZipArkivoEntryAttributes attributes = zipView.readAttributes();
                assertEquals("dir/hello.txt", attributes.path());
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(permissions, attributes.permissions());

                try (var output = writer.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
                assertThrows(IllegalStateException.class, () -> zipView.setMethod(ZipMethod.stored()));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                PosixFileAttributes attributes =
                        Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), PosixFileAttributes.class);
                assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ
                ), attributes.permissions());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer persists ZIP metadata for stored entries.
    @Test
    public void streamingWriterStoredEntryMetadata() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-stored-");
        byte[] content = "stored-content".getBytes(StandardCharsets.UTF_8);
        byte[] localExtraData = new byte[]{1, 2, 3};
        byte[] centralExtraData = new byte[]{4, 5};
        byte[] rawComment = new byte[]{6, 7, 8};
        FileTime lastModifiedTime = FileTime.fromMillis(1_893_456_000_000L);
        long crc32 = crc32(content);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginDirectory("meta");
                ZipArkivoEntryAttributeView directoryView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(directoryView);
                directoryView.setTimes(lastModifiedTime, null, null);
                directoryView.setRawComment(rawComment);
                writer.endEntry();

                writer.beginFile("meta/stored.bin");
                ZipArkivoEntryAttributeView fileView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(fileView);
                fileView.setMethod(ZipMethod.stored());
                fileView.setTimes(lastModifiedTime, null, null);
                fileView.setUncompressedSizeAndCrc32(content.length, crc32);
                fileView.setInternalAttributes(1);
                fileView.setExternalAttributes(0x20L);
                fileView.setLocalExtraData(localExtraData);
                fileView.setCentralDirectoryExtraData(centralExtraData);
                fileView.setRawComment(rawComment);
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }

                writer.beginSymbolicLink("meta/link", "stored.bin");
                writer.endEntry();
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                ZipArkivoEntryAttributes directoryAttributes =
                        Files.readAttributes(fileSystem.getPath("/meta"), ZipArkivoEntryAttributes.class);
                assertEquals(true, directoryAttributes.isDirectory());
                assertEquals(ZipMethod.stored(), directoryAttributes.method());
                assertArrayEquals(rawComment, directoryAttributes.rawComment());
                assertEquals(lastModifiedTime, directoryAttributes.lastModifiedTime());

                ZipArkivoEntryAttributes fileAttributes =
                        Files.readAttributes(fileSystem.getPath("/meta/stored.bin"), ZipArkivoEntryAttributes.class);
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/meta/stored.bin")));
                assertEquals(ZipMethod.stored(), fileAttributes.method());
                assertEquals(content.length, fileAttributes.compressedSize());
                assertEquals(content.length, fileAttributes.size());
                assertEquals(crc32, fileAttributes.crc32());
                assertEquals(1, fileAttributes.internalAttributes());
                assertEquals(0x20L, fileAttributes.externalAttributes());
                assertArrayEquals(localExtraData, fileAttributes.localExtraData());
                assertArrayEquals(centralExtraData, fileAttributes.centralDirectoryExtraData());
                assertArrayEquals(rawComment, fileAttributes.rawComment());
                assertEquals(lastModifiedTime, fileAttributes.lastModifiedTime());

                ZipArkivoEntryAttributes linkAttributes =
                        Files.readAttributes(fileSystem.getPath("/meta/link"), ZipArkivoEntryAttributes.class);
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals("stored.bin", Files.readString(fileSystem.getPath("/meta/link"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a ZIP file system can create a new archive through NIO write operations.
    @Test
    public void fileSystemCreateWritesArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-");

        try {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(
                                 archivePath,
                                 Map.of(
                                         ZipArkivoFileSystem.ARCHIVE_OPEN_OPTIONS.key(),
                                         Set.of(
                                                 StandardOpenOption.CREATE,
                                                 StandardOpenOption.TRUNCATE_EXISTING,
                                                 StandardOpenOption.WRITE
                                         )
                                 )
                         )) {
                assertEquals(false, fileSystem.isReadOnly());
                Files.createDirectory(fileSystem.getPath("/dir"));
                Files.writeString(fileSystem.getPath("/dir/hello.txt"), "hello", StandardCharsets.UTF_8);
                try (SeekableByteChannel channel = Files.newByteChannel(
                        fileSystem.getPath("/dir/channel.bin"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                )) {
                    assertEquals(0, channel.position());
                    assertEquals(7, channel.write(ByteBuffer.wrap("channel".getBytes(StandardCharsets.UTF_8))));
                    assertEquals(7, channel.position());
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(true, Files.isDirectory(fileSystem.getPath("/dir")));
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                assertEquals("channel", Files.readString(fileSystem.getPath("/dir/channel.bin"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP reader can read entries from an input stream.
    @Test
    public void streamingReaderFromInputStream() throws IOException {
        Path archivePath = createDeflatedZipArchive();

        try {
            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                ArrayList<String> visited = new ArrayList<>();
                while (reader.next()) {
                    ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                    PosixFileAttributes posixAttributes = reader.readAttributes(PosixFileAttributes.class);
                    assertEquals(attributes.isDirectory(), posixAttributes.isDirectory());
                    assertEquals("owner", attributes.owner().getName());
                    assertEquals("owner", posixAttributes.owner().getName());
                    visited.add(attributes.path());
                    if (attributes.isDirectory()) {
                        assertEquals("dir/", attributes.path());
                    } else {
                        assertEquals("dir/hello.txt", attributes.path());
                        try (var input = reader.openInputStream()) {
                            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                        }
                    }
                }
                assertEquals(List.of("dir/", "dir/hello.txt"), visited);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read stored entries written with data descriptors.
    @Test
    public void streamingReaderStoredDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("stored-descriptor-");
        byte[] content = "stored descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("stored.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored.txt", attributes.path());
                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies basic ZIP path operations.
    @Test
    public void paths() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(Path.of("sample.zip"))) {
            Path path = fileSystem.getPath("/a/b/../c.txt");

            assertEquals("/a/b/../c.txt", path.toString());
            assertEquals("c.txt", path.getFileName().toString());
            assertEquals("/a/b/..", path.getParent().toString());
            assertEquals("/a/c.txt", path.normalize().toString());
            assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
        }
    }

    /// Verifies that preamble bytes can be read from an archive path.
    @Test
    public void preambleFromArchivePath() throws IOException {
        byte[] preamble = new byte[]{1, 2, 3, 4};
        Path archivePath = createTemporaryArchive(preamble);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that preamble bytes can be read from a volume source.
    @Test
    public void preambleFromVolumeSource() throws IOException {
        byte[] preamble = new byte[]{5, 6, 7};
        Path archivePath = createTemporaryArchive(preamble);

        try {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(archivePath)))) {
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that preamble detection handles ZIP offsets that already include the preamble size.
    @Test
    public void preambleFromArchivePathWithAdjustedZipOffsets() throws IOException {
        byte[] preamble = new byte[]{9, 8, 7, 6, 5};
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithPreambleAndAdjustedOffsets(preamble));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
                try (var entries = fileSystem.openEntryStream()) {
                    assertEquals("/a", entries.next().toString());
                    try (var input = entries.openInputStream()) {
                        assertArrayEquals(new byte[0], input.readAllBytes());
                    }
                    assertNull(entries.next());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that regular ZIP entries can be read through NIO file operations.
    @Test
    public void readDeflatedZipEntries() throws IOException {
        Path archivePath = createDeflatedZipArchive();
        Path copyTarget = archivePath.getParent().resolve("copied.txt");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/dir/hello.txt");

                assertEquals("hello", Files.readString(file, StandardCharsets.UTF_8));
                assertEquals(file, file.toRealPath());
                assertEquals(
                        URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri() + "!/dir/hello.txt"),
                        file.toUri()
                );
                try (var input = Files.newInputStream(file)) {
                    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }
                assertEquals(true, fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
                assertEquals(false, fileSystem.getPathMatcher("glob:**/*.bin").matches(file));
                assertEquals(true, fileSystem.getPathMatcher("regex:.*/hello\\.txt").matches(file));
                assertEquals("zip", Files.getFileStore(file).type());

                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(5L, attributes.size());
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(true, attributes.compressedSize() > 0);

                Map<String, Object> namedAttributes = Files.readAttributes(file, "zip:size,compressedSize,method");
                assertEquals(5L, namedAttributes.get("size"));
                assertEquals(ZipMethod.deflated(), namedAttributes.get("method"));
                assertEquals(true, ((Long) namedAttributes.get("compressedSize")) > 0);

                ArrayList<String> children = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/dir"))) {
                    for (Path child : stream) {
                        children.add(child.toString());
                    }
                }
                assertEquals(List.of("/dir/hello.txt"), children);

                try (var entries = fileSystem.openEntryStream()) {
                    assertEquals("/dir", entries.next().toString());
                    assertEquals("/dir/hello.txt", entries.next().toString());
                    try (var input = entries.openInputStream()) {
                        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                    }
                    assertNull(entries.next());
                }

                Files.copy(file, copyTarget);
                assertEquals("hello", Files.readString(copyTarget, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(copyTarget);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP64 end records can locate the central directory.
    @Test
    public void readZip64CentralDirectory() throws IOException {
        Path archivePath = createTemporaryArchiveContent(zip64CentralDirectoryArchive());

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/a");

                assertEquals("z", Files.readString(file, StandardCharsets.UTF_8));
                assertEquals(1L, Files.size(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a ZIP file system can be opened and resolved through provider URIs.
    @Test
    public void openUri() throws IOException {
        Path archivePath = createDeflatedZipArchive();
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI entryUri = URI.create(fileSystemUri + "!/dir/hello.txt");

        try {
            try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
                Path entry = provider.getPath(entryUri);
                assertEquals(entryUri, entry.toUri());
                assertEquals("hello", Files.readString(entry, StandardCharsets.UTF_8));
                assertThrows(
                        FileSystemAlreadyExistsException.class,
                        () -> provider.newFileSystem(fileSystemUri, Map.of())
                );
            }
            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that closing a ZIP file system closes an owned volume source.
    @Test
    public void closeVolumeSource() throws IOException {
        TestVolumeSource volumes = new TestVolumeSource();
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes);

        fileSystem.close();

        assertEquals(false, fileSystem.isOpen());
        assertEquals(true, volumes.closed);
        assertThrows(IllegalStateException.class, () -> fileSystem.getPath("/"));
    }

    /// Asserts that the preamble channel exposes exactly the expected preamble bytes.
    private static void assertPreambleContent(byte[] expected, ZipArkivoFileSystem fileSystem) throws IOException {
        try (SeekableByteChannel channel = fileSystem.openPreambleChannel()) {
            assertEquals(expected.length, channel.size());
            ByteBuffer buffer = ByteBuffer.allocate(expected.length);
            assertEquals(expected.length, channel.read(buffer));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertArrayEquals(expected, buffer.array());
        }
    }

    /// Returns a minimal empty ZIP archive with the given preamble bytes.
    private static byte[] emptyZipWithPreamble(byte[] preamble) {
        ByteBuffer buffer = ByteBuffer.allocate(preamble.length + 22).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(preamble);
        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP archive whose offsets include the preamble size.
    private static byte[] singleEntryZipWithPreambleAndAdjustedOffsets(byte[] preamble) {
        byte[] name = new byte[]{'a'};
        int localHeaderOffset = preamble.length;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderOffset + localHeaderSize;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                preamble.length + localHeaderSize + centralDirectorySize + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(preamble);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP64 archive whose EOCD stores central directory location through ZIP64 fields.
    private static byte[] zip64CentralDirectoryArchive() {
        byte[] name = new byte[]{'a'};
        byte[] content = new byte[]{'z'};
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize + content.length;
        int centralDirectorySize = 46 + name.length;
        int zip64EndOffset = centralDirectoryOffset + centralDirectorySize;

        ByteBuffer buffer = ByteBuffer.allocate(
                localHeaderSize
                        + content.length
                        + centralDirectorySize
                        + 56
                        + 20
                        + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
        buffer.put(content);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);

        buffer.putInt(0x06064b50);
        buffer.putLong(44);
        buffer.putShort((short) 45);
        buffer.putShort((short) 45);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putLong(1);
        buffer.putLong(1);
        buffer.putLong(centralDirectorySize);
        buffer.putLong(centralDirectoryOffset);

        buffer.putInt(0x07064b50);
        buffer.putInt(0);
        buffer.putLong(zip64EndOffset);
        buffer.putInt(1);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0xffff);
        buffer.putShort((short) 0xffff);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns the unsigned ZIP CRC-32 value of the given content.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Creates a temporary archive file under the module build directory.
    private static Path createTemporaryArchive(byte[] preamble) throws IOException {
        return createTemporaryArchiveContent(emptyZipWithPreamble(preamble));
    }

    /// Creates a temporary archive file with the given content under the module build directory.
    private static Path createTemporaryArchiveContent(byte[] content) throws IOException {
        Path archivePath = createTemporaryArchivePath("preamble-");
        Files.write(archivePath, content);
        return archivePath;
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-zip-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sfx.zip");
    }

    /// Creates a temporary deflated ZIP archive under the module build directory.
    private static Path createDeflatedZipArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("real-zip-");
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
            writer.beginDirectory("dir");
            writer.endEntry();
            writer.beginFile("dir/hello.txt");
            try (var output = writer.openOutputStream()) {
                output.write("hello".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archivePath;
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// Test volume source that records close calls.
    @NotNullByDefault
    private static final class TestVolumeSource implements ArkivoVolumeSource {
        /// Whether this source has been closed.
        private boolean closed;

        /// Opens no volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return null;
        }

        /// Records that this source has been closed.
        @Override
        public void close() {
            closed = true;
        }
    }
}

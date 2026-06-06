// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import java.io.ByteArrayOutputStream;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

import java.io.IOException;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic 7z Arkivo file system behavior.
@NotNullByDefault
public final class SevenZipArkivoFileSystemTest {
    /// Verifies that a 7z file system can be opened from an archive path.
    @Test
    public void openPath() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertEquals(SevenZipArkivoFileSystemProvider.instance(), fileSystem.provider());
                assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
                assertEquals(true, fileSystem.isOpen());
                assertEquals(true, fileSystem.isReadOnly());
                assertEquals("/", fileSystem.getSeparator());
                assertEquals(0, fileSystem.majorVersion());
                assertEquals(4, fileSystem.minorVersion());
                assertEquals(0L, fileSystem.nextHeaderOffset());
                assertEquals(0L, fileSystem.nextHeaderSize());
                assertEquals(0L, fileSystem.nextHeaderCrc32());
                assertEquals(true, fileSystem.supportedFileAttributeViews().contains("basic"));
                assertEquals("7z", Files.getFileStore(fileSystem.getPath("/")).type());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies basic 7z path operations.
    @Test
    public void paths() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path path = fileSystem.getPath("/a/b/../c.txt");

                assertEquals("/a/b/../c.txt", path.toString());
                assertEquals("c.txt", path.getFileName().toString());
                assertEquals("/a/b/..", path.getParent().toString());
                assertEquals("/a/c.txt", path.normalize().toString());
                assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
                assertEquals(true, fileSystem.getPathMatcher("glob:**/*.txt").matches(path));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies root directory and basic attributes.
    @Test
    public void rootDirectory() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path root = fileSystem.getPath("/");
                BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class);
                ArrayList<Path> children = new ArrayList<>();

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                    for (Path child : stream) {
                        children.add(child);
                    }
                }

                assertEquals(true, attributes.isDirectory());
                assertEquals(List.of(), children);
                assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(
                        fileSystem.getPath("/missing"),
                        BasicFileAttributes.class
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that empty directory and file entries are indexed.
    @Test
    public void emptyEntries() throws IOException {
        Path archivePath = createTemporaryArchivePath("empty-entries-");
        Files.write(archivePath, archiveWithEmptyEntries());

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                ArrayList<String> rootChildren = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        rootChildren.add(child.toString());
                    }
                }

                Path directory = fileSystem.getPath("/dir");
                Path emptyFile = fileSystem.getPath("/empty.txt");
                BasicFileAttributes directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
                BasicFileAttributes fileAttributes = Files.readAttributes(emptyFile, BasicFileAttributes.class);

                assertEquals(List.of("/dir", "/empty.txt"), rootChildren);
                assertEquals(true, directoryAttributes.isDirectory());
                assertEquals(false, directoryAttributes.isRegularFile());
                assertEquals(false, fileAttributes.isDirectory());
                assertEquals(true, fileAttributes.isRegularFile());
                assertEquals(0L, fileAttributes.size());
                assertArrayEquals(new byte[0], Files.readAllBytes(emptyFile));
                try (SeekableByteChannel channel = Files.newByteChannel(emptyFile)) {
                    assertEquals(0L, channel.size());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z Copy method can be read.
    @Test
    public void copyFileEntry() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("copy-file-");
        Files.write(archivePath, archiveWithCopyFile(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(false, attributes.isDirectory());
                assertEquals(true, attributes.isRegularFile());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that multiple file entries can share one Copy-method 7z folder output.
    @Test
    public void copySubStreamEntries() throws IOException {
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two!".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[first.length + second.length];
        System.arraycopy(first, 0, content, 0, first.length);
        System.arraycopy(second, 0, content, first.length, second.length);
        Path archivePath = createTemporaryArchivePath("copy-substreams-");
        Files.write(archivePath, archiveWithCopySubStreams(content, first.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path firstFile = fileSystem.getPath("/one.txt");
                Path secondFile = fileSystem.getPath("/two.txt");
                ArrayList<String> rootChildren = new ArrayList<>();

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        rootChildren.add(child.toString());
                    }
                }

                assertEquals(List.of("/one.txt", "/two.txt"), rootChildren);
                assertArrayEquals(first, Files.readAllBytes(firstFile));
                assertArrayEquals(second, Files.readAllBytes(secondFile));
                try (var input = Files.newInputStream(secondFile)) {
                    assertArrayEquals(second, input.readAllBytes());
                }
                try (SeekableByteChannel channel = Files.newByteChannel(secondFile)) {
                    assertEquals(second.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(second.length);
                    assertEquals(second.length, channel.read(buffer));
                    assertArrayEquals(second, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z file time metadata is exposed through basic file attributes.
    @Test
    public void fileTimes() throws IOException {
        byte[] content = "metadata".getBytes(StandardCharsets.UTF_8);
        FileTime creationTime = FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));
        FileTime lastAccessTime = FileTime.from(Instant.parse("2026-01-03T03:04:05Z"));
        FileTime lastModifiedTime = FileTime.from(Instant.parse("2026-01-04T03:04:05Z"));
        Path archivePath = createTemporaryArchivePath("file-times-");
        Files.write(archivePath, archiveWithCopyFile(
                content,
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                0x20
        ));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                SevenZipArkivoEntryAttributes sevenZipAttributes =
                        Files.readAttributes(file, SevenZipArkivoEntryAttributes.class);
                Map<String, Object> namedAttributes = Files.readAttributes(
                        file,
                        "basic:creationTime,lastAccessTime,lastModifiedTime"
                );
                Map<String, Object> namedSevenZipAttributes = Files.readAttributes(
                        file,
                        "7z:path,windowsAttributes"
                );

                assertEquals(creationTime, attributes.creationTime());
                assertEquals(lastAccessTime, attributes.lastAccessTime());
                assertEquals(lastModifiedTime, attributes.lastModifiedTime());
                assertEquals("hello.txt", sevenZipAttributes.path());
                assertEquals(0x20, sevenZipAttributes.windowsAttributes());
                assertEquals(creationTime, namedAttributes.get("creationTime"));
                assertEquals(lastAccessTime, namedAttributes.get("lastAccessTime"));
                assertEquals(lastModifiedTime, namedAttributes.get("lastModifiedTime"));
                assertEquals("hello.txt", namedSevenZipAttributes.get("path"));
                assertEquals(0x20, namedSevenZipAttributes.get("windowsAttributes"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }


    /// Verifies that a non-empty file stored with the 7z LZMA method can be read.
    @Test
    public void lzmaFileEntry() throws IOException {
        byte[] content = "hello lzma".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = lzmaPayload(content);
        Path archivePath = createTemporaryArchivePath("lzma-file-");
        Files.write(archivePath, archiveWithLZMAFile(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z LZMA2 method can be read.
    @Test
    public void lzma2FileEntry() throws IOException {
        byte[] content = "hello lzma2".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = lzma2Payload(content);
        Path archivePath = createTemporaryArchivePath("lzma2-file-");
        Files.write(archivePath, archiveWithLZMA2File(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z file system can be opened and resolved through provider URIs.
    @Test
    public void openUri() throws IOException {
        Path archivePath = createMinimalArchive().toAbsolutePath().normalize();
        SevenZipArkivoFileSystemProvider provider = new SevenZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(SevenZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI rootUri = URI.create(fileSystemUri + "!/");

        try {
            try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
                assertEquals("/", provider.getPath(rootUri).toString());
                assertEquals(rootUri, fileSystem.getPath("/").toUri());
                assertThrows(
                        FileSystemAlreadyExistsException.class,
                        () -> provider.newFileSystem(fileSystemUri, Map.of())
                );
            }
            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
        } finally {
            try {
                provider.getFileSystem(fileSystemUri).close();
            } catch (FileSystemNotFoundException ignored) {
                // The test closes the file system in the normal path.
            }
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that invalid 7z signatures are rejected.
    @Test
    public void invalidSignature() throws IOException {
        Path archivePath = createTemporaryArchivePath("invalid-");
        Files.write(archivePath, new byte[32]);

        try {
            assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty next header CRC is validated.
    @Test
    public void nonEmptyNextHeader() throws IOException {
        byte[] nextHeader = new byte[]{0};
        Path archivePath = createTemporaryArchivePath("next-header-");
        Files.write(archivePath, archive(nextHeader, crc32(nextHeader)));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertEquals(0L, fileSystem.nextHeaderOffset());
                assertEquals(1L, fileSystem.nextHeaderSize());
                assertEquals(crc32(nextHeader), fileSystem.nextHeaderCrc32());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that invalid next header CRC values are rejected.
    @Test
    public void invalidNextHeaderCrc() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-next-header-");
        Files.write(archivePath, archive(new byte[]{0}, 1L));

        try {
            assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Creates a temporary minimal 7z archive under the module build directory.
    private static Path createMinimalArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("minimal-");
        Files.write(archivePath, minimalArchive());
        return archivePath;
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-7z-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sample.7z");
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// Returns a minimal 7z archive with an empty next header.
    private static byte[] minimalArchive() {
        return archive(new byte[0], 0L);
    }

    /// Returns a 7z archive with one empty directory and one empty file.
    private static byte[] archiveWithEmptyEntries() throws IOException {
        byte[] header = emptyEntriesHeader();
        return archive(header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the Copy method.
    private static byte[] archiveWithCopyFile(byte[] content) throws IOException {
        byte[] header = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0]);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with two files stored as substreams of one Copy-method folder.
    private static byte[] archiveWithCopySubStreams(byte[] content, int firstSize) throws IOException {
        byte[] header = copySubStreamsHeader(firstSize, content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file and metadata.
    private static byte[] archiveWithCopyFile(
            byte[] content,
            FileTime creationTime,
            FileTime lastAccessTime,
            FileTime lastModifiedTime,
            int windowsAttributes
    ) throws IOException {
        byte[] header = fileHeader(
                content.length,
                content.length,
                new byte[]{0x00},
                new byte[0],
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                windowsAttributes
        );
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the LZMA method.
    private static byte[] archiveWithLZMAFile(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                uncompressedSize,
                new byte[]{0x03, 0x01, 0x01},
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the LZMA2 method.
    private static byte[] archiveWithLZMA2File(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                uncompressedSize,
                new byte[]{0x21},
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with the given next header and expected next header CRC-32.
    private static byte[] archive(byte[] nextHeader, long nextHeaderCrc32) {
        return archive(new byte[0], nextHeader, nextHeaderCrc32);
    }

    /// Returns a 7z archive with packed data followed by the given next header.
    private static byte[] archive(byte[] packedData, byte[] nextHeader, long nextHeaderCrc32) {
        ByteBuffer buffer = ByteBuffer.allocate(32 + packedData.length + nextHeader.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(packedData.length);
        buffer.putLong(nextHeader.length);
        buffer.putInt((int) nextHeaderCrc32);
        buffer.put(packedData);
        buffer.put(nextHeader);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }

    /// Returns a plain 7z header with one empty directory and one empty file.
    private static byte[] emptyEntriesHeader() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);
        output.write(0x05);
        writeNumber(output, 2);

        byte[] emptyStreamBits = new byte[]{(byte) 0xc0};
        output.write(0x0e);
        writeNumber(output, emptyStreamBits.length);
        output.write(emptyStreamBits);

        byte[] emptyFileBits = new byte[]{0x40};
        output.write(0x0f);
        writeNumber(output, emptyFileBits.length);
        output.write(emptyFileBits);

        byte[] names = namesProperty("dir", "empty.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);

        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one file using the given coder.
    private static byte[] fileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties
    ) throws IOException {
        return fileHeader(packedSize, uncompressedSize, methodId, properties, null, null, null, -1);
    }

    /// Returns a plain 7z header with one file using the given coder and metadata.
    private static byte[] fileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(methodId.length | (properties.length != 0 ? 0x20 : 0));
        output.write(methodId);
        if (properties.length != 0) {
            writeNumber(output, properties.length);
            output.write(properties);
        }
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("hello.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        if (creationTime != null) {
            writeTimeProperty(output, 0x12, creationTime);
        }
        if (lastAccessTime != null) {
            writeTimeProperty(output, 0x13, lastAccessTime);
        }
        if (lastModifiedTime != null) {
            writeTimeProperty(output, 0x14, lastModifiedTime);
        }
        if (windowsAttributes >= 0) {
            writeWindowsAttributesProperty(output, windowsAttributes);
        }
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with two Copy-method file substreams.
    private static byte[] copySubStreamsHeader(int firstSize, int totalSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, totalSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, totalSize);
        output.write(0x00);

        output.write(0x08);
        output.write(0x0d);
        writeNumber(output, 2);
        output.write(0x09);
        writeNumber(output, firstSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 2);
        byte[] names = namesProperty("one.txt", "two.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Writes a one-file 7z time property.
    private static void writeTimeProperty(ByteArrayOutputStream output, int property, FileTime time) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(1);
        data.write(0);
        writeLongLE(data, windowsTicks(time));
        output.write(property);
        writeNumber(output, data.size());
        output.write(data.toByteArray());
    }

    /// Writes a one-file 7z Windows attributes property.
    private static void writeWindowsAttributesProperty(ByteArrayOutputStream output, int attributes) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(1);
        data.write(0);
        writeIntLE(data, attributes);
        output.write(0x15);
        writeNumber(output, data.size());
        output.write(data.toByteArray());
    }

    /// Returns a raw LZMA payload and its 7z coder properties.
    private static CoderPayload lzmaPayload(byte[] content) throws IOException {
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(1 << 20);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LZMAOutputStream lzma = new LZMAOutputStream(output, options, false)) {
            lzma.write(content);
        }

        byte[] properties = new byte[5];
        properties[0] = (byte) ((options.getPb() * 5 + options.getLp()) * 9 + options.getLc());
        ByteBuffer.wrap(properties, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(options.getDictSize());
        return new CoderPayload(output.toByteArray(), properties);
    }

    /// Returns a raw LZMA2 payload and its 7z coder properties.
    private static CoderPayload lzma2Payload(byte[] content) throws IOException {
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(1 << 20);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FinishableByteArrayOutputStream target = new FinishableByteArrayOutputStream(output);
        try (FinishableOutputStream lzma2 = options.getOutputStream(target, ArrayCache.getDummyCache())) {
            lzma2.write(content);
        }
        return new CoderPayload(output.toByteArray(), new byte[]{lzma2Property(options.getDictSize())});
    }

    /// Returns the 7z LZMA2 dictionary property for an exact dictionary size.
    private static byte lzma2Property(int dictionarySize) {
        for (int property = 0; property <= 37; property++) {
            int value = (2 | (property & 1)) << ((property >>> 1) + 11);
            if (value == dictionarySize) {
                return (byte) property;
            }
        }
        throw new IllegalArgumentException("dictionarySize cannot be represented exactly");
    }

    /// Stores generated coder payload bytes and 7z coder properties.
    @NotNullByDefault
    private static final class CoderPayload {
        /// The raw coder payload bytes.
        private final byte[] content;

        /// The 7z coder properties.
        private final byte[] properties;

        /// Creates a generated coder payload.
        private CoderPayload(byte[] content, byte[] properties) {
            this.content = content;
            this.properties = properties;
        }

        /// Returns the raw coder payload bytes.
        private byte[] content() {
            return content;
        }

        /// Returns the 7z coder properties.
        private byte[] properties() {
            return properties;
        }
    }

    /// Adapts a byte array output stream to XZ for Java's finishable output API.
    @NotNullByDefault
    private static final class FinishableByteArrayOutputStream extends FinishableOutputStream {
        /// The target output stream.
        private final ByteArrayOutputStream target;

        /// Creates a finishable output stream adapter.
        private FinishableByteArrayOutputStream(ByteArrayOutputStream target) {
            this.target = target;
        }

        /// Writes one byte.
        @Override
        public void write(int value) {
            target.write(value);
        }

        /// Writes bytes.
        @Override
        public void write(byte[] buffer, int offset, int length) {
            target.write(buffer, offset, length);
        }

        /// Finishes this stream.
        @Override
        public void finish() {
        }
    }

    /// Returns a 7z names property payload.
    private static byte[] namesProperty(String... names) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        for (String name : names) {
            output.write(name.getBytes(StandardCharsets.UTF_16LE));
            output.write(0);
            output.write(0);
        }
        return output.toByteArray();
    }

    /// Writes a small 7z variable-length integer.
    private static void writeNumber(ByteArrayOutputStream output, int value) {
        if (value < 0 || value > 0x7f) {
            throw new IllegalArgumentException("test value is out of range");
        }
        output.write(value);
    }

    /// Writes a little-endian `int`.
    private static void writeIntLE(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }

    /// Writes a little-endian `long`.
    private static void writeLongLE(ByteArrayOutputStream output, long value) {
        writeIntLE(output, (int) value);
        writeIntLE(output, (int) (value >>> 32));
    }

    /// Converts a file time to Windows FILETIME ticks.
    private static long windowsTicks(FileTime time) {
        Instant instant = time.toInstant();
        return 116_444_736_000_000_000L
                + instant.getEpochSecond() * 10_000_000L
                + instant.getNano() / 100L;
    }

    /// Returns the unsigned CRC-32 value of the given content.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }
}

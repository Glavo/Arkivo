// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar;

import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests RAR streaming reader behavior.
@NotNullByDefault
public final class RarArkivoStreamingReaderTest {
    /// The RAR5 archive signature.
    private static final byte @Unmodifiable [] RAR5_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// Verifies that stored RAR5 entries can be streamed with metadata.
    @Test
    public void readsStoredRar5Entries() throws IOException {
        byte[] first = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(
                service("CMT", "comment".getBytes(StandardCharsets.UTF_8)),
                directory("dir/", 1_700_000_000L, 040755),
                storedFile("dir/hello.txt", 1_700_000_010L, 0100644, first, owner("alice", "staff", 1000, 1001)),
                symbolicLink("link", 1_700_000_020L, 0120777, "dir/hello.txt")
        );
        ArrayList<String> paths = new ArrayList<>();

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes directory = reader.readAttributes(RarArkivoEntryAttributes.class);
            paths.add(directory.path());
            assertEquals("dir/", directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(040755, directory.fileAttributes());
            assertEquals(FileTime.fromMillis(1_700_000_000_000L), directory.lastModifiedTime());

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes file = reader.readAttributes(RarArkivoEntryAttributes.class);
            BasicFileAttributes basicFile = reader.readAttributes(BasicFileAttributes.class);
            paths.add(file.path());
            assertEquals("dir/hello.txt", file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, file.hostOs());
            assertEquals(0, file.compressionMethod());
            assertEquals(first.length, file.packedSize());
            assertEquals(first.length, file.unpackedSize());
            assertEquals(first.length, basicFile.size());
            assertEquals(crc32(first), file.dataCrc32());
            assertEquals(RarArkivoEntryAttributes.NO_REDIRECTION_TYPE, file.redirectionType());
            assertEquals(0, file.redirectionFlags());
            assertNull(file.redirectionTarget());
            assertEquals("alice", file.userName());
            assertEquals("staff", file.groupName());
            assertEquals(1000, file.userId());
            assertEquals(1001, file.groupId());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(first, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes link = reader.readAttributes(RarArkivoEntryAttributes.class);
            paths.add(link.path());
            assertEquals("link", link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals("dir/hello.txt", link.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK, link.redirectionType());
            assertEquals(0, link.redirectionFlags());
            assertEquals("dir/hello.txt", link.redirectionTarget());
            assertEquals(false, link.redirectionTargetDirectory());
            assertNull(link.userName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }

        assertEquals(List.of("dir/", "dir/hello.txt", "link"), paths);
    }

    /// Verifies that stored RAR5 entries split across file parts are exposed as one logical entry.
    @Test
    public void readsStoredMultiVolumeEntry() throws IOException {
        byte[] firstPart = "split ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "entry".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        byte[] archive = archive(
                splitStoredFilePart("split.txt", 1_700_000_000L, 0100644, content.length, contentCrc32, firstPart, false, true),
                splitStoredFilePart("split.txt", 1_700_000_000L, 0100644, content.length, contentCrc32, secondPart, true, false),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("split.txt", attributes.path());
            assertEquals(firstPart.length, attributes.packedSize());
            assertEquals(content.length, attributes.unpackedSize());
            assertEquals(contentCrc32, attributes.dataCrc32());
            assertEquals(false, attributes.continuesFromPreviousVolume());
            assertEquals(true, attributes.continuesInNextVolume());

            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals("split.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());

            assertEquals(true, reader.next());
            assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("after".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            assertEquals(true, reader.next());
            assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that a RAR5 entry whose first part is unavailable cannot be opened as content.
    @Test
    public void rejectsStoredEntryStartingFromPreviousVolumeOnOpen() throws IOException {
        byte[] body = "tail".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(splitStoredFilePart(
                "tail.txt",
                1_700_000_000L,
                0100644,
                body.length,
                crc32(body),
                body,
                true,
                false
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(true, attributes.continuesFromPreviousVolume());

            IOException exception = assertThrows(IOException.class, reader::openInputStream);
            assertEquals(true, exception.getMessage().contains("previous volume"));
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that non-symbolic-link RAR5 redirection records are preserved as metadata.
    @Test
    public void readsNonSymbolicRedirectionMetadata() throws IOException {
        byte[] archive = archive(
                redirectedEntry(
                        "hard-link",
                        1_700_000_000L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK,
                        0,
                        "dir/hello.txt"
                ),
                redirectedEntry(
                        "junction",
                        1_700_000_001L,
                        040755,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_JUNCTION,
                        RarArkivoEntryAttributes.REDIRECTION_FLAG_TARGET_DIRECTORY,
                        "target-dir"
                )
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes hardLink = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("hard-link", hardLink.path());
            assertEquals(false, hardLink.isRegularFile());
            assertEquals(false, hardLink.isSymbolicLink());
            assertEquals(true, hardLink.isOther());
            assertNull(hardLink.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK, hardLink.redirectionType());
            assertEquals(0, hardLink.redirectionFlags());
            assertEquals("dir/hello.txt", hardLink.redirectionTarget());
            assertEquals(false, hardLink.redirectionTargetDirectory());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes junction = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("junction", junction.path());
            assertEquals(false, junction.isRegularFile());
            assertEquals(false, junction.isSymbolicLink());
            assertEquals(true, junction.isOther());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_JUNCTION, junction.redirectionType());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_FLAG_TARGET_DIRECTORY, junction.redirectionFlags());
            assertEquals("target-dir", junction.redirectionTarget());
            assertEquals(true, junction.redirectionTargetDirectory());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that stored RAR5 entries are exposed through the read-only file system API.
    @Test
    public void opensStoredEntriesAsReadOnlyFileSystem() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] splitFirstPart = "split ".getBytes(StandardCharsets.UTF_8);
        byte[] splitSecondPart = "content".getBytes(StandardCharsets.UTF_8);
        byte[] splitContent = concatenate(splitFirstPart, splitSecondPart);
        long splitCrc32 = crc32(splitContent);
        byte[] hash = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            hash[index] = (byte) index;
        }
        Path archivePath = createTemporaryArchivePath("rar-fs-");
        Files.write(archivePath, archive(
                directory("dir/", 1_700_000_000L, 040755),
                storedFile(
                        "dir/hello.txt",
                        1_700_000_010L,
                        0100644,
                        content,
                        concatenate(owner("alice", "staff", 1000, 1001), blake2spHash(hash))
                ),
                splitStoredFilePart(
                        "dir/split.txt",
                        1_700_000_011L,
                        0100644,
                        splitContent.length,
                        splitCrc32,
                        splitFirstPart,
                        false,
                        true
                ),
                splitStoredFilePart(
                        "dir/split.txt",
                        1_700_000_011L,
                        0100644,
                        splitContent.length,
                        splitCrc32,
                        splitSecondPart,
                        true,
                        false
                ),
                symbolicLink("link", 1_700_000_020L, 0120777, "dir/hello.txt"),
                compressedFile("dir/compressed.bin", 1_700_000_020L, 0100644, new byte[]{1, 2, 3}, 1)
        ));

        RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archivePath);
        try (fileSystem) {
            assertEquals(true, fileSystem.isReadOnly());

            Path directory = fileSystem.getPath("/dir");
            BasicFileAttributes directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
            assertEquals(true, directoryAttributes.isDirectory());

            Path file = fileSystem.getPath("/dir/hello.txt");
            RarArkivoEntryAttributes fileAttributes = Files.readAttributes(file, RarArkivoEntryAttributes.class);
            assertEquals(true, fileAttributes.isRegularFile());
            assertEquals(content.length, fileAttributes.size());
            assertEquals("alice", fileAttributes.userName());
            assertArrayEquals(content, Files.readAllBytes(file));

            Map<String, Object> selectedBasicAttributes = Files.readAttributes(file, "basic:size,isRegularFile");
            assertEquals((long) content.length, selectedBasicAttributes.get("size"));
            assertEquals(true, selectedBasicAttributes.get("isRegularFile"));
            assertEquals(false, selectedBasicAttributes.containsKey("packedSize"));

            Map<String, Object> selectedRarAttributes = Files.readAttributes(
                    file,
                    "rar:path,hostOs,fileAttributes,compressionMethod,packedSize,unpackedSize,dataCrc32,"
                            + "blake2spHash,userName,groupName,userId,groupId"
            );
            assertEquals("dir/hello.txt", selectedRarAttributes.get("path"));
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, selectedRarAttributes.get("hostOs"));
            assertEquals(0100644L, selectedRarAttributes.get("fileAttributes"));
            assertEquals(0, selectedRarAttributes.get("compressionMethod"));
            assertEquals((long) content.length, selectedRarAttributes.get("packedSize"));
            assertEquals((long) content.length, selectedRarAttributes.get("unpackedSize"));
            assertEquals(crc32(content), selectedRarAttributes.get("dataCrc32"));
            assertArrayEquals(hash, (byte[]) selectedRarAttributes.get("blake2spHash"));
            assertEquals("alice", selectedRarAttributes.get("userName"));
            assertEquals("staff", selectedRarAttributes.get("groupName"));
            assertEquals(1000L, selectedRarAttributes.get("userId"));
            assertEquals(1001L, selectedRarAttributes.get("groupId"));

            byte[] namedHash = Objects.requireNonNull(
                    (byte[]) Files.readAttributes(file, "rar:blake2spHash").get("blake2spHash"),
                    "namedHash"
            );
            namedHash[0] = 99;
            assertArrayEquals(hash, (byte[]) Files.readAttributes(file, "rar:blake2spHash").get("blake2spHash"));

            Path splitFile = fileSystem.getPath("/dir/split.txt");
            RarArkivoEntryAttributes splitAttributes = Files.readAttributes(splitFile, RarArkivoEntryAttributes.class);
            assertEquals(splitContent.length, splitAttributes.size());
            assertEquals(splitFirstPart.length, splitAttributes.packedSize());
            assertEquals(splitContent.length, splitAttributes.unpackedSize());
            assertEquals(splitCrc32, splitAttributes.dataCrc32());
            assertEquals(false, splitAttributes.continuesFromPreviousVolume());
            assertEquals(true, splitAttributes.continuesInNextVolume());
            assertArrayEquals(splitContent, Files.readAllBytes(splitFile));

            Path link = fileSystem.getPath("/link");
            RarArkivoEntryAttributes linkAttributes = Files.readAttributes(link, RarArkivoEntryAttributes.class);
            assertEquals(true, linkAttributes.isSymbolicLink());
            assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));
            Map<String, Object> selectedLinkAttributes = Files.readAttributes(
                    link,
                    "rar:isSymbolicLink,linkName,redirectionType,redirectionFlags,redirectionTarget,"
                            + "redirectionTargetDirectory"
            );
            assertEquals(true, selectedLinkAttributes.get("isSymbolicLink"));
            assertEquals("dir/hello.txt", selectedLinkAttributes.get("linkName"));
            assertEquals(
                    RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK,
                    selectedLinkAttributes.get("redirectionType")
            );
            assertEquals(0L, selectedLinkAttributes.get("redirectionFlags"));
            assertEquals("dir/hello.txt", selectedLinkAttributes.get("redirectionTarget"));
            assertEquals(false, selectedLinkAttributes.get("redirectionTargetDirectory"));

            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                assertEquals(content.length, channel.size());
                channel.position(1);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2);
                assertEquals(2, channel.read(buffer));
                buffer.flip();
                assertEquals((byte) 'e', buffer.get());
                assertEquals((byte) 'l', buffer.get());
            }

            ArrayList<String> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    children.add(child.toString());
                }
            }
            assertEquals(Set.of("/dir/hello.txt", "/dir/split.txt", "/dir/compressed.bin"), Set.copyOf(children));

            IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/dir/compressed.bin")));
            assertEquals(true, exception.getMessage().contains("content is not available"));
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
        }

        assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/dir"));
        deleteTemporaryArchive(archivePath);
    }

    /// Verifies that stored RAR5 entries can be read from explicit archive volumes.
    @Test
    public void opensStoredSplitEntryFromVolumeSource() throws IOException {
        byte[] firstPart = "volume ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "source".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar-volumes-");
        Path secondVolume = firstVolume.getParent().resolve("sample.part2.rar");
        Files.write(firstVolume, archiveVolume(false, splitStoredFilePart(
                "split.txt",
                1_700_000_000L,
                0100644,
                content.length,
                contentCrc32,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, archiveVolume(
                true,
                splitStoredFilePart(
                        "split.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        contentCrc32,
                        secondPart,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(
                    firstVolume,
                    secondVolume
            )))) {
                Path splitFile = fileSystem.getPath("/split.txt");
                assertArrayEquals(content, Files.readAllBytes(splitFile));
                RarArkivoEntryAttributes attributes = Files.readAttributes(splitFile, RarArkivoEntryAttributes.class);
                assertEquals(firstPart.length, attributes.packedSize());
                assertEquals(content.length, attributes.unpackedSize());
                assertEquals(true, attributes.continuesInNextVolume());
                assertThrows(UnsupportedOperationException.class, splitFile::toUri);

                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that provider URI entry points register and unregister RAR file systems.
    @Test
    public void providerUriLifecycleSupportsEntryPaths() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("rar-provider-");
        Files.write(archivePath, archive(storedFile(
                "dir/hello.txt",
                1_700_000_000L,
                0100644,
                content,
                null
        )));

        RarArkivoFileSystemProvider provider = RarArkivoFileSystemProvider.instance();
        URI archiveUri = archivePath.toUri().normalize();
        URI fileSystemUri = URI.create(RarArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString());
        URI entryUri = URI.create(RarArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString() + "!/dir/hello.txt");

        try {
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

    /// Verifies that compressed RAR entries are visible but not exposed as stored data.
    @Test
    public void rejectsUnsupportedCompressionMethodOnOpen() throws IOException {
        byte[] archive = archive(compressedFile("compressed.bin", 1_700_000_000L, 0100644, new byte[]{1, 2, 3}, 1));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("compressed.bin", attributes.path());
            assertEquals(1, attributes.compressionMethod());

            IOException exception = assertThrows(IOException.class, reader::openInputStream);
            assertEquals(true, exception.getMessage().contains("Unsupported RAR compression method"));
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that RAR5 high precision Unix file time extra records are exposed through NIO attributes.
    @Test
    public void readsUnixFileTimeExtraRecord() throws IOException {
        byte[] archive = archive(storedFile(
                "times.txt",
                1L,
                0100644,
                "time".getBytes(StandardCharsets.UTF_8),
                unixFileTimes(
                        1_700_000_000L,
                        123_456_789L,
                        1_700_000_001L,
                        987_654_321L,
                        1_700_000_002L,
                        1L
                )
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);

            assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_456_789L), attributes.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_001L, 987_654_321L), attributes.creationTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_002L, 1L), attributes.lastAccessTime().toInstant());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that RAR5 Windows FILETIME extra records are converted to NIO file times.
    @Test
    public void readsWindowsFileTimeExtraRecord() throws IOException {
        byte[] archive = archive(storedFile(
                "windows-times.txt",
                1L,
                0100644,
                "time".getBytes(StandardCharsets.UTF_8),
                windowsFileTimes(
                        Instant.ofEpochSecond(1_700_000_010L, 100L),
                        Instant.ofEpochSecond(1_700_000_011L, 200L),
                        Instant.ofEpochSecond(1_700_000_012L, 300L)
                )
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);

            assertEquals(Instant.ofEpochSecond(1_700_000_010L, 100L), attributes.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_011L, 200L), attributes.creationTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_012L, 300L), attributes.lastAccessTime().toInstant());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that RAR5 BLAKE2sp file hash extra records are exposed as entry metadata.
    @Test
    public void readsBlake2spHashExtraRecord() throws IOException {
        byte[] hash = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            hash[index] = (byte) index;
        }
        byte[] archive = archive(storedFile(
                "hash.txt",
                1L,
                0100644,
                "hash".getBytes(StandardCharsets.UTF_8),
                blake2spHash(hash)
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);

            byte @Nullable [] firstHash = attributes.blake2spHash();
            assertArrayEquals(hash, firstHash);
            Objects.requireNonNull(firstHash, "firstHash")[0] = 99;
            assertArrayEquals(hash, attributes.blake2spHash());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that entries without a RAR5 file hash extra record expose no BLAKE2sp hash.
    @Test
    public void exposesNullWhenBlake2spHashIsAbsent() throws IOException {
        byte[] archive = archive(storedFile("no-hash.txt", 0, 0100644, new byte[0], null));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());

            assertNull(reader.readAttributes(RarArkivoEntryAttributes.class).blake2spHash());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that unsafe member paths are rejected.
    @Test
    public void rejectsParentDirectoryEntryPath() throws IOException {
        byte[] archive = archive(storedFile("../evil.txt", 0, 0100644, new byte[0], null));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);

            assertEquals(true, exception.getMessage().contains("must not contain .."));
        }
    }

    /// Verifies that RAR4 archives are detected as currently unsupported.
    @Test
    public void rejectsRar4SignatureAsUnsupported() throws IOException {
        byte[] archive = new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);

            assertEquals(true, exception.getMessage().contains("RAR4 archives are not supported yet"));
        }
    }

    /// Verifies that stored entry CRC32 mismatches are reported while draining the entry.
    @Test
    public void rejectsBadStoredEntryCrc32() throws IOException {
        byte[] archive = archive(storedFileWithCrc("bad.txt", 0, 0100644, "bad".getBytes(StandardCharsets.UTF_8), 0));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());

            IOException exception = assertThrows(IOException.class, () -> {
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("Invalid RAR entry CRC32"));
        }
    }

    /// Verifies that reader close can retry source cleanup after failure.
    @Test
    public void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(archive(
                storedFile("hello.txt", 0, 0100644, "hello".getBytes(StandardCharsets.UTF_8), null)
        ));
        RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(source);
        assertEquals(true, reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, reader::next);
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Creates one RAR5 archive.
    private static byte[] archive(Member... members) throws IOException {
        return archiveVolume(true, members);
    }

    /// Creates one RAR5 archive volume.
    private static byte[] archiveVolume(boolean includeEndHeader, Member... members) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR5_SIGNATURE);
        writeBlock(output, 1, 0, fields(writer -> writer.writeVint(0)), new byte[0], new byte[0]);
        for (Member member : members) {
            writeMember(output, member);
        }
        if (includeEndHeader) {
            writeBlock(output, 5, 0, fields(writer -> writer.writeVint(0)), new byte[0], new byte[0]);
        }
        return output.toByteArray();
    }

    /// Writes one RAR5 member block.
    private static void writeMember(ByteArrayOutputStream output, Member member) throws IOException {
        if (member.service()) {
            writeBlock(output, 3, blockFlags(member), fileFields(member, false), new byte[0], member.body());
            return;
        }
        writeBlock(output, 2, blockFlags(member), fileFields(member, true), member.extraArea(), member.body());
    }

    /// Returns encoded RAR5 block continuation flags for one member.
    private static long blockFlags(Member member) {
        long flags = 0L;
        if (member.continuesFromPreviousVolume()) {
            flags |= 0x0008L;
        }
        if (member.continuesInNextVolume()) {
            flags |= 0x0010L;
        }
        return flags;
    }

    /// Concatenates two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Creates a stored file member.
    private static Member storedFile(
            String path,
            long modificationTime,
            long attributes,
            byte[] body,
            @Nullable byte @Unmodifiable [] extraArea
    ) {
        return storedFileWithCrc(path, modificationTime, attributes, body, crc32(body), extraArea);
    }

    /// Creates a stored file member with an explicit CRC32.
    private static Member storedFileWithCrc(String path, long modificationTime, long attributes, byte[] body, long crc32) {
        return storedFileWithCrc(path, modificationTime, attributes, body, crc32, null);
    }

    /// Creates a stored file member with an explicit CRC32 and extra area.
    private static Member storedFileWithCrc(
            String path,
            long modificationTime,
            long attributes,
            byte[] body,
            long crc32,
            @Nullable byte @Unmodifiable [] extraArea
    ) {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                0,
                crc32,
                body.length,
                body,
                extraArea,
                false,
                false,
                false
        );
    }

    /// Creates a stored file member that is split across physical archive parts.
    private static Member splitStoredFilePart(
            String path,
            long modificationTime,
            long attributes,
            long unpackedSize,
            long crc32,
            byte[] body,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                0,
                crc32,
                unpackedSize,
                body,
                null,
                false,
                continuesFromPreviousVolume,
                continuesInNextVolume
        );
    }

    /// Creates a compressed file member.
    private static Member compressedFile(
            String path,
            long modificationTime,
            long attributes,
            byte[] body,
            int compressionMethod
    ) {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                compressionMethod,
                crc32(body),
                body.length,
                body,
                null,
                false,
                false,
                false
        );
    }

    /// Creates a directory member.
    private static Member directory(String path, long modificationTime, long attributes) {
        return new Member(path, modificationTime, attributes, true, false, 0, 0, 0, new byte[0], null, false, false, false);
    }

    /// Creates a symbolic link member.
    private static Member symbolicLink(String path, long modificationTime, long attributes, String target)
            throws IOException {
        return new Member(path, modificationTime, attributes, false, true, 0, 0, 0, new byte[0], symlink(target), false, false, false);
    }

    /// Creates a redirected entry member.
    private static Member redirectedEntry(
            String path,
            long modificationTime,
            long attributes,
            int redirectionType,
            long redirectionFlags,
            String target
    ) throws IOException {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                0,
                0,
                0,
                new byte[0],
                redirection(redirectionType, redirectionFlags, target),
                false,
                false,
                false
        );
    }

    /// Creates a service header member.
    private static Member service(String name, byte[] body) {
        return new Member(name, 0, 0, false, false, 0, crc32(body), body.length, body, null, true, false, false);
    }

    /// Encodes RAR5 file header fields.
    private static byte[] fileFields(Member member, boolean includeCrc) throws IOException {
        return fields(writer -> {
            long fileFlags = 0x0002L;
            if (member.directory()) {
                fileFlags |= 0x0001L;
            }
            if (includeCrc) {
                fileFlags |= 0x0004L;
            }
            writer.writeVint(fileFlags);
            writer.writeVint(member.unpackedSize());
            writer.writeVint(member.attributes());
            writer.writeUInt32(member.modificationTime());
            if (includeCrc) {
                writer.writeUInt32(member.crc32());
            }
            writer.writeVint((long) member.compressionMethod() << 7);
            writer.writeVint(RarArkivoEntryAttributes.HOST_OS_UNIX);
            byte[] name = member.path().getBytes(StandardCharsets.UTF_8);
            writer.writeVint(name.length);
            writer.write(name);
        });
    }

    /// Writes one complete RAR5 block.
    private static void writeBlock(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte[] fields,
            byte[] extraArea,
            byte[] data
    ) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        VintWriter writer = new VintWriter(headerData);
        long headerFlags = flags;
        if (extraArea.length > 0) {
            headerFlags |= 0x0001L;
        }
        if (data.length > 0) {
            headerFlags |= 0x0002L;
        }
        writer.writeVint(type);
        writer.writeVint(headerFlags);
        if (extraArea.length > 0) {
            writer.writeVint(extraArea.length);
        }
        if (data.length > 0) {
            writer.writeVint(data.length);
        }
        writer.write(fields);
        writer.write(extraArea);

        byte[] headerDataBytes = headerData.toByteArray();
        byte[] headerSizeBytes = vint(headerDataBytes.length);
        CRC32 headerCrc32 = new CRC32();
        headerCrc32.update(headerSizeBytes, 0, headerSizeBytes.length);
        headerCrc32.update(headerDataBytes, 0, headerDataBytes.length);

        writeUInt32(output, headerCrc32.getValue());
        output.write(headerSizeBytes);
        output.write(headerDataBytes);
        output.write(data);
    }

    /// Creates a Unix owner extra area.
    private static byte[] owner(String userName, String groupName, long userId, long groupId) throws IOException {
        return extraRecord(0x06, fields(writer -> {
            writer.writeVint(0x000f);
            writeLengthPrefixedString(writer, userName);
            writeLengthPrefixedString(writer, groupName);
            writer.writeVint(userId);
            writer.writeVint(groupId);
        }));
    }

    /// Creates a symbolic link redirection extra area.
    private static byte[] symlink(String target) throws IOException {
        return redirection(RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK, 0, target);
    }

    /// Creates a file system redirection extra area.
    private static byte[] redirection(int redirectionType, long redirectionFlags, String target) throws IOException {
        return extraRecord(0x05, fields(writer -> {
            writer.writeVint(redirectionType);
            writer.writeVint(redirectionFlags);
            writeLengthPrefixedString(writer, target);
        }));
    }

    /// Creates a BLAKE2sp file hash extra area.
    private static byte[] blake2spHash(byte[] hash) throws IOException {
        if (hash.length != 32) {
            throw new IllegalArgumentException("hash must contain 32 bytes");
        }
        return extraRecord(0x02, fields(writer -> {
            writer.writeVint(0);
            writer.write(hash);
        }));
    }

    /// Creates a Unix file time extra area.
    private static byte[] unixFileTimes(
            long modifiedSeconds,
            long modifiedNanos,
            long createdSeconds,
            long createdNanos,
            long accessedSeconds,
            long accessedNanos
    ) throws IOException {
        return extraRecord(0x03, fields(writer -> {
            writer.writeVint(0x001f);
            writer.writeUInt32(modifiedSeconds);
            writer.writeUInt32(modifiedNanos);
            writer.writeUInt32(createdSeconds);
            writer.writeUInt32(createdNanos);
            writer.writeUInt32(accessedSeconds);
            writer.writeUInt32(accessedNanos);
        }));
    }

    /// Creates a Windows FILETIME extra area.
    private static byte[] windowsFileTimes(Instant modified, Instant created, Instant accessed) throws IOException {
        return extraRecord(0x03, fields(writer -> {
            writer.writeVint(0x000e);
            writer.writeUInt64(windowsFileTime(modified));
            writer.writeUInt64(windowsFileTime(created));
            writer.writeUInt64(windowsFileTime(accessed));
        }));
    }

    /// Converts an instant to Windows FILETIME ticks.
    private static long windowsFileTime(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(Math.addExact(instant.getEpochSecond(), 11_644_473_600L), 10_000_000L),
                instant.getNano() / 100L
        );
    }

    /// Creates one extra area record.
    private static byte[] extraRecord(int type, byte[] data) throws IOException {
        ByteArrayOutputStream recordData = new ByteArrayOutputStream();
        VintWriter writer = new VintWriter(recordData);
        writer.writeVint(type);
        writer.write(data);

        byte[] recordDataBytes = recordData.toByteArray();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VintWriter outputWriter = new VintWriter(output);
        outputWriter.writeVint(recordDataBytes.length);
        outputWriter.write(recordDataBytes);
        return output.toByteArray();
    }

    /// Writes a length-prefixed UTF-8 string.
    private static void writeLengthPrefixedString(VintWriter writer, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writer.writeVint(bytes.length);
        writer.write(bytes);
    }

    /// Encodes fields through a writer callback.
    private static byte[] fields(WriterConsumer consumer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        consumer.accept(new VintWriter(output));
        return output.toByteArray();
    }

    /// Encodes one RAR variable length integer.
    private static byte[] vint(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long remaining = value;
        while (true) {
            int next = (int) (remaining & 0x7fL);
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            output.write(next);
            if (remaining == 0) {
                return output.toByteArray();
            }
        }
    }

    /// Writes one little-endian unsigned 32-bit integer.
    private static void writeUInt32(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
        output.write((byte) (value >>> 16));
        output.write((byte) (value >>> 24));
    }

    /// Writes one little-endian unsigned 64-bit integer.
    private static void writeUInt64(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
        output.write((byte) (value >>> 16));
        output.write((byte) (value >>> 24));
        output.write((byte) (value >>> 32));
        output.write((byte) (value >>> 40));
        output.write((byte) (value >>> 48));
        output.write((byte) (value >>> 56));
    }

    /// Returns the unsigned CRC32 value for the given bytes.
    private static long crc32(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, data.length);
        return crc32.getValue();
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-rar-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sample.rar");
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// Consumes a RAR test writer.
    @FunctionalInterface
    private interface WriterConsumer {
        /// Writes fields to the given writer.
        void accept(VintWriter writer) throws IOException;
    }

    /// Writes RAR test header primitives.
    @NotNullByDefault
    private static final class VintWriter {
        /// The destination stream.
        private final ByteArrayOutputStream output;

        /// Creates a primitive writer.
        private VintWriter(ByteArrayOutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        /// Writes one variable length integer.
        private void writeVint(long value) {
            byte[] bytes = vint(value);
            output.write(bytes, 0, bytes.length);
        }

        /// Writes one little-endian unsigned 32-bit integer.
        private void writeUInt32(long value) {
            RarArkivoStreamingReaderTest.writeUInt32(output, value);
        }

        /// Writes one little-endian unsigned 64-bit integer.
        private void writeUInt64(long value) {
            RarArkivoStreamingReaderTest.writeUInt64(output, value);
        }

        /// Writes raw bytes.
        private void write(byte[] bytes) throws IOException {
            output.write(bytes);
        }
    }

    /// One RAR test member.
    ///
    /// @param path the stored RAR entry path or service header name
    /// @param modificationTime the Unix modification time in seconds
    /// @param attributes the raw operating-system-specific file attributes
    /// @param directory whether this member is a directory
    /// @param symbolicLink whether this member is a symbolic link
    /// @param compressionMethod the RAR compression method number
    /// @param crc32 the unsigned CRC32 value stored in the file header
    /// @param unpackedSize the unpacked size stored in the file header
    /// @param body the packed data bytes
    /// @param extraArea the encoded extra area, or `null` when absent
    /// @param service whether this member is a service header
    /// @param continuesFromPreviousVolume whether this member continues data from the previous volume
    /// @param continuesInNextVolume whether this member continues data in the next volume
    @NotNullByDefault
    private record Member(
            String path,
            long modificationTime,
            long attributes,
            boolean directory,
            boolean symbolicLink,
            int compressionMethod,
            long crc32,
            long unpackedSize,
            byte @Unmodifiable [] body,
            byte @Nullable @Unmodifiable [] extraArea,
            boolean service,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        /// Creates one RAR test member.
        private Member {
            Objects.requireNonNull(path, "path");
            if (unpackedSize < 0) {
                throw new IllegalArgumentException("unpackedSize must not be negative");
            }
            body = body.clone();
            extraArea = extraArea != null ? extraArea.clone() : new byte[0];
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

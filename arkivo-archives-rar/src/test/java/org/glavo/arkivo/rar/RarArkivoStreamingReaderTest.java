// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar;

import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.ArkivoStoredContent;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
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
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.time.ZoneOffset;
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

    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// RAR4 file header flag indicating that the name field includes Unicode metadata.
    private static final long RAR4_FILE_FLAG_UNICODE = 0x0200L;

    /// Verifies that explicit volume sources and configured cached-content storage are both owned by the file system.
    @Test
    public void explicitVolumeFileSystemOwnsConfiguredStorage() throws IOException {
        byte[] content = "volume-content".getBytes(StandardCharsets.UTF_8);
        TestSeekableChannelSource source = new TestSeekableChannelSource(rar4Archive(
                storedFile("value.txt", 1_700_000_000L, 0100644, content, null)
        ));
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                source,
                Map.of(ArkivoFileSystem.EDIT_STORAGE.key(), storage)
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/value.txt")));
        }
        assertEquals(1, source.closeCount());
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, storage.createdContentCount());
        assertEquals(1, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
    }

    /// Verifies that file redirections share one cached body and failed cleanup can be retried.
    @Test
    public void fileSystemStorageSharesRedirectedBodiesAndRetriesCleanup() throws IOException {
        byte[] content = "shared-content".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("rar-storage-");
        Files.write(archivePath, archive(
                storedFile("source.txt", 1_700_000_000L, 0100644, content, null),
                redirectedEntry(
                        "hard.txt",
                        1_700_000_001L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK,
                        0,
                        "source.txt"
                ),
                redirectedEntry(
                        "copy.txt",
                        1_700_000_002L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY,
                        0,
                        "source.txt"
                )
        ));
        TrackingEditStorage storage = new TrackingEditStorage(true);
        RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                archivePath,
                Map.of(ArkivoFileSystem.EDIT_STORAGE.key(), storage)
        );
        try {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/source.txt")));
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/hard.txt")));
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/copy.txt")));
            IOException failure = assertThrows(IOException.class, fileSystem::close);
            assertEquals("content close failed", failure.getMessage());
            assertEquals(1, storage.createdContentCount());
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());

            fileSystem.close();
            fileSystem.close();
            assertEquals(2, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
        } finally {
            try {
                fileSystem.close();
            } finally {
                Files.deleteIfExists(archivePath);
            }
        }
    }

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
            PosixFileAttributes posixFile = reader.readAttributes(PosixFileAttributes.class);
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
            assertEquals("alice", posixFile.owner().getName());
            assertEquals("staff", posixFile.group().getName());
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.OTHERS_READ
                    ),
                    posixFile.permissions()
            );
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
                        "file-copy",
                        1_700_000_001L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY,
                        0,
                        "dir/hello.txt"
                ),
                redirectedEntry(
                        "junction",
                        1_700_000_002L,
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
            assertEquals(true, hardLink.isRegularFile());
            assertEquals(false, hardLink.isSymbolicLink());
            assertEquals(false, hardLink.isOther());
            assertNull(hardLink.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK, hardLink.redirectionType());
            assertEquals(0, hardLink.redirectionFlags());
            assertEquals("dir/hello.txt", hardLink.redirectionTarget());
            assertEquals(false, hardLink.redirectionTargetDirectory());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes fileCopy = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("file-copy", fileCopy.path());
            assertEquals(true, fileCopy.isRegularFile());
            assertEquals(false, fileCopy.isSymbolicLink());
            assertEquals(false, fileCopy.isOther());
            assertNull(fileCopy.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY, fileCopy.redirectionType());
            assertEquals(0, fileCopy.redirectionFlags());
            assertEquals("dir/hello.txt", fileCopy.redirectionTarget());
            assertEquals(false, fileCopy.redirectionTargetDirectory());
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
        byte[] backslashContent = "backslash path".getBytes(StandardCharsets.UTF_8);
        Set<PosixFilePermission> filePermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
        );
        byte[] splitFirstPart = "split ".getBytes(StandardCharsets.UTF_8);
        byte[] splitSecondPart = "content".getBytes(StandardCharsets.UTF_8);
        byte[] splitContent = concatenate(splitFirstPart, splitSecondPart);
        long splitCrc32 = crc32(splitContent);
        byte[] hash = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            hash[index] = (byte) index;
        }
        Path archivePath = createTemporaryArchivePath("rar-fs-");
        Path copiedDirectory = archivePath.getParent().resolve("copied-dir");
        Path existingFile = archivePath.getParent().resolve("existing-file");
        Files.write(archivePath, archive(
                directory("dir/", 1_700_000_000L, 040755),
                storedFile(
                        "dir/hello.txt",
                        1_700_000_010L,
                        0100644,
                        content,
                        concatenate(owner("alice", "staff", 1000, 1001), blake2spHash(hash))
                ),
                storedFile(
                        "windows\\path\\backslash.txt",
                        1_700_000_010L,
                        0100644,
                        backslashContent,
                        null
                ),
                redirectedEntry(
                        "dir/hard-link.txt",
                        1_700_000_010L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK,
                        0,
                        "dir/hello.txt"
                ),
                redirectedEntry(
                        "dir/file-copy.txt",
                        1_700_000_010L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY,
                        0,
                        "dir/hello.txt"
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
            Files.copy(directory, copiedDirectory);
            assertEquals(true, Files.isDirectory(copiedDirectory));
            assertThrows(FileAlreadyExistsException.class, () -> Files.copy(directory, copiedDirectory));
            Files.copy(directory, copiedDirectory, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(existingFile, "existing", StandardCharsets.UTF_8);
            Files.copy(directory, existingFile, StandardCopyOption.REPLACE_EXISTING);
            assertEquals(true, Files.isDirectory(existingFile));

            Path file = fileSystem.getPath("/dir/hello.txt");
            RarArkivoEntryAttributes fileAttributes = Files.readAttributes(file, RarArkivoEntryAttributes.class);
            RarArkivoEntryAttributeView rarView = Objects.requireNonNull(Files.getFileAttributeView(
                    file,
                    RarArkivoEntryAttributeView.class
            ));
            assertEquals(true, fileAttributes.isRegularFile());
            assertEquals(content.length, fileAttributes.size());
            assertEquals("alice", fileAttributes.userName());
            assertEquals("rar", rarView.name());
            assertEquals("dir/hello.txt", rarView.readAttributes().path());
            assertEquals("alice", rarView.readAttributes().userName());
            assertArrayEquals(hash, rarView.readAttributes().blake2spHash());
            PosixFileAttributes posixAttributes = Files.readAttributes(file, PosixFileAttributes.class);
            assertEquals("alice", posixAttributes.owner().getName());
            assertEquals("staff", posixAttributes.group().getName());
            assertEquals(filePermissions, posixAttributes.permissions());
            FileOwnerAttributeView ownerView =
                    Objects.requireNonNull(Files.getFileAttributeView(file, FileOwnerAttributeView.class));
            assertEquals("owner", ownerView.name());
            assertEquals("alice", ownerView.getOwner().getName());
            assertThrows(ReadOnlyFileSystemException.class, () -> ownerView.setOwner(() -> "other-user"));
            PosixFileAttributeView posixView =
                    Objects.requireNonNull(Files.getFileAttributeView(file, PosixFileAttributeView.class));
            assertEquals("posix", posixView.name());
            assertEquals(filePermissions, posixView.readAttributes().permissions());
            assertEquals("alice", posixView.getOwner().getName());
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setGroup(() -> "other-group"));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> posixView.setPermissions(Set.<PosixFilePermission>of())
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> rarView.setTimes(
                    FileTime.fromMillis(1_700_000_030_000L),
                    null,
                    null
            ));
            var fileStore = Files.getFileStore(file);
            assertEquals(fileStore.name(), fileStore.getAttribute("name"));
            assertEquals(fileStore.type(), fileStore.getAttribute("type"));
            assertEquals(Boolean.valueOf(fileStore.isReadOnly()), fileStore.getAttribute("basic:readOnly"));
            assertEquals(Long.valueOf(fileStore.getTotalSpace()), fileStore.getAttribute("totalSpace"));
            assertEquals(Long.valueOf(fileStore.getUsableSpace()), fileStore.getAttribute("usableSpace"));
            assertEquals(Long.valueOf(fileStore.getUnallocatedSpace()), fileStore.getAttribute("unallocatedSpace"));
            assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("rar:type"));
            assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("missing"));
            assertEquals(true, fileStore.supportsFileAttributeView(RarArkivoEntryAttributeView.class));
            assertEquals(true, fileStore.supportsFileAttributeView(FileOwnerAttributeView.class));
            assertEquals(true, fileStore.supportsFileAttributeView(PosixFileAttributeView.class));
            assertEquals(true, fileStore.supportsFileAttributeView("rar"));
            assertEquals(true, fileStore.supportsFileAttributeView("owner"));
            assertEquals(true, fileStore.supportsFileAttributeView("posix"));
            assertArrayEquals(content, Files.readAllBytes(file));

            Path backslashFile = fileSystem.getPath("/windows/path/backslash.txt");
            assertArrayEquals(backslashContent, Files.readAllBytes(backslashFile));

            Path hardLink = fileSystem.getPath("/dir/hard-link.txt");
            RarArkivoEntryAttributes hardLinkAttributes =
                    Files.readAttributes(hardLink, RarArkivoEntryAttributes.class);
            assertEquals(true, hardLinkAttributes.isRegularFile());
            assertEquals(false, hardLinkAttributes.isOther());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK, hardLinkAttributes.redirectionType());
            assertEquals("dir/hello.txt", hardLinkAttributes.redirectionTarget());
            assertEquals(content.length, hardLinkAttributes.size());
            assertArrayEquals(content, Files.readAllBytes(hardLink));

            Path fileCopy = fileSystem.getPath("/dir/file-copy.txt");
            RarArkivoEntryAttributes fileCopyAttributes =
                    Files.readAttributes(fileCopy, RarArkivoEntryAttributes.class);
            assertEquals(true, fileCopyAttributes.isRegularFile());
            assertEquals(false, fileCopyAttributes.isOther());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY, fileCopyAttributes.redirectionType());
            assertEquals("dir/hello.txt", fileCopyAttributes.redirectionTarget());
            assertEquals(content.length, fileCopyAttributes.size());
            assertArrayEquals(content, Files.readAllBytes(fileCopy));

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

            Map<String, Object> ownerNamedAttributes = Files.readAttributes(file, "owner:owner");
            assertEquals("alice", ((UserPrincipal) ownerNamedAttributes.get("owner")).getName());

            Map<String, Object> posixNamedAttributes =
                    Files.readAttributes(file, "posix:owner,group,permissions,isRegularFile");
            assertEquals("alice", ((UserPrincipal) posixNamedAttributes.get("owner")).getName());
            assertEquals("staff", ((GroupPrincipal) posixNamedAttributes.get("group")).getName());
            assertEquals(filePermissions, posixNamedAttributes.get("permissions"));
            assertEquals(true, posixNamedAttributes.get("isRegularFile"));

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
            assertEquals(
                    Set.of(
                            "/dir/hello.txt",
                            "/dir/hard-link.txt",
                            "/dir/file-copy.txt",
                            "/dir/split.txt",
                            "/dir/compressed.bin"
                    ),
                    Set.copyOf(children)
            );

            IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/dir/compressed.bin")));
            assertEquals(true, exception.getMessage().contains("content is not available"));
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
        }

        assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/dir"));
        Files.deleteIfExists(existingFile);
        Files.deleteIfExists(copiedDirectory);
        deleteTemporaryArchive(archivePath);
    }

    /// Verifies that a repeatable seekable channel source supports random-access RAR file system operations.
    @Test
    public void randomAccessFileSystemFromSeekableChannelSource() throws IOException {
        byte[] content = "seekable channel source content".getBytes(StandardCharsets.UTF_8);
        TestSeekableChannelSource source = new TestSeekableChannelSource(archive(
                storedFile("hello.txt", 0, 0100644, content, null)
        ));

        try (RarArkivoFileSystem fileSystem = RarArkivoFormat.instance().open(source)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/hello.txt")));
            assertEquals(true, source.openCount() > 0);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(0, source.closeCount());
        }

        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that failed RAR parsing closes a seekable channel source and every channel opened from it.
    @Test
    public void failedSeekableChannelSourceOpenClosesSource() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(new byte[0]);

        assertThrows(IOException.class, () -> RarArkivoFileSystem.open(source, Map.of()));

        assertEquals(true, source.openCount() > 0);
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
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

    /// Verifies that stored RAR4 entries split across explicit archive volumes can be read.
    @Test
    public void opensStoredRar4SplitEntryFromVolumeSource() throws IOException {
        byte[] firstPart = "rar4 volume ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "source".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar4-volumes-");
        Path secondVolume = firstVolume.getParent().resolve("sample.part2.rar");
        Files.write(firstVolume, rar4ArchiveVolume(false, splitStoredFilePart(
                "split.txt",
                1_700_000_000L,
                0100644,
                content.length,
                contentCrc32,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, rar4ArchiveVolume(
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

    /// Verifies that stored RAR5 entries can be read from conventional `partN.rar` paths.
    @Test
    public void opensStoredSplitEntryFromPartPath() throws IOException {
        byte[] firstPart = "part path ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "source".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar-part-path-").resolveSibling("sample.part1.rar");
        Path secondVolume = firstVolume.resolveSibling("sample.part2.rar");
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
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(firstVolume)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/split.txt")));
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

    /// Verifies that stored RAR4 entries can be read from legacy `.rar` and `.r00` paths.
    @Test
    public void opensStoredRar4SplitEntryFromLegacyPath() throws IOException {
        byte[] firstPart = "rar4 legacy ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "path".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar4-legacy-path-");
        Path secondVolume = firstVolume.resolveSibling("sample.r00");
        Files.write(firstVolume, rar4ArchiveVolume(false, splitStoredFilePart(
                "split.txt",
                1_700_000_000L,
                0100644,
                content.length,
                contentCrc32,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, rar4ArchiveVolume(
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
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(firstVolume)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/split.txt")));
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

    /// Verifies that stored RAR4 entries can be streamed and exposed through the file system API.
    @Test
    public void readsStoredRar4Entries() throws IOException {
        byte[] content = "rar4 stored content".getBytes(StandardCharsets.UTF_8);
        byte[] unicodeContent = "rar4 unicode content".getBytes(StandardCharsets.UTF_8);
        String unicodePath = "unicod\u00e9/na\u00efve.txt";
        long modificationTime = 1_700_000_010L;
        byte[] archive = rar4Archive(
                directory("dir/", modificationTime, 040755),
                storedFile("dir/hello.txt", modificationTime, 0100644, content, null),
                storedFile(unicodePath, modificationTime, 0100644, unicodeContent, null)
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes directory = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("dir/", directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, directory.hostOs());
            assertEquals(0, directory.compressionMethod());
            assertEquals(FileTime.from(Instant.ofEpochSecond(modificationTime)), directory.lastModifiedTime());

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes file = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("dir/hello.txt", file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, file.hostOs());
            assertEquals(0, file.compressionMethod());
            assertEquals(content.length, file.packedSize());
            assertEquals(content.length, file.unpackedSize());
            assertEquals(crc32(content), file.dataCrc32());
            assertEquals(FileTime.from(Instant.ofEpochSecond(modificationTime)), file.lastModifiedTime());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes unicodeFile = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(unicodePath, unicodeFile.path());
            assertEquals(true, unicodeFile.isRegularFile());
            assertEquals(0, unicodeFile.compressionMethod());
            assertEquals(unicodeContent.length, unicodeFile.packedSize());
            assertEquals(unicodeContent.length, unicodeFile.unpackedSize());
            assertEquals(crc32(unicodeContent), unicodeFile.dataCrc32());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(unicodeContent, input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }

        Path archivePath = createTemporaryArchivePath("rar4-fs-");
        Files.write(archivePath, archive);
        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archivePath)) {
            Path file = fileSystem.getPath("/dir/hello.txt");
            assertArrayEquals(content, Files.readAllBytes(file));
            assertEquals(content.length, Files.size(file));
            assertArrayEquals(unicodeContent, Files.readAllBytes(fileSystem.getPath("/" + unicodePath)));
        }
        deleteTemporaryArchive(archivePath);
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

    /// Creates one RAR4 archive.
    private static byte[] rar4Archive(Member... members) throws IOException {
        return rar4ArchiveVolume(true, members);
    }

    /// Creates one RAR4 archive volume.
    private static byte[] rar4ArchiveVolume(boolean includeEndHeader, Member... members) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        writeRar4Block(output, 0x73, 0, new byte[6], new byte[0]);
        for (Member member : members) {
            writeRar4Member(output, member);
        }
        if (includeEndHeader) {
            writeRar4Block(output, 0x7b, 0, new byte[0], new byte[0]);
        }
        return output.toByteArray();
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

    /// Writes one RAR4 member block.
    private static void writeRar4Member(ByteArrayOutputStream output, Member member) throws IOException {
        if (member.service()) {
            return;
        }

        boolean unicodeName = needsRar4UnicodeName(member.path());
        byte[] name = rar4NameBytes(member.path(), unicodeName);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeUInt32(fields, member.body().length);
        writeUInt32(fields, member.unpackedSize());
        fields.write(3);
        writeUInt32(fields, member.crc32());
        writeUInt32(fields, rar4DosTime(member.modificationTime()));
        fields.write(29);
        fields.write(member.compressionMethod() == 0 ? 0x30 : 0x30 + member.compressionMethod());
        writeUInt16(fields, name.length);
        writeUInt32(fields, member.attributes());
        fields.write(name);

        long flags = 0x8000L;
        if (member.continuesFromPreviousVolume()) {
            flags |= 0x0001L;
        }
        if (member.continuesInNextVolume()) {
            flags |= 0x0002L;
        }
        if (unicodeName) {
            flags |= RAR4_FILE_FLAG_UNICODE;
        }
        writeRar4Block(output, 0x74, flags, fields.toByteArray(), member.body());
    }

    /// Returns whether a RAR4 fixture name needs Unicode metadata.
    private static boolean needsRar4UnicodeName(String path) {
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) > 0x7f) {
                return true;
            }
        }
        return false;
    }

    /// Encodes a RAR4 fixture name.
    private static byte[] rar4NameBytes(String path, boolean unicodeName) {
        if (!unicodeName) {
            return path.getBytes(StandardCharsets.UTF_8);
        }

        byte[] fallbackName = rar4FallbackName(path);
        byte[] encodedName = rar4UnicodeNameData(path);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(fallbackName, 0, fallbackName.length);
        output.write(0);
        output.write(encodedName, 0, encodedName.length);
        return output.toByteArray();
    }

    /// Returns a single-byte fallback name for a RAR4 Unicode fixture name.
    private static byte[] rar4FallbackName(String path) {
        byte[] fallbackName = new byte[path.length()];
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            fallbackName[index] = character <= 0x7f ? (byte) character : (byte) '?';
        }
        return fallbackName;
    }

    /// Encodes Unicode name data for a RAR4 fixture name.
    private static byte[] rar4UnicodeNameData(String path) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        for (int offset = 0; offset < path.length(); ) {
            int groupLength = Math.min(4, path.length() - offset);
            int flags = 0;
            for (int index = 0; index < groupLength; index++) {
                flags |= 0x02 << (6 - index * 2);
            }
            output.write(flags);
            for (int index = 0; index < groupLength; index++) {
                char character = path.charAt(offset++);
                output.write(character & 0xff);
                output.write(character >>> 8);
            }
        }
        return output.toByteArray();
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

    /// Writes one complete RAR4 block.
    private static void writeRar4Block(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte[] fields,
            byte[] data
    ) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        headerData.write(type);
        writeUInt16(headerData, flags);
        writeUInt16(headerData, 7 + fields.length);
        headerData.write(fields);

        byte[] headerDataBytes = headerData.toByteArray();
        CRC32 headerCrc32 = new CRC32();
        headerCrc32.update(headerDataBytes, 0, headerDataBytes.length);
        writeUInt16(output, headerCrc32.getValue());
        output.write(headerDataBytes);
        output.write(data);
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

    /// Writes one little-endian unsigned 16-bit integer.
    private static void writeUInt16(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
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

    /// Converts an epoch second value to a RAR4 DOS timestamp.
    private static long rar4DosTime(long epochSeconds) {
        var time = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC);
        int year = time.getYear();
        if (year < 1980 || year > 2107) {
            throw new IllegalArgumentException("RAR4 DOS timestamp year is out of range");
        }
        return (long) (year - 1980) << 25
                | (long) time.getMonthValue() << 21
                | (long) time.getDayOfMonth() << 16
                | (long) time.getHour() << 11
                | (long) time.getMinute() << 5
                | time.getSecond() / 2L;
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

    /// Repeatable single-archive source that records opened channel and source lifecycles.
    @NotNullByDefault
    private static final class TestSeekableChannelSource implements ArkivoSeekableChannelSource {
        /// The archive bytes exposed by each opened channel.
        private final byte @Unmodifiable [] content;

        /// The channels opened from this source.
        private final ArrayList<TestByteArraySeekableChannel> openedChannels = new ArrayList<>();

        /// The number of times this source has been closed.
        private int closeCount;

        /// Creates a repeatable source over the given archive bytes.
        private TestSeekableChannelSource(byte[] content) {
            this.content = Objects.requireNonNull(content, "content").clone();
        }

        /// Opens an independent channel over the archive bytes.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closeCount > 0) {
                throw new IOException("source is closed");
            }
            TestByteArraySeekableChannel channel = new TestByteArraySeekableChannel(content);
            openedChannels.add(channel);
            return channel;
        }

        /// Records that this source has been closed.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns the number of channels opened from this source.
        private int openCount() {
            return openedChannels.size();
        }

        /// Returns whether every channel opened from this source has been closed.
        private boolean allOpenedChannelsClosed() {
            for (TestByteArraySeekableChannel channel : openedChannels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }

        /// Returns the number of times this source has been closed.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Read-only seekable channel over an immutable byte array.
    @NotNullByDefault
    private static final class TestByteArraySeekableChannel implements SeekableByteChannel {
        /// The immutable channel content.
        private final byte @Unmodifiable [] content;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a read-only channel over the given content.
        private TestByteArraySeekableChannel(byte[] content) {
            this.content = Objects.requireNonNull(content, "content").clone();
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(destination.remaining(), content.length - position);
            destination.put(content, position, count);
            position += count;
            return count;
        }

        /// Always rejects writes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            Objects.requireNonNull(source, "source");
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition is out of range");
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the content size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Always rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
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
    /// @param path                        the stored RAR entry path or service header name
    /// @param modificationTime            the Unix modification time in seconds
    /// @param attributes                  the raw operating-system-specific file attributes
    /// @param directory                   whether this member is a directory
    /// @param symbolicLink                whether this member is a symbolic link
    /// @param compressionMethod           the RAR compression method number
    /// @param crc32                       the unsigned CRC32 value stored in the file header
    /// @param unpackedSize                the unpacked size stored in the file header
    /// @param body                        the packed data bytes
    /// @param extraArea                   the encoded extra area, or `null` when absent
    /// @param service                     whether this member is a service header
    /// @param continuesFromPreviousVolume whether this member continues data from the previous volume
    /// @param continuesInNextVolume       whether this member continues data in the next volume
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

    /// Tracks cached-content allocation and close calls while delegating content to memory storage.
    @NotNullByDefault
    private static final class TrackingEditStorage implements ArkivoEditStorage {
        /// The delegate memory storage.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Whether the first stored-content close call must fail.
        private final boolean failFirstContentClose;

        /// The number of created content objects.
        private int createdContentCount;

        /// The total number of stored-content close calls.
        private int contentCloseCount;

        /// The number of storage close calls.
        private int closeCount;

        /// Creates tracking storage with the requested cleanup behavior.
        private TrackingEditStorage(boolean failFirstContentClose) {
            this.failFirstContentClose = failFirstContentClose;
        }

        /// Creates one tracked stored-content object.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createdContentCount++;
            return new TrackingStoredContent(delegate.createContent(path, expectedSize));
        }

        /// Closes the delegate storage and records the call.
        @Override
        public void close() throws IOException {
            closeCount++;
            delegate.close();
        }

        /// Returns the number of created content objects.
        private int createdContentCount() {
            return createdContentCount;
        }

        /// Returns the total number of stored-content close calls.
        private int contentCloseCount() {
            return contentCloseCount;
        }

        /// Returns the number of storage close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Tracks one delegated stored-content object.
        @NotNullByDefault
        private final class TrackingStoredContent implements ArkivoStoredContent {
            /// The delegated stored content.
            private final ArkivoStoredContent content;

            /// Whether this content has failed its first close call.
            private boolean firstCloseFailed;

            /// Creates tracked stored content.
            private TrackingStoredContent(ArkivoStoredContent content) {
                this.content = content;
            }

            /// Opens a channel over the delegated content.
            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                return content.openChannel(options);
            }

            /// Returns the delegated content size.
            @Override
            public long size() throws IOException {
                return content.size();
            }

            /// Closes the delegated content or injects the configured first failure.
            @Override
            public void close() throws IOException {
                contentCloseCount++;
                if (failFirstContentClose && !firstCloseFailed) {
                    firstCloseFailed = true;
                    throw new IOException("content close failed");
                }
                content.close();
            }
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

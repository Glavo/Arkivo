// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotLinkException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stable ZIP and POSIX attribute snapshots for pending streaming-writer entries.
@NotNullByDefault
final class ZipArkivoStreamingWriterAttributesTest {
    /// Temporary directory used by the indexed metadata round-trip test.
    @TempDir
    private Path temporaryDirectory;

    /// Directory bodies must satisfy configured expectations before any local record is written.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesDirectoryExpectations(boolean wrongSize) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive);
        try {
            var entry = writer.beginDirectory("directory");
            var view = Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class));
            view.setUncompressedSizeAndCrc32(wrongSize ? 1L : 0L, wrongSize ? 0L : 1L);
            for (int attempt = 0; attempt < 2; attempt++) {
                IOException failure = assertThrows(IOException.class, entry::close);
                assertTrue(failure.getMessage().contains(wrongSize
                        ? "size does not match" : "CRC-32 does not match"), failure::toString);
                assertEquals(0, archive.size());
            }
            assertThrows(IOException.class, writer::close);
            int completedSize = archive.size();
            writer.close();
            assertEquals(completedSize, archive.size());
        } finally {
            writer.close();
        }
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            assertNull(input.getNextEntry());
        }
    }

    /// Empty entries must satisfy explicitly configured size and CRC expectations through every completion path.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesEmptyEntryExpectations(boolean wrongSize) throws IOException {
        for (ZipMethod method : new ZipMethod[]{ZipMethod.STORED, ZipMethod.DEFLATED}) {
            for (int completion = 0; completion < 3; completion++) {
                ByteArrayOutputStream archive = new ByteArrayOutputStream();
                ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive);
                try {
                    var entry = writer.beginFile("entry");
                    var view = Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class));
                    view.setMethod(method);
                    view.setUncompressedSizeAndCrc32(wrongSize ? 1L : 0L, wrongSize ? 0L : 1L);
                    IOException failure = switch (completion) {
                        case 0 -> assertThrows(IOException.class, entry::close);
                        case 1 -> assertThrows(IOException.class, entry.openOutputStream()::close);
                        default -> assertThrows(IOException.class, writer::close);
                    };
                    assertTrue(failure.getMessage().contains(wrongSize
                            ? "size does not match" : "CRC-32 does not match"), failure::toString);
                    int failedSize = archive.size();
                    if (completion != 2) {
                        assertThrows(IOException.class, writer::close);
                    }
                    writer.close();
                    assertEquals(failedSize, archive.size());
                } finally {
                    writer.close();
                }
            }
        }
    }

    /// Missing expectations default to an empty stored body, while explicitly configured zeros remain valid.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writesEmptyStoredEntries(boolean explicitExpectations) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            var entry = writer.beginFile("empty");
            var view = Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class));
            view.setMethod(ZipMethod.STORED);
            if (explicitExpectations) {
                view.setUncompressedSizeAndCrc32(0L, 0L);
            }
            entry.close();
        }
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            var entry = Objects.requireNonNull(input.getNextEntry());
            assertEquals("empty", entry.getName());
            assertEquals(0L, entry.getSize());
            assertEquals(0L, entry.getCrc());
            assertEquals(-1, input.read());
            assertNull(input.getNextEntry());
        }
    }

    /// Rejects conflicting physical parent records without resolving symbolic links in streaming entry names.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void rejectsNonDirectoryStreamingParents(int kind) throws IOException {
        for (boolean linkParent : new boolean[]{false, true}) {
            Path archive = temporaryDirectory.resolve("parent-" + kind + "-" + linkParent + ".zip");
            var writer = ZipArkivoStreamingWriter.create(archive);
            try {
                writer.beginDirectory("real").close();
                if (linkParent) {
                    writer.beginSymbolicLink("parent", "real").close();
                } else {
                    writer.beginFile("parent").close();
                }
                var entry = switch (kind) {
                    case 0, 1 -> writer.beginFile("parent/child");
                    case 2 -> writer.beginDirectory("parent/child");
                    case 3 -> writer.beginSymbolicLink("parent/child", "target");
                    default -> throw new AssertionError(kind);
                };
                assertThrows(NotDirectoryException.class, () -> {
                    if (kind == 0) {
                        try (var ignored = entry.openOutputStream()) { }
                    } else {
                        entry.close();
                    }
                });
                assertThrows(NotDirectoryException.class, writer::close);
            } finally {
                writer.close();
            }
            try (var fs = ZipArkivoFileSystem.open(archive)) {
                assertEquals(linkParent, Files.isSymbolicLink(fs.getPath("parent")));
                try (var entries = Files.list(fs.getPath("real"))) {
                    assertEquals(0, entries.count());
                }
                assertFalse(Files.exists(fs.getPath("parent/child")));
            }
        }
    }

    /// Null timestamp arguments retain pending values and the modification time written to the DOS header.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void preservesUnspecifiedTimestamps(int changed) throws IOException {
        Path archive = temporaryDirectory.resolve("timestamps.zip");
        FileTime original = FileTime.from(Instant.parse("2020-02-04T06:08:10Z"));
        FileTime replacement = FileTime.from(Instant.parse("2021-03-05T07:09:12Z"));
        FileTime modified = (changed & 1) != 0 ? replacement : original;
        FileTime accessed = (changed & 2) != 0 ? replacement : original;
        FileTime created = (changed & 4) != 0 ? replacement : original;
        try (var writer = ZipArkivoStreamingWriter.create(archive)) {
            try (var entry = writer.beginFile("entry")) {
                BasicFileAttributeView view = requireView(entry, BasicFileAttributeView.class);
                view.setTimes(original, original, original);
                BasicFileAttributes snapshot = view.readAttributes();
                view.setTimes((changed & 1) != 0 ? replacement : null,
                        (changed & 2) != 0 ? replacement : null,
                        (changed & 4) != 0 ? replacement : null);
                BasicFileAttributes updated = view.readAttributes();
                assertEquals(modified, updated.lastModifiedTime());
                assertEquals(accessed, updated.lastAccessTime());
                assertEquals(created, updated.creationTime());
                assertEquals(original, snapshot.lastModifiedTime());
                assertEquals(original, snapshot.lastAccessTime());
                assertEquals(original, snapshot.creationTime());
            }
        }
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            BasicFileAttributes attributes = Files.readAttributes(fileSystem.getPath("/entry"),
                    BasicFileAttributes.class);
            assertEquals(modified, attributes.lastModifiedTime());
            // No timestamp extra fields were supplied; these read attributes fall back to the modification time.
            assertEquals(modified, attributes.lastAccessTime());
            assertEquals(modified, attributes.creationTime());
        }
    }

    /// Verifies file defaults, configured metadata, defensive copies, method flags, and snapshot stability.
    @Test
    void snapshotsConfiguredFileAttributes() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("dir/value.txt");
            ZipArkivoEntryAttributeView zipView = requireView(entry, ZipArkivoEntryAttributeView.class);
            PosixFileAttributeView posixView = requireView(entry, PosixFileAttributeView.class);
            BasicFileAttributeView basicView = requireView(entry, BasicFileAttributeView.class);

            assertEquals("zip", zipView.name());
            assertEquals("posix", posixView.name());
            assertThrows(UnsupportedOperationException.class, () -> zipView.setOwner(posixView.getOwner()));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> zipView.setGroup(posixView.readAttributes().group())
            );
            posixView.setOwner(posixView.getOwner());
            posixView.setGroup(posixView.readAttributes().group());
            assertThrows(UserPrincipalNotFoundException.class, () -> posixView.setOwner(() -> "missing"));
            assertThrows(UserPrincipalNotFoundException.class, () -> posixView.setGroup(() -> "missing"));

            byte @Unmodifiable [] malformedExtraData = {0x01, 0x00, 0x02, 0x00, 0x03};
            IOException localExtraFailure = assertThrows(
                    IOException.class,
                    () -> zipView.setLocalExtraData(malformedExtraData)
            );
            assertTrue(localExtraFailure.getMessage().contains("Invalid ZIP extra field length"));
            IOException centralExtraFailure = assertThrows(
                    IOException.class,
                    () -> zipView.setCentralDirectoryExtraData(malformedExtraData)
            );
            assertTrue(centralExtraFailure.getMessage().contains("Invalid ZIP extra field length"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> zipView.setUncompressedSizeAndCrc32(-1L, 0L)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> zipView.setUncompressedSizeAndCrc32(0L, -1L)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> zipView.setUncompressedSizeAndCrc32(0L, 0x1_0000_0000L)
            );
            assertThrows(IllegalArgumentException.class, () -> zipView.setInternalAttributes(-1));
            assertThrows(IllegalArgumentException.class, () -> zipView.setInternalAttributes(0x1_0000));
            assertThrows(IllegalArgumentException.class, () -> zipView.setExternalAttributes(-1L));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> zipView.setExternalAttributes(0x1_0000_0000L)
            );

            ZipArkivoEntryAttributes defaults = zipView.readAttributes();
            assertArrayEquals("dir/value.txt".getBytes(StandardCharsets.UTF_8), defaults.rawPath());
            assertEquals("dir/value.txt", defaults.path());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, defaults.compressedSize());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_CRC32, defaults.crc32());
            assertEquals(1 << 11, defaults.generalPurposeFlags());
            assertEquals(20, defaults.versionMadeBy());
            assertEquals(20, defaults.versionNeededToExtract());
            assertEquals(0, defaults.internalAttributes());
            assertEquals(0L, defaults.externalAttributes());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, defaults.userId());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, defaults.groupId());
            assertEquals(ZipMethod.DEFLATED.id(), defaults.compressionMethodId());
            assertEquals(ZipMethod.DEFLATED, defaults.compressionMethod());
            assertEquals(ZipEncryption.NONE, defaults.encryption());
            assertArrayEquals(new byte[0], defaults.localExtraData());
            assertArrayEquals(new byte[0], defaults.centralDirectoryExtraData());
            assertNull(defaults.rawComment());
            assertEquals(FileTime.fromMillis(0L), defaults.lastModifiedTime());
            assertEquals(defaults.lastModifiedTime(), defaults.lastAccessTime());
            assertEquals(defaults.lastModifiedTime(), defaults.creationTime());
            assertTrue(defaults.isRegularFile());
            assertFalse(defaults.isDirectory());
            assertFalse(defaults.isSymbolicLink());
            assertFalse(defaults.isOther());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, defaults.size());
            assertNull(defaults.fileKey());
            assertTrue(defaults.permissions().contains(PosixFilePermission.OWNER_READ));

            BasicFileAttributes basicDefaults = basicView.readAttributes();
            assertTrue(basicDefaults.isRegularFile());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, basicDefaults.size());

            FileTime modifiedTime = FileTime.from(Instant.parse("2031-02-03T04:05:06Z"));
            FileTime accessTime = FileTime.from(Instant.parse("2032-03-04T05:06:07Z"));
            FileTime creationTime = FileTime.from(Instant.parse("2033-04-05T06:07:08Z"));
            byte[] localExtraData = {0x34, 0x12, 0x01, 0x00, 0x55};
            byte[] centralExtraData = {0x78, 0x56, 0x00, 0x00};
            byte[] rawComment = "configured comment".getBytes(StandardCharsets.UTF_8);
            Set<PosixFilePermission> permissions = Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ
            );

            zipView.setTimes(modifiedTime, accessTime, creationTime);
            zipView.setMethod(ZipMethod.LZMA);
            zipView.setEncryption(ZipEncryption.ZIP_CRYPTO);
            zipView.setUncompressedSizeAndCrc32(42L, 0xfedc_ba98L);
            zipView.setInternalAttributes(0x1234);
            zipView.setExternalAttributes(0x89ab_cdefL);
            zipView.setLocalExtraData(localExtraData);
            zipView.setCentralDirectoryExtraData(centralExtraData);
            zipView.setRawComment(rawComment);
            zipView.setPermissions(permissions);
            localExtraData[4] = 0;
            centralExtraData[0] = 0;
            rawComment[0] = 0;

            ZipArkivoEntryAttributes configured = zipView.readAttributes();
            assertEquals(42L, configured.size());
            assertEquals(0xfedc_ba98L, configured.crc32());
            assertEquals(ZipMethod.LZMA, configured.compressionMethod());
            assertEquals(ZipEncryption.ZIP_CRYPTO, configured.encryption());
            assertEquals(63, configured.versionNeededToExtract());
            assertEquals((1 << 11) | (1 << 1) | 1, configured.generalPurposeFlags());
            assertEquals(3, configured.versionMadeBy() >>> 8);
            assertEquals(0x1234, configured.internalAttributes());
            assertEquals(0x89ab_cdefL, configured.externalAttributes());
            assertEquals(modifiedTime, configured.lastModifiedTime());
            assertEquals(accessTime, configured.lastAccessTime());
            assertEquals(creationTime, configured.creationTime());
            assertEquals(permissions, configured.permissions());
            assertArrayEquals(new byte[]{0x34, 0x12, 0x01, 0x00, 0x55}, configured.localExtraData());
            assertArrayEquals(new byte[]{0x78, 0x56, 0x00, 0x00}, configured.centralDirectoryExtraData());
            assertArrayEquals("configured comment".getBytes(StandardCharsets.UTF_8), configured.rawComment());

            byte[] returnedPath = configured.rawPath();
            byte[] returnedLocalExtraData = configured.localExtraData();
            byte[] returnedCentralExtraData = configured.centralDirectoryExtraData();
            byte[] returnedComment = Objects.requireNonNull(configured.rawComment());
            returnedPath[0] = 0;
            returnedLocalExtraData[0] = 0;
            returnedCentralExtraData[0] = 0;
            returnedComment[0] = 0;
            assertArrayEquals("dir/value.txt".getBytes(StandardCharsets.UTF_8), configured.rawPath());
            assertEquals(0x34, configured.localExtraData()[0]);
            assertEquals(0x78, configured.centralDirectoryExtraData()[0]);
            assertEquals('c', configured.rawComment()[0]);

            PosixFileAttributes posixAttributes = posixView.readAttributes();
            assertEquals(modifiedTime, posixAttributes.lastModifiedTime());
            assertEquals(accessTime, posixAttributes.lastAccessTime());
            assertEquals(creationTime, posixAttributes.creationTime());
            assertTrue(posixAttributes.isRegularFile());
            assertFalse(posixAttributes.isDirectory());
            assertFalse(posixAttributes.isSymbolicLink());
            assertFalse(posixAttributes.isOther());
            assertEquals(42L, posixAttributes.size());
            assertNull(posixAttributes.fileKey());
            assertEquals(configured.owner(), posixAttributes.owner());
            assertEquals(configured.group(), posixAttributes.group());
            assertEquals(permissions, posixAttributes.permissions());
            posixView.setOwner(posixAttributes.owner());
            posixView.setGroup(posixAttributes.group());

            zipView.setMethod(ZipMethod.DEFLATE64);
            assertEquals(21, zipView.readAttributes().versionNeededToExtract());
            zipView.setTimes(null, null, null);
            zipView.setMethod(ZipMethod.STORED);
            zipView.setEncryption(ZipEncryption.NONE);
            zipView.setUncompressedSizeAndCrc32(0L, 0L);
            zipView.setInternalAttributes(0);
            zipView.setExternalAttributes(0L);
            zipView.setLocalExtraData(new byte[0]);
            zipView.setCentralDirectoryExtraData(new byte[0]);
            zipView.setRawComment(null);
            zipView.setPermissions(Set.of());

            assertArrayEquals("configured comment".getBytes(StandardCharsets.UTF_8), configured.rawComment());
            assertEquals(42L, configured.size());
            assertEquals(ZipMethod.LZMA, configured.compressionMethod());
            assertEquals(permissions, configured.permissions());
            try (var output = entry.openOutputStream()) {
                // An empty body matches the configured stored-entry size and CRC-32.
            }
            assertThrows(IllegalStateException.class, () -> zipView.setMethod(ZipMethod.DEFLATED));
        }

        assertTrue(archive.size() > 0);
    }

    /// Verifies directory and symbolic-link snapshots expose their effective stored and POSIX metadata.
    @Test
    void classifiesPendingDirectoryAndSymbolicLinkAttributes() throws IOException {
        Path archive = temporaryDirectory.resolve("attributes.zip");
        FileTime linkTime = FileTime.from(Instant.parse("2034-05-06T07:08:08Z"));
        Set<PosixFilePermission> linkPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        );

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archive)) {
            ArkivoStreamingWriter.Entry directory = writer.beginDirectory("directory");
            ZipArkivoEntryAttributeView directoryView = requireView(directory, ZipArkivoEntryAttributeView.class);
            PosixFileAttributeView directoryPosixView = requireView(directory, PosixFileAttributeView.class);
            ZipArkivoEntryAttributes directoryAttributes = directoryView.readAttributes();
            assertTrue(directoryAttributes.isDirectory());
            assertFalse(directoryAttributes.isRegularFile());
            assertFalse(directoryAttributes.isSymbolicLink());
            assertEquals(0L, directoryAttributes.size());
            assertEquals(ZipMethod.STORED, directoryAttributes.compressionMethod());
            assertEquals(ZipEncryption.NONE, directoryAttributes.encryption());
            assertEquals(0x10L, directoryAttributes.externalAttributes());
            PosixFileAttributes directoryPosixAttributes = directoryPosixView.readAttributes();
            assertTrue(directoryPosixAttributes.isDirectory());
            assertFalse(directoryPosixAttributes.isRegularFile());
            assertThrows(UnsupportedOperationException.class, () -> directoryView.setMethod(ZipMethod.DEFLATED));
            assertThrows(IllegalStateException.class, directory::openOutputStream);
            directory.close();

            ArkivoStreamingWriter.Entry symbolicLink = writer.beginSymbolicLink("link", "directory");
            ZipArkivoEntryAttributeView linkView = requireView(symbolicLink, ZipArkivoEntryAttributeView.class);
            PosixFileAttributeView linkPosixView = requireView(symbolicLink, PosixFileAttributeView.class);
            linkPosixView.setTimes(linkTime, linkTime, linkTime);
            linkPosixView.setPermissions(linkPermissions);
            ZipArkivoEntryAttributes linkAttributes = linkView.readAttributes();
            assertTrue(linkAttributes.isSymbolicLink());
            assertFalse(linkAttributes.isRegularFile());
            assertFalse(linkAttributes.isDirectory());
            assertEquals(0L, linkAttributes.size());
            assertEquals(ZipMethod.STORED, linkAttributes.compressionMethod());
            assertEquals(3, linkAttributes.versionMadeBy() >>> 8);
            assertEquals(linkPermissions, linkAttributes.permissions());
            PosixFileAttributes linkPosixAttributes = linkPosixView.readAttributes();
            assertTrue(linkPosixAttributes.isSymbolicLink());
            assertEquals(linkTime, linkPosixAttributes.lastModifiedTime());
            assertEquals(linkPermissions, linkPosixAttributes.permissions());
            assertThrows(UnsupportedOperationException.class, () -> linkView.setMethod(ZipMethod.DEFLATED));
            assertThrows(IllegalStateException.class, symbolicLink::openChannel);
            symbolicLink.close();
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertTrue(Files.readAttributes(
                    fileSystem.getPath("/directory"),
                    ZipArkivoEntryAttributes.class
            ).isDirectory());
            ZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                    fileSystem.getPath("/link"),
                    ZipArkivoEntryAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            assertTrue(linkAttributes.isSymbolicLink());
            assertEquals(linkTime, linkAttributes.lastModifiedTime());
            assertEquals(linkPermissions, linkAttributes.permissions());
        }
    }

    /// Verifies stored-entry metadata, directory copying, and symbolic-link metadata survive indexed reopening.
    @Test
    void persistsStoredEntryMetadata() throws IOException {
        Path archive = temporaryDirectory.resolve("stored-metadata.zip");
        Path copiedDirectory = temporaryDirectory.resolve("copied-metadata");
        Path existingFile = temporaryDirectory.resolve("existing-file");
        byte @Unmodifiable [] content = "stored-content".getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] localExtraData = {0x70, 0x70, 0x03, 0x00, 0x01, 0x02, 0x03};
        byte @Unmodifiable [] centralExtraData = {0x71, 0x70, 0x02, 0x00, 0x04, 0x05};
        byte @Unmodifiable [] rawComment = {0x06, 0x07, 0x08};
        FileTime lastModifiedTime = FileTime.fromMillis(1_893_456_000_000L);
        long expectedCrc32 = crc32(content);

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archive)) {
            ArkivoStreamingWriter.Entry directory = writer.beginDirectory("meta");
            ZipArkivoEntryAttributeView directoryView = requireView(directory, ZipArkivoEntryAttributeView.class);
            directoryView.setTimes(lastModifiedTime, null, null);
            directoryView.setRawComment(rawComment);
            directory.close();

            ArkivoStreamingWriter.Entry storedEntry = writer.beginFile("meta/stored.bin");
            ZipArkivoEntryAttributeView fileView = requireView(storedEntry, ZipArkivoEntryAttributeView.class);
            fileView.setMethod(ZipMethod.STORED);
            fileView.setTimes(lastModifiedTime, null, null);
            fileView.setUncompressedSizeAndCrc32(content.length, expectedCrc32);
            fileView.setInternalAttributes(1);
            fileView.setExternalAttributes(0x20L);
            fileView.setLocalExtraData(localExtraData);
            fileView.setCentralDirectoryExtraData(centralExtraData);
            fileView.setRawComment(rawComment);
            try (var output = storedEntry.openOutputStream()) {
                output.write(content);
            }

            writer.beginSymbolicLink("meta/link", "stored.bin").close();
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            ZipArkivoEntryAttributes directoryAttributes = Files.readAttributes(
                    fileSystem.getPath("/meta"),
                    ZipArkivoEntryAttributes.class
            );
            assertTrue(directoryAttributes.isDirectory());
            assertEquals(ZipMethod.STORED, directoryAttributes.compressionMethod());
            assertArrayEquals(rawComment, directoryAttributes.rawComment());
            assertEquals(lastModifiedTime, directoryAttributes.lastModifiedTime());

            Files.copy(fileSystem.getPath("/meta"), copiedDirectory);
            assertTrue(Files.isDirectory(copiedDirectory));
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> Files.copy(fileSystem.getPath("/meta"), copiedDirectory)
            );
            Files.copy(fileSystem.getPath("/meta"), copiedDirectory, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(existingFile, "existing", StandardCharsets.UTF_8);
            Files.copy(fileSystem.getPath("/meta"), existingFile, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(Files.isDirectory(existingFile));

            Path storedPath = fileSystem.getPath("/meta/stored.bin");
            ZipArkivoEntryAttributes fileAttributes = Files.readAttributes(
                    storedPath,
                    ZipArkivoEntryAttributes.class
            );
            assertArrayEquals(content, Files.readAllBytes(storedPath));
            assertEquals(ZipMethod.STORED, fileAttributes.compressionMethod());
            assertEquals(content.length, fileAttributes.compressedSize());
            assertEquals(content.length, fileAttributes.size());
            assertEquals(expectedCrc32, fileAttributes.crc32());
            assertEquals(1, fileAttributes.internalAttributes());
            assertEquals(0x20L, fileAttributes.externalAttributes());
            assertArrayEquals(localExtraData, fileAttributes.localExtraData());
            assertArrayEquals(centralExtraData, fileAttributes.centralDirectoryExtraData());
            assertArrayEquals(rawComment, fileAttributes.rawComment());
            assertEquals(lastModifiedTime, fileAttributes.lastModifiedTime());

            Path link = fileSystem.getPath("/meta/link");
            ZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                    link,
                    ZipArkivoEntryAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            assertTrue(linkAttributes.isSymbolicLink());
            assertArrayEquals(content, Files.readAllBytes(link));
            assertEquals(fileSystem.getPath("stored.bin"), Files.readSymbolicLink(link));
            assertThrows(NotLinkException.class, () -> Files.readSymbolicLink(storedPath));
        }
    }

    /// Returns the requested non-null pending entry attribute view.
    private static <V extends FileAttributeView> V requireView(
            ArkivoStreamingWriter.Entry entry,
            Class<V> type
    ) throws IOException {
        return Objects.requireNonNull(entry.attributeView(type), type.getName());
    }

    /// Computes the unsigned CRC-32 value for the given content.
    private static long crc32(byte @Unmodifiable [] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }
}

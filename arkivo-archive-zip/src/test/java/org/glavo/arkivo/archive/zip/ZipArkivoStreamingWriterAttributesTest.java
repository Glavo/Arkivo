// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

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
            ZipArkivoEntryAttributes defaults = zipView.readAttributes();
            assertArrayEquals("dir/value.txt".getBytes(StandardCharsets.UTF_8), defaults.rawPath());
            assertEquals("dir/value.txt", defaults.path());
            assertNull(defaults.comment());
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
            assertEquals("configured comment", configured.comment());
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

            assertEquals("configured comment", configured.comment());
            assertEquals(42L, configured.size());
            assertEquals(ZipMethod.LZMA, configured.compressionMethod());
            assertEquals(permissions, configured.permissions());
            entry.close();
        }

        assertTrue(archive.size() > 0);
    }

    /// Verifies directory and symbolic-link snapshots expose their effective stored and POSIX metadata.
    @Test
    void classifiesPendingDirectoryAndSymbolicLinkAttributes() throws IOException {
        Path archive = temporaryDirectory.resolve("attributes.zip");
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
            directory.close();

            ArkivoStreamingWriter.Entry symbolicLink = writer.beginSymbolicLink("link", "directory");
            ZipArkivoEntryAttributeView linkView = requireView(symbolicLink, ZipArkivoEntryAttributeView.class);
            PosixFileAttributeView linkPosixView = requireView(symbolicLink, PosixFileAttributeView.class);
            FileTime linkTime = FileTime.from(Instant.parse("2034-05-06T07:08:09Z"));
            Set<PosixFilePermission> linkPermissions = Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
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
            symbolicLink.close();
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertTrue(Files.readAttributes(
                    fileSystem.getPath("/directory"),
                    ZipArkivoEntryAttributes.class
            ).isDirectory());
            assertTrue(Files.readAttributes(
                    fileSystem.getPath("/link"),
                    ZipArkivoEntryAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            ).isSymbolicLink());
        }
    }

    /// Returns the requested non-null pending entry attribute view.
    private static <V extends FileAttributeView> V requireView(
            ArkivoStreamingWriter.Entry entry,
            Class<V> type
    ) throws IOException {
        return Objects.requireNonNull(entry.attributeView(type), type.getName());
    }
}

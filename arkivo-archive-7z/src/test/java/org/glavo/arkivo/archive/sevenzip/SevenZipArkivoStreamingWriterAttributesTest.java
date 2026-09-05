// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stable 7z and POSIX attribute snapshots for pending streaming-writer entries.
@NotNullByDefault
final class SevenZipArkivoStreamingWriterAttributesTest {
    /// Temporary directory used by the indexed metadata round-trip test.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies file defaults, configured metadata, POSIX projections, and snapshot stability.
    @Test
    void snapshotsConfiguredFileAttributes() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("directory\\./value.bin");
            SevenZipArkivoEntryAttributeView sevenZipView = requireView(
                    entry,
                    SevenZipArkivoEntryAttributeView.class
            );
            BasicFileAttributeView basicView = requireView(entry, BasicFileAttributeView.class);
            PosixFileAttributeView posixView = requireView(entry, PosixFileAttributeView.class);

            assertSame(sevenZipView, basicView);
            assertEquals("7z", sevenZipView.name());
            assertEquals("posix", posixView.name());
            SevenZipArkivoEntryAttributes defaults = sevenZipView.readAttributes();
            PosixFileAttributes posixDefaults = posixView.readAttributes();
            assertEquals("directory/value.bin", defaults.path());
            assertNull(defaults.coderGraph());
            assertFalse(defaults.solid());
            assertEquals(SevenZipArkivoEntryAttributes.NO_SUBSTREAM_INDEX, defaults.substreamIndex());
            assertEquals(0, defaults.substreamCount());
            assertEquals(SevenZipArkivoEntryAttributes.NO_DATA_OFFSET, defaults.dataOffset());
            assertEquals(0L, defaults.decodedOffset());
            assertEquals(0L, defaults.packedSize());
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_CRC32, defaults.packedCrc32());
            assertEquals(List.of(), defaults.packedStreams());
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_CRC32, defaults.crc32());
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES, defaults.windowsAttributes());
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE, defaults.unixMode());
            assertEquals(FileTime.fromMillis(0L), defaults.lastModifiedTime());
            assertEquals(defaults.lastModifiedTime(), defaults.lastAccessTime());
            assertEquals(defaults.lastModifiedTime(), defaults.creationTime());
            assertTrue(defaults.isRegularFile());
            assertFalse(defaults.isDirectory());
            assertFalse(defaults.isSymbolicLink());
            assertFalse(defaults.isOther());
            assertEquals(0L, defaults.size());
            assertNull(defaults.fileKey());
            assertEquals("owner", posixDefaults.owner().getName());
            assertEquals("group", posixDefaults.group().getName());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), posixDefaults.permissions());

            BasicFileAttributes basicDefaults = basicView.readAttributes();
            assertTrue(basicDefaults.isRegularFile());
            assertEquals(0L, basicDefaults.size());
            assertTrue(posixDefaults.isRegularFile());
            assertEquals(posixDefaults.owner(), posixView.getOwner());

            FileTime modifiedTime = FileTime.from(Instant.parse("2031-02-03T04:05:06Z"));
            FileTime accessTime = FileTime.from(Instant.parse("2032-03-04T05:06:07Z"));
            FileTime creationTime = FileTime.from(Instant.parse("2033-04-05T06:07:08Z"));
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
            posixView.setTimes(modifiedTime, accessTime, creationTime);
            sevenZipView.setWindowsAttributes(0x20);
            posixView.setPermissions(permissions);
            posixView.setOwner(posixDefaults.owner());
            posixView.setGroup(posixDefaults.group());
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> posixView.setOwner(() -> "different-owner")
            );
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> posixView.setGroup(() -> "different-group")
            );
            sevenZipView.setCompression(SevenZipCompression.copy());
            sevenZipView.setFilter(SevenZipFilter.delta());
            sevenZipView.setFilters(SevenZipFilterChain.of(SevenZipFilter.bcjX86()));
            sevenZipView.clearFilter();

            SevenZipArkivoEntryAttributes configured = sevenZipView.readAttributes();
            PosixFileAttributes configuredPosix = posixView.readAttributes();
            assertEquals((0100640 << 16) | 0x20, configured.windowsAttributes());
            assertEquals(0100640, configured.unixMode());
            assertEquals(modifiedTime, configured.lastModifiedTime());
            assertEquals(accessTime, configured.lastAccessTime());
            assertEquals(creationTime, configured.creationTime());
            assertEquals(permissions, configuredPosix.permissions());

            assertEquals(modifiedTime, configuredPosix.lastModifiedTime());
            assertEquals(accessTime, configuredPosix.lastAccessTime());
            assertEquals(creationTime, configuredPosix.creationTime());
            assertTrue(configuredPosix.isRegularFile());
            assertFalse(configuredPosix.isDirectory());
            assertFalse(configuredPosix.isSymbolicLink());
            assertFalse(configuredPosix.isOther());
            assertEquals(0L, configuredPosix.size());
            assertNull(configuredPosix.fileKey());
            assertEquals(posixDefaults.owner(), configuredPosix.owner());
            assertEquals(posixDefaults.group(), configuredPosix.group());
            assertEquals(permissions, configuredPosix.permissions());

            FileTime laterTime = FileTime.from(Instant.parse("2034-05-06T07:08:09Z"));
            posixView.setTimes(laterTime, null, null);
            posixView.setPermissions(Set.of(PosixFilePermission.OWNER_READ));
            assertEquals(modifiedTime, configured.lastModifiedTime());
            assertEquals(0100640, configured.unixMode());
            assertEquals(permissions, configuredPosix.permissions());

            try (OutputStream body = entry.openOutputStream()) {
                body.write(new byte[]{1, 2, 3});
            }
            assertThrows(IllegalStateException.class, () -> sevenZipView.setWindowsAttributes(0));
            assertThrows(IllegalStateException.class, () -> posixView.setPermissions(Set.of()));
        }

        assertTrue(archive.size() > 0);
    }

    /// Verifies pending directories and symbolic links expose and persist their effective Unix metadata.
    @Test
    void classifiesAndPersistsDirectoryAndSymbolicLinkAttributes() throws IOException {
        Path archive = temporaryDirectory.resolve("attributes.7z");
        Set<PosixFilePermission> directoryPermissions = PosixFilePermissions.fromString("rwxr-x---");
        Set<PosixFilePermission> linkPermissions = PosixFilePermissions.fromString("rwxr-xr--");

        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.create(archive)) {
            ArkivoStreamingWriter.Entry directory = writer.beginDirectory("directory");
            SevenZipArkivoEntryAttributes directoryDefaults = requireView(
                    directory,
                    SevenZipArkivoEntryAttributeView.class
            ).readAttributes();
            PosixFileAttributeView directoryPosixView = requireView(directory, PosixFileAttributeView.class);
            PosixFileAttributes directoryPosixDefaults = directoryPosixView.readAttributes();
            assertTrue(directoryDefaults.isDirectory());
            assertFalse(directoryDefaults.isRegularFile());
            assertFalse(directoryDefaults.isSymbolicLink());
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE, directoryDefaults.unixMode());
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), directoryPosixDefaults.permissions());
            assertTrue(directoryPosixDefaults.isDirectory());
            directoryPosixView.setPermissions(directoryPermissions);
            SevenZipArkivoEntryAttributes configuredDirectory = requireView(
                    directory,
                    SevenZipArkivoEntryAttributeView.class
            ).readAttributes();
            assertEquals(0040750, configuredDirectory.unixMode());
            assertEquals(directoryPermissions, directoryPosixView.readAttributes().permissions());
            assertThrows(IllegalStateException.class, directory::openOutputStream);
            directory.close();

            ArkivoStreamingWriter.Entry symbolicLink = writer.beginSymbolicLink("link", "directory");
            SevenZipArkivoEntryAttributeView linkView = requireView(
                    symbolicLink,
                    SevenZipArkivoEntryAttributeView.class
            );
            PosixFileAttributeView linkPosixView = requireView(symbolicLink, PosixFileAttributeView.class);
            SevenZipArkivoEntryAttributes linkDefaults = linkView.readAttributes();
            PosixFileAttributes linkPosixDefaults = linkPosixView.readAttributes();
            assertTrue(linkDefaults.isSymbolicLink());
            assertFalse(linkDefaults.isRegularFile());
            assertFalse(linkDefaults.isDirectory());
            assertEquals(0120777, linkDefaults.unixMode());
            assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), linkPosixDefaults.permissions());
            assertTrue(linkPosixDefaults.isSymbolicLink());
            linkPosixView.setPermissions(linkPermissions);
            SevenZipArkivoEntryAttributes configuredLink = linkView.readAttributes();
            assertEquals(0120754, configuredLink.unixMode());
            assertEquals(linkPermissions, linkPosixView.readAttributes().permissions());
            assertThrows(IllegalStateException.class, symbolicLink::openChannel);
            symbolicLink.close();
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
            Path directory = fileSystem.getPath("/directory");
            Path link = fileSystem.getPath("/link");
            SevenZipArkivoEntryAttributes directoryAttributes = Files.readAttributes(
                    directory,
                    SevenZipArkivoEntryAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            SevenZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                    link,
                    SevenZipArkivoEntryAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            assertTrue(directoryAttributes.isDirectory());
            assertEquals(0040750, directoryAttributes.unixMode());
            assertTrue(linkAttributes.isSymbolicLink());
            assertEquals(0120754, linkAttributes.unixMode());
            assertEquals(linkPermissions, Files.readAttributes(
                    link,
                    PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            ).permissions());
            assertEquals(fileSystem.getPath("directory"), Files.readSymbolicLink(link));
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

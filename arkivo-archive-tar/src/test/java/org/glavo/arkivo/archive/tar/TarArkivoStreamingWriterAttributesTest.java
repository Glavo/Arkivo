// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies live attribute projections exposed by pending TAR streaming-writer entries.
@NotNullByDefault
final class TarArkivoStreamingWriterAttributesTest {
    /// Verifies pending attributes expose defaults for every entry type and reflect later metadata mutations.
    @Test
    void exposesLiveAttributesForEveryPendingEntryType() throws IOException {
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(new ByteArrayOutputStream())) {
            ArkivoStreamingWriter.Entry file = writer.beginFile("file.txt");
            TarArkivoEntryAttributeView tarView = assertInstanceOf(
                    TarArkivoEntryAttributeView.class,
                    file.attributeView(TarArkivoEntryAttributeView.class)
            );
            TarArkivoEntryAttributes fileAttributes = tarView.readAttributes();
            PosixFileAttributes livePosixAttributes = assertInstanceOf(
                    PosixFileAttributes.class,
                    fileAttributes
            );
            assertPendingType(fileAttributes, "file.txt", '0', 0644, null, true, false, false);

            FileTime modifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_001L, 250_000_000L));
            FileTime accessTime = FileTime.from(Instant.ofEpochSecond(1_700_000_002L, 500_000_000L));
            FileTime statusChangeTime = FileTime.from(Instant.ofEpochSecond(1_700_000_003L, 750_000_000L));
            FileTime creationTime = FileTime.from(Instant.ofEpochSecond(1_700_000_004L, 125_000_000L));
            tarView.setTimes(modifiedTime, null, null);
            tarView.setRecordedLastAccessTime(accessTime);
            tarView.setRecordedStatusChangeTime(statusChangeTime);
            tarView.setRecordedCreationTime(creationTime);
            tarView.setUserId(42L);
            tarView.setGroupId(84L);
            tarView.setUserName("alice");
            tarView.setGroupName("staff");
            tarView.setMode(0600);

            assertEquals(modifiedTime, fileAttributes.lastModifiedTime());
            assertEquals(accessTime, fileAttributes.lastAccessTime());
            assertEquals(creationTime, fileAttributes.creationTime());
            assertEquals(accessTime, fileAttributes.recordedLastAccessTime());
            assertEquals(statusChangeTime, fileAttributes.recordedStatusChangeTime());
            assertEquals(creationTime, fileAttributes.recordedCreationTime());
            assertEquals(42L, fileAttributes.userId());
            assertEquals(84L, fileAttributes.groupId());
            assertEquals("alice", fileAttributes.userName());
            assertEquals("staff", fileAttributes.groupName());
            assertEquals("alice", livePosixAttributes.owner().getName());
            assertEquals("staff", livePosixAttributes.group().getName());
            assertEquals(0600, fileAttributes.mode());
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    livePosixAttributes.permissions()
            );

            PosixFileAttributeView posixView = assertInstanceOf(
                    PosixFileAttributeView.class,
                    file.attributeView(PosixFileAttributeView.class)
            );
            PosixFileAttributes posixAttributes = posixView.readAttributes();
            assertEquals(livePosixAttributes.owner(), posixAttributes.owner());
            assertEquals(livePosixAttributes.group(), posixAttributes.group());
            assertEquals(livePosixAttributes.permissions(), posixAttributes.permissions());
            file.close();

            ArkivoStreamingWriter.Entry directory = writer.beginDirectory("directory");
            assertPendingType(
                    readAttributes(directory),
                    "directory/",
                    '5',
                    0755,
                    null,
                    false,
                    true,
                    false
            );
            directory.close();

            ArkivoStreamingWriter.Entry symbolicLink = writer.beginSymbolicLink("symbolic", "target.txt");
            assertPendingType(
                    readAttributes(symbolicLink),
                    "symbolic",
                    '2',
                    0777,
                    "target.txt",
                    false,
                    false,
                    true
            );
            symbolicLink.close();

            ArkivoStreamingWriter.Entry hardLink = writer.beginHardLink("hard", "file.txt");
            assertPendingType(
                    readAttributes(hardLink),
                    "hard",
                    TarArkivoEntryAttributes.HARD_LINK_TYPE,
                    0644,
                    "file.txt",
                    true,
                    false,
                    false
            );
            hardLink.close();
        }
    }

    /// Reads the TAR-specific projection of one pending entry.
    private static TarArkivoEntryAttributes readAttributes(ArkivoStreamingWriter.Entry entry) throws IOException {
        TarArkivoEntryAttributeView view = assertInstanceOf(
                TarArkivoEntryAttributeView.class,
                entry.attributeView(TarArkivoEntryAttributeView.class)
        );
        return view.readAttributes();
    }

    /// Verifies common pending metadata and one entry type's classification.
    private static void assertPendingType(
            TarArkivoEntryAttributes attributes,
            String path,
            int typeFlag,
            int mode,
            @Nullable String linkName,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink
    ) {
        assertEquals(path, attributes.path());
        assertEquals((byte) typeFlag, attributes.typeFlag());
        assertEquals(mode, attributes.mode());
        assertEquals(linkName, attributes.linkName());
        assertEquals(regularFile, attributes.isRegularFile());
        assertEquals(directory, attributes.isDirectory());
        assertEquals(symbolicLink, attributes.isSymbolicLink());
        assertEquals(typeFlag == TarArkivoEntryAttributes.HARD_LINK_TYPE, attributes.isHardLink());
        assertFalse(attributes.isOther());
        assertEquals(0L, attributes.size());
        assertNull(attributes.fileKey());
        assertTrue(attributes.userId() >= 0L);
        assertTrue(attributes.groupId() >= 0L);
    }
}

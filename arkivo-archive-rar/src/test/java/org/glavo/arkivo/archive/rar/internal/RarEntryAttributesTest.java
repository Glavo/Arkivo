// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable RAR entry metadata snapshots and their NIO attribute projection.
@NotNullByDefault
final class RarEntryAttributesTest {
    /// Verifies every stored property, synthesized principal, permission, and defensive hash copy.
    @Test
    void exposesStableMetadataSnapshot() {
        byte[] hash = {1, 2, 3, 4};
        FileTime modified = FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));
        FileTime created = FileTime.from(Instant.parse("2026-02-03T04:05:06Z"));
        FileTime accessed = FileTime.from(Instant.parse("2026-03-04T05:06:07Z"));
        RarEntryAttributes attributes = attributes(
                "link",
                false,
                true,
                false,
                7L,
                11L,
                0x89ab_cdefL,
                hash,
                modified,
                created,
                accessed
        );
        hash[0] = 0;

        assertEquals("link", attributes.path());
        assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, attributes.hostOs());
        assertEquals(0120770L, attributes.fileAttributes());
        assertEquals(3, attributes.compressionMethod());
        assertEquals(7L, attributes.packedSize());
        assertEquals(11L, attributes.unpackedSize());
        assertEquals(0x89ab_cdefL, attributes.dataCrc32());
        assertTrue(attributes.isEncrypted());
        assertTrue(attributes.continuesFromPreviousVolume());
        assertTrue(attributes.continuesInNextVolume());
        assertEquals("target", attributes.linkName());
        assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK, attributes.redirectionType());
        assertEquals(RarArkivoEntryAttributes.REDIRECTION_FLAG_TARGET_DIRECTORY, attributes.redirectionFlags());
        assertEquals("redirected", attributes.redirectionTarget());
        assertTrue(attributes.redirectionTargetDirectory());
        assertEquals("alice", attributes.userName());
        assertEquals("staff", attributes.groupName());
        assertEquals(1000L, attributes.userId());
        assertEquals(100L, attributes.groupId());
        assertEquals(modified, attributes.lastModifiedTime());
        assertEquals(created, attributes.creationTime());
        assertEquals(accessed, attributes.lastAccessTime());
        assertEquals(11L, attributes.size());
        assertEquals("link", attributes.fileKey());
        assertEquals("alice", attributes.owner().getName());
        assertEquals("staff", attributes.group().getName());
        assertEquals(PosixFilePermissions.fromString("rwxrwx---"), attributes.permissions());
        assertFalse(attributes.isRegularFile());
        assertFalse(attributes.isDirectory());
        assertTrue(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());

        byte[] returnedHash = attributes.blake2spHash();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, returnedHash);
        returnedHash[1] = 0;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, attributes.blake2spHash());
    }

    /// Verifies each NIO file-type classification and absent optional metadata.
    @Test
    void classifiesEntryTypesAndAbsentValues() {
        FileTime epoch = FileTime.fromMillis(0L);
        RarEntryAttributes regular = attributes("file", false, false, false, 0L, -1L, -1L, null, epoch, epoch, epoch);
        RarEntryAttributes directory = attributes("dir", true, false, false, 0L, 0L, 0L, null, epoch, epoch, epoch);
        RarEntryAttributes other = attributes("other", false, false, true, 0L, 0L, 0L, null, epoch, epoch, epoch);

        assertTrue(regular.isRegularFile());
        assertFalse(regular.isDirectory());
        assertFalse(regular.isSymbolicLink());
        assertFalse(regular.isOther());
        assertEquals(RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE, regular.size());
        assertNull(regular.blake2spHash());

        assertFalse(directory.isRegularFile());
        assertTrue(directory.isDirectory());
        assertFalse(directory.isSymbolicLink());
        assertFalse(directory.isOther());

        assertFalse(other.isRegularFile());
        assertFalse(other.isDirectory());
        assertFalse(other.isSymbolicLink());
        assertTrue(other.isOther());
    }

    /// Verifies numeric ranges and required snapshot values are rejected at construction time.
    @Test
    void validatesConstructorArguments() {
        FileTime epoch = FileTime.fromMillis(0L);

        assertThrows(
                IllegalArgumentException.class,
                () -> attributes("file", false, false, false, -1L, 0L, 0L, null, epoch, epoch, epoch)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> attributes("file", false, false, false, 0L, -2L, 0L, null, epoch, epoch, epoch)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> attributes("file", false, false, false, 0L, 0L, -2L, null, epoch, epoch, epoch)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> attributes("file", false, false, false, 0L, 0L, 0x1_0000_0000L, null, epoch, epoch, epoch)
        );
        assertThrows(
                NullPointerException.class,
                () -> attributes(null, false, false, false, 0L, 0L, 0L, null, epoch, epoch, epoch)
        );
        assertThrows(
                NullPointerException.class,
                () -> attributes("file", false, false, false, 0L, 0L, 0L, null, null, epoch, epoch)
        );
        assertThrows(
                NullPointerException.class,
                () -> attributes("file", false, false, false, 0L, 0L, 0L, null, epoch, null, epoch)
        );
        assertThrows(
                NullPointerException.class,
                () -> attributes("file", false, false, false, 0L, 0L, 0L, null, epoch, epoch, null)
        );
    }

    /// Creates one attributes snapshot with representative RAR-specific metadata.
    private static RarEntryAttributes attributes(
            @Nullable String path,
            boolean directory,
            boolean symbolicLink,
            boolean other,
            long packedSize,
            long unpackedSize,
            long dataCrc32,
            byte @Nullable [] hash,
            @Nullable FileTime modified,
            @Nullable FileTime created,
            @Nullable FileTime accessed
    ) {
        return new RarEntryAttributes(
                path,
                directory,
                symbolicLink,
                other,
                "target",
                RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK,
                RarArkivoEntryAttributes.REDIRECTION_FLAG_TARGET_DIRECTORY,
                "redirected",
                "alice",
                "staff",
                1000L,
                100L,
                RarArkivoEntryAttributes.HOST_OS_UNIX,
                0120770L,
                3,
                packedSize,
                unpackedSize,
                dataCrc32,
                hash,
                true,
                true,
                true,
                modified,
                created,
                accessed
        );
    }
}

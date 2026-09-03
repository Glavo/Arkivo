// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies RAR-specific POSIX principal and permission synthesis.
@NotNullByDefault
final class RarPosixSupportTest {
    /// Verifies explicit names, numeric fallbacks, and absent owner metadata.
    @Test
    void resolvesOwnersAndGroups() {
        long unknown = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

        assertEquals("alice", RarPosixSupport.owner("alice", 1000L).getName());
        assertEquals("1000", RarPosixSupport.owner(null, 1000L).getName());
        assertSame(RarPosixSupport.DEFAULT_OWNER, RarPosixSupport.owner(null, unknown));

        assertEquals("staff", RarPosixSupport.group("staff", 100L).getName());
        assertEquals("100", RarPosixSupport.group(null, 100L).getName());
        assertSame(RarPosixSupport.DEFAULT_GROUP, RarPosixSupport.group(null, unknown));
    }

    /// Verifies the preserving lookup service accepts arbitrary archive principal names.
    @Test
    void preservesLookupNames() throws IOException {
        UserPrincipalLookupService lookup = RarPosixSupport.userPrincipalLookupService();

        assertEquals("archive-owner", lookup.lookupPrincipalByName("archive-owner").getName());
        assertEquals("archive-group", lookup.lookupPrincipalByGroupName("archive-group").getName());
    }

    /// Verifies permissions are decoded only for Unix-hosted entries and returned immutably.
    @Test
    void decodesUnixPermissions() {
        Set<PosixFilePermission> allPermissions = Set.of(PosixFilePermission.values());

        assertEquals(
                Set.of(),
                RarPosixSupport.permissions(RarArkivoEntryAttributes.HOST_OS_WINDOWS, 0777L)
        );
        Set<PosixFilePermission> decoded =
                RarPosixSupport.permissions(RarArkivoEntryAttributes.HOST_OS_UNIX, 0100000L | 0777L);
        assertEquals(allPermissions, decoded);
        assertEquals(
                Set.of(),
                RarPosixSupport.permissions(RarArkivoEntryAttributes.HOST_OS_UNIX, 0100000L)
        );
        assertThrows(UnsupportedOperationException.class, decoded::clear);
    }
}

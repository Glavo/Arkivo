// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests shared POSIX mode and principal support.
@NotNullByDefault
final class PosixSupportTest {
    /// Verifies all POSIX permission bits round-trip without retaining file type bits.
    @Test
    void roundTripsPermissionBits() {
        Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.values());
        assertEquals(0777, PosixModes.permissionBits(permissions));
        assertEquals(permissions, PosixModes.permissions(PosixModes.REGULAR_FILE_TYPE | 0777));
        assertThrows(
                UnsupportedOperationException.class,
                () -> PosixModes.permissions(0644).add(PosixFilePermission.OWNER_EXECUTE)
        );
    }

    /// Verifies POSIX file type classification, including modes with omitted regular-file bits.
    @Test
    void classifiesFileTypes() {
        assertTrue(PosixModes.isRegularFile(0644));
        assertTrue(PosixModes.isRegularFile(PosixModes.REGULAR_FILE_TYPE | 0644));
        assertTrue(PosixModes.isDirectory(PosixModes.DIRECTORY_FILE_TYPE | 0755));
        assertTrue(PosixModes.isSymbolicLink(PosixModes.SYMBOLIC_LINK_FILE_TYPE | 0777));
        assertTrue(PosixModes.isOther(0060000));
        assertFalse(PosixModes.isOther(PosixModes.DIRECTORY_FILE_TYPE));
    }

    /// Verifies dynamically typed permission values are validated and copied immutably.
    @Test
    void validatesDynamicPermissionSets() {
        Set<PosixFilePermission> permissions = PosixPermissions.copyOf(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ),
                "permissions"
        );
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ), permissions);
        assertThrows(UnsupportedOperationException.class, () -> permissions.add(PosixFilePermission.OTHERS_READ));
        assertThrows(IllegalArgumentException.class, () -> PosixPermissions.copyOf("0644", "permissions"));
        assertThrows(IllegalArgumentException.class, () -> PosixPermissions.copyOf(Set.of("OWNER_READ"), "permissions"));
    }

    /// Verifies archive-local principals compare by principal kind and name.
    @Test
    void comparesNamedPrincipals() {
        assertEquals(new NamedUserPrincipal("1000"), new NamedUserPrincipal("1000"));
        assertEquals(new NamedGroupPrincipal("1000"), new NamedGroupPrincipal("1000"));
        assertNotEquals(new NamedUserPrincipal("1000"), new NamedGroupPrincipal("1000"));
    }

    /// Verifies preserving lookup accepts arbitrary user and group names.
    @Test
    void preservesLookupNames() throws IOException {
        PreservingUserPrincipalLookupService service = PreservingUserPrincipalLookupService.instance();
        assertEquals("alice", service.lookupPrincipalByName("alice").getName());
        assertEquals("staff", service.lookupPrincipalByGroupName("staff").getName());
    }

    /// Verifies fixed lookup returns its configured principals and rejects every other name.
    @Test
    void restrictsFixedLookupNames() throws IOException {
        NamedUserPrincipal user = new NamedUserPrincipal("owner");
        NamedGroupPrincipal group = new NamedGroupPrincipal("group");
        FixedUserPrincipalLookupService service = new FixedUserPrincipalLookupService(user, group);

        assertSame(user, service.lookupPrincipalByName("owner"));
        assertSame(group, service.lookupPrincipalByGroupName("group"));
        assertThrows(UserPrincipalNotFoundException.class, () -> service.lookupPrincipalByName("alice"));
        assertThrows(UserPrincipalNotFoundException.class, () -> service.lookupPrincipalByGroupName("staff"));
        service.requireUser(new NamedUserPrincipal("owner"));
        service.requireGroup(new NamedGroupPrincipal("group"));
    }
}

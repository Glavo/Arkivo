// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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
    /// Checks every permission combination against the JDK parser, independently of Arkivo's reverse mapping.
    @Test
    void decodesEveryPermissionCombination() {
        for (int bits = 0; bits <= 0777; bits++) {
            StringBuilder text = new StringBuilder(9);
            for (int index = 0; index < 9; index++) {
                text.append((bits & (0400 >> index)) != 0 ? "rwxrwxrwx".charAt(index) : '-');
            }
            Set<PosixFilePermission> expected = PosixFilePermissions.fromString(text.toString());
            Set<PosixFilePermission> actual = PosixModes.permissions(bits);
            assertEquals(expected, actual, text.toString());
            assertEquals(expected.hashCode(), actual.hashCode());
            assertEquals(bits, PosixModes.permissionBits(actual));
            for (long highBits : new long[]{0, 07000, 0100000, 0040000, 0120000, 0060000,
                    1L << 32, Long.MIN_VALUE, ~0777L}) {
                assertSame(actual, PosixModes.permissions(highBits | bits));
            }
            assertSame(actual, PosixPermissions.copyOf(expected, "permissions"));
            expected.clear();
            assertEquals(Integer.bitCount(bits), actual.size());
            assertThrows(UnsupportedOperationException.class, actual::clear);
            assertThrows(UnsupportedOperationException.class, () -> actual.add(PosixFilePermission.OWNER_READ));
            assertThrows(UnsupportedOperationException.class, () -> actual.remove(PosixFilePermission.OWNER_READ));
            if (!actual.isEmpty()) {
                var iterator = actual.iterator();
                iterator.next();
                assertThrows(UnsupportedOperationException.class, iterator::remove);
            }
        }
    }

    /// Rejects invalid dynamic attributes and isolates a validated snapshot from later caller changes.
    @Test
    void rejectsInvalidPermissionValuesWithoutChangingSnapshots() {
        EnumSet<PosixFilePermission> source = EnumSet.of(PosixFilePermission.OWNER_READ);
        Set<PosixFilePermission> snapshot = PosixPermissions.copyOf(source, "permissions");
        source.clear();
        assertEquals(Set.of(PosixFilePermission.OWNER_READ), snapshot);
        Set<@Nullable Object> withNull = new HashSet<>();
        withNull.add(null);
        withNull.add(PosixFilePermission.OWNER_READ);
        for (Object invalid : List.of(withNull, Set.of(PosixFilePermission.OWNER_READ, "OWNER_WRITE"), 0644)) {
            assertThrows(IllegalArgumentException.class, () -> PosixPermissions.copyOf(invalid, "permissions"));
        }
        assertThrows(IllegalArgumentException.class, () -> PosixPermissions.copyOf(null, "permissions"));
        assertThrows(NullPointerException.class, () -> PosixPermissions.copyOf(source, null));
        assertThrows(NullPointerException.class, () -> PosixModes.permissionBits(null));
        Set<@Nullable PosixFilePermission> typedWithNull = new HashSet<>();
        typedWithNull.add(null);
        assertThrows(NullPointerException.class, () -> PosixModes.permissionBits(typedWithNull));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ), snapshot);
    }

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

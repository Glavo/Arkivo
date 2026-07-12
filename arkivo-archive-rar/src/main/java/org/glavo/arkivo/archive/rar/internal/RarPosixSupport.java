// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// Provides POSIX metadata helpers for RAR file systems.
@NotNullByDefault
final class RarPosixSupport {
    /// The synthesized default owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("");

    /// The synthesized default group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("");

    /// The user principal lookup service for RAR principal names.
    private static final UserPrincipalLookupService USER_PRINCIPAL_LOOKUP_SERVICE =
            new RarUserPrincipalLookupService();

    /// Prevents instantiation.
    private RarPosixSupport() {
    }

    /// Returns the lookup service for RAR owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return USER_PRINCIPAL_LOOKUP_SERVICE;
    }

    /// Returns the owner principal represented by RAR Unix owner metadata.
    static UserPrincipal owner(@Nullable String userName, long userId) {
        if (userName != null) {
            return new NamedUserPrincipal(userName);
        }
        if (userId != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            return new NamedUserPrincipal(Long.toString(userId));
        }
        return DEFAULT_OWNER;
    }

    /// Returns the group principal represented by RAR Unix owner metadata.
    static GroupPrincipal group(@Nullable String groupName, long groupId) {
        if (groupName != null) {
            return new NamedGroupPrincipal(groupName);
        }
        if (groupId != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            return new NamedGroupPrincipal(Long.toString(groupId));
        }
        return DEFAULT_GROUP;
    }

    /// Returns the POSIX permissions encoded by the RAR host-specific file attributes.
    static @Unmodifiable Set<PosixFilePermission> permissions(int hostOs, long fileAttributes) {
        if (hostOs != RarArkivoEntryAttributes.HOST_OS_UNIX) {
            return Set.of();
        }

        EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((fileAttributes & 0400L) != 0) {
            permissions.add(PosixFilePermission.OWNER_READ);
        }
        if ((fileAttributes & 0200L) != 0) {
            permissions.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((fileAttributes & 0100L) != 0) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if ((fileAttributes & 0040L) != 0) {
            permissions.add(PosixFilePermission.GROUP_READ);
        }
        if ((fileAttributes & 0020L) != 0) {
            permissions.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((fileAttributes & 0010L) != 0) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if ((fileAttributes & 0004L) != 0) {
            permissions.add(PosixFilePermission.OTHERS_READ);
        }
        if ((fileAttributes & 0002L) != 0) {
            permissions.add(PosixFilePermission.OTHERS_WRITE);
        }
        if ((fileAttributes & 0001L) != 0) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        return Set.copyOf(permissions);
    }

    /// Implements RAR principal lookup by preserving requested names.
    private static final class RarUserPrincipalLookupService extends UserPrincipalLookupService {
        /// Looks up a user principal by name.
        @Override
        public UserPrincipal lookupPrincipalByName(String name) throws UserPrincipalNotFoundException {
            return new NamedUserPrincipal(Objects.requireNonNull(name, "name"));
        }

        /// Looks up a group principal by name.
        @Override
        public GroupPrincipal lookupPrincipalByGroupName(String group) throws UserPrincipalNotFoundException {
            return new NamedGroupPrincipal(Objects.requireNonNull(group, "group"));
        }
    }

    /// Stores a named user principal.
    ///
    /// @param name the principal name
    private record NamedUserPrincipal(String name) implements UserPrincipal {
        /// Creates a named user principal.
        private NamedUserPrincipal {
            Objects.requireNonNull(name, "name");
        }

        /// Returns the principal name.
        @Override
        public String getName() {
            return name;
        }
    }

    /// Stores a named group principal.
    ///
    /// @param name the principal name
    private record NamedGroupPrincipal(String name) implements GroupPrincipal {
        /// Creates a named group principal.
        private NamedGroupPrincipal {
            Objects.requireNonNull(name, "name");
        }

        /// Returns the principal name.
        @Override
        public String getName() {
            return name;
        }
    }
}

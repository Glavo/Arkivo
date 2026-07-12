// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

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

/// Provides POSIX metadata helpers for TAR file systems.
@NotNullByDefault
final class TarPosixSupport {
    /// The synthesized default owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("");

    /// The synthesized default group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("");

    /// The user principal lookup service for TAR principal names.
    private static final UserPrincipalLookupService USER_PRINCIPAL_LOOKUP_SERVICE =
            new TarUserPrincipalLookupService();

    /// Prevents instantiation.
    private TarPosixSupport() {
    }

    /// Returns the lookup service for TAR owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return USER_PRINCIPAL_LOOKUP_SERVICE;
    }

    /// Returns the owner principal represented by a TAR user name.
    static UserPrincipal owner(@Nullable String userName) {
        return userName != null ? new NamedUserPrincipal(userName) : DEFAULT_OWNER;
    }

    /// Returns the group principal represented by a TAR group name.
    static GroupPrincipal group(@Nullable String groupName) {
        return groupName != null ? new NamedGroupPrincipal(groupName) : DEFAULT_GROUP;
    }

    /// Returns the POSIX permissions encoded by the given TAR mode bits.
    static @Unmodifiable Set<PosixFilePermission> permissions(int mode) {
        EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) {
            permissions.add(PosixFilePermission.OWNER_READ);
        }
        if ((mode & 0200) != 0) {
            permissions.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((mode & 0100) != 0) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if ((mode & 0040) != 0) {
            permissions.add(PosixFilePermission.GROUP_READ);
        }
        if ((mode & 0020) != 0) {
            permissions.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((mode & 0010) != 0) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if ((mode & 0004) != 0) {
            permissions.add(PosixFilePermission.OTHERS_READ);
        }
        if ((mode & 0002) != 0) {
            permissions.add(PosixFilePermission.OTHERS_WRITE);
        }
        if ((mode & 0001) != 0) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        return Set.copyOf(permissions);
    }

    /// Implements TAR principal lookup by preserving requested names.
    private static final class TarUserPrincipalLookupService extends UserPrincipalLookupService {
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

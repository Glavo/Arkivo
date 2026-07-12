// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// Provides POSIX metadata helpers for AR file systems.
@NotNullByDefault
final class ArPosixSupport {
    /// The POSIX mode mask for file type bits.
    private static final int FILE_TYPE_MASK = 0170000;

    /// The POSIX mode type bits for regular files.
    private static final int REGULAR_FILE_TYPE = 0100000;

    /// The POSIX mode type bits for directories.
    private static final int DIRECTORY_TYPE = 0040000;

    /// The POSIX mode type bits for symbolic links.
    private static final int SYMBOLIC_LINK_TYPE = 0120000;

    /// The user principal lookup service for AR principal names.
    private static final UserPrincipalLookupService USER_PRINCIPAL_LOOKUP_SERVICE =
            new ArUserPrincipalLookupService();

    /// Prevents instantiation.
    private ArPosixSupport() {
    }

    /// Returns the lookup service for AR owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return USER_PRINCIPAL_LOOKUP_SERVICE;
    }

    /// Returns the owner principal represented by an AR numeric user identifier.
    static UserPrincipal owner(long userId) {
        return new NamedUserPrincipal(Long.toString(userId));
    }

    /// Returns the group principal represented by an AR numeric group identifier.
    static GroupPrincipal group(long groupId) {
        return new NamedGroupPrincipal(Long.toString(groupId));
    }

    /// Returns the POSIX permissions encoded by the given AR mode bits.
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

    /// Returns mode bits for a regular file with the given POSIX permissions.
    static int regularFileMode(Set<PosixFilePermission> permissions) {
        return REGULAR_FILE_TYPE | permissionsMode(permissions);
    }

    /// Returns mode bits for a directory with the given POSIX permissions.
    static int directoryMode(Set<PosixFilePermission> permissions) {
        return DIRECTORY_TYPE | permissionsMode(permissions);
    }

    /// Returns mode bits for a symbolic link with the given POSIX permissions.
    static int symbolicLinkMode(Set<PosixFilePermission> permissions) {
        return SYMBOLIC_LINK_TYPE | permissionsMode(permissions);
    }

    /// Returns permission mode bits represented by the given POSIX permissions.
    private static int permissionsMode(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            switch (permission) {
                case OWNER_READ -> mode |= 0400;
                case OWNER_WRITE -> mode |= 0200;
                case OWNER_EXECUTE -> mode |= 0100;
                case GROUP_READ -> mode |= 0040;
                case GROUP_WRITE -> mode |= 0020;
                case GROUP_EXECUTE -> mode |= 0010;
                case OTHERS_READ -> mode |= 0004;
                case OTHERS_WRITE -> mode |= 0002;
                case OTHERS_EXECUTE -> mode |= 0001;
            }
        }
        return mode;
    }

    /// Returns whether the given AR mode bits describe a regular file.
    static boolean isRegularFile(int mode) {
        int type = mode & FILE_TYPE_MASK;
        return type == 0 || type == REGULAR_FILE_TYPE;
    }

    /// Returns whether the given AR mode bits describe a directory.
    static boolean isDirectory(int mode) {
        return (mode & FILE_TYPE_MASK) == DIRECTORY_TYPE;
    }

    /// Returns whether the given AR mode bits describe a symbolic link.
    static boolean isSymbolicLink(int mode) {
        return (mode & FILE_TYPE_MASK) == SYMBOLIC_LINK_TYPE;
    }

    /// Returns whether the given AR mode bits describe an unsupported special file type.
    static boolean isOther(int mode) {
        int type = mode & FILE_TYPE_MASK;
        return type != 0
                && type != REGULAR_FILE_TYPE
                && type != DIRECTORY_TYPE
                && type != SYMBOLIC_LINK_TYPE;
    }

    /// Implements AR principal lookup by preserving requested names.
    private static final class ArUserPrincipalLookupService extends UserPrincipalLookupService {
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Set;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.glavo.arkivo.zip.internal.ZipConstants.VERSION_NEEDED;

/// Provides synthesized POSIX metadata for ZIP file systems.
@NotNullByDefault
final class ZipPosixSupport {
    /// The default synthesized owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("owner");

    /// The default synthesized group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("group");

    /// The ZIP version made by value used for Unix external attributes.
    static final int UNIX_VERSION_MADE_BY = (3 << 8) | VERSION_NEEDED;

    /// The default synthesized regular file permissions.
    static final @Unmodifiable Set<PosixFilePermission> DEFAULT_FILE_PERMISSIONS =
            Set.of(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ);

    /// The default synthesized directory permissions.
    static final @Unmodifiable Set<PosixFilePermission> DEFAULT_DIRECTORY_PERMISSIONS =
            Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE);

    /// Prevents instantiation.
    private ZipPosixSupport() {
    }

    /// Returns synthesized permissions for the given entry type.
    static @Unmodifiable Set<PosixFilePermission> defaultPermissions(boolean directory) {
        return directory ? DEFAULT_DIRECTORY_PERMISSIONS : DEFAULT_FILE_PERMISSIONS;
    }

    /// Returns decoded POSIX permissions from ZIP metadata, or synthesized permissions when none are present.
    static @Unmodifiable Set<PosixFilePermission> permissions(
            int versionMadeBy,
            long externalAttributes,
            boolean directory
    ) {
        Set<PosixFilePermission> permissions = permissionsFromExternalAttributes(versionMadeBy, externalAttributes);
        return permissions != null ? permissions : defaultPermissions(directory);
    }

    /// Decodes POSIX permissions from ZIP external file attributes.
    private static @Nullable @Unmodifiable Set<PosixFilePermission> permissionsFromExternalAttributes(
            int versionMadeBy,
            long externalAttributes
    ) {
        int hostSystem = (versionMadeBy >>> 8) & 0xff;
        int mode = (int) ((externalAttributes >>> 16) & 0xffff);
        if (hostSystem != 3 || (mode & 0777) == 0) {
            return null;
        }

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

    /// Encodes POSIX permissions as ZIP external file attributes.
    static long externalAttributes(Set<PosixFilePermission> permissions, boolean directory) {
        int mode = directory ? 040000 : 0100000;
        if (permissions.contains(PosixFilePermission.OWNER_READ)) {
            mode |= 0400;
        }
        if (permissions.contains(PosixFilePermission.OWNER_WRITE)) {
            mode |= 0200;
        }
        if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
            mode |= 0100;
        }
        if (permissions.contains(PosixFilePermission.GROUP_READ)) {
            mode |= 0040;
        }
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)) {
            mode |= 0020;
        }
        if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) {
            mode |= 0010;
        }
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
            mode |= 0004;
        }
        if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            mode |= 0002;
        }
        if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
            mode |= 0001;
        }
        long externalAttributes = Integer.toUnsignedLong(mode << 16);
        return directory ? externalAttributes | 0x10L : externalAttributes;
    }

    /// Stores a synthetic named user principal.
    ///
    /// @param name the stable principal name
    private record NamedUserPrincipal(String name) implements UserPrincipal {
        /// Returns the stable principal name.
        @Override
        public String getName() {
            return name;
        }
    }

    /// Stores a synthetic named group principal.
    ///
    /// @param name the stable principal name
    private record NamedGroupPrincipal(String name) implements GroupPrincipal {
        /// Returns the stable principal name.
        @Override
        public String getName() {
            return name;
        }
    }
}

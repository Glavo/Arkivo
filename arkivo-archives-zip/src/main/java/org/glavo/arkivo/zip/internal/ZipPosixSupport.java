// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

/// Provides synthesized POSIX metadata for ZIP file systems.
@NotNullByDefault
final class ZipPosixSupport {
    /// The default synthesized owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("owner");

    /// The default synthesized group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("group");

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

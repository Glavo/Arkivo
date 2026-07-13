// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.FixedUserPrincipalLookupService;
import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Set;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.glavo.arkivo.archive.zip.internal.ZipConstants.VERSION_NEEDED;

/// Provides synthesized POSIX metadata for ZIP file systems.
@NotNullByDefault
final class ZipPosixSupport {
    /// The default synthesized owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("owner");

    /// The default synthesized group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("group");

    /// The user principal lookup service for synthesized ZIP principals.
    private static final FixedUserPrincipalLookupService USER_PRINCIPAL_LOOKUP_SERVICE =
            new FixedUserPrincipalLookupService(DEFAULT_OWNER, DEFAULT_GROUP);

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

    /// Returns the lookup service for synthesized ZIP owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return USER_PRINCIPAL_LOOKUP_SERVICE;
    }

    /// Requires the given owner principal to match the synthesized ZIP owner.
    static void requireDefaultOwner(UserPrincipal owner) throws UserPrincipalNotFoundException {
        USER_PRINCIPAL_LOOKUP_SERVICE.requireUser(owner);
    }

    /// Requires the given group principal to match the synthesized ZIP group.
    static void requireDefaultGroup(GroupPrincipal group) throws UserPrincipalNotFoundException {
        USER_PRINCIPAL_LOOKUP_SERVICE.requireGroup(group);
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

    /// Returns whether ZIP external file attributes describe a Unix symbolic link.
    static boolean isSymbolicLink(int versionMadeBy, long externalAttributes) {
        int hostSystem = (versionMadeBy >>> 8) & 0xff;
        int mode = (int) ((externalAttributes >>> 16) & 0xffff);
        return hostSystem == 3 && PosixModes.isSymbolicLink(mode);
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
        return PosixModes.permissions(mode);
    }

    /// Encodes POSIX permissions as ZIP external file attributes.
    static long externalAttributes(Set<PosixFilePermission> permissions, boolean directory) {
        int mode = directory ? PosixModes.DIRECTORY_FILE_TYPE : PosixModes.REGULAR_FILE_TYPE;
        return externalAttributes(mode, permissions, directory ? 0x10L : 0L);
    }

    /// Encodes symbolic link attributes as ZIP external file attributes.
    static long symbolicLinkExternalAttributes(@Nullable Set<PosixFilePermission> permissions) {
        Set<PosixFilePermission> linkPermissions = permissions != null
                ? permissions
                : Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE
                );
        return externalAttributes(PosixModes.SYMBOLIC_LINK_FILE_TYPE, linkPermissions, 0L);
    }

    /// Encodes POSIX permissions and file type bits as ZIP external file attributes.
    private static long externalAttributes(int fileTypeMode, Set<PosixFilePermission> permissions, long lowAttributes) {
        int mode = fileTypeMode | PosixModes.permissionBits(permissions);
        long externalAttributes = Integer.toUnsignedLong(mode << 16);
        return externalAttributes | lowAttributes;
    }
}

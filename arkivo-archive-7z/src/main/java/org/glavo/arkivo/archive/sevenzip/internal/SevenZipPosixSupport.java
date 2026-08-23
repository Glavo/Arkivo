// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.FixedUserPrincipalLookupService;
import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Objects;
import java.util.Set;

/// Provides synthesized POSIX metadata helpers for 7z file systems.
@NotNullByDefault
final class SevenZipPosixSupport {
    /// The default synthesized owner principal.
    private static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("owner");

    /// The default synthesized group principal.
    private static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("group");

    /// The lookup service for synthesized 7z principals.
    private static final FixedUserPrincipalLookupService USER_PRINCIPAL_LOOKUP_SERVICE =
            new FixedUserPrincipalLookupService(DEFAULT_OWNER, DEFAULT_GROUP);

    /// The default synthesized permissions for writable regular files.
    private static final @Unmodifiable Set<PosixFilePermission> WRITABLE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
    );

    /// The default synthesized permissions for read-only regular files.
    private static final @Unmodifiable Set<PosixFilePermission> READ_ONLY_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
    );

    /// The default synthesized permissions for writable directories.
    private static final @Unmodifiable Set<PosixFilePermission> WRITABLE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
    );

    /// The default synthesized permissions for read-only directories.
    private static final @Unmodifiable Set<PosixFilePermission> READ_ONLY_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
    );

    /// Prevents instantiation.
    private SevenZipPosixSupport() {
    }

    /// Returns the default synthesized owner principal.
    static UserPrincipal owner() {
        return DEFAULT_OWNER;
    }

    /// Returns the default synthesized group principal.
    static GroupPrincipal group() {
        return DEFAULT_GROUP;
    }

    /// Returns the lookup service for synthesized 7z owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return USER_PRINCIPAL_LOOKUP_SERVICE;
    }

    /// Requires a principal name to match the synthesized owner.
    static void requireDefaultOwner(UserPrincipal owner) throws UserPrincipalNotFoundException {
        USER_PRINCIPAL_LOOKUP_SERVICE.requireUser(owner);
    }

    /// Requires a principal name to match the synthesized group.
    static void requireDefaultGroup(GroupPrincipal group) throws UserPrincipalNotFoundException {
        USER_PRINCIPAL_LOOKUP_SERVICE.requireGroup(group);
    }

    /// Returns the Unix mode stored in the high 16 bits of 7z attributes, or `UNKNOWN_UNIX_MODE`.
    static int unixMode(int windowsAttributes) {
        if (windowsAttributes == SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES) {
            return SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE;
        }
        int mode = (windowsAttributes >>> 16) & 0xffff;
        return mode != 0 ? mode : SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE;
    }

    /// Returns whether the 7z attributes describe a Unix symbolic link.
    static boolean isSymbolicLink(int windowsAttributes) {
        int mode = unixMode(windowsAttributes);
        return mode != SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE
                && PosixModes.isSymbolicLink(mode);
    }

    /// Returns whether the 7z attributes describe another Unix special file type.
    static boolean isOther(int windowsAttributes) {
        int mode = unixMode(windowsAttributes);
        if (mode == SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE) {
            return false;
        }
        return PosixModes.isOther(mode);
    }

    /// Returns synthesized POSIX permissions for a 7z entry or synthetic directory.
    static @Unmodifiable Set<PosixFilePermission> permissions(boolean directory, boolean readOnly) {
        if (directory) {
            return readOnly ? READ_ONLY_DIRECTORY_PERMISSIONS : WRITABLE_DIRECTORY_PERMISSIONS;
        }
        return readOnly ? READ_ONLY_FILE_PERMISSIONS : WRITABLE_FILE_PERMISSIONS;
    }

    /// Returns POSIX permissions from 7z attributes, or synthesized permissions when mode bits are absent.
    static @Unmodifiable Set<PosixFilePermission> permissions(
            boolean directory,
            boolean readOnly,
            int windowsAttributes
    ) {
        Set<PosixFilePermission> permissions = permissionsFromUnixMode(unixMode(windowsAttributes));
        return permissions != null ? permissions : permissions(directory, readOnly);
    }

    /// Returns Windows attributes containing POSIX mode bits for a regular file.
    static int regularFileWindowsAttributes(Set<PosixFilePermission> permissions) {
        return unixModeWindowsAttributes(PosixModes.REGULAR_FILE_TYPE, permissions);
    }

    /// Returns Windows attributes containing POSIX mode bits for a directory.
    static int directoryWindowsAttributes(Set<PosixFilePermission> permissions) {
        return unixModeWindowsAttributes(PosixModes.DIRECTORY_FILE_TYPE, permissions);
    }

    /// Returns Windows attributes containing POSIX mode bits for a symbolic link.
    static int symbolicLinkWindowsAttributes(Set<PosixFilePermission> permissions) {
        return unixModeWindowsAttributes(PosixModes.SYMBOLIC_LINK_FILE_TYPE, permissions);
    }

    /// Returns Windows attributes with replaced POSIX permissions and preserved file type and low attribute bits.
    static int withPermissions(
            boolean directory,
            int windowsAttributes,
            Set<PosixFilePermission> permissions
    ) {
        Objects.requireNonNull(permissions, "permissions");
        int unixMode = unixMode(windowsAttributes);
        int fileType;
        if (unixMode != SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE) {
            fileType = unixMode & PosixModes.FILE_TYPE_MASK;
        } else if (directory) {
            fileType = PosixModes.DIRECTORY_FILE_TYPE;
        } else if (isSymbolicLink(windowsAttributes)) {
            fileType = PosixModes.SYMBOLIC_LINK_FILE_TYPE;
        } else {
            fileType = PosixModes.REGULAR_FILE_TYPE;
        }
        int lowAttributes = windowsAttributes == SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
                ? 0
                : windowsAttributes & 0xffff;
        return lowAttributes | (fileType | PosixModes.permissionBits(permissions)) << 16;
    }

    /// Returns Windows attributes containing the given Unix file type and POSIX permissions.
    private static int unixModeWindowsAttributes(int fileType, Set<PosixFilePermission> permissions) {
        return (fileType | PosixModes.permissionBits(permissions)) << 16;
    }

    /// Decodes POSIX permissions from stored Unix mode bits.
    private static @Nullable @Unmodifiable Set<PosixFilePermission> permissionsFromUnixMode(int mode) {
        if (mode == SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE) {
            return null;
        }

        return PosixModes.permissions(mode);
    }
}

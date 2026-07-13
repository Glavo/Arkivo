// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.glavo.arkivo.archive.internal.PreservingUserPrincipalLookupService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Set;

/// Provides POSIX metadata helpers for AR file systems.
@NotNullByDefault
final class ArPosixSupport {
    /// Prevents instantiation.
    private ArPosixSupport() {
    }

    /// Returns the lookup service for AR owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return PreservingUserPrincipalLookupService.instance();
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
        return PosixModes.permissions(mode);
    }

    /// Returns mode bits for a regular file with the given POSIX permissions.
    static int regularFileMode(Set<PosixFilePermission> permissions) {
        return PosixModes.REGULAR_FILE_TYPE | PosixModes.permissionBits(permissions);
    }

    /// Returns mode bits for a directory with the given POSIX permissions.
    static int directoryMode(Set<PosixFilePermission> permissions) {
        return PosixModes.DIRECTORY_FILE_TYPE | PosixModes.permissionBits(permissions);
    }

    /// Returns mode bits for a symbolic link with the given POSIX permissions.
    static int symbolicLinkMode(Set<PosixFilePermission> permissions) {
        return PosixModes.SYMBOLIC_LINK_FILE_TYPE | PosixModes.permissionBits(permissions);
    }

    /// Returns whether the given AR mode bits describe a regular file.
    static boolean isRegularFile(int mode) {
        return PosixModes.isRegularFile(mode);
    }

    /// Returns whether the given AR mode bits describe a directory.
    static boolean isDirectory(int mode) {
        return PosixModes.isDirectory(mode);
    }

    /// Returns whether the given AR mode bits describe a symbolic link.
    static boolean isSymbolicLink(int mode) {
        return PosixModes.isSymbolicLink(mode);
    }

    /// Returns whether the given AR mode bits describe an unsupported special file type.
    static boolean isOther(int mode) {
        return PosixModes.isOther(mode);
    }
}

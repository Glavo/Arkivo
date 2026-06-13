// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.sevenzip.SevenZipArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Set;

/// Provides synthesized POSIX metadata helpers for 7z file systems.
@NotNullByDefault
final class SevenZipPosixSupport {
    /// The POSIX mode mask for file type bits.
    private static final int FILE_TYPE_MASK = 0170000;

    /// The POSIX mode type bits for regular files.
    private static final int REGULAR_FILE_TYPE = 0100000;

    /// The POSIX mode type bits for directories.
    private static final int DIRECTORY_TYPE = 0040000;

    /// The POSIX mode type bits for symbolic links.
    private static final int SYMBOLIC_LINK_TYPE = 0120000;

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
        return SevenZipPrincipalSupport.DEFAULT_OWNER;
    }

    /// Returns the default synthesized group principal.
    static GroupPrincipal group() {
        return SevenZipPrincipalSupport.DEFAULT_GROUP;
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
                && (mode & FILE_TYPE_MASK) == SYMBOLIC_LINK_TYPE;
    }

    /// Returns whether the 7z attributes describe another Unix special file type.
    static boolean isOther(int windowsAttributes) {
        int mode = unixMode(windowsAttributes);
        if (mode == SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE) {
            return false;
        }
        int type = mode & FILE_TYPE_MASK;
        return type != 0
                && type != REGULAR_FILE_TYPE
                && type != DIRECTORY_TYPE
                && type != SYMBOLIC_LINK_TYPE;
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
        return unixModeWindowsAttributes(REGULAR_FILE_TYPE, permissions);
    }

    /// Returns Windows attributes containing POSIX mode bits for a directory.
    static int directoryWindowsAttributes(Set<PosixFilePermission> permissions) {
        return unixModeWindowsAttributes(DIRECTORY_TYPE, permissions);
    }

    /// Returns Windows attributes containing POSIX mode bits for a symbolic link.
    static int symbolicLinkWindowsAttributes(Set<PosixFilePermission> permissions) {
        return unixModeWindowsAttributes(SYMBOLIC_LINK_TYPE, permissions);
    }

    /// Returns Windows attributes containing the given Unix file type and POSIX permissions.
    private static int unixModeWindowsAttributes(int fileType, Set<PosixFilePermission> permissions) {
        return (fileType | permissionsMode(permissions)) << 16;
    }

    /// Encodes POSIX permissions as Unix permission mode bits.
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

    /// Decodes POSIX permissions from stored Unix mode bits.
    private static @Nullable @Unmodifiable Set<PosixFilePermission> permissionsFromUnixMode(int mode) {
        if (mode == SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE || (mode & 0777) == 0) {
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
}

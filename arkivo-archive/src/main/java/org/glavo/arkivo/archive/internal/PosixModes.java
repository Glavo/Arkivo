// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// Converts POSIX mode values and classifies their file type bits.
@NotNullByDefault
public final class PosixModes {
    /// The mask selecting POSIX file type bits.
    public static final int FILE_TYPE_MASK = 0170000;

    /// The POSIX file type bits for a regular file.
    public static final int REGULAR_FILE_TYPE = 0100000;

    /// The POSIX file type bits for a directory.
    public static final int DIRECTORY_FILE_TYPE = 0040000;

    /// The POSIX file type bits for a symbolic link.
    public static final int SYMBOLIC_LINK_FILE_TYPE = 0120000;

    /// Prevents instantiation.
    private PosixModes() {
    }

    /// Decodes the permission bits in a POSIX mode value.
    public static @Unmodifiable Set<PosixFilePermission> permissions(long mode) {
        EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400L) != 0) {
            permissions.add(PosixFilePermission.OWNER_READ);
        }
        if ((mode & 0200L) != 0) {
            permissions.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((mode & 0100L) != 0) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if ((mode & 0040L) != 0) {
            permissions.add(PosixFilePermission.GROUP_READ);
        }
        if ((mode & 0020L) != 0) {
            permissions.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((mode & 0010L) != 0) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if ((mode & 0004L) != 0) {
            permissions.add(PosixFilePermission.OTHERS_READ);
        }
        if ((mode & 0002L) != 0) {
            permissions.add(PosixFilePermission.OTHERS_WRITE);
        }
        if ((mode & 0001L) != 0) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        return Set.copyOf(permissions);
    }

    /// Encodes POSIX permissions as the low nine mode bits.
    public static int permissionBits(Set<PosixFilePermission> permissions) {
        Objects.requireNonNull(permissions, "permissions");
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            mode |= switch (permission) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 0040;
                case GROUP_WRITE -> 0020;
                case GROUP_EXECUTE -> 0010;
                case OTHERS_READ -> 0004;
                case OTHERS_WRITE -> 0002;
                case OTHERS_EXECUTE -> 0001;
            };
        }
        return mode;
    }

    /// Returns whether a mode value has regular-file type bits or no type bits.
    public static boolean isRegularFile(long mode) {
        long type = mode & FILE_TYPE_MASK;
        return type == 0 || type == REGULAR_FILE_TYPE;
    }

    /// Returns whether a mode value has directory type bits.
    public static boolean isDirectory(long mode) {
        return (mode & FILE_TYPE_MASK) == DIRECTORY_FILE_TYPE;
    }

    /// Returns whether a mode value has symbolic-link type bits.
    public static boolean isSymbolicLink(long mode) {
        return (mode & FILE_TYPE_MASK) == SYMBOLIC_LINK_FILE_TYPE;
    }

    /// Returns whether a mode value has a recognized special file type other than a directory or symbolic link.
    public static boolean isOther(long mode) {
        long type = mode & FILE_TYPE_MASK;
        return type != 0
                && type != REGULAR_FILE_TYPE
                && type != DIRECTORY_FILE_TYPE
                && type != SYMBOLIC_LINK_FILE_TYPE;
    }
}

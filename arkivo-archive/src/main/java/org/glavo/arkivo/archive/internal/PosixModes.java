// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
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

    /// The nine permission bits, excluding file type and special mode bits.
    private static final int PERMISSION_MASK = 0777;

    /// Immutable permission sets indexed by the low nine mode bits.
    private static final @Unmodifiable List<@Unmodifiable Set<PosixFilePermission>> PERMISSION_SETS =
            createPermissionSets();

    /// Prevents instantiation.
    private PosixModes() {
    }

    /// Decodes the permission bits in a POSIX mode value.
    ///
    /// @param mode the integral POSIX mode whose low nine bits are decoded
    /// @return the immutable decoded permission set
    public static @Unmodifiable Set<PosixFilePermission> permissions(long mode) {
        return PERMISSION_SETS.get((int) mode & PERMISSION_MASK);
    }

    /// Builds the bounded lookup table without exposing its mutable construction sets.
    private static @Unmodifiable List<@Unmodifiable Set<PosixFilePermission>> createPermissionSets() {
        var result = new ArrayList<@Unmodifiable Set<PosixFilePermission>>(PERMISSION_MASK + 1);
        PosixFilePermission @Unmodifiable [] values = PosixFilePermission.values();
        for (int mode = 0; mode <= PERMISSION_MASK; mode++) {
            EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            for (PosixFilePermission permission : values) {
                if ((mode & permissionBit(permission)) != 0) permissions.add(permission);
            }
            result.add(Collections.unmodifiableSet(permissions));
        }
        return List.copyOf(result);
    }

    /// Encodes POSIX permissions as the low nine mode bits.
    ///
    /// @param permissions the permission set to encode
    /// @return the encoded low nine POSIX mode bits
    public static int permissionBits(Set<PosixFilePermission> permissions) {
        Objects.requireNonNull(permissions, "permissions");
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            mode |= permissionBit(permission);
        }
        return mode;
    }

    /// Returns the POSIX bit corresponding to one non-null permission.
    static int permissionBit(PosixFilePermission permission) {
        return switch (permission) {
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

    /// Returns whether a mode value has regular-file type bits or no type bits.
    ///
    /// @param mode the POSIX mode to classify
    /// @return {@code true} for a regular-file type or an unspecified type
    public static boolean isRegularFile(long mode) {
        long type = mode & FILE_TYPE_MASK;
        return type == 0 || type == REGULAR_FILE_TYPE;
    }

    /// Returns whether a mode value has directory type bits.
    ///
    /// @param mode the POSIX mode to classify
    /// @return {@code true} if {@code mode} identifies a directory
    public static boolean isDirectory(long mode) {
        return (mode & FILE_TYPE_MASK) == DIRECTORY_FILE_TYPE;
    }

    /// Returns whether a mode value has symbolic-link type bits.
    ///
    /// @param mode the POSIX mode to classify
    /// @return {@code true} if {@code mode} identifies a symbolic link
    public static boolean isSymbolicLink(long mode) {
        return (mode & FILE_TYPE_MASK) == SYMBOLIC_LINK_FILE_TYPE;
    }

    /// Returns whether a mode value has a recognized special file type other than a directory or symbolic link.
    ///
    /// @param mode the POSIX mode to classify
    /// @return {@code true} if nonzero type bits identify another file type
    public static boolean isOther(long mode) {
        long type = mode & FILE_TYPE_MASK;
        return type != 0
                && type != REGULAR_FILE_TYPE
                && type != DIRECTORY_FILE_TYPE
                && type != SYMBOLIC_LINK_FILE_TYPE;
    }
}

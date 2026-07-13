// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// Validates and copies dynamically typed POSIX permission sets.
@NotNullByDefault
public final class PosixPermissions {
    /// Prevents instantiation.
    private PosixPermissions() {
    }

    /// Returns an immutable POSIX permission set from a dynamically typed attribute value.
    ///
    /// @param value the value expected to be a set of POSIX permissions
    /// @param description the value description used in validation messages
    public static @Unmodifiable Set<PosixFilePermission> copyOf(
            @Nullable Object value,
            String description
    ) {
        Objects.requireNonNull(description, "description");
        if (!(value instanceof Set<?> values)) {
            throw new IllegalArgumentException(description + " value must be a Set");
        }

        EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (Object element : values) {
            if (!(element instanceof PosixFilePermission permission)) {
                throw new IllegalArgumentException(description + " contains a non-POSIX permission value");
            }
            permissions.add(permission);
        }
        return Set.copyOf(permissions);
    }
}

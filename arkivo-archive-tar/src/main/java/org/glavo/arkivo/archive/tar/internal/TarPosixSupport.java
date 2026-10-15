// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;

/// Provides POSIX metadata helpers for TAR file systems.
@NotNullByDefault
final class TarPosixSupport {
    /// The synthesized default owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("");

    /// The synthesized default group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("");

    /// Prevents instantiation.
    private TarPosixSupport() {
    }

    /// Rejects principal names that cannot be preserved in USTAR or PAX fields.
    ///
    /// @param name the replacement name, or `null` to clear it
    /// @throws IllegalArgumentException if the name contains NUL or an unpaired UTF-16 surrogate
    static void validateName(@Nullable String name) {
        if (name == null) {
            return;
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value == 0) {
                throw new IllegalArgumentException("TAR principal names cannot contain NUL");
            }
            if (Character.isHighSurrogate(value) && index + 1 < name.length()
                    && Character.isLowSurrogate(name.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(value)) {
                throw new IllegalArgumentException("TAR principal names cannot contain unpaired surrogates");
            }
        }
    }

    /// Returns the owner principal represented by a TAR user name.
    static UserPrincipal owner(@Nullable String userName) {
        return userName != null ? new NamedUserPrincipal(userName) : DEFAULT_OWNER;
    }

    /// Returns the group principal represented by a TAR group name.
    static GroupPrincipal group(@Nullable String groupName) {
        return groupName != null ? new NamedGroupPrincipal(groupName) : DEFAULT_GROUP;
    }
}

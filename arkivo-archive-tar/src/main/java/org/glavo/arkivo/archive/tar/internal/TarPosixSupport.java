// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.glavo.arkivo.archive.internal.PreservingUserPrincipalLookupService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Set;

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

    /// Returns the lookup service for TAR owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return PreservingUserPrincipalLookupService.instance();
    }

    /// Returns the owner principal represented by a TAR user name.
    static UserPrincipal owner(@Nullable String userName) {
        return userName != null ? new NamedUserPrincipal(userName) : DEFAULT_OWNER;
    }

    /// Returns the group principal represented by a TAR group name.
    static GroupPrincipal group(@Nullable String groupName) {
        return groupName != null ? new NamedGroupPrincipal(groupName) : DEFAULT_GROUP;
    }

    /// Returns the POSIX permissions encoded by the given TAR mode bits.
    static @Unmodifiable Set<PosixFilePermission> permissions(int mode) {
        return PosixModes.permissions(mode);
    }
}

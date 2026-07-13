// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.glavo.arkivo.archive.internal.PreservingUserPrincipalLookupService;
import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Set;

/// Provides POSIX metadata helpers for RAR file systems.
@NotNullByDefault
final class RarPosixSupport {
    /// The synthesized default owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("");

    /// The synthesized default group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("");

    /// Prevents instantiation.
    private RarPosixSupport() {
    }

    /// Returns the lookup service for RAR owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return PreservingUserPrincipalLookupService.instance();
    }

    /// Returns the owner principal represented by RAR Unix owner metadata.
    static UserPrincipal owner(@Nullable String userName, long userId) {
        if (userName != null) {
            return new NamedUserPrincipal(userName);
        }
        if (userId != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            return new NamedUserPrincipal(Long.toString(userId));
        }
        return DEFAULT_OWNER;
    }

    /// Returns the group principal represented by RAR Unix owner metadata.
    static GroupPrincipal group(@Nullable String groupName, long groupId) {
        if (groupName != null) {
            return new NamedGroupPrincipal(groupName);
        }
        if (groupId != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            return new NamedGroupPrincipal(Long.toString(groupId));
        }
        return DEFAULT_GROUP;
    }

    /// Returns the POSIX permissions encoded by the RAR host-specific file attributes.
    static @Unmodifiable Set<PosixFilePermission> permissions(int hostOs, long fileAttributes) {
        if (hostOs != RarArkivoEntryAttributes.HOST_OS_UNIX) {
            return Set.of();
        }
        return PosixModes.permissions(fileAttributes);
    }
}

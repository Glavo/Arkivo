// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.glavo.arkivo.archive.internal.PreservingUserPrincipalLookupService;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;

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
}

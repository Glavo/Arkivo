// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.FixedUserPrincipalLookupService;
import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;

/// Provides synthesized principals for 7z file systems.
@NotNullByDefault
final class SevenZipPrincipalSupport {
    /// The default synthesized owner principal.
    static final UserPrincipal DEFAULT_OWNER = new NamedUserPrincipal("owner");

    /// The default synthesized group principal.
    static final GroupPrincipal DEFAULT_GROUP = new NamedGroupPrincipal("group");

    /// The user principal lookup service for synthesized 7z principals.
    private static final FixedUserPrincipalLookupService USER_PRINCIPAL_LOOKUP_SERVICE =
            new FixedUserPrincipalLookupService(DEFAULT_OWNER, DEFAULT_GROUP);

    /// Prevents instantiation.
    private SevenZipPrincipalSupport() {
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
}

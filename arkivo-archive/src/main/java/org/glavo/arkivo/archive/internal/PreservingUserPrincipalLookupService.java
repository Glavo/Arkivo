// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Objects;

/// Resolves every requested name to an archive-local principal with the same name.
@NotNullByDefault
public final class PreservingUserPrincipalLookupService extends UserPrincipalLookupService {
    /// The shared stateless lookup service.
    private static final PreservingUserPrincipalLookupService INSTANCE =
            new PreservingUserPrincipalLookupService();

    /// Prevents external instantiation of the stateless service.
    private PreservingUserPrincipalLookupService() {
    }

    /// Returns the shared lookup service.
    public static PreservingUserPrincipalLookupService instance() {
        return INSTANCE;
    }

    /// Returns a user principal preserving the requested name.
    @Override
    public UserPrincipal lookupPrincipalByName(String name) throws UserPrincipalNotFoundException {
        return new NamedUserPrincipal(Objects.requireNonNull(name, "name"));
    }

    /// Returns a group principal preserving the requested name.
    @Override
    public GroupPrincipal lookupPrincipalByGroupName(String group) throws UserPrincipalNotFoundException {
        return new NamedGroupPrincipal(Objects.requireNonNull(group, "group"));
    }
}

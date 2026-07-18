// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Objects;

/// Resolves only one fixed user principal and one fixed group principal.
@NotNullByDefault
public final class FixedUserPrincipalLookupService extends UserPrincipalLookupService {
    /// The supported user principal.
    private final UserPrincipal user;

    /// The supported group principal.
    private final GroupPrincipal group;

    /// Creates a lookup service for the given fixed principals.
    ///
    /// @param user the only supported user principal
    /// @param group the only supported group principal
    public FixedUserPrincipalLookupService(UserPrincipal user, GroupPrincipal group) {
        this.user = Objects.requireNonNull(user, "user");
        this.group = Objects.requireNonNull(group, "group");
    }

    /// Returns the fixed user principal when its name matches.
    @Override
    public UserPrincipal lookupPrincipalByName(String name) throws UserPrincipalNotFoundException {
        Objects.requireNonNull(name, "name");
        if (user.getName().equals(name)) {
            return user;
        }
        throw new UserPrincipalNotFoundException(name);
    }

    /// Returns the fixed group principal when its name matches.
    @Override
    public GroupPrincipal lookupPrincipalByGroupName(String name) throws UserPrincipalNotFoundException {
        Objects.requireNonNull(name, "name");
        if (group.getName().equals(name)) {
            return group;
        }
        throw new UserPrincipalNotFoundException(name);
    }

    /// Requires the given principal to have the fixed user name.
    ///
    /// @param principal the user principal whose name is checked
    /// @throws UserPrincipalNotFoundException if the principal name differs from the fixed user name
    public void requireUser(UserPrincipal principal) throws UserPrincipalNotFoundException {
        lookupPrincipalByName(Objects.requireNonNull(principal, "principal").getName());
    }

    /// Requires the given principal to have the fixed group name.
    ///
    /// @param principal the group principal whose name is checked
    /// @throws UserPrincipalNotFoundException if the principal name differs from the fixed group name
    public void requireGroup(GroupPrincipal principal) throws UserPrincipalNotFoundException {
        lookupPrincipalByGroupName(Objects.requireNonNull(principal, "principal").getName());
    }
}

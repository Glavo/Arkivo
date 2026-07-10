// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

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
    private static final UserPrincipalLookupService USER_PRINCIPAL_LOOKUP_SERVICE =
            new SyntheticUserPrincipalLookupService();

    /// Prevents instantiation.
    private SevenZipPrincipalSupport() {
    }

    /// Returns the lookup service for synthesized 7z owner and group principals.
    static UserPrincipalLookupService userPrincipalLookupService() {
        return USER_PRINCIPAL_LOOKUP_SERVICE;
    }

    /// Requires a principal name to match the synthesized owner.
    static void requireDefaultOwner(UserPrincipal owner) throws UserPrincipalNotFoundException {
        if (!DEFAULT_OWNER.getName().equals(owner.getName())) {
            throw new UserPrincipalNotFoundException(owner.getName());
        }
    }

    /// Requires a principal name to match the synthesized group.
    static void requireDefaultGroup(GroupPrincipal group) throws UserPrincipalNotFoundException {
        if (!DEFAULT_GROUP.getName().equals(group.getName())) {
            throw new UserPrincipalNotFoundException(group.getName());
        }
    }

    /// Stores a synthetic named user principal.
    ///
    /// @param name the stable principal name
    private record NamedUserPrincipal(String name) implements UserPrincipal {
        /// Returns the stable principal name.
        @Override
        public String getName() {
            return name;
        }
    }

    /// Stores a synthetic named group principal.
    ///
    /// @param name the stable principal name
    private record NamedGroupPrincipal(String name) implements GroupPrincipal {
        /// Returns the stable principal name.
        @Override
        public String getName() {
            return name;
        }
    }

    /// Resolves the stable synthesized 7z owner and group principals.
    @NotNullByDefault
    private static final class SyntheticUserPrincipalLookupService extends UserPrincipalLookupService {
        /// Looks up the synthesized 7z owner principal.
        @Override
        public UserPrincipal lookupPrincipalByName(String name) throws UserPrincipalNotFoundException {
            if (DEFAULT_OWNER.getName().equals(name)) {
                return DEFAULT_OWNER;
            }
            throw new UserPrincipalNotFoundException(name);
        }

        /// Looks up the synthesized 7z group principal.
        @Override
        public GroupPrincipal lookupPrincipalByGroupName(String group) throws UserPrincipalNotFoundException {
            if (DEFAULT_GROUP.getName().equals(group)) {
                return DEFAULT_GROUP;
            }
            throw new UserPrincipalNotFoundException(group);
        }
    }
}

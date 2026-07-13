// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;

/// A user principal identified only by its archive-stored name.
///
/// @param name the principal name
@NotNullByDefault
public record NamedUserPrincipal(String name) implements UserPrincipal {
    /// Creates a named user principal.
    public NamedUserPrincipal {
        Objects.requireNonNull(name, "name");
    }

    /// Returns the principal name.
    @Override
    public String getName() {
        return name;
    }
}

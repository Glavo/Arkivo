// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.GroupPrincipal;
import java.util.Objects;

/// A group principal identified only by its archive-stored name.
///
/// @param name the principal name
@NotNullByDefault
public record NamedGroupPrincipal(String name) implements GroupPrincipal {
    /// Creates a named group principal.
    public NamedGroupPrincipal {
        Objects.requireNonNull(name, "name");
    }

    /// Returns the principal name.
    @Override
    public String getName() {
        return name;
    }
}

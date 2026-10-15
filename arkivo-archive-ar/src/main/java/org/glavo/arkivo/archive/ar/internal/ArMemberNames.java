// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies AR member names reserved for archive metadata.
@NotNullByDefault
final class ArMemberNames {
    /// Prevents instantiation.
    private ArMemberNames() {
    }

    /// Tests a decoded member name or standard identifier for a BSD symbol-table name.
    static boolean isBsdSymbolTable(String identifier) {
        String name = identifier.endsWith("/")
                ? identifier.substring(0, identifier.length() - 1)
                : identifier;
        return "__.SYMDEF".equals(name)
                || "__.SYMDEF SORTED".equals(name)
                || "__.SYMDEF_64".equals(name)
                || "__.SYMDEF_64 SORTED".equals(name);
    }
}

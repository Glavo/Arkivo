// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies a ZIP compression method.
///
/// @param id the numeric ZIP compression method identifier
/// @param name the stable display name for the method
@NotNullByDefault
public record ZipMethod(
        int id,
        String name
) {
    /// The stored method identifier.
    public static final int STORED_ID = 0;

    /// The deflated method identifier.
    public static final int DEFLATED_ID = 8;

    /// The stored ZIP method.
    private static final ZipMethod STORED = new ZipMethod(STORED_ID, "stored");

    /// The deflated ZIP method.
    private static final ZipMethod DEFLATED = new ZipMethod(DEFLATED_ID, "deflated");

    /// Returns the stored ZIP method.
    public static ZipMethod stored() {
        return STORED;
    }

    /// Returns the deflated ZIP method.
    public static ZipMethod deflated() {
        return DEFLATED;
    }

    /// Returns a ZIP method for the given numeric identifier.
    public static ZipMethod of(int id) {
        return switch (id) {
            case STORED_ID -> STORED;
            case DEFLATED_ID -> DEFLATED;
            default -> new ZipMethod(id, "method-" + id);
        };
    }
}

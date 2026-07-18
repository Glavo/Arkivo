// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.jetbrains.annotations.NotNullByDefault;

/// Configures an XZ Delta preprocessing filter.
///
/// Encoding replaces each byte with its difference from the byte at the configured history distance. Decoding restores
/// the original bytes; the transform preserves length.
///
/// @param distance the original-byte history distance from one through 256
@NotNullByDefault
public record XZDeltaFilter(long distance) implements XZFilter {
    /// Validates the Delta history distance.
    ///
    /// @throws IllegalArgumentException if {@code distance} is outside the range from one through 256
    public XZDeltaFilter {
        if (distance < 1L || distance > 256L) {
            throw new IllegalArgumentException("XZ Delta distance must be between 1 and 256: " + distance);
        }
    }
}

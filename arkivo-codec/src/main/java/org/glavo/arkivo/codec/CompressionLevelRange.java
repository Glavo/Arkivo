// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the inclusive algorithm-specific compression-level range and default.
///
/// @param minimum minimum accepted compression level
/// @param maximum maximum accepted compression level
/// @param defaultValue default compression level
@NotNullByDefault
public record CompressionLevelRange(int minimum, int maximum, int defaultValue) {
    /// Validates the ordered range and default value.
    public CompressionLevelRange {
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        if (defaultValue < minimum || defaultValue > maximum) {
            throw new IllegalArgumentException("defaultValue must be within the supported range");
        }
    }

    /// Returns whether the range accepts the given compression level.
    public boolean contains(int value) {
        return value >= minimum && value <= maximum;
    }
}

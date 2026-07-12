// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Stores a non-negative byte count used by size-related codec options.
///
/// @param value the byte count
@NotNullByDefault
public record ByteSize(long value) {
    /// Validates the non-negative byte count.
    public ByteSize {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }
}

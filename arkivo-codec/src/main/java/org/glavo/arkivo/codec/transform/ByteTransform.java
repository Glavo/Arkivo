// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;

/// Transforms a contiguous prefix of buffered bytes for one stateful preprocessing filter.
///
/// Implementations are stateful and not safe for concurrent use. They must not retain the supplied buffer after an
/// operation returns.
@FunctionalInterface
@NotNullByDefault
public interface ByteTransform {
    /// Transforms bytes in place and returns the contiguous prefix that is ready for downstream consumption.
    ///
    /// Only the returned prefix may be modified. The remaining suffix must stay unchanged so it can be presented again
    /// with additional lookahead.
    ///
    /// @param buffer the mutable byte storage
    /// @param offset the first byte to inspect
    /// @param length the number of available bytes
    /// @return the transformed prefix length from zero through `length`
    int transform(byte[] buffer, int offset, int length);

    /// Selects whether a transform produces or consumes its encoded representation.
    @NotNullByDefault
    enum Direction {
        /// Converts original bytes into the transform's encoded representation.
        ENCODE,

        /// Restores original bytes from the transform's encoded representation.
        DECODE
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes one immutable decoded-to-encoded UDIF byte range.
///
/// @param type the raw UDIF run type code
/// @param logicalOffset the decoded-image byte offset
/// @param logicalLength the decoded byte length
/// @param physicalOffset the encoded-file byte offset, or zero for sparse runs
/// @param physicalLength the encoded byte length, or zero for sparse runs
@NotNullByDefault
record UDIFRun(
        int type,
        long logicalOffset,
        long logicalLength,
        long physicalOffset,
        long physicalLength
) {
    /// Returns the exclusive decoded-image end offset.
    ///
    /// @return the exclusive logical end, validated when the run is parsed
    long logicalEnd() {
        return logicalOffset + logicalLength;
    }

    /// Returns whether this run produces zero-filled bytes without reading encoded data.
    ///
    /// @return {@code true} for zero and ignore runs
    boolean isSparse() {
        return type == UDIFConstants.BLOCK_ZEROES || type == UDIFConstants.BLOCK_IGNORE;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Stores one validated HFS Plus file fork and its complete extent sequence.
///
/// @param logicalSize the logical fork size in bytes
/// @param totalBlocks the declared allocation-block count
/// @param extents the complete extent sequence, which is empty only for an empty fork
@NotNullByDefault
record HFSPlusFork(long logicalSize, long totalBlocks, @Unmodifiable List<HFSPlusExtent> extents) {
    /// Copies the extent sequence into this immutable fork description.
    HFSPlusFork {
        extents = List.copyOf(extents);
    }

    /// Returns an immutable copy with a resolved complete extent sequence.
    HFSPlusFork withExtents(List<HFSPlusExtent> resolvedExtents) {
        return new HFSPlusFork(logicalSize, totalBlocks, resolvedExtents);
    }
}

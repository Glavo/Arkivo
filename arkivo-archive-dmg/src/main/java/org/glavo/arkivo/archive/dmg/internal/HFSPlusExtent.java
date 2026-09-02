// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes one HFS Plus allocation extent.
///
/// @param startBlock the first allocation block
/// @param blockCount the number of allocation blocks
@NotNullByDefault
record HFSPlusExtent(long startBlock, long blockCount) {
    /// Returns the exclusive allocation-block end.
    long endBlock() {
        return startBlock + blockCount;
    }
}

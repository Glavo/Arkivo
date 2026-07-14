// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects a Zstandard match-finding strategy from fastest to strongest.
@NotNullByDefault
public enum ZstdStrategy {
    /// Uses a single hash-table lookup per position.
    FAST,

    /// Uses a double hash-table lookup.
    DFAST,

    /// Selects the first locally best match.
    GREEDY,

    /// Delays match selection by one position.
    LAZY,

    /// Performs two-step lazy match selection.
    LAZY2,

    /// Uses a binary tree with two-step lazy selection.
    BT_LAZY2,

    /// Uses binary-tree optimal parsing.
    BT_OPT,

    /// Uses deeper binary-tree optimal parsing.
    BT_ULTRA,

    /// Uses the strongest binary-tree optimal parser.
    BT_ULTRA2
}

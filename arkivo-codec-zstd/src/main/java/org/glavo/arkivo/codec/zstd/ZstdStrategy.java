// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects a Zstandard match-finding strategy from fastest to strongest.
@NotNullByDefault
public enum ZstdStrategy {
    /// Uses one primary hash-table candidate per position.
    FAST,

    /// Chooses between primary four-byte and secondary eight-byte hash candidates.
    DFAST,

    /// Selects the locally best bounded hash-chain match immediately.
    GREEDY,

    /// Delays match selection by one position.
    LAZY,

    /// Performs two-step lazy match selection.
    LAZY2,

    /// Uses bounded binary-tree candidate search with two-step lazy selection.
    BT_LAZY2,

    /// Uses binary-tree candidates and dynamic programming to minimize an approximate block bit cost.
    BT_OPT,

    /// Adds shortened-match alternatives to binary-tree dynamic-programming parsing.
    BT_ULTRA,

    /// Uses the strongest candidate-ending search over binary-tree matches.
    BT_ULTRA2
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects a Zstandard match-finding strategy from fastest to strongest.
@NotNullByDefault
public enum ZstdStrategy {
    /// Uses a single hash-table lookup per position.
    FAST(1),

    /// Uses a double hash-table lookup.
    DFAST(2),

    /// Selects the first locally best match.
    GREEDY(3),

    /// Delays match selection by one position.
    LAZY(4),

    /// Performs two-step lazy match selection.
    LAZY2(5),

    /// Uses a binary tree with two-step lazy selection.
    BT_LAZY2(6),

    /// Uses binary-tree optimal parsing.
    BT_OPT(7),

    /// Uses deeper binary-tree optimal parsing.
    BT_ULTRA(8),

    /// Uses the strongest binary-tree optimal parser.
    BT_ULTRA2(9);

    /// The native Zstandard strategy value.
    private final int nativeValue;

    /// Creates a strategy with its native Zstandard value.
    ZstdStrategy(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    /// Returns the native Zstandard strategy value.
    int nativeValue() {
        return nativeValue;
    }
}

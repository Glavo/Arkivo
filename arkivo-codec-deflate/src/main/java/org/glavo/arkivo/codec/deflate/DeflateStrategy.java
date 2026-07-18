// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects how a Deflate encoder searches for repeated input and emits literals.
@NotNullByDefault
public enum DeflateStrategy {
    /// Uses the codec's normal match search and entropy coding.
    DEFAULT,

    /// Tunes match selection for data produced by a filter or predictor.
    FILTERED,

    /// Disables string matching and emits entropy-coded literals only.
    HUFFMAN_ONLY
}

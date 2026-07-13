// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects how a compressor searches for repeated input.
///
/// Codecs advertise `StandardCodecOptions.COMPRESSION_STRATEGY` only when they support every value.
@NotNullByDefault
public enum CompressionStrategy {
    /// Uses the codec's normal match search and entropy coding.
    DEFAULT,

    /// Tunes match selection for data produced by a filter or predictor.
    FILTERED,

    /// Disables string matching and emits entropy-coded literals only.
    HUFFMAN_ONLY
}

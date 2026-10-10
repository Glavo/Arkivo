// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.Charset;

/// Describes bounded evidence for a charset that strictly decodes an entire field.
/// Non-positive scores do not make the encoding invalid. Scores are not probabilities.
///
/// @param charset     validated charset
/// @param score       sum of feature contributions
/// @param evidence    number of contributing features after repetition limits
/// @param suspicious  unlikely character mixtures, used only as a soft penalty
/// @param controls    control characters unsuitable for the field's role
/// @param nonAscii    non-ASCII scalar count, saturated at two
/// @param fingerprint scored-feature hash used only to reduce repeated context contributions
@NotNullByDefault
public record EncodingScore(Charset charset, long score, int evidence, int suspicious, int controls, int nonAscii, long fingerprint) {
    /// Returns a bounded mean score in fixed-point units, penalizing control characters.
    long strength() {
        return Math.max(-255 * 256L, Math.min(255 * 256L,
                score * 256 / Math.max(1, evidence) - (suspicious + controls) * 64 * 256L));
    }

    /// Compares character plausibility and mean statistical support; positive means this is stronger.
    int compareQuality(EncodingScore other) {
        return Long.compare(strength(), other.strength());
    }
}

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
/// @param suspicious  unsupported characters or unmatched delimiters in the scored segment
/// @param fingerprint scored-feature hash used only to reduce repeated context contributions
@NotNullByDefault
record EncodingScore(Charset charset, long score, int evidence, int suspicious, long fingerprint) {
    /// Compares lexical plausibility and mean feature scores; positive means this is stronger.
    int compareQuality(EncodingScore other) {
        int result = Integer.compare(other.suspicious, suspicious);
        return result != 0 ? result : Long.compare(score * Math.max(1, other.evidence),
                other.score * Math.max(1, evidence));
    }
}

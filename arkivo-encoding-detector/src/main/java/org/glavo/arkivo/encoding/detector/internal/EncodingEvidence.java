// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/// Freezes format-independent metadata evidence without retaining source fields.
/// Scores aggregate bounded field evidence, not votes, probabilities, or an encoding declaration.
///
/// @param kind role of the fields used to build this evidence
/// @param candidates candidate support; copied on construction
@NotNullByDefault
public record EncodingEvidence(EncodingDetector.Kind kind, @Unmodifiable List<Support> candidates) {
    /// Copies candidate support into an immutable summary.
    /// @param kind metadata role
    /// @param candidates support records to copy
    public EncodingEvidence {
        java.util.Objects.requireNonNull(kind);
        candidates = List.copyOf(candidates);
    }

    /// Records relative scores from distinct feature fingerprints.
    ///
    /// @param charset candidate encoding
    /// @param score   sum of bounded relative field scores; higher values provide stronger support
    /// @param fields  number of contributing feature sets
    /// @param contributions per-feature-set scores, retained to exclude the queried field's own evidence
    @NotNullByDefault
    public record Support(Charset charset, long score, int fields,
                          @Unmodifiable Map<EncodingScore, Long> contributions) {
        /// Copies contributions without retaining any source bytes.
        /// @param charset candidate encoding
        /// @param score summed relative support
        /// @param fields number of contributing feature sets
        /// @param contributions per-feature-set scores to copy
        public Support {
            contributions = Map.copyOf(contributions);
        }
    }
}

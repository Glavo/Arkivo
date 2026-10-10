// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.Charset;
import java.util.List;

/// Freezes format-independent metadata evidence without retaining source fields.
/// Rankings are ordinal support, not confidence estimates or an archive-wide encoding declaration.
///
/// @param kind role of the fields used to build this evidence
/// @param candidates candidate support; copied on construction
@NotNullByDefault
record EncodingEvidence(EncodingDetector.Kind kind, @Unmodifiable List<Support> candidates) {
    /// Copies candidate support into an immutable summary.
    EncodingEvidence {
        java.util.Objects.requireNonNull(kind);
        candidates = List.copyOf(candidates);
    }

    /// Records ordinal support from distinct feature fingerprints.
    ///
    /// @param charset candidate encoding
    /// @param rank    sum of per-field wins minus losses against other valid candidates
    /// @param fields  number of contributing feature sets
    @NotNullByDefault
    record Support(Charset charset, long rank, int fields) {
    }
}

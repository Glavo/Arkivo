// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.Charset;

/// Summarizes scores from distinct, independently decoded fields without retaining their contents.
/// Rejection in another field does not invalidate this evidence or establish that field's encoding.
///
/// @param charset candidate encoding
/// @param score sum of the accepted fields' scores; not a probability or a calibrated archive prior
/// @param fields number of distinct fields contributing to the sum, including zero and negative scores
@NotNullByDefault
record EncodingEvidence(Charset charset, long score, int fields) {
}

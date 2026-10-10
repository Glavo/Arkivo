// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.Charset;

/// Stores one complete field's language-model score without retaining its bytes.
/// Scores can be negative and are not probabilities. Equal scores need not mean equal decoded text.
///
/// @param charset encoding accepted by the field scorer
/// @param score accumulated score, including end-of-field contributions
@NotNullByDefault
record EncodingScore(Charset charset, long score) {
}

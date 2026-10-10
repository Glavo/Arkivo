// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// State used by the LatinCaseState scoring transitions.
@NotNullByDefault
enum LatinCaseState {
    /// Space.
    SPACE,
    /// Upper.
    UPPER,
    /// Lower.
    LOWER,
    /// All Caps.
    ALL_CAPS
}

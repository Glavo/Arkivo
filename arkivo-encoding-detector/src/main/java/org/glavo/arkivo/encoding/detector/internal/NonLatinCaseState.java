// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// State used by the NonLatinCaseState scoring transitions.
@NotNullByDefault
enum NonLatinCaseState {
    /// Space.
    SPACE,
    /// Upper.
    UPPER,
    /// Lower.
    LOWER,
    /// Upper Lower.
    UPPER_LOWER,
    /// All Caps.
    ALL_CAPS,
    /// Mix.
    MIX
}

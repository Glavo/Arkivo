// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// State used by the OrdinalState scoring transitions.
@NotNullByDefault
enum OrdinalState {
    /// Other.
    OTHER,
    /// Space.
    SPACE,
    /// Period After N.
    PERIOD_AFTER_N,
    /// Ordinal Expecting Space.
    ORDINAL_EXPECTING_SPACE,
    /// Ordinal Expecting Space Undo Implausibility.
    ORDINAL_EXPECTING_SPACE_UNDO_IMPLAUSIBILITY,
    /// Ordinal Expecting Space Or Digit.
    ORDINAL_EXPECTING_SPACE_OR_DIGIT,
    /// Ordinal Expecting Space Or Digit Undo Implausibily.
    ORDINAL_EXPECTING_SPACE_OR_DIGIT_UNDO_IMPLAUSIBILITY,
    /// Upper N.
    UPPER_N,
    /// Lower N.
    LOWER_N,
    /// Feminine Abbreviation Start Letter.
    FEMININE_ABBREVIATION_START_LETTER,
    /// Digit.
    DIGIT,
    /// Roman.
    ROMAN,
    /// Copyright.
    COPYRIGHT
}

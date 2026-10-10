// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// State used by the LatinCj scoring transitions.
@NotNullByDefault
enum LatinCj {
    /// Ascii Letter.
    ASCII_LETTER,
    /// Cj.
    CJ,
    /// Other.
    OTHER
}

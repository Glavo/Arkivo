// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// State used by the LatinKorean scoring transitions.
@NotNullByDefault
enum LatinKorean {
    /// Ascii Letter.
    ASCII_LETTER,
    /// Hangul.
    HANGUL,
    /// Hanja.
    HANJA,
    /// Other.
    OTHER
}

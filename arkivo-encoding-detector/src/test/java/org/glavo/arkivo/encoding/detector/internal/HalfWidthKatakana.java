// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// State used by the HalfWidthKatakana scoring transitions.
@NotNullByDefault
enum HalfWidthKatakana {
    /// Dakuten Forbidden.
    DAKUTEN_FORBIDDEN,
    /// Dakuten Allowed.
    DAKUTEN_ALLOWED,
    /// Dakuten Or Handakuten Allowed.
    DAKUTEN_OR_HANDAKUTEN_ALLOWED
}

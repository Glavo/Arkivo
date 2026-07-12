// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies how ZIP entry names without an authoritative Unicode encoding marker are decoded.
@NotNullByDefault
public enum ZipEntryNameEncodingMode {
    /// Uses ZIP standard behavior and falls back to CP437 when no UTF-8 marker is present.
    STANDARD,

    /// Uses a caller-provided fallback charset when no UTF-8 marker is present.
    CHARSET,

    /// Chooses a fallback charset from a deterministic candidate list when no UTF-8 marker is present.
    AUTO
}

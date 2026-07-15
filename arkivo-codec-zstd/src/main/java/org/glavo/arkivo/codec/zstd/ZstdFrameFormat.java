// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects the physical framing used for Zstandard compressed data.
@NotNullByDefault
public enum ZstdFrameFormat {
    /// Uses the standard four-byte Zstandard frame magic and permits skippable frames while decoding.
    STANDARD,

    /// Omits the standard frame magic and requires explicit format selection while decoding.
    MAGICLESS
}

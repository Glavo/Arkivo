// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the header of one standard or skippable Zstandard frame.
@NotNullByDefault
public sealed interface ZstdFrameInfo permits ZstdStandardFrameInfo, ZstdSkippableFrameInfo {
    /// Returns the complete frame-header size in bytes.
    int headerSize();

    /// Returns whether this is a skippable frame.
    boolean skippable();
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the immutable header metadata of one standard or skippable Zstandard frame.
///
/// Header information does not validate compressed blocks or a trailing checksum and does not imply that the complete
/// frame is present in the source buffer.
@NotNullByDefault
public sealed interface ZstdFrameInfo permits ZstdStandardFrameInfo, ZstdSkippableFrameInfo {
    /// Returns the complete frame-header size in bytes.
    ///
    /// @return the header size in bytes
    int headerSize();

    /// Returns whether this is a skippable frame.
    ///
    /// @return {@code true} for a skippable frame, or {@code false} for a compressed standard frame
    boolean skippable();
}

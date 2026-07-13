// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects how an incremental encoder handles input supplied by one operation.
@NotNullByDefault
public enum EncodeDirective {
    /// Consumes input while keeping codec state available for later input.
    CONTINUE,

    /// Consumes input and flushes pending output without ending the frame.
    FLUSH,

    /// Consumes input and finishes the current frame.
    ///
    /// Multi-frame encoders remain open; other encoders release their resources.
    END_FRAME
}

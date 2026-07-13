// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects how one incremental decode operation handles a completed frame.
@NotNullByDefault
public enum DecodeDirective {
    /// Allows the decoder to continue through frame boundaries when no output is pending.
    CONTINUE,

    /// Returns `CodecStatus.FRAME_FINISHED` before beginning a following frame.
    STOP_AT_FRAME
}

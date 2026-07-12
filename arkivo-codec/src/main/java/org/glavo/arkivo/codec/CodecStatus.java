// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the codec context state after one incremental operation.
@NotNullByDefault
public enum CodecStatus {
    /// The context remains active and can process more data.
    ACTIVE,

    /// Pending output was flushed and the current frame remains active.
    FLUSHED,

    /// The current encoded frame was completed.
    FRAME_FINISHED,

    /// The decoder reached the end of its compressed input.
    END_OF_INPUT
}

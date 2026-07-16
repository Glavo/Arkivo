// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Reports byte progress and resulting state for one incremental codec operation.
///
/// @param inputBytes bytes consumed during this operation
/// @param outputBytes bytes produced during this operation
/// @param status context state after the operation
@NotNullByDefault
public record CodecResult(long inputBytes, long outputBytes, Status status) {
    /// Validates byte counters and the resulting status.
    public CodecResult {
        if (inputBytes < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("codec byte counts must not be negative");
        }
        Objects.requireNonNull(status, "status");
    }

    /// Describes the codec context state after one incremental operation.
    @NotNullByDefault
    public enum Status {
        /// The context remains active and can process more data.
        ACTIVE,

        /// Pending output was flushed and the current frame remains active.
        FLUSHED,

        /// The current encoded or decoded frame was completed.
        FRAME_FINISHED,

        /// The decoder reached the end of its compressed input.
        END_OF_INPUT
    }
}

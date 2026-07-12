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
public record CodecResult(long inputBytes, long outputBytes, CodecStatus status) {
    /// Validates byte counters and the resulting status.
    public CodecResult {
        if (inputBytes < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("codec byte counts must not be negative");
        }
        Objects.requireNonNull(status, "status");
    }
}

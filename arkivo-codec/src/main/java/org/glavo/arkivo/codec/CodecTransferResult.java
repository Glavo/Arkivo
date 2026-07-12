// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Reports byte counts for a completed channel-to-channel codec operation.
///
/// @param inputBytes bytes consumed from the operation source
/// @param outputBytes bytes written to the operation target
@NotNullByDefault
public record CodecTransferResult(long inputBytes, long outputBytes) {
    /// Validates non-negative transfer counters.
    public CodecTransferResult {
        if (inputBytes < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("transfer byte counts must not be negative");
        }
    }
}

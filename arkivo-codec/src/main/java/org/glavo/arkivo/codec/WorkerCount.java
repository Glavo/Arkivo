// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects a non-negative number of codec worker threads.
///
/// @param value zero for codec-controlled or single-threaded operation, otherwise the worker count
@NotNullByDefault
public record WorkerCount(int value) {
    /// Validates the non-negative worker count.
    public WorkerCount {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }
}

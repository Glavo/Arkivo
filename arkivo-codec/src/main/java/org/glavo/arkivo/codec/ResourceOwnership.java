// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects whether a resource adapter borrows or owns its backing resource.
///
/// This policy governs ordinary lifecycle closure. An adapter that preserves [java.nio.channels.InterruptibleChannel]
/// semantics may have to close a borrowed backing channel when its operation thread is interrupted or another thread
/// closes the adapter during an active operation; closing the endpoint is what unblocks that operation.
@NotNullByDefault
public enum ResourceOwnership {
    /// Leaves the backing resource open after ordinary idle closure.
    BORROWED,

    /// Closes the backing resource when the adapter closes.
    OWNED
}

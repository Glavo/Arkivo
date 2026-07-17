// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects whether a resource adapter borrows or owns its backing resource.
@NotNullByDefault
public enum ResourceOwnership {
    /// Leaves the backing resource open when the adapter closes.
    BORROWED,

    /// Closes the backing resource when the adapter closes.
    OWNED
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects whether closing a codec context also closes its backing channel.
@NotNullByDefault
public enum ChannelOwnership {
    /// Leaves the backing channel open when the codec context closes.
    RETAIN,

    /// Closes the backing channel when the codec context closes.
    CLOSE
}

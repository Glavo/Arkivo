// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects whether an optional format checksum follows the codec default or is explicitly controlled.
@NotNullByDefault
public enum ChecksumMode {
    /// Uses the codec's default checksum behavior.
    DEFAULT,

    /// Enables checksum generation or verification.
    ENABLED,

    /// Disables checksum generation or verification.
    DISABLED
}

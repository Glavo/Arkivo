// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes a single-stream compression format.
@NotNullByDefault
public interface CompressionFormat {
    /// Returns the stable compression format name.
    String name();
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Indicates that a decoder requires more working memory than an operation allowed.
@NotNullByDefault
public final class DecompressionMemoryLimitException extends DecompressionLimitException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// Creates an exception for one exceeded decoder working-memory limit.
    public DecompressionMemoryLimitException(long maximumMemorySize, long requiredMemorySize) {
        super(
                "Required decoder memory of " + requiredMemorySize
                        + " bytes exceeds the configured maximum of " + maximumMemorySize + " bytes",
                Kind.MEMORY_SIZE,
                maximumMemorySize,
                requiredMemorySize
        );
    }

    /// Returns the configured maximum decoder working-memory size.
    public long maximumMemorySize() {
        return maximum();
    }

    /// Returns the decoder working-memory size required by the codec configuration.
    public long requiredMemorySize() {
        return actual();
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Indicates that decompression produced more bytes than an operation allowed.
@NotNullByDefault
public final class DecompressionLimitException extends IOException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// The configured maximum output size.
    private final long maximumOutputSize;

    /// Creates an exception for one exceeded decompression limit.
    public DecompressionLimitException(long maximumOutputSize) {
        super("Decompressed output exceeds the configured maximum of " + maximumOutputSize + " bytes");
        if (maximumOutputSize < 0L) {
            throw new IllegalArgumentException("maximumOutputSize must not be negative");
        }
        this.maximumOutputSize = maximumOutputSize;
    }

    /// Returns the configured maximum output size.
    public long maximumOutputSize() {
        return maximumOutputSize;
    }
}

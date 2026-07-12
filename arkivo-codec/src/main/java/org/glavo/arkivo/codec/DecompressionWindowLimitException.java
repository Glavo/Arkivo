// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Indicates that a compressed stream requires a decoding window larger than an operation allowed.
@NotNullByDefault
public final class DecompressionWindowLimitException extends IOException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// The configured maximum decoding window size.
    private final long maximumWindowSize;

    /// The decoding window size required by the format or stream.
    private final long requiredWindowSize;

    /// Creates an exception for one exceeded decoding window limit.
    public DecompressionWindowLimitException(long maximumWindowSize, long requiredWindowSize) {
        super("Required decoding window of " + requiredWindowSize
                + " bytes exceeds the configured maximum of " + maximumWindowSize + " bytes");
        if (maximumWindowSize < 0L) {
            throw new IllegalArgumentException("maximumWindowSize must not be negative");
        }
        if (requiredWindowSize <= maximumWindowSize) {
            throw new IllegalArgumentException("requiredWindowSize must exceed maximumWindowSize");
        }
        this.maximumWindowSize = maximumWindowSize;
        this.requiredWindowSize = requiredWindowSize;
    }

    /// Returns the configured maximum decoding window size.
    public long maximumWindowSize() {
        return maximumWindowSize;
    }

    /// Returns the decoding window size required by the format or stream.
    public long requiredWindowSize() {
        return requiredWindowSize;
    }
}

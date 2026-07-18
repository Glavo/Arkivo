// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Indicates that a compressed stream requires a decoding window larger than an operation allowed.
@NotNullByDefault
public final class DecompressionWindowLimitException extends DecompressionLimitException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// Creates an exception for one exceeded decoding window limit.
    ///
    /// @param maximumWindowSize the configured non-negative maximum
    /// @param requiredWindowSize the required size greater than {@code maximumWindowSize}
    public DecompressionWindowLimitException(long maximumWindowSize, long requiredWindowSize) {
        super(
                "Required decoding window of " + requiredWindowSize
                        + " bytes exceeds the configured maximum of " + maximumWindowSize + " bytes",
                Kind.WINDOW_SIZE,
                maximumWindowSize,
                requiredWindowSize
        );
    }

    /// Returns the configured maximum decoding window size.
    ///
    /// @return the configured maximum in bytes
    public long maximumWindowSize() {
        return maximum();
    }

    /// Returns the decoding window size required by the format or stream.
    ///
    /// @return the required size in bytes
    public long requiredWindowSize() {
        return actual();
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Indicates that decompression produced more bytes than an operation allowed.
@NotNullByDefault
public final class DecompressionOutputLimitException extends DecompressionLimitException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// Creates an exception when only the first disallowed output byte is known.
    public DecompressionOutputLimitException(long maximumOutputSize) {
        this(maximumOutputSize, firstDisallowedSize(maximumOutputSize));
    }

    /// Creates an exception for an observed decoded output size.
    public DecompressionOutputLimitException(long maximumOutputSize, long actualOutputSize) {
        super(
                "Decompressed output of " + actualOutputSize
                        + " bytes exceeds the configured maximum of " + maximumOutputSize + " bytes",
                Kind.OUTPUT_SIZE,
                maximumOutputSize,
                actualOutputSize
        );
    }

    /// Returns the configured maximum decoded output size.
    public long maximumOutputSize() {
        return maximum();
    }

    /// Returns the observed decoded output size.
    public long actualOutputSize() {
        return actual();
    }

    /// Returns the first representable size that exceeds the given limit.
    private static long firstDisallowedSize(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("maximumOutputSize must not be negative");
        }
        if (value == Long.MAX_VALUE) {
            throw new IllegalArgumentException("maximumOutputSize cannot be Long.MAX_VALUE");
        }
        return value + 1L;
    }
}

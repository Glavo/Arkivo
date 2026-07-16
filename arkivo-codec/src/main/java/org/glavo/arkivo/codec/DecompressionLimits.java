// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines operation-scoped safety limits for decompression.
///
/// A limit equal to `UNLIMITED_SIZE` is not enforced. Limits belong to a decoder operation rather than an immutable
/// codec configuration, so the same codec can safely serve callers with different trust boundaries.
///
/// @param maximumOutputSize the maximum decoded byte count, or `UNLIMITED_SIZE`
/// @param maximumWindowSize the maximum algorithm history-window size, or `UNLIMITED_SIZE`
@NotNullByDefault
public record DecompressionLimits(long maximumOutputSize, long maximumWindowSize) {
    /// The sentinel used for a size that is not limited.
    public static final long UNLIMITED_SIZE = -1L;

    /// A reusable limits value that imposes no output or window restriction.
    public static final DecompressionLimits UNLIMITED =
            new DecompressionLimits(UNLIMITED_SIZE, UNLIMITED_SIZE);

    /// Validates both limit values.
    public DecompressionLimits {
        validateLimit(maximumOutputSize, "maximumOutputSize");
        validateLimit(maximumWindowSize, "maximumWindowSize");
    }

    /// Creates limits containing only a decoded-output bound.
    public static DecompressionLimits ofMaximumOutputSize(long maximumOutputSize) {
        return new DecompressionLimits(maximumOutputSize, UNLIMITED_SIZE);
    }

    /// Creates limits containing only a history-window bound.
    public static DecompressionLimits ofMaximumWindowSize(long maximumWindowSize) {
        return new DecompressionLimits(UNLIMITED_SIZE, maximumWindowSize);
    }

    /// Returns a copy with the requested decoded-output bound.
    public DecompressionLimits withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new DecompressionLimits(maximumOutputSize, maximumWindowSize);
    }

    /// Returns a copy with the requested history-window bound.
    public DecompressionLimits withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new DecompressionLimits(maximumOutputSize, maximumWindowSize);
    }

    /// Rejects a required history window larger than the configured maximum.
    public void requireWindowSize(long requiredWindowSize) throws DecompressionWindowLimitException {
        if (requiredWindowSize < 0L) {
            throw new IllegalArgumentException("requiredWindowSize must not be negative");
        }
        if (maximumWindowSize != UNLIMITED_SIZE && requiredWindowSize > maximumWindowSize) {
            throw new DecompressionWindowLimitException(maximumWindowSize, requiredWindowSize);
        }
    }

    /// Validates a limit or the unlimited sentinel.
    private static void validateLimit(long value, String name) {
        if (value < UNLIMITED_SIZE) {
            throw new IllegalArgumentException(name + " must be non-negative or UNLIMITED_SIZE");
        }
    }
}

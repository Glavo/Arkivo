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
/// @param maximumMemorySize the maximum decoder working-memory size, or `UNLIMITED_SIZE`
@NotNullByDefault
public record DecompressionLimits(
        long maximumOutputSize,
        long maximumWindowSize,
        long maximumMemorySize
) {
    /// The sentinel used for a size that is not limited.
    public static final long UNLIMITED_SIZE = -1L;

    /// A reusable limits value that imposes no output, window, or memory restriction.
    public static final DecompressionLimits UNLIMITED =
            new DecompressionLimits(UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);

    /// Validates every limit value.
    public DecompressionLimits {
        validateLimit(maximumOutputSize, "maximumOutputSize");
        validateLimit(maximumWindowSize, "maximumWindowSize");
        validateLimit(maximumMemorySize, "maximumMemorySize");
    }

    /// Creates limits containing only a decoded-output bound.
    public static DecompressionLimits ofMaximumOutputSize(long maximumOutputSize) {
        return new DecompressionLimits(maximumOutputSize, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates limits containing only a history-window bound.
    public static DecompressionLimits ofMaximumWindowSize(long maximumWindowSize) {
        return new DecompressionLimits(UNLIMITED_SIZE, maximumWindowSize, UNLIMITED_SIZE);
    }

    /// Creates limits containing only a decoder working-memory bound.
    public static DecompressionLimits ofMaximumMemorySize(long maximumMemorySize) {
        return new DecompressionLimits(UNLIMITED_SIZE, UNLIMITED_SIZE, maximumMemorySize);
    }

    /// Returns a copy with the requested decoded-output bound.
    public DecompressionLimits withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new DecompressionLimits(maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns a copy with the requested history-window bound.
    public DecompressionLimits withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new DecompressionLimits(maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns a copy with the requested decoder working-memory bound.
    public DecompressionLimits withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new DecompressionLimits(maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns the effective history-window limit after applying the decoder-memory ceiling.
    ///
    /// History-based decoders whose dominant allocation is their window may use this value directly. Decoders with
    /// additional model memory must enforce [#maximumMemorySize()] separately.
    public long effectiveMaximumWindowSize() {
        if (maximumWindowSize == UNLIMITED_SIZE) {
            return maximumMemorySize;
        }
        if (maximumMemorySize == UNLIMITED_SIZE) {
            return maximumWindowSize;
        }
        return Math.min(maximumWindowSize, maximumMemorySize);
    }

    /// Rejects a required history window larger than the configured maximum.
    public void requireWindowSize(long requiredWindowSize) throws DecompressionWindowLimitException {
        if (requiredWindowSize < 0L) {
            throw new IllegalArgumentException("requiredWindowSize must not be negative");
        }
        long effectiveMaximumWindowSize = effectiveMaximumWindowSize();
        if (effectiveMaximumWindowSize != UNLIMITED_SIZE
                && requiredWindowSize > effectiveMaximumWindowSize) {
            throw new DecompressionWindowLimitException(effectiveMaximumWindowSize, requiredWindowSize);
        }
    }

    /// Rejects a decoder working-memory requirement larger than the configured maximum.
    public void requireMemorySize(long requiredMemorySize) throws DecompressionMemoryLimitException {
        if (requiredMemorySize < 0L) {
            throw new IllegalArgumentException("requiredMemorySize must not be negative");
        }
        if (maximumMemorySize != UNLIMITED_SIZE && requiredMemorySize > maximumMemorySize) {
            throw new DecompressionMemoryLimitException(maximumMemorySize, requiredMemorySize);
        }
    }

    /// Validates a limit or the unlimited sentinel.
    private static void validateLimit(long value, String name) {
        if (value < UNLIMITED_SIZE) {
            throw new IllegalArgumentException(name + " must be non-negative or UNLIMITED_SIZE");
        }
    }
}

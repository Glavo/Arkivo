// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines immutable parameters for one decoding operation.
///
/// Each size field is a safety limit and accepts `UNLIMITED_SIZE` to request no bound. These options belong to one
/// decoder session rather than an immutable codec configuration, so the same codec can serve operations with different
/// trust boundaries. Each codec documents which format structures and allocations it can account for. In particular,
/// `maximumMemorySize` bounds codec-accounted decoder working memory; it is neither a JVM heap limit nor a guarantee
/// that every allocation made while decoding is included.
///
/// @param maximumOutputSize the maximum decoded byte count, or `UNLIMITED_SIZE`
/// @param maximumWindowSize the maximum algorithm history-window size, or `UNLIMITED_SIZE`
/// @param maximumMemorySize the maximum decoder working-memory size, or `UNLIMITED_SIZE`
@NotNullByDefault
public record DecodingOptions(
        long maximumOutputSize,
        long maximumWindowSize,
        long maximumMemorySize
) {
    /// The sentinel used for a size that is not limited.
    public static final long UNLIMITED_SIZE = -1L;

    /// The default options, which impose no output, window, or memory restriction.
    public static final DecodingOptions DEFAULT =
            new DecodingOptions(UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);

    /// Validates all operation parameters.
    ///
    /// @throws IllegalArgumentException if any size is less than [#UNLIMITED_SIZE]
    public DecodingOptions {
        validateLimit(maximumOutputSize, "maximumOutputSize");
        validateLimit(maximumWindowSize, "maximumWindowSize");
        validateLimit(maximumMemorySize, "maximumMemorySize");
    }

    /// Creates options containing only a decoded-output bound.
    ///
    /// @param maximumOutputSize the non-negative decoded byte limit, or [#UNLIMITED_SIZE]
    /// @return options containing only the requested output bound
    /// @throws IllegalArgumentException if `maximumOutputSize` is less than [#UNLIMITED_SIZE]
    public static DecodingOptions ofMaximumOutputSize(long maximumOutputSize) {
        return new DecodingOptions(maximumOutputSize, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates options containing only a history-window bound.
    ///
    /// @param maximumWindowSize the non-negative history-window byte limit, or [#UNLIMITED_SIZE]
    /// @return options containing only the requested window bound
    /// @throws IllegalArgumentException if `maximumWindowSize` is less than [#UNLIMITED_SIZE]
    public static DecodingOptions ofMaximumWindowSize(long maximumWindowSize) {
        return new DecodingOptions(UNLIMITED_SIZE, maximumWindowSize, UNLIMITED_SIZE);
    }

    /// Creates options containing only a decoder working-memory bound.
    ///
    /// @param maximumMemorySize the non-negative working-memory byte limit, or [#UNLIMITED_SIZE]
    /// @return options containing only the requested memory bound
    /// @throws IllegalArgumentException if `maximumMemorySize` is less than [#UNLIMITED_SIZE]
    public static DecodingOptions ofMaximumMemorySize(long maximumMemorySize) {
        return new DecodingOptions(UNLIMITED_SIZE, UNLIMITED_SIZE, maximumMemorySize);
    }

    /// Returns a copy with the requested decoded-output bound.
    ///
    /// @param maximumOutputSize the non-negative decoded byte limit, or [#UNLIMITED_SIZE]
    /// @return this instance when unchanged, otherwise a copy with the requested bound
    /// @throws IllegalArgumentException if `maximumOutputSize` is less than [#UNLIMITED_SIZE]
    public DecodingOptions withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new DecodingOptions(maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns a copy with the requested history-window bound.
    ///
    /// @param maximumWindowSize the non-negative history-window byte limit, or [#UNLIMITED_SIZE]
    /// @return this instance when unchanged, otherwise a copy with the requested bound
    /// @throws IllegalArgumentException if `maximumWindowSize` is less than [#UNLIMITED_SIZE]
    public DecodingOptions withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new DecodingOptions(maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns a copy with the requested decoder working-memory bound.
    ///
    /// @param maximumMemorySize the non-negative working-memory byte limit, or [#UNLIMITED_SIZE]
    /// @return this instance when unchanged, otherwise a copy with the requested bound
    /// @throws IllegalArgumentException if `maximumMemorySize` is less than [#UNLIMITED_SIZE]
    public DecodingOptions withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new DecodingOptions(maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns the effective history-window limit after applying the decoder-memory ceiling.
    ///
    /// History-based decoders whose dominant allocation is their window may use this value directly. Decoders with
    /// additional model memory must enforce [#maximumMemorySize()] separately.
    ///
    /// @return the smaller enforced window or memory limit, or [#UNLIMITED_SIZE] if both are unrestricted
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
    ///
    /// @param requiredWindowSize the non-negative history-window size required by the stream
    /// @throws DecompressionWindowLimitException if the requirement exceeds the effective window limit
    /// @throws IllegalArgumentException          if `requiredWindowSize` is negative
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
    ///
    /// @param requiredMemorySize the non-negative working-memory size required by the decoder
    /// @throws DecompressionMemoryLimitException if the requirement exceeds the memory limit
    /// @throws IllegalArgumentException          if `requiredMemorySize` is negative
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

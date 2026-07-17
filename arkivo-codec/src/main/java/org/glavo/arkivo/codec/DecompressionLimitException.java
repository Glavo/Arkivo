// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Indicates that decompression exceeded an operation-scoped safety limit.
@NotNullByDefault
public abstract sealed class DecompressionLimitException extends IOException
        permits DecompressionMemoryLimitException,
        DecompressionOutputLimitException,
        DecompressionWindowLimitException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// The kind of safety limit that was exceeded.
    private final Kind kind;

    /// The configured maximum value.
    private final long maximum;

    /// The observed or required value that exceeded the maximum.
    private final long actual;

    /// Creates one structured decompression limit failure.
    DecompressionLimitException(String message, Kind kind, long maximum, long actual) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
        if (maximum < 0L) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        if (actual <= maximum) {
            throw new IllegalArgumentException("actual must exceed maximum");
        }
        this.maximum = maximum;
        this.actual = actual;
    }

    /// Returns the kind of safety limit that was exceeded.
    public final Kind kind() {
        return kind;
    }

    /// Returns the configured maximum value.
    public final long maximum() {
        return maximum;
    }

    /// Returns the observed or required value that exceeded the maximum.
    public final long actual() {
        return actual;
    }

    /// Identifies an operation-scoped decompression safety limit.
    @NotNullByDefault
    public enum Kind {
        /// Total decoded output size.
        OUTPUT_SIZE,

        /// Algorithm history-window size.
        WINDOW_SIZE,

        /// Decoder working-memory size.
        MEMORY_SIZE
    }
}

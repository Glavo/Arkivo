// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Reports that archive metadata or decoded entry data exceeded a configured reading limit.
///
/// Observations beyond the range of `long` are reported as [Long#MAX_VALUE]. In particular, equal maximum and actual
/// values of `Long.MAX_VALUE` indicate that the observed count exceeded that value, not that the limit was reached.
@NotNullByDefault
public final class ArkivoReadLimitException extends IOException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// The configured limit that was exceeded.
    private final ArkivoReadLimitKind kind;

    /// The maximum permitted value.
    private final long maximum;

    /// The observed value, saturated at `Long.MAX_VALUE`.
    private final long actual;

    /// The archive-local entry path associated with the failure, or `null` for archive-wide failures.
    private final @Nullable String entryPath;

    /// Creates an archive reading limit failure.
    ///
    /// @param kind      the resource category whose limit was exceeded
    /// @param maximum   the configured non-negative maximum
    /// @param actual    the observed value, saturated at [Long#MAX_VALUE]; it must exceed `maximum` unless both are
    ///                  `Long.MAX_VALUE`, which represents an observation beyond the range of `long`
    /// @param entryPath the affected archive-local entry path, or {@code null} for an archive-wide limit
    /// @throws IllegalArgumentException if `maximum` is negative, or `actual` does not exceed it and the values are
    ///                                  not both `Long.MAX_VALUE`
    public ArkivoReadLimitException(
            ArkivoReadLimitKind kind,
            long maximum,
            long actual,
            @Nullable String entryPath
    ) {
        super(message(kind, maximum, actual, entryPath));
        if (maximum < 0L) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        if (actual <= maximum && !(maximum == Long.MAX_VALUE && actual == Long.MAX_VALUE)) {
            throw new IllegalArgumentException("actual must exceed maximum");
        }
        this.kind = Objects.requireNonNull(kind, "kind");
        this.maximum = maximum;
        this.actual = actual;
        this.entryPath = entryPath;
    }

    /// Returns the configured limit that was exceeded.
    ///
    /// @return the exceeded resource category
    public ArkivoReadLimitKind kind() {
        return kind;
    }

    /// Returns the maximum permitted value.
    ///
    /// @return the configured non-negative maximum
    public long maximum() {
        return maximum;
    }

    /// Returns the observed value, saturated at [Long#MAX_VALUE].
    ///
    /// @return the observed value, which exceeds [#maximum()] unless both values are `Long.MAX_VALUE`
    public long actual() {
        return actual;
    }

    /// Returns the archive-local entry path associated with the failure, or `null` when no entry is applicable.
    ///
    /// @return the affected entry path, or {@code null} for an archive-wide limit
    public @Nullable String entryPath() {
        return entryPath;
    }

    /// Builds the stable diagnostic message for a limit failure.
    private static String message(
            ArkivoReadLimitKind kind,
            long maximum,
            long actual,
            @Nullable String entryPath
    ) {
        Objects.requireNonNull(kind, "kind");
        String subject = switch (kind) {
            case ENTRY_COUNT -> "Archive entry count";
            case ENTRY_SIZE -> "Archive entry size";
            case TOTAL_ENTRY_SIZE -> "Total archive entry size";
            case METADATA_SIZE -> "Archive metadata size";
            case DECODED_ARCHIVE_SIZE -> "Decoded archive size";
            case OUTER_COMPRESSION_LAYERS -> "Outer compression layer count";
        };
        String pathSuffix = entryPath != null ? " for " + entryPath : "";
        String observation = maximum == Long.MAX_VALUE && actual == Long.MAX_VALUE
                ? "greater than " + Long.MAX_VALUE : Long.toString(actual);
        return subject + pathSuffix + " exceeds the configured maximum of " + maximum
                + " (actual " + observation + ")";
    }
}

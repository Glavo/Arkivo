// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Configures one read-only archive operation.
///
/// @param threadSafety the file-system synchronization strategy
/// @param editStorage  the storage used for materialized entry content, or `null` to select the format default
/// @param limits       the resource limits enforced for the whole archive operation
@NotNullByDefault
public record ArchiveReadOptions(
        ArkivoFileSystemThreadSafety threadSafety,
        @Nullable ArkivoEditStorage editStorage,
        ArchiveReadLimits limits
) {
    /// The default read configuration.
    public static final ArchiveReadOptions DEFAULT = new ArchiveReadOptions(
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            ArchiveReadLimits.UNLIMITED
    );

    /// Validates the configuration.
    public ArchiveReadOptions {
        Objects.requireNonNull(threadSafety, "threadSafety");
        Objects.requireNonNull(limits, "limits");
    }

    /// Returns a copy with the requested thread-safety strategy.
    public ArchiveReadOptions withThreadSafety(ArkivoFileSystemThreadSafety value) {
        return new ArchiveReadOptions(value, editStorage, limits);
    }

    /// Returns a copy with the requested edit storage.
    public ArchiveReadOptions withEditStorage(@Nullable ArkivoEditStorage value) {
        return new ArchiveReadOptions(threadSafety, value, limits);
    }

    /// Returns a copy with the requested read limits.
    public ArchiveReadOptions withLimits(ArchiveReadLimits value) {
        return new ArchiveReadOptions(threadSafety, editStorage, value);
    }
}

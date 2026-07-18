// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Configures creation of a new archive.
///
/// @param threadSafety the file-system synchronization strategy
/// @param editStorage  the storage used for new entry content, or `null` to select the format default
@NotNullByDefault
public record ArchiveCreateOptions(
        ArkivoFileSystemThreadSafety threadSafety,
        @Nullable ArkivoEditStorage editStorage
) {
    /// The default creation configuration.
    public static final ArchiveCreateOptions DEFAULT = new ArchiveCreateOptions(
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null
    );

    /// Validates the configuration.
    public ArchiveCreateOptions {
        Objects.requireNonNull(threadSafety, "threadSafety");
    }

    /// Returns a copy with the requested thread-safety strategy.
    ///
    /// @param value the strategy for the returned options
    /// @return a copy with {@code threadSafety} set to {@code value}
    public ArchiveCreateOptions withThreadSafety(ArkivoFileSystemThreadSafety value) {
        return new ArchiveCreateOptions(value, editStorage);
    }

    /// Returns a copy with the requested edit storage.
    ///
    /// @param value the storage for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code editStorage} set to {@code value}
    public ArchiveCreateOptions withEditStorage(@Nullable ArkivoEditStorage value) {
        return new ArchiveCreateOptions(threadSafety, value);
    }
}

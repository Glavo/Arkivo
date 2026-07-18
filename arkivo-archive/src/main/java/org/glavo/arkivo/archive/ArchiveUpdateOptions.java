// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Configures a complete-rewrite update of an existing archive.
///
/// @param threadSafety the file-system synchronization strategy
/// @param editStorage  the storage used for materialized and replacement content, or `null` to select the format default
/// @param commitTarget the publication target, or `null` when the source path is replaced transactionally
/// @param limits       the resource limits enforced while reading the source archive
@NotNullByDefault
public record ArchiveUpdateOptions(
        ArkivoFileSystemThreadSafety threadSafety,
        @Nullable ArkivoEditStorage editStorage,
        @Nullable ArkivoCommitTarget commitTarget,
        ArchiveReadLimits limits
) {
    /// The default update configuration.
    public static final ArchiveUpdateOptions DEFAULT = new ArchiveUpdateOptions(
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            ArchiveReadLimits.UNLIMITED
    );

    /// Validates the configuration.
    public ArchiveUpdateOptions {
        Objects.requireNonNull(threadSafety, "threadSafety");
        Objects.requireNonNull(limits, "limits");
    }

    /// Returns a copy with the requested thread-safety strategy.
    ///
    /// @param value the strategy for the returned options
    /// @return a copy with {@code threadSafety} set to {@code value}
    public ArchiveUpdateOptions withThreadSafety(ArkivoFileSystemThreadSafety value) {
        return new ArchiveUpdateOptions(value, editStorage, commitTarget, limits);
    }

    /// Returns a copy with the requested edit storage.
    ///
    /// @param value the storage for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code editStorage} set to {@code value}
    public ArchiveUpdateOptions withEditStorage(@Nullable ArkivoEditStorage value) {
        return new ArchiveUpdateOptions(threadSafety, value, commitTarget, limits);
    }

    /// Returns a copy with the requested commit target.
    ///
    /// @param value the target for the returned options, or {@code null} to replace the source path
    /// @return a copy with {@code commitTarget} set to {@code value}
    public ArchiveUpdateOptions withCommitTarget(@Nullable ArkivoCommitTarget value) {
        return new ArchiveUpdateOptions(threadSafety, editStorage, value, limits);
    }

    /// Returns a copy with the requested read limits.
    ///
    /// @param value the operation-wide limits for the returned options
    /// @return a copy with {@code limits} set to {@code value}
    public ArchiveUpdateOptions withLimits(ArchiveReadLimits value) {
        return new ArchiveUpdateOptions(threadSafety, editStorage, commitTarget, value);
    }
}

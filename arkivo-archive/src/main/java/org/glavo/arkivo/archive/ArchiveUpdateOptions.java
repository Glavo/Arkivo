// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Configures a complete-rewrite update of an existing archive.
///
/// @param threadSafety the file-system synchronization strategy
/// @param editStorageFactory the factory for operation-owned materialized and replacement content storage, or `null` to
///                           select the format default
/// @param commitTarget the publication target, or `null` when the source path is replaced transactionally
/// @param passwordProvider the provider used to decrypt input or encrypt output, or `null` to disable password lookup
/// @param metadataCharsetDetector the detector for metadata without an authoritative encoding, or `null` to select the
///                                format default
/// @param limits the resource limits enforced while reading the source archive
@NotNullByDefault
public record ArchiveUpdateOptions(
        ArkivoFileSystemThreadSafety threadSafety,
        @Nullable ArkivoEditStorageFactory editStorageFactory,
        @Nullable ArkivoCommitTarget commitTarget,
        @Nullable ArkivoPasswordProvider passwordProvider,
        @Nullable ArchiveMetadataCharsetDetector metadataCharsetDetector,
        ArchiveReadLimits limits
) {
    /// The default update configuration.
    public static final ArchiveUpdateOptions DEFAULT = new ArchiveUpdateOptions(
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            null,
            null,
            ArchiveReadLimits.DEFAULT
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
        return new ArchiveUpdateOptions(
                value, editStorageFactory, commitTarget, passwordProvider, metadataCharsetDetector, limits
        );
    }

    /// Returns a copy with the requested edit-storage factory.
    ///
    /// @param value the factory for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code editStorageFactory} set to {@code value}
    public ArchiveUpdateOptions withEditStorageFactory(@Nullable ArkivoEditStorageFactory value) {
        return new ArchiveUpdateOptions(
                threadSafety, value, commitTarget, passwordProvider, metadataCharsetDetector, limits
        );
    }

    /// Returns a copy with the requested commit target.
    ///
    /// @param value the target for the returned options, or {@code null} to replace the source path
    /// @return a copy with {@code commitTarget} set to {@code value}
    public ArchiveUpdateOptions withCommitTarget(@Nullable ArkivoCommitTarget value) {
        return new ArchiveUpdateOptions(
                threadSafety, editStorageFactory, value, passwordProvider, metadataCharsetDetector, limits
        );
    }

    /// Returns a copy with the requested password provider.
    ///
    /// @param value the provider for the returned options, or {@code null} to disable password lookup
    /// @return a copy with {@code passwordProvider} set to {@code value}
    public ArchiveUpdateOptions withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
        return new ArchiveUpdateOptions(
                threadSafety, editStorageFactory, commitTarget, value, metadataCharsetDetector, limits
        );
    }

    /// Returns a copy with the requested metadata charset detector.
    ///
    /// @param value the detector for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code metadataCharsetDetector} set to {@code value}
    public ArchiveUpdateOptions withMetadataCharsetDetector(@Nullable ArchiveMetadataCharsetDetector value) {
        return new ArchiveUpdateOptions(
                threadSafety, editStorageFactory, commitTarget, passwordProvider, value, limits
        );
    }

    /// Returns a copy with the requested read limits.
    ///
    /// @param value the operation-wide limits for the returned options
    /// @return a copy with {@code limits} set to {@code value}
    public ArchiveUpdateOptions withLimits(ArchiveReadLimits value) {
        return new ArchiveUpdateOptions(
                threadSafety, editStorageFactory, commitTarget, passwordProvider, metadataCharsetDetector, value
        );
    }

    /// Returns the read-only view used to probe and decode the source archive.
    ///
    /// The returned options preserve synchronization, storage, password, metadata-charset, and limit policies. Update
    /// publication is intentionally omitted.
    ///
    /// @return immutable read options equivalent to the source-reading portion of this configuration
    public ArchiveReadOptions readOptions() {
        return new ArchiveReadOptions(
                threadSafety,
                editStorageFactory,
                passwordProvider,
                metadataCharsetDetector,
                limits
        );
    }
}

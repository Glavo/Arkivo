// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Configures one read-only archive operation.
///
/// @param threadSafety the file-system synchronization strategy
/// @param editStorageFactory the factory for operation-owned materialized entry storage, or `null` to select the format
///                           default
/// @param passwordProvider the password provider used by formats that support encryption, or `null` to disable password
///                         lookup
/// @param metadataCharsetDetector the detector for metadata without an authoritative encoding, or `null` to select the
///                                format default
/// @param limits the resource limits enforced for the whole archive operation
@NotNullByDefault
public record ArchiveReadOptions(
        ArkivoFileSystemThreadSafety threadSafety,
        @Nullable ArkivoEditStorageFactory editStorageFactory,
        @Nullable ArkivoPasswordProvider passwordProvider,
        @Nullable ArchiveMetadataCharsetDetector metadataCharsetDetector,
        ArchiveReadLimits limits
) {
    /// The default read configuration.
    public static final ArchiveReadOptions DEFAULT = new ArchiveReadOptions(
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            null,
            ArchiveReadLimits.DEFAULT
    );

    /// Validates the configuration.
    public ArchiveReadOptions {
        Objects.requireNonNull(threadSafety, "threadSafety");
        Objects.requireNonNull(limits, "limits");
    }

    /// Returns a copy with the requested thread-safety strategy.
    ///
    /// @param value the strategy for the returned options
    /// @return a copy with {@code threadSafety} set to {@code value}
    public ArchiveReadOptions withThreadSafety(ArkivoFileSystemThreadSafety value) {
        return new ArchiveReadOptions(value, editStorageFactory, passwordProvider, metadataCharsetDetector, limits);
    }

    /// Returns a copy with the requested edit-storage factory.
    ///
    /// @param value the factory for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code editStorageFactory} set to {@code value}
    public ArchiveReadOptions withEditStorageFactory(@Nullable ArkivoEditStorageFactory value) {
        return new ArchiveReadOptions(threadSafety, value, passwordProvider, metadataCharsetDetector, limits);
    }

    /// Returns a copy with the requested password provider.
    ///
    /// @param value the provider for the returned options, or {@code null} to disable password lookup
    /// @return a copy with {@code passwordProvider} set to {@code value}
    public ArchiveReadOptions withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
        return new ArchiveReadOptions(threadSafety, editStorageFactory, value, metadataCharsetDetector, limits);
    }

    /// Returns a copy with the requested metadata charset detector.
    ///
    /// @param value the detector for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code metadataCharsetDetector} set to {@code value}
    public ArchiveReadOptions withMetadataCharsetDetector(@Nullable ArchiveMetadataCharsetDetector value) {
        return new ArchiveReadOptions(threadSafety, editStorageFactory, passwordProvider, value, limits);
    }

    /// Returns a copy with the requested read limits.
    ///
    /// @param value the operation-wide limits for the returned options
    /// @return a copy with {@code limits} set to {@code value}
    public ArchiveReadOptions withLimits(ArchiveReadLimits value) {
        return new ArchiveReadOptions(threadSafety, editStorageFactory, passwordProvider, metadataCharsetDetector, value);
    }
}

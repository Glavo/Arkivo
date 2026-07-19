// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Configures creation of a new archive.
///
/// @param threadSafety the file-system synchronization strategy
/// @param editStorageFactory the factory for operation-owned new-entry storage, or `null` to select the format default
/// @param passwordProvider the password provider used by formats configured to encrypt output, or `null` to disable
///                         password lookup
/// @param metadataCharsetDetector the detector for metadata without an authoritative encoding, or `null` to select the
///                                format default
@NotNullByDefault
public record ArchiveCreateOptions(
        ArkivoFileSystemThreadSafety threadSafety,
        @Nullable ArkivoEditStorageFactory editStorageFactory,
        @Nullable ArkivoPasswordProvider passwordProvider,
        @Nullable ArchiveMetadataCharsetDetector metadataCharsetDetector
) {
    /// The default creation configuration.
    public static final ArchiveCreateOptions DEFAULT = new ArchiveCreateOptions(
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
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
        return new ArchiveCreateOptions(value, editStorageFactory, passwordProvider, metadataCharsetDetector);
    }

    /// Returns a copy with the requested edit-storage factory.
    ///
    /// @param value the factory for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code editStorageFactory} set to {@code value}
    public ArchiveCreateOptions withEditStorageFactory(@Nullable ArkivoEditStorageFactory value) {
        return new ArchiveCreateOptions(threadSafety, value, passwordProvider, metadataCharsetDetector);
    }

    /// Returns a copy with the requested password provider.
    ///
    /// @param value the provider for the returned options, or {@code null} to disable password lookup
    /// @return a copy with {@code passwordProvider} set to {@code value}
    public ArchiveCreateOptions withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
        return new ArchiveCreateOptions(threadSafety, editStorageFactory, value, metadataCharsetDetector);
    }

    /// Returns a copy with the requested metadata charset detector.
    ///
    /// @param value the detector for the returned options, or {@code null} to select the format default
    /// @return a copy with {@code metadataCharsetDetector} set to {@code value}
    public ArchiveCreateOptions withMetadataCharsetDetector(@Nullable ArchiveMetadataCharsetDetector value) {
        return new ArchiveCreateOptions(threadSafety, editStorageFactory, passwordProvider, value);
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ar.ArArchiveOptions;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Holds typed AR settings after public options or an NIO environment have been resolved.
///
/// @param openOptions the backing archive access mode
/// @param readOptions the metadata, limits, synchronization, and staging settings
/// @param commitTarget the update publication target, or `null` for the default
@NotNullByDefault
public record ArArkivoFileSystemConfig(
        @Unmodifiable Set<OpenOption> openOptions,
        ArchiveReadOptions readOptions,
        @Nullable ArkivoCommitTarget commitTarget
) {
    /// The environment key for legacy metadata decoding.
    private static final ArchiveOption<ArchiveMetadataDecoder> METADATA_DECODER =
            ArchiveEnvironmentOptions.metadataDecoderOption("arkivo.ar.metadataDecoder");

    /// Copies the access options and requires a read configuration.
    public ArArkivoFileSystemConfig {
        openOptions = Set.copyOf(openOptions);
        Objects.requireNonNull(readOptions, "readOptions");
    }

    /// Resolves an NIO environment without retaining its map.
    ///
    /// @param environment the provider environment
    /// @return typed operation settings
    public static ArArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        var options = ArchiveOptions.fromEnvironment(environment);
        var read = new ArchiveReadOptions(
                options.getOrDefault(ArchiveEnvironmentOptions.THREAD_SAFETY, ArkivoFileSystemThreadSafety.CONCURRENT_READ),
                options.get(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY), null, options.get(METADATA_DECODER),
                options.getOrDefault(ArchiveEnvironmentOptions.READ_LIMITS, ArchiveReadLimits.UNLIMITED));
        return new ArArkivoFileSystemConfig(
                options.getOrDefault(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(StandardOpenOption.READ)),
                read, options.get(ArchiveEnvironmentOptions.COMMIT_TARGET));
    }

    /// Resolves a typed read operation.
    ///
    /// @param options the public read settings
    /// @return typed read-only settings
    public static ArArkivoFileSystemConfig fromReadOptions(ArArchiveOptions.Read options) {
        return new ArArkivoFileSystemConfig(Set.of(StandardOpenOption.READ), options.common(), null);
    }

    /// Resolves a typed creation operation.
    ///
    /// @param options the public creation settings
    /// @return typed create-new settings
    public static ArArkivoFileSystemConfig fromCreateOptions(ArArchiveOptions.Create options) {
        var common = options.common();
        var read = new ArchiveReadOptions(common.threadSafety(), common.editStorageFactory(), null,
                null, ArchiveReadLimits.UNLIMITED);
        return new ArArkivoFileSystemConfig(Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                read, null);
    }

    /// Resolves a typed complete-rewrite operation.
    ///
    /// @param options the public update settings
    /// @return typed update settings
    public static ArArkivoFileSystemConfig fromUpdateOptions(ArArchiveOptions.Update options) {
        var common = options.common();
        var read = new ArchiveReadOptions(common.threadSafety(), common.editStorageFactory(), null,
                common.metadataDecoder(), common.limits());
        return new ArArkivoFileSystemConfig(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                read, common.commitTarget());
    }
}

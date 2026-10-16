// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.tar.TarArchiveOptions;
import org.glavo.arkivo.archive.tar.TarCompression;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Holds typed TAR settings after public options or an NIO environment have been resolved.
///
/// @param openOptions the backing archive access mode
/// @param readOptions the metadata, limits, synchronization, and staging settings
/// @param commitTarget the update publication target, or `null` for the default
/// @param compression the read or output compression policy, or `null` for the mode's default
/// @param sourceCompression the update input policy, or `null` for detection
@NotNullByDefault
public record TarArkivoFileSystemConfig(
        @Unmodifiable Set<OpenOption> openOptions,
        ArchiveReadOptions readOptions,
        @Nullable ArkivoCommitTarget commitTarget,
        @Nullable TarCompression compression,
        @Nullable TarCompression sourceCompression
) {
    /// The environment key for legacy metadata decoding.
    private static final ArchiveOption<ArchiveMetadataDecoder> METADATA_DECODER =
            ArchiveEnvironmentOptions.metadataDecoderOption("arkivo.tar.metadataDecoder");

    /// The environment key for read or output compression.
    private static final ArchiveOption<TarCompression> COMPRESSION =
            compressionOption("compression");

    /// The environment key for update input compression.
    private static final ArchiveOption<TarCompression> SOURCE_COMPRESSION =
            compressionOption("sourceCompression");

    /// Copies the access options and requires a read configuration.
    public TarArkivoFileSystemConfig {
        openOptions = Set.copyOf(openOptions);
        Objects.requireNonNull(readOptions, "readOptions");
    }

    /// Resolves an NIO environment without retaining its map.
    ///
    /// @param environment the provider environment
    /// @return typed operation settings
    public static TarArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        var options = ArchiveOptions.fromEnvironment(environment);
        var read = new ArchiveReadOptions(
                options.getOrDefault(ArchiveEnvironmentOptions.THREAD_SAFETY, ArkivoFileSystemThreadSafety.CONCURRENT_READ),
                options.get(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY), null, options.get(METADATA_DECODER),
                options.getOrDefault(ArchiveEnvironmentOptions.READ_LIMITS, ArchiveReadLimits.UNLIMITED));
        return new TarArkivoFileSystemConfig(
                options.getOrDefault(ArchiveEnvironmentOptions.OPEN_OPTIONS, Set.of(StandardOpenOption.READ)),
                read, options.get(ArchiveEnvironmentOptions.COMMIT_TARGET),
                options.get(COMPRESSION), options.get(SOURCE_COMPRESSION));
    }

    /// Resolves a typed read operation.
    ///
    /// @param options the public read settings
    /// @return typed read-only settings
    public static TarArkivoFileSystemConfig fromReadOptions(TarArchiveOptions.Read options) {
        return new TarArkivoFileSystemConfig(Set.of(StandardOpenOption.READ), options.common(), null, options.compression(), null);
    }

    /// Resolves a typed creation operation.
    ///
    /// @param options the public creation settings
    /// @return typed create-new settings
    public static TarArkivoFileSystemConfig fromCreateOptions(TarArchiveOptions.Create options) {
        var common = options.common();
        var read = new ArchiveReadOptions(common.threadSafety(), common.editStorageFactory(), null,
                null, ArchiveReadLimits.UNLIMITED);
        return new TarArkivoFileSystemConfig(Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                read, null, options.compression(), null);
    }

    /// Resolves a typed complete-rewrite operation.
    ///
    /// @param options the public update settings
    /// @return typed update settings
    public static TarArkivoFileSystemConfig fromUpdateOptions(TarArchiveOptions.Update options) {
        var common = options.common();
        var read = new ArchiveReadOptions(common.threadSafety(), common.editStorageFactory(), null,
                common.metadataDecoder(), common.limits());
        return new TarArkivoFileSystemConfig(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                read, common.commitTarget(), options.targetCompression(), options.sourceCompression());
    }

    /// Returns the internal TAR compression-policy option.
    private static ArchiveOption<TarCompression> compressionOption(String name) {
        return ArchiveOption.of(
                "arkivo.tar." + name,
                TarCompression.class,
                value -> {
                    if (value instanceof TarCompression compression) {
                        return compression;
                    }
                    if (value instanceof CompressionCodec<?> codec) {
                        return TarCompression.using(codec);
                    }
                    if (value instanceof String formatName) {
                        return TarCompression.using(CompressionFormats.require(formatName).defaultCodec());
                    }
                    throw new IllegalArgumentException(
                            "Expected TarCompression, CompressionCodec, or String for key: arkivo.tar." + name
                    );
                }
        );
    }

}

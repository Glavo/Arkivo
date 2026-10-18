// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipEntryNameErrorAction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Holds validated ZIP settings after typed options or an NIO environment have been resolved.
///
/// @param openOptions the normalized backing archive access mode; copied during construction
/// @param passwordProvider the entry password provider, or `null` when unavailable
/// @param defaultEncryption the encryption for new entries without an explicit override
/// @param splitSize the maximum output volume size, or [#NO_SPLIT_SIZE]
/// @param legacyMetadataDecoder the decoder for names without Unicode metadata
/// @param threadSafety the file-system synchronization strategy
/// @param editStorageFactory the staging-storage factory, or `null` to select a default
/// @param commitTarget the publication target override, or `null` to select a default
/// @param readLimits the resource limits for the archive read portion of the operation
/// @param entryNameErrorAction the name-decoding error action; must be `REPORT` for writable archives
@NotNullByDefault
public record ZipArkivoFileSystemConfig(
        @Unmodifiable Set<OpenOption> openOptions,
        @Nullable ArkivoPasswordProvider passwordProvider,
        ZipEncryption defaultEncryption,
        long splitSize,
        ArchiveMetadataDecoder legacyMetadataDecoder,
        ArkivoFileSystemThreadSafety threadSafety,
        @Nullable ArkivoEditStorageFactory editStorageFactory,
        @Nullable ArkivoCommitTarget commitTarget,
        ArchiveReadLimits readLimits,
        ZipEntryNameErrorAction entryNameErrorAction
) {
    /// The NIO environment key for read-only entry-name error handling.
    private static final ArchiveOption<ZipEntryNameErrorAction> ENTRY_NAME_ERROR_ACTION =
            ArchiveOption.of("arkivo.zip.entryNameErrorAction", ZipEntryNameErrorAction.class);

    /// The NIO environment key for a password provider.
    private static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.zip.passwordProvider", ArkivoPasswordProvider.class);

    /// The NIO environment key for default entry encryption.
    private static final ArchiveOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArchiveOption.of(
                    "arkivo.zip.defaultEncryption",
                    ZipArkivoFileSystemConfig::encryptionValue
            );

    /// The NIO environment key for split output size.
    private static final ArchiveOption<Long> SPLIT_SIZE =
            ArchiveOption.of("arkivo.zip.splitSize", ZipArkivoFileSystemConfig::longValue);

    /// The NIO environment key for legacy metadata decoding.
    private static final ArchiveOption<ArchiveMetadataDecoder> LEGACY_METADATA_DECODER =
            ArchiveEnvironmentOptions.metadataDecoderOption(
                    "arkivo.zip.legacyMetadataDecoder"
            );

    /// The split size value used when split output is disabled.
    public static final long NO_SPLIT_SIZE = -1L;

    /// The default open options used by read-only ZIP file systems.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_READ_OPEN_OPTIONS =
            Set.of(StandardOpenOption.READ);

    /// The default open options used by streaming ZIP writers opened from paths.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_WRITE_OPEN_OPTIONS =
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

    /// The open options used by explicit complete-rewrite volume updates.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_UPDATE_OPEN_OPTIONS =
            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE);

    /// The default parsed ZIP file system configuration.
    public static final ZipArkivoFileSystemConfig DEFAULTS = new ZipArkivoFileSystemConfig(
            DEFAULT_READ_OPEN_OPTIONS,
            null,
            ZipEncryption.NONE,
            NO_SPLIT_SIZE,
            ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER,
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            ArchiveReadLimits.UNLIMITED,
            ZipEntryNameErrorAction.REPORT
    );

    /// Copies and normalizes the access options, and validates the output and decoding settings.
    ///
    /// @throws NullPointerException if a non-nullable component or an open-option element is `null`
    /// @throws IllegalArgumentException if the open options conflict, `splitSize` is outside the ZIP limits,
    /// or name-decoding recovery is requested for a writable archive
    public ZipArkivoFileSystemConfig {
        openOptions = normalizeOpenOptions(openOptions);
        if (splitSize != NO_SPLIT_SIZE
                && (splitSize < ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
                || splitSize > ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE)) {
            throw new IllegalArgumentException(
                    "splitSize must be NO_SPLIT_SIZE or between MINIMUM_SPLIT_SIZE and MAXIMUM_SPLIT_SIZE"
            );
        }
        Objects.requireNonNull(entryNameErrorAction, "entryNameErrorAction");
        if (entryNameErrorAction != ZipEntryNameErrorAction.REPORT
                && openOptions.contains(StandardOpenOption.WRITE)) {
            throw new IllegalArgumentException("Entry-name recovery is only supported for read-only ZIP operations");
        }
        Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        Objects.requireNonNull(legacyMetadataDecoder, "legacyMetadataDecoder");
        Objects.requireNonNull(threadSafety, "threadSafety");
        Objects.requireNonNull(readLimits, "readLimits");
    }

    /// Parses ZIP file system configuration from archive options.
    ///
    /// @param options the generic archive options
    /// @return normalized read-oriented ZIP configuration
    /// @throws NullPointerException if `options` is `null`
    /// @throws IllegalArgumentException if an option value is invalid or the open options conflict
    public static ZipArkivoFileSystemConfig fromOptions(ArchiveOptions options) {
        Objects.requireNonNull(options, "options");

        if (options.isEmpty()) {
            return DEFAULTS;
        }

        @Nullable ArkivoPasswordProvider passwordProvider = options.get(PASSWORD_PROVIDER);
        Set<OpenOption> openOptions = options.getOrDefault(ArchiveEnvironmentOptions.OPEN_OPTIONS, DEFAULT_READ_OPEN_OPTIONS);
        ZipEncryption defaultEncryption =
                options.getOrDefault(DEFAULT_ENCRYPTION, ZipEncryption.NONE);
        long splitSize = options.getOrDefault(SPLIT_SIZE, NO_SPLIT_SIZE);
        ArchiveMetadataDecoder legacyMetadataDecoder =
                options.getOrDefault(
                        LEGACY_METADATA_DECODER,
                        ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER
                );
        ArkivoFileSystemThreadSafety threadSafety =
                options.getOrDefault(
                        ArchiveEnvironmentOptions.THREAD_SAFETY,
                        ArkivoFileSystemThreadSafety.CONCURRENT_READ
                );
        @Nullable ArkivoEditStorageFactory editStorageFactory =
                options.get(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY);
        @Nullable ArkivoCommitTarget commitTarget = options.get(ArchiveEnvironmentOptions.COMMIT_TARGET);

        ArchiveReadLimits readLimits =
                options.getOrDefault(ArchiveEnvironmentOptions.READ_LIMITS, ArchiveReadLimits.UNLIMITED);
        ZipEntryNameErrorAction errorAction = options.getOrDefault(ENTRY_NAME_ERROR_ACTION, ZipEntryNameErrorAction.REPORT);
        return new ZipArkivoFileSystemConfig(
                openOptions,
                passwordProvider,
                defaultEncryption,
                splitSize,
                legacyMetadataDecoder,
                threadSafety,
                editStorageFactory,
                commitTarget,
                readLimits,
                errorAction
        );
    }

    /// Creates ZIP configuration from a strongly typed read operation.
    ///
    /// @param options the strongly typed ZIP read options
    /// @return normalized read-only file-system configuration
    /// @throws NullPointerException if `options` is `null`
    public static ZipArkivoFileSystemConfig fromReadOptions(ZipArchiveOptions.Read options) {
        Objects.requireNonNull(options, "options");
        return new ZipArkivoFileSystemConfig(
                DEFAULT_READ_OPEN_OPTIONS,
                options.passwordProvider(),
                ZipEncryption.NONE,
                NO_SPLIT_SIZE,
                options.legacyMetadataDecoder(),
                options.common().threadSafety(),
                options.common().editStorageFactory(),
                null,
                options.common().limits(),
                options.entryNameErrorAction()
        );
    }

    /// Creates ZIP configuration from a strongly typed creation operation.
    ///
    /// @param options the strongly typed ZIP creation options
    /// @return normalized forward-write file-system configuration
    /// @throws NullPointerException if `options` is `null`
    public static ZipArkivoFileSystemConfig fromCreateOptions(ZipArchiveOptions.Create options) {
        Objects.requireNonNull(options, "options");
        return new ZipArkivoFileSystemConfig(
                DEFAULT_WRITE_OPEN_OPTIONS,
                options.passwordProvider(),
                options.defaultEncryption(),
                NO_SPLIT_SIZE,
                ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER,
                options.common().threadSafety(),
                options.common().editStorageFactory(),
                null,
                ArchiveReadLimits.UNLIMITED,
                ZipEntryNameErrorAction.REPORT
        );
    }

    /// Creates ZIP configuration from a strongly typed update operation.
    ///
    /// @param options the strongly typed ZIP update options
    /// @return normalized complete-rewrite update configuration
    /// @throws NullPointerException if `options` is `null`
    public static ZipArkivoFileSystemConfig fromUpdateOptions(ZipArchiveOptions.Update options) {
        Objects.requireNonNull(options, "options");
        return new ZipArkivoFileSystemConfig(
                DEFAULT_UPDATE_OPEN_OPTIONS,
                options.passwordProvider(),
                options.defaultEncryption(),
                NO_SPLIT_SIZE,
                options.legacyMetadataDecoder(),
                options.common().threadSafety(),
                options.common().editStorageFactory(),
                options.common().commitTarget(),
                options.common().limits(),
                ZipEntryNameErrorAction.REPORT
        );
    }

    /// Returns whether the normalized access options include [StandardOpenOption#WRITE].
    ///
    /// @return `true` for both creation and update modes
    public boolean archiveWritable() {
        return openOptions.contains(StandardOpenOption.WRITE);
    }

    /// Converts a raw NIO encryption value.
    private static ZipEncryption encryptionValue(Object value) {
        if (value instanceof ZipEncryption encryption) {
            return encryption;
        }
        if (value instanceof String name) {
            return ZipEncryption.parse(name);
        }
        throw new IllegalArgumentException("Expected ZipEncryption or String for key: arkivo.zip.defaultEncryption");
    }

    /// Converts a raw NIO integral value.
    private static Long longValue(Object value) {
        if (value instanceof Byte number) {
            return number.longValue();
        }
        if (value instanceof Short number) {
            return number.longValue();
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Expected integral value for key: arkivo.zip.splitSize", exception);
            }
        }
        throw new IllegalArgumentException("Expected integral value for key: arkivo.zip.splitSize");
    }

    /// Normalizes and validates open options.
    private static @Unmodifiable Set<OpenOption> normalizeOpenOptions(Set<? extends OpenOption> options) {
        Objects.requireNonNull(options, "options");
        LinkedHashSet<OpenOption> result = new LinkedHashSet<>();
        for (OpenOption option : options) {
            result.add(Objects.requireNonNull(option, "option"));
        }

        boolean read = result.contains(StandardOpenOption.READ);
        boolean write = result.contains(StandardOpenOption.WRITE);
        boolean append = result.contains(StandardOpenOption.APPEND);
        boolean create = result.contains(StandardOpenOption.CREATE);
        boolean createNew = result.contains(StandardOpenOption.CREATE_NEW);
        boolean truncate = result.contains(StandardOpenOption.TRUNCATE_EXISTING);

        if (!read && !write && !append && !create && !createNew && !truncate) {
            result.add(StandardOpenOption.READ);
            read = true;
        }

        if (append && truncate) {
            throw new IllegalArgumentException("ZIP archive write open options cannot include both APPEND and TRUNCATE_EXISTING");
        }

        if (read && write) {
            result.remove(StandardOpenOption.READ);
            read = false;
            if (!append && !truncate && !createNew) {
                result.add(StandardOpenOption.APPEND);
                append = true;
            }
        }

        if (read) {
            if (append || create || createNew || truncate) {
                throw new IllegalArgumentException(
                        "ZIP archive read open options cannot include write, append, create, or truncate options"
                );
            }
            return Set.copyOf(result);
        }

        if (!write) {
            throw new IllegalArgumentException("ZIP archive write open options must include WRITE");
        }
        if (!append && !truncate && !createNew) {
            throw new IllegalArgumentException(
                    "ZIP archive write open options must include APPEND, TRUNCATE_EXISTING, or CREATE_NEW"
            );
        }
        return Set.copyOf(result);
    }
}

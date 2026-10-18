// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompressionMethod;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Stores parsed 7z file system configuration.
@NotNullByDefault
public final class SevenZipArkivoFileSystemConfig {
    /// The NIO environment key for a password provider.
    private static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.7z.passwordProvider", ArkivoPasswordProvider.class);

    /// The NIO environment key for compression.
    private static final ArchiveOption<SevenZipCompression> COMPRESSION =
            ArchiveOption.of(
                    "arkivo.7z.compression",
                    SevenZipArkivoFileSystemConfig::compressionOptionValue
            );

    /// The NIO environment key for one filter.
    private static final ArchiveOption<SevenZipFilter> FILTER =
            ArchiveOption.of(
                    "arkivo.7z.filter",
                    SevenZipArkivoFileSystemConfig::filterOptionValue
            );

    /// The NIO environment key for a filter chain.
    private static final ArchiveOption<SevenZipFilterChain> FILTERS =
            ArchiveOption.of(
                    "arkivo.7z.filters",
                    SevenZipArkivoFileSystemConfig::filterChainOptionValue
            );

    /// The NIO environment key for solid grouping.
    private static final ArchiveOption<Integer> SOLID_FILE_COUNT =
            ArchiveOption.of(
                    "arkivo.7z.solidFileCount",
                    SevenZipArkivoFileSystemConfig::solidFileCountOptionValue
            );

    /// The NIO environment key for split output.
    private static final ArchiveOption<Long> SPLIT_SIZE =
            ArchiveOption.of(
                    "arkivo.7z.splitSize",
                    SevenZipArkivoFileSystemConfig::splitSizeOptionValue
            );

    /// The NIO environment key for header encryption.
    private static final ArchiveOption<Boolean> ENCRYPT_HEADERS =
            ArchiveOption.of(
                    "arkivo.7z.encryptHeaders",
                    SevenZipArkivoFileSystemConfig::booleanOptionValue
            );

    /// The split size value used when split output is disabled.
    public static final long NO_SPLIT_SIZE = -1L;

    /// The default maximum number of non-empty files in one 7z folder.
    public static final int DEFAULT_SOLID_FILE_COUNT = 1;

    /// The default open options used by read-only 7z file systems.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_READ_OPEN_OPTIONS =
            Set.of(StandardOpenOption.READ);

    /// The default open options used by explicit 7z output factories.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_WRITE_OPEN_OPTIONS =
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

    /// The default parsed 7z file system configuration.
    public static final SevenZipArkivoFileSystemConfig DEFAULTS = new SevenZipArkivoFileSystemConfig(
            DEFAULT_READ_OPEN_OPTIONS,
            null,
            SevenZipCompression.copy(),
            SevenZipFilterChain.EMPTY,
            DEFAULT_SOLID_FILE_COUNT,
            NO_SPLIT_SIZE,
            false,
            false,
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            ArchiveReadLimits.UNLIMITED
    );


    /// The open options used to open the backing archive path.
    private final @Unmodifiable Set<OpenOption> openOptions;

    /// The provider used to decrypt encrypted 7z content and metadata or encrypt newly written content.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The default compression applied to non-empty output entries.
    private final SevenZipCompression compression;

    /// The preprocessing filters applied in order before default output compression.
    private final SevenZipFilterChain filters;

    /// The maximum number of non-empty files encoded into one solid folder.
    private final int solidFileCount;

    /// The maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    private final long splitSize;

    /// Whether the split size was explicitly supplied rather than inherited.
    private final boolean splitSizeConfigured;

    /// Whether new 7z archives should encrypt metadata headers.
    private final boolean encryptHeaders;

    /// The requested 7z file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// The factory for storage used to stage decoded random-read and update entry bodies, or `null` for defaults.
    private final @Nullable ArkivoEditStorageFactory editStorageFactory;

    /// The target used to publish a rewritten single-volume update, or `null` for default publication.
    private final @Nullable ArkivoCommitTarget commitTarget;

    /// The resource limits for the archive read portion of this operation.
    private final ArchiveReadLimits readLimits;

    /// Creates parsed 7z file system configuration with update staging and publication settings.
    private SevenZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            SevenZipCompression compression,
            SevenZipFilterChain filters,
            int solidFileCount,
            long splitSize,
            boolean splitSizeConfigured,
            boolean encryptHeaders,
            ArkivoFileSystemThreadSafety threadSafety,
            @Nullable ArkivoEditStorageFactory editStorageFactory,
            @Nullable ArkivoCommitTarget commitTarget,
            ArchiveReadLimits readLimits
    ) {
        if (solidFileCount <= 0) {
            throw new IllegalArgumentException("solidFileCount must be positive");
        }
        if (splitSize != NO_SPLIT_SIZE && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive or NO_SPLIT_SIZE");
        }
        this.openOptions = normalizeOpenOptions(Objects.requireNonNull(openOptions, "openOptions"));
        this.passwordProvider = passwordProvider;
        this.compression = Objects.requireNonNull(compression, "compression");
        this.filters = Objects.requireNonNull(filters, "filters");
        this.solidFileCount = solidFileCount;
        this.splitSize = splitSize;
        this.splitSizeConfigured = splitSizeConfigured;
        this.encryptHeaders = encryptHeaders;
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
        this.editStorageFactory = editStorageFactory;
        this.commitTarget = commitTarget;
        this.readLimits = Objects.requireNonNull(readLimits, "readLimits");
    }


    /// Parses 7z file system configuration from archive options.
    ///
    /// @param options the generic options, interpreted with read-only access as the default
    /// @return the validated 7z configuration, or `DEFAULTS` when no options override the read defaults
    /// @throws IllegalArgumentException if mutually exclusive options are present or an option is invalid for the
    ///                                  selected access mode
    public static SevenZipArkivoFileSystemConfig fromOptions(ArchiveOptions options) {
        Objects.requireNonNull(options, "options");

        if (options.isEmpty()) {
            return DEFAULTS;
        }

        if (options.contains(FILTER)
                && options.contains(FILTERS)) {
            throw new IllegalArgumentException("7z filter and filters options are mutually exclusive");
        }
        ArchiveReadLimits readLimits =
                options.getOrDefault(ArchiveEnvironmentOptions.READ_LIMITS, ArchiveReadLimits.UNLIMITED);
        SevenZipArkivoFileSystemConfig config = new SevenZipArkivoFileSystemConfig(
                options.getOrDefault(ArchiveEnvironmentOptions.OPEN_OPTIONS, DEFAULT_READ_OPEN_OPTIONS),
                options.get(PASSWORD_PROVIDER),
                options.getOrDefault(COMPRESSION, SevenZipCompression.copy()),
                filters(options),
                options.getOrDefault(SOLID_FILE_COUNT, DEFAULT_SOLID_FILE_COUNT),
                options.getOrDefault(SPLIT_SIZE, NO_SPLIT_SIZE),
                options.contains(SPLIT_SIZE),
                options.getOrDefault(ENCRYPT_HEADERS, false),
                options.getOrDefault(ArchiveEnvironmentOptions.THREAD_SAFETY, ArkivoFileSystemThreadSafety.CONCURRENT_READ),
                options.get(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY),
                options.get(ArchiveEnvironmentOptions.COMMIT_TARGET),
                readLimits
        );
        if (!config.archiveWritable() && options.contains(COMPRESSION)) {
            throw new IllegalArgumentException("7z compression requires write archive options");
        }
        if (!config.archiveWritable()
                && (options.contains(FILTER)
                || options.contains(FILTERS))) {
            throw new IllegalArgumentException("7z filters require write archive options");
        }
        if (!config.archiveWritable() && options.contains(SOLID_FILE_COUNT)) {
            throw new IllegalArgumentException("7z solid output requires write archive options");
        }
        if (!config.archiveWritable() && options.contains(ENCRYPT_HEADERS)) {
            throw new IllegalArgumentException("7z encrypted headers require write archive options");
        }
        if (!config.archiveUpdate() && config.commitTarget() != null) {
            throw new IllegalArgumentException("7z commit targets require read/write update mode");
        }
        if (config.archiveWritable() && !config.archiveUpdate() && config.editStorageFactory() != null) {
            throw new IllegalArgumentException("7z edit storage is unavailable in forward-only write mode");
        }
        if (config.archiveUpdate()
                && config.splitSize() != NO_SPLIT_SIZE
                && config.commitTarget() != null) {
            throw new IllegalArgumentException("7z split updates do not support single-file commit targets");
        }
        return config;
    }

    /// Creates 7z configuration from a strongly typed read operation.
    ///
    /// @param options the password, storage, limits, and thread-safety settings for the read operation
    /// @return a read-only configuration using the standard archive path open options
    public static SevenZipArkivoFileSystemConfig fromReadOptions(SevenZipArchiveOptions.Read options) {
        Objects.requireNonNull(options, "options");
        return new SevenZipArkivoFileSystemConfig(
                DEFAULT_READ_OPEN_OPTIONS,
                options.passwordProvider(),
                SevenZipCompression.copy(),
                SevenZipFilterChain.EMPTY,
                DEFAULT_SOLID_FILE_COUNT,
                NO_SPLIT_SIZE,
                false,
                false,
                options.common().threadSafety(),
                options.common().editStorageFactory(),
                null,
                options.common().limits()
        );
    }

    /// Creates 7z configuration from a strongly typed creation operation.
    ///
    /// @param options the encoding, storage, and thread-safety settings for the creation operation
    /// @return a single-volume create-or-replace configuration
    public static SevenZipArkivoFileSystemConfig fromCreateOptions(SevenZipArchiveOptions.Create options) {
        return fromCreateOptions(options, NO_SPLIT_SIZE, false);
    }

    /// Creates 7z configuration from a strongly typed creation operation with explicit split output.
    ///
    /// @param options the encoding, storage, and thread-safety settings for the creation operation
    /// @param splitSize the positive output-volume limit, or `NO_SPLIT_SIZE` to disable splitting
    /// @return a create-or-replace configuration that records the split-size choice as explicit
    /// @throws IllegalArgumentException if `splitSize` is neither positive nor `NO_SPLIT_SIZE`
    public static SevenZipArkivoFileSystemConfig fromCreateOptions(
            SevenZipArchiveOptions.Create options,
            long splitSize
    ) {
        return fromCreateOptions(options, splitSize, true);
    }

    /// Creates 7z configuration from strongly typed creation settings and split provenance.
    private static SevenZipArkivoFileSystemConfig fromCreateOptions(
            SevenZipArchiveOptions.Create options,
            long splitSize,
            boolean splitSizeConfigured
    ) {
        Objects.requireNonNull(options, "options");
        return new SevenZipArkivoFileSystemConfig(
                DEFAULT_WRITE_OPEN_OPTIONS,
                options.passwordProvider(),
                options.compression(),
                options.filters(),
                options.solidFileCount(),
                splitSize,
                splitSizeConfigured,
                options.encryptHeaders(),
                options.common().threadSafety(),
                options.common().editStorageFactory(),
                null,
                ArchiveReadLimits.UNLIMITED
        );
    }

    /// Creates 7z configuration from a strongly typed update operation.
    ///
    /// @param options the decoding, encoding, staging, publication, limits, and thread-safety settings for the update
    /// @return a single-volume complete-rewrite update configuration
    public static SevenZipArkivoFileSystemConfig fromUpdateOptions(SevenZipArchiveOptions.Update options) {
        return fromUpdateOptions(options, NO_SPLIT_SIZE, false);
    }

    /// Creates 7z configuration from a strongly typed update operation with explicit split output.
    ///
    /// @param options the decoding, encoding, staging, publication, limits, and thread-safety settings for the update
    /// @param splitSize the positive output-volume limit, or `NO_SPLIT_SIZE` to disable splitting
    /// @return a complete-rewrite update configuration that records the split-size choice as explicit
    /// @throws IllegalArgumentException if `splitSize` is neither positive nor `NO_SPLIT_SIZE`
    public static SevenZipArkivoFileSystemConfig fromUpdateOptions(
            SevenZipArchiveOptions.Update options,
            long splitSize
    ) {
        return fromUpdateOptions(options, splitSize, true);
    }

    /// Creates 7z configuration from strongly typed update settings and split provenance.
    private static SevenZipArkivoFileSystemConfig fromUpdateOptions(
            SevenZipArchiveOptions.Update options,
            long splitSize,
            boolean splitSizeConfigured
    ) {
        Objects.requireNonNull(options, "options");
        return new SevenZipArkivoFileSystemConfig(
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                options.passwordProvider(),
                options.compression(),
                options.filters(),
                options.solidFileCount(),
                splitSize,
                splitSizeConfigured,
                options.encryptHeaders(),
                options.common().threadSafety(),
                options.common().editStorageFactory(),
                options.common().commitTarget(),
                options.common().limits()
        );
    }


    /// Returns the open options used to open the backing archive path.
    ///
    /// @return the immutable, normalized archive-path open options
    public @Unmodifiable Set<OpenOption> openOptions() {
        return openOptions;
    }


    /// Returns whether the archive file should be opened for writes.
    ///
    /// @return `true` when the normalized open options include `StandardOpenOption.WRITE`
    public boolean archiveWritable() {
        return openOptions.contains(StandardOpenOption.WRITE);
    }


    /// Returns whether the archive should be opened for complete-rewrite updates.
    ///
    /// @return `true` when the normalized open options include both read and write access
    public boolean archiveUpdate() {
        return openOptions.contains(StandardOpenOption.READ)
                && openOptions.contains(StandardOpenOption.WRITE);
    }


    /// Returns the provider used to decrypt encrypted 7z content and metadata or encrypt newly written content.
    ///
    /// @return the password provider, or `null` when none was configured
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }


    /// Returns the default compression applied to non-empty output entries.
    ///
    /// @return the output compression configuration
    public SevenZipCompression compression() {
        return compression;
    }


    /// Returns the preprocessing filters applied in order before default output compression.
    ///
    /// @return the ordered output filter chain
    public SevenZipFilterChain filters() {
        return filters;
    }


    /// Returns the maximum number of non-empty files encoded into one solid folder.
    ///
    /// @return the positive solid-folder file limit
    public int solidFileCount() {
        return solidFileCount;
    }


    /// Returns the maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    ///
    /// @return the positive volume-size limit, or `NO_SPLIT_SIZE`
    public long splitSize() {
        return splitSize;
    }


    /// Returns whether the split size was explicitly configured.
    ///
    /// @return `true` when the caller supplied the split-size choice rather than inheriting it
    public boolean splitSizeConfigured() {
        return splitSizeConfigured;
    }


    /// Returns whether new 7z archives should encrypt metadata headers.
    ///
    /// @return `true` when output metadata headers are encrypted
    public boolean encryptHeaders() {
        return encryptHeaders;
    }


    /// Returns the requested 7z file system thread-safety strategy.
    ///
    /// @return the synchronization strategy for file-system operations
    public ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }


    /// Returns the configured update entry-storage factory, or `null` for default temporary-file storage.
    ///
    /// @return the caller-supplied staging-storage factory, or `null` to use temporary files
    public @Nullable ArkivoEditStorageFactory editStorageFactory() {
        return editStorageFactory;
    }


    /// Returns the configured single-volume update commit target.
    ///
    /// @return the caller-supplied publication target, or `null` to replace the source path by default
    public @Nullable ArkivoCommitTarget commitTarget() {
        return commitTarget;
    }


    /// Returns all resource limits for the archive read portion of this operation.
    ///
    /// @return the complete immutable read-limit policy
    public ArchiveReadLimits readLimits() {
        return readLimits;
    }

    /// Converts a raw split-size environment value.
    private static Long splitSizeOptionValue(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof String stringValue) {
            return Long.parseLong(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected Long, compatible integral number, or String for key: " + SPLIT_SIZE.key()
        );
    }

    /// Converts a raw compression environment value.
    private static SevenZipCompression compressionOptionValue(Object value) {
        if (value instanceof SevenZipCompression compression) {
            return compression;
        }
        if (value instanceof SevenZipCompressionMethod method) {
            return SevenZipCompression.of(method);
        }
        if (value instanceof String stringValue) {
            return SevenZipCompression.of(SevenZipCompressionMethod.parse(stringValue));
        }
        throw new IllegalArgumentException(
                "Expected SevenZipCompression, SevenZipCompressionMethod, or String for key: " + COMPRESSION.key()
        );
    }

    /// Converts a raw preprocessing-filter environment value.
    private static SevenZipFilter filterOptionValue(Object value) {
        if (value instanceof SevenZipFilter filter) {
            return filter;
        }
        if (value instanceof SevenZipFilterMethod method) {
            return SevenZipFilter.of(method);
        }
        if (value instanceof String stringValue) {
            return SevenZipFilter.of(SevenZipFilterMethod.parse(stringValue));
        }
        throw new IllegalArgumentException(
                "Expected SevenZipFilter, SevenZipFilterMethod, or String for key: " + FILTER.key()
        );
    }

    /// Converts a raw preprocessing-filter-chain environment value.
    private static SevenZipFilterChain filterChainOptionValue(Object value) {
        if (value instanceof SevenZipFilterChain chain) {
            return chain;
        }
        if (value instanceof Iterable<?> values) {
            ArrayList<SevenZipFilter> filters = new ArrayList<>();
            for (Object item : values) {
                if (!(item instanceof SevenZipFilter filter)) {
                    throw new IllegalArgumentException(
                            "Expected only SevenZipFilter values for key: " + FILTERS.key()
                    );
                }
                filters.add(filter);
            }
            return filters.isEmpty() ? SevenZipFilterChain.EMPTY : new SevenZipFilterChain(filters);
        }
        return SevenZipFilterChain.of(filterOptionValue(value));
    }

    /// Converts and validates a raw solid-file-count environment value.
    private static Integer solidFileCountOptionValue(Object value) {
        int count;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            count = ((Number) value).intValue();
        } else if (value instanceof Long longValue) {
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("7z solid file count is outside the Integer range");
            }
            count = longValue.intValue();
        } else if (value instanceof String stringValue) {
            count = Integer.parseInt(stringValue);
        } else {
            throw new IllegalArgumentException(
                    "Expected Integer, compatible integral number, or String for key: " + SOLID_FILE_COUNT.key()
            );
        }
        if (count <= 0) {
            throw new IllegalArgumentException("7z solid file count must be positive");
        }
        return count;
    }

    /// Converts a raw encrypted-header environment value.
    private static Boolean booleanOptionValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        throw new IllegalArgumentException("Expected Boolean or String for key: " + ENCRYPT_HEADERS.key());
    }

    /// Parses the configured default filter chain.
    private static SevenZipFilterChain filters(ArchiveOptions options) {
        SevenZipFilterChain chain = options.get(FILTERS);
        if (chain != null) {
            return chain;
        }
        SevenZipFilter filter = options.get(FILTER);
        return filter != null ? SevenZipFilterChain.of(filter) : SevenZipFilterChain.EMPTY;
    }


    /// Normalizes and validates open options.
    private static @Unmodifiable Set<OpenOption> normalizeOpenOptions(Set<? extends OpenOption> options) {
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
        if (append) {
            throw new UnsupportedOperationException("7z archive writes do not support APPEND");
        }
        if (read && write) {
            if (truncate || createNew) {
                throw new UnsupportedOperationException(
                        "7z update mode does not support TRUNCATE_EXISTING or CREATE_NEW"
                );
            }
            for (OpenOption option : result) {
                if (option != StandardOpenOption.READ
                        && option != StandardOpenOption.WRITE
                        && option != StandardOpenOption.CREATE) {
                    throw new UnsupportedOperationException("Unsupported 7z archive update option: " + option);
                }
            }
        } else {
            if (read && (create || createNew || truncate)) {
                throw new IllegalArgumentException("7z archive creation options require WRITE");
            }
            if (write || create || createNew || truncate) {
                if (!write) {
                    throw new IllegalArgumentException("7z archive write mode requires WRITE");
                }
                if (!truncate && !createNew) {
                    throw new UnsupportedOperationException(
                            "7z archive write mode requires TRUNCATE_EXISTING or CREATE_NEW"
                    );
                }
                for (OpenOption option : result) {
                    if (option != StandardOpenOption.WRITE
                            && option != StandardOpenOption.CREATE
                            && option != StandardOpenOption.CREATE_NEW
                            && option != StandardOpenOption.TRUNCATE_EXISTING) {
                        throw new UnsupportedOperationException("Unsupported 7z archive write option: " + option);
                    }
                }
            }
        }
        return Set.copyOf(result);
    }
}

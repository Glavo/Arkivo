// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoCommitTarget;
import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.sevenzip.SevenZipCompression;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.sevenzip.SevenZipFilter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Stores parsed 7z file system configuration.
@NotNullByDefault
public final class SevenZipArkivoFileSystemConfig {
    /// The split size value used when split output is disabled.
    public static final long NO_SPLIT_SIZE = -1L;

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
            null,
            NO_SPLIT_SIZE,
            false,
            false,
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null
    );

    /// The open options used to open the backing archive path.
    private final @Unmodifiable Set<OpenOption> openOptions;

    /// The provider used to decrypt encrypted 7z content and metadata or encrypt newly written content.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The default compression applied to non-empty output entries.
    private final SevenZipCompression compression;

    /// The preprocessing filter applied before default output compression, or `null` when disabled.
    private final @Nullable SevenZipFilter filter;

    /// The maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    private final long splitSize;

    /// Whether the split size was explicitly supplied rather than inherited.
    private final boolean splitSizeConfigured;

    /// Whether new 7z archives should encrypt metadata headers.
    private final boolean encryptHeaders;

    /// The requested 7z file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// The storage used to stage decoded update entry bodies, or `null` for the default temporary-file storage.
    private final @Nullable ArkivoEditStorage editStorage;

    /// The target used to publish a rewritten single-volume update, or `null` for default publication.
    private final @Nullable ArkivoCommitTarget commitTarget;

    /// Creates parsed 7z file system configuration.
    public SevenZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            SevenZipCompression compression,
            @Nullable SevenZipFilter filter,
            long splitSize,
            boolean encryptHeaders,
            ArkivoFileSystemThreadSafety threadSafety
    ) {
        this(
                openOptions,
                passwordProvider,
                compression,
                filter,
                splitSize,
                splitSize != NO_SPLIT_SIZE,
                encryptHeaders,
                threadSafety,
                null,
                null
        );
    }

    /// Creates parsed 7z file system configuration with update staging and publication settings.
    private SevenZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            SevenZipCompression compression,
            @Nullable SevenZipFilter filter,
            long splitSize,
            boolean splitSizeConfigured,
            boolean encryptHeaders,
            ArkivoFileSystemThreadSafety threadSafety,
            @Nullable ArkivoEditStorage editStorage,
            @Nullable ArkivoCommitTarget commitTarget
    ) {
        if (splitSize != NO_SPLIT_SIZE && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive or NO_SPLIT_SIZE");
        }
        this.openOptions = normalizeOpenOptions(Objects.requireNonNull(openOptions, "openOptions"));
        this.passwordProvider = passwordProvider;
        this.compression = Objects.requireNonNull(compression, "compression");
        this.filter = filter;
        this.splitSize = splitSize;
        this.splitSizeConfigured = splitSizeConfigured;
        this.encryptHeaders = encryptHeaders;
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
        this.editStorage = editStorage;
        this.commitTarget = commitTarget;
    }

    /// Parses 7z file system configuration from an environment map.
    public static SevenZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        return fromEnvironment(environment, DEFAULT_READ_OPEN_OPTIONS);
    }

    /// Parses 7z output configuration from an environment map.
    public static SevenZipArkivoFileSystemConfig fromWriterEnvironment(Map<String, ?> environment) {
        return fromEnvironment(environment, DEFAULT_WRITE_OPEN_OPTIONS);
    }

    /// Parses 7z complete-rewrite update configuration from an environment map.
    public static SevenZipArkivoFileSystemConfig fromUpdateEnvironment(Map<String, ?> environment) {
        return fromEnvironment(
                environment,
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );
    }

    /// Parses 7z configuration using the requested default archive open options.
    private static SevenZipArkivoFileSystemConfig fromEnvironment(
            Map<String, ?> environment,
            Set<? extends OpenOption> defaultOpenOptions
    ) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(defaultOpenOptions, "defaultOpenOptions");
        if (environment.containsKey("arkivo.7z.password")) {
            throw new IllegalArgumentException(
                    "The arkivo.7z.password option has been removed; use arkivo.7z.passwordProvider instead"
            );
        }
        if (environment.isEmpty() && defaultOpenOptions.equals(DEFAULT_READ_OPEN_OPTIONS)) {
            return DEFAULTS;
        }

        SevenZipArkivoFileSystemConfig config = new SevenZipArkivoFileSystemConfig(
                ArkivoFileSystem.OPEN_OPTIONS.readOrDefault(environment, Set.copyOf(defaultOpenOptions)),
                passwordProvider(environment),
                SevenZipArkivoFileSystem.COMPRESSION.readOrDefault(environment, SevenZipCompression.copy()),
                SevenZipArkivoFileSystem.FILTER.read(environment),
                splitSize(environment),
                SevenZipArkivoFileSystem.SPLIT_SIZE.isPresent(environment),
                SevenZipArkivoFileSystem.ENCRYPT_HEADERS.readOrDefault(environment, false),
                ArkivoFileSystem.THREAD_SAFETY.readOrDefault(
                        environment,
                        ArkivoFileSystemThreadSafety.CONCURRENT_READ
                ),
                ArkivoFileSystem.EDIT_STORAGE.read(environment),
                ArkivoFileSystem.COMMIT_TARGET.read(environment)
        );
        if (!config.archiveWritable() && SevenZipArkivoFileSystem.COMPRESSION.isPresent(environment)) {
            throw new IllegalArgumentException("7z compression requires write archive options");
        }
        if (!config.archiveWritable() && SevenZipArkivoFileSystem.FILTER.isPresent(environment)) {
            throw new IllegalArgumentException("7z filter requires write archive options");
        }
        if (!config.archiveWritable() && SevenZipArkivoFileSystem.ENCRYPT_HEADERS.isPresent(environment)) {
            throw new IllegalArgumentException("7z encrypted headers require write archive options");
        }
        if (!config.archiveUpdate() && config.commitTarget() != null) {
            throw new IllegalArgumentException("7z commit targets require read/write update mode");
        }
        if (!config.archiveUpdate() && config.editStorage() != null) {
            throw new IllegalArgumentException("7z edit storage requires read/write update mode");
        }
        if (config.archiveUpdate() && ArkivoFileSystem.SOURCE_MUTATION_POLICY.isPresent(environment)) {
            throw new UnsupportedOperationException("7z update mode always performs a complete archive rewrite");
        }
        if (config.archiveUpdate()
                && config.splitSize() != NO_SPLIT_SIZE
                && config.commitTarget() != null) {
            throw new IllegalArgumentException("7z split updates do not support single-file commit targets");
        }
        return config;
    }

    /// Returns the open options used to open the backing archive path.
    public @Unmodifiable Set<OpenOption> openOptions() {
        return openOptions;
    }

    /// Returns whether the archive file should be opened for writes.
    public boolean archiveWritable() {
        return openOptions.contains(StandardOpenOption.WRITE);
    }

    /// Returns whether the archive should be opened for complete-rewrite updates.
    public boolean archiveUpdate() {
        return openOptions.contains(StandardOpenOption.READ)
                && openOptions.contains(StandardOpenOption.WRITE);
    }

    /// Returns the provider used to decrypt encrypted 7z content and metadata or encrypt newly written content.
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns the default compression applied to non-empty output entries.
    public SevenZipCompression compression() {
        return compression;
    }

    /// Returns the preprocessing filter applied before default output compression, or `null` when disabled.
    public @Nullable SevenZipFilter filter() {
        return filter;
    }

    /// Returns the maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    public long splitSize() {
        return splitSize;
    }

    /// Returns whether the split size was explicitly configured.
    public boolean splitSizeConfigured() {
        return splitSizeConfigured;
    }

    /// Returns whether new 7z archives should encrypt metadata headers.
    public boolean encryptHeaders() {
        return encryptHeaders;
    }

    /// Returns the requested 7z file system thread-safety strategy.
    public ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Returns the configured update entry storage, or `null` for the default temporary-file storage.
    public @Nullable ArkivoEditStorage editStorage() {
        return editStorage;
    }

    /// Returns the configured single-volume update commit target.
    public @Nullable ArkivoCommitTarget commitTarget() {
        return commitTarget;
    }

    /// Parses the password provider from an environment map.
    private static @Nullable ArkivoPasswordProvider passwordProvider(Map<String, ?> environment) {
        return SevenZipArkivoFileSystem.PASSWORD_PROVIDER.read(environment);
    }

    /// Parses the split size from an environment map.
    private static long splitSize(Map<String, ?> environment) {
        Long splitSize = SevenZipArkivoFileSystem.SPLIT_SIZE.read(environment);
        return splitSize != null ? splitSize : NO_SPLIT_SIZE;
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

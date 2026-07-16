// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoSourceMutationPolicy;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipLegacyCharsetDetector;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Stores parsed ZIP file system configuration.
@NotNullByDefault
public final class ZipArkivoFileSystemConfig {
    /// The split size value used when split output is disabled.
    public static final long NO_SPLIT_SIZE = -1L;

    /// The primitive value used when a common archive read limit is not configured.
    public static final long NO_READ_LIMIT = -1L;

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
            ZipEncryption.none(),
            NO_SPLIT_SIZE,
            ZipLegacyCharsetDetector.standard(),
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            null,
            NO_READ_LIMIT,
            NO_READ_LIMIT,
            NO_READ_LIMIT,
            NO_READ_LIMIT
    );

    /// The open options used to open the backing archive path.
    private final @Unmodifiable Set<OpenOption> openOptions;

    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The encryption method used for new entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    private final long splitSize;

    /// The detector used to select charsets for legacy ZIP entry names and comments.
    private final ZipLegacyCharsetDetector legacyCharsetDetector;

    /// The requested ZIP file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// The configured edit storage override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoEditStorage editStorage;

    /// The configured commit target override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoCommitTarget commitTarget;

    /// The configured source mutation policy override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoSourceMutationPolicy sourceMutationPolicy;

    /// The maximum accepted logical entry count, or NO_READ_LIMIT.
    private final long maximumEntryCount;

    /// The maximum accepted logical size of one entry, or NO_READ_LIMIT.
    private final long maximumEntrySize;

    /// The maximum accepted sum of logical entry sizes, or NO_READ_LIMIT.
    private final long maximumTotalEntrySize;

    /// The maximum cumulative archive metadata size, or NO_READ_LIMIT.
    private final long maximumMetadataSize;

    /// Creates parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            long splitSize,
            ZipLegacyCharsetDetector legacyCharsetDetector,
            ArkivoFileSystemThreadSafety threadSafety,
            @Nullable ArkivoEditStorage editStorage,
            @Nullable ArkivoCommitTarget commitTarget,
            @Nullable ArkivoSourceMutationPolicy sourceMutationPolicy
    ) {
        this(
                openOptions,
                passwordProvider,
                defaultEncryption,
                splitSize,
                legacyCharsetDetector,
                threadSafety,
                editStorage,
                commitTarget,
                sourceMutationPolicy,
                NO_READ_LIMIT,
                NO_READ_LIMIT,
                NO_READ_LIMIT,
                NO_READ_LIMIT
        );
    }

    /// Creates parsed ZIP file system configuration with common archive read limits.
    ZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            long splitSize,
            ZipLegacyCharsetDetector legacyCharsetDetector,
            ArkivoFileSystemThreadSafety threadSafety,
            @Nullable ArkivoEditStorage editStorage,
            @Nullable ArkivoCommitTarget commitTarget,
            @Nullable ArkivoSourceMutationPolicy sourceMutationPolicy,
            long maximumEntryCount,
            long maximumEntrySize,
            long maximumTotalEntrySize,
            long maximumMetadataSize
    ) {
        Set<OpenOption> normalizedOpenOptions = normalizeOpenOptions(
                Objects.requireNonNull(openOptions, "openOptions")
        );
        if (splitSize != NO_SPLIT_SIZE
                && (splitSize < ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
                || splitSize > ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE)) {
            throw new IllegalArgumentException(
                    "splitSize must be NO_SPLIT_SIZE or between MINIMUM_SPLIT_SIZE and MAXIMUM_SPLIT_SIZE"
            );
        }
        this.openOptions = normalizedOpenOptions;
        this.passwordProvider = passwordProvider;
        this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        this.splitSize = splitSize;
        this.legacyCharsetDetector = Objects.requireNonNull(
                legacyCharsetDetector,
                "legacyCharsetDetector"
        );
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
        this.editStorage = editStorage;
        this.commitTarget = commitTarget;
        this.sourceMutationPolicy = sourceMutationPolicy;
        this.maximumEntryCount = requireReadLimit(maximumEntryCount, "maximumEntryCount");
        this.maximumEntrySize = requireReadLimit(maximumEntrySize, "maximumEntrySize");
        this.maximumTotalEntrySize = requireReadLimit(maximumTotalEntrySize, "maximumTotalEntrySize");
        this.maximumMetadataSize = requireReadLimit(maximumMetadataSize, "maximumMetadataSize");
    }

    /// Parses ZIP file system configuration from archive options.
    public static ZipArkivoFileSystemConfig fromOptions(ArchiveOptions options) {
        return fromOptions(options, DEFAULT_READ_OPEN_OPTIONS);
    }

    /// Parses ZIP streaming writer configuration from archive options.
    public static ZipArkivoFileSystemConfig fromWriterOptions(ArchiveOptions options) {
        return fromOptions(options, DEFAULT_WRITE_OPEN_OPTIONS);
    }

    /// Parses ZIP complete-rewrite update configuration from archive options.
    public static ZipArkivoFileSystemConfig fromUpdateOptions(ArchiveOptions options) {
        return fromOptions(options, DEFAULT_UPDATE_OPEN_OPTIONS);
    }

    /// Parses ZIP file system configuration from archive options with open option defaults.
    private static ZipArkivoFileSystemConfig fromOptions(
            ArchiveOptions options,
            Set<? extends OpenOption> defaultOpenOptions
    ) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(defaultOpenOptions, "defaultOpenOptions");

        if (options.isEmpty() && defaultOpenOptions.equals(DEFAULT_READ_OPEN_OPTIONS)) {
            return DEFAULTS;
        }

        ArkivoPasswordProvider passwordProvider = passwordProvider(options);
        Set<OpenOption> openOptions = openOptions(options, defaultOpenOptions);
        ZipEncryption defaultEncryption =
                options.getOrDefault(ZipArkivoFileSystem.DEFAULT_ENCRYPTION, ZipEncryption.none());
        long splitSize = splitSize(options);
        ZipLegacyCharsetDetector legacyCharsetDetector =
                options.getOrDefault(
                        ZipArkivoFileSystem.LEGACY_CHARSET_DETECTOR,
                        ZipLegacyCharsetDetector.standard()
                );
        ArkivoFileSystemThreadSafety threadSafety =
                options.getOrDefault(
                        ArkivoFileSystem.THREAD_SAFETY,
                        ArkivoFileSystemThreadSafety.CONCURRENT_READ
                );
        ArkivoEditStorage editStorage = options.get(ArkivoFileSystem.EDIT_STORAGE);
        ArkivoCommitTarget commitTarget = options.get(ArkivoFileSystem.COMMIT_TARGET);
        ArkivoSourceMutationPolicy sourceMutationPolicy = options.get(ArkivoFileSystem.SOURCE_MUTATION_POLICY);

        return new ZipArkivoFileSystemConfig(
                openOptions,
                passwordProvider,
                defaultEncryption,
                splitSize,
                legacyCharsetDetector,
                threadSafety,
                editStorage,
                commitTarget,
                sourceMutationPolicy,
                options.getOrDefault(ArkivoFileSystem.MAX_ENTRY_COUNT, NO_READ_LIMIT),
                options.getOrDefault(ArkivoFileSystem.MAX_ENTRY_SIZE, NO_READ_LIMIT),
                options.getOrDefault(ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE, NO_READ_LIMIT),
                options.getOrDefault(ArkivoFileSystem.MAX_METADATA_SIZE, NO_READ_LIMIT)
        );
    }

    /// Returns the open options used to open the backing archive path.
    public @Unmodifiable Set<OpenOption> openOptions() {
        return openOptions;
    }

    /// Returns whether the archive file should be opened for forward-only ZIP writes.
    public boolean archiveWritable() {
        return openOptions.contains(StandardOpenOption.WRITE);
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns the encryption method used for new entries that do not override encryption.
    public ZipEncryption defaultEncryption() {
        return defaultEncryption;
    }

    /// Returns the maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    public long splitSize() {
        return splitSize;
    }

    /// Returns the detector used to select charsets for legacy ZIP entry names and comments.
    public ZipLegacyCharsetDetector legacyCharsetDetector() {
        return legacyCharsetDetector;
    }

    /// Returns the requested ZIP file system thread-safety strategy.
    public ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Returns the configured edit storage override, or `null` when the file system should choose a default.
    public @Nullable ArkivoEditStorage editStorage() {
        return editStorage;
    }

    /// Returns the configured commit target override, or `null` when the file system should choose a default.
    public @Nullable ArkivoCommitTarget commitTarget() {
        return commitTarget;
    }

    /// Returns the configured source mutation policy override, or `null` when the file system should choose a default.
    public @Nullable ArkivoSourceMutationPolicy sourceMutationPolicy() {
        return sourceMutationPolicy;
    }

    /// Returns the maximum accepted logical entry count, or NO_READ_LIMIT.
    public long maximumEntryCount() {
        return maximumEntryCount;
    }

    /// Returns the maximum accepted logical size of one entry, or NO_READ_LIMIT.
    public long maximumEntrySize() {
        return maximumEntrySize;
    }

    /// Returns the maximum accepted sum of logical entry sizes, or NO_READ_LIMIT.
    public long maximumTotalEntrySize() {
        return maximumTotalEntrySize;
    }

    /// Returns the maximum cumulative archive metadata size, or NO_READ_LIMIT.
    public long maximumMetadataSize() {
        return maximumMetadataSize;
    }

    /// Parses the password provider from an archive options.
    private static @Nullable ArkivoPasswordProvider passwordProvider(ArchiveOptions options) {
        return options.get(ZipArkivoFileSystem.PASSWORD_PROVIDER);
    }

    /// Parses the split size from an archive options.
    private static long splitSize(ArchiveOptions options) {
        return options.getOrDefault(ZipArkivoFileSystem.SPLIT_SIZE, NO_SPLIT_SIZE);
    }

    /// Validates one primitive common read limit.
    private static long requireReadLimit(long value, String name) {
        if (value < NO_READ_LIMIT) {
            throw new IllegalArgumentException(name + " must be -1 or non-negative");
        }
        return value;
    }

    /// Parses open options from archive options.
    private static @Unmodifiable Set<OpenOption> openOptions(
            ArchiveOptions archiveOptions,
            Set<? extends OpenOption> defaultOpenOptions
    ) {
        Set<OpenOption> options = archiveOptions.getOrDefault(
                ArkivoFileSystem.OPEN_OPTIONS,
                Set.copyOf(defaultOpenOptions)
        );
        return normalizeOpenOptions(options);
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

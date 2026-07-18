// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.internal.ArchiveOption;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.ArchiveEnvironmentOptions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.Charset;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Stores parsed ZIP file system configuration.
@NotNullByDefault
public final class ZipArkivoFileSystemConfig {
    /// The legacy NIO environment key for a password provider.
    private static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.zip", "passwordProvider", ArkivoPasswordProvider.class);

    /// The legacy NIO environment key for default entry encryption.
    private static final ArchiveOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArchiveOption.of(
                    "arkivo.zip",
                    "defaultEncryption",
                    ZipEncryption.class,
                    ZipArkivoFileSystemConfig::encryptionValue
            );

    /// The legacy NIO environment key for split output size.
    private static final ArchiveOption<Long> SPLIT_SIZE =
            ArchiveOption.of("arkivo.zip", "splitSize", Long.class, ZipArkivoFileSystemConfig::longValue);

    /// The legacy NIO environment key for name detection.
    private static final ArchiveOption<ArchiveMetadataCharsetDetector> LEGACY_CHARSET_DETECTOR =
            ArchiveOption.of(
                    "arkivo.zip",
                    "legacyCharsetDetector",
                    ArchiveMetadataCharsetDetector.class,
                    ZipArkivoFileSystemConfig::legacyCharsetDetectorValue
            );

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

    /// The ZIP-standard detector used when no legacy charset detector is configured.
    private static final ArchiveMetadataCharsetDetector DEFAULT_LEGACY_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(Charset.forName("IBM437"));

    /// The default parsed ZIP file system configuration.
    public static final ZipArkivoFileSystemConfig DEFAULTS = new ZipArkivoFileSystemConfig(
            DEFAULT_READ_OPEN_OPTIONS,
            null,
            ZipEncryption.NONE,
            NO_SPLIT_SIZE,
            DEFAULT_LEGACY_CHARSET_DETECTOR,
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            ArchiveReadLimits.UNLIMITED
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
    private final ArchiveMetadataCharsetDetector legacyCharsetDetector;

    /// The requested ZIP file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// The configured edit storage override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoEditStorage editStorage;

    /// The configured commit target override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoCommitTarget commitTarget;

    /// The maximum accepted logical entry count, or NO_READ_LIMIT.
    private final ArchiveReadLimits readLimits;

    /// Creates parsed ZIP file system configuration.
    ///
    /// @param openOptions the backing archive open options; this set is copied
    /// @param passwordProvider the entry password provider, or `null` when unavailable
    /// @param defaultEncryption the encryption for new entries without an explicit override
    /// @param splitSize the maximum output volume size, or [#NO_SPLIT_SIZE]
    /// @param legacyCharsetDetector the detector for names and comments without Unicode metadata
    /// @param threadSafety the synchronization strategy
    /// @param editStorage the edit storage override, or `null` to select a default
    /// @param commitTarget the publication target override, or `null` to select a default
    /// @throws NullPointerException if `openOptions`, an option element, `defaultEncryption`,
    /// `legacyCharsetDetector`, or `threadSafety` is `null`
    /// @throws IllegalArgumentException if the open options conflict or `splitSize` is outside the ZIP limits
    public ZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            long splitSize,
            ArchiveMetadataCharsetDetector legacyCharsetDetector,
            ArkivoFileSystemThreadSafety threadSafety,
            @Nullable ArkivoEditStorage editStorage,
            @Nullable ArkivoCommitTarget commitTarget
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
                ArchiveReadLimits.UNLIMITED
        );
    }

    /// Creates parsed ZIP file system configuration with common archive read limits.
    ZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            long splitSize,
            ArchiveMetadataCharsetDetector legacyCharsetDetector,
            ArkivoFileSystemThreadSafety threadSafety,
            @Nullable ArkivoEditStorage editStorage,
            @Nullable ArkivoCommitTarget commitTarget,
            ArchiveReadLimits readLimits
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
        this.readLimits = Objects.requireNonNull(readLimits, "readLimits");
    }

    /// Parses ZIP file system configuration from archive options.
    ///
    /// @param options the generic archive options
    /// @return normalized read-oriented ZIP configuration
    /// @throws NullPointerException if `options` is `null`
    /// @throws IllegalArgumentException if an option value is invalid or the open options conflict
    public static ZipArkivoFileSystemConfig fromOptions(ArchiveOptions options) {
        return fromOptions(options, DEFAULT_READ_OPEN_OPTIONS);
    }

    /// Parses ZIP streaming writer configuration from archive options.
    ///
    /// @param options the generic archive options
    /// @return normalized write-oriented ZIP configuration
    /// @throws NullPointerException if `options` is `null`
    /// @throws IllegalArgumentException if an option value is invalid or the open options conflict
    public static ZipArkivoFileSystemConfig fromWriterOptions(ArchiveOptions options) {
        return fromOptions(options, DEFAULT_WRITE_OPEN_OPTIONS);
    }

    /// Parses ZIP complete-rewrite update configuration from archive options.
    ///
    /// @param options the generic archive options
    /// @return normalized update-oriented ZIP configuration
    /// @throws NullPointerException if `options` is `null`
    /// @throws IllegalArgumentException if an option value is invalid or the open options conflict
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
                options.getOrDefault(DEFAULT_ENCRYPTION, ZipEncryption.NONE);
        long splitSize = splitSize(options);
        ArchiveMetadataCharsetDetector legacyCharsetDetector =
                options.getOrDefault(
                        LEGACY_CHARSET_DETECTOR,
                        DEFAULT_LEGACY_CHARSET_DETECTOR
                );
        ArkivoFileSystemThreadSafety threadSafety =
                options.getOrDefault(
                        ArchiveEnvironmentOptions.THREAD_SAFETY,
                        ArkivoFileSystemThreadSafety.CONCURRENT_READ
                );
        ArkivoEditStorage editStorage = options.get(ArchiveEnvironmentOptions.EDIT_STORAGE);
        ArkivoCommitTarget commitTarget = options.get(ArchiveEnvironmentOptions.COMMIT_TARGET);

        ArchiveReadLimits readLimits =
                options.getOrDefault(ArchiveEnvironmentOptions.READ_LIMITS, ArchiveReadLimits.UNLIMITED);
        return new ZipArkivoFileSystemConfig(
                openOptions,
                passwordProvider,
                defaultEncryption,
                splitSize,
                legacyCharsetDetector,
                threadSafety,
                editStorage,
                commitTarget,
                readLimits
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
                options.legacyCharsetDetector(),
                options.common().threadSafety(),
                options.common().editStorage(),
                null,
                options.common().limits()
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
                ZipArchiveOptions.DEFAULT_LEGACY_CHARSET_DETECTOR,
                options.common().threadSafety(),
                options.common().editStorage(),
                null,
                ArchiveReadLimits.UNLIMITED
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
                options.legacyCharsetDetector(),
                options.common().threadSafety(),
                options.common().editStorage(),
                options.common().commitTarget(),
                options.common().limits()
        );
    }

    /// Returns the open options used to open the backing archive path.
    ///
    /// @return the immutable normalized open-option set
    public @Unmodifiable Set<OpenOption> openOptions() {
        return openOptions;
    }

    /// Returns whether the archive file should be opened for forward-only ZIP writes.
    ///
    /// @return `true` when the normalized options contain [StandardOpenOption#WRITE]
    public boolean archiveWritable() {
        return openOptions.contains(StandardOpenOption.WRITE);
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    ///
    /// @return the password provider, or `null` when unavailable
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns the encryption method used for new entries that do not override encryption.
    ///
    /// @return the default entry encryption method
    public ZipEncryption defaultEncryption() {
        return defaultEncryption;
    }

    /// Returns the maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    ///
    /// @return the maximum volume size, or [#NO_SPLIT_SIZE]
    public long splitSize() {
        return splitSize;
    }

    /// Returns the detector used to select charsets for legacy ZIP entry names and comments.
    ///
    /// @return the legacy metadata charset detector
    public ArchiveMetadataCharsetDetector legacyCharsetDetector() {
        return legacyCharsetDetector;
    }

    /// Returns the requested ZIP file system thread-safety strategy.
    ///
    /// @return the synchronization strategy
    public ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Returns the configured edit storage override, or `null` when the file system should choose a default.
    ///
    /// @return the edit storage override, or `null` to select a default
    public @Nullable ArkivoEditStorage editStorage() {
        return editStorage;
    }

    /// Returns the configured commit target override, or `null` when the file system should choose a default.
    ///
    /// @return the publication target override, or `null` to select a default
    public @Nullable ArkivoCommitTarget commitTarget() {
        return commitTarget;
    }

    /// Returns the maximum accepted logical entry count, or `NO_READ_LIMIT`.
    ///
    /// @return the entry-count limit, or [#NO_READ_LIMIT]
    public long maximumEntryCount() {
        return readLimits.maximumEntryCount();
    }

    /// Returns the maximum accepted logical size of one entry, or `NO_READ_LIMIT`.
    ///
    /// @return the per-entry logical size limit, or [#NO_READ_LIMIT]
    public long maximumEntrySize() {
        return readLimits.maximumEntrySize();
    }

    /// Returns the maximum accepted sum of logical entry sizes, or `NO_READ_LIMIT`.
    ///
    /// @return the aggregate logical entry-size limit, or [#NO_READ_LIMIT]
    public long maximumTotalEntrySize() {
        return readLimits.maximumTotalEntrySize();
    }

    /// Returns the maximum cumulative archive metadata size, or `NO_READ_LIMIT`.
    ///
    /// @return the cumulative metadata-size limit, or [#NO_READ_LIMIT]
    public long maximumMetadataSize() {
        return readLimits.maximumMetadataSize();
    }

    /// Returns all resource limits for the archive read portion of this operation.
    ///
    /// @return the immutable archive read limits
    public ArchiveReadLimits readLimits() {
        return readLimits;
    }

    /// Parses the password provider from an archive options.
    private static @Nullable ArkivoPasswordProvider passwordProvider(ArchiveOptions options) {
        return options.get(PASSWORD_PROVIDER);
    }

    /// Parses the split size from an archive options.
    private static long splitSize(ArchiveOptions options) {
        return options.getOrDefault(SPLIT_SIZE, NO_SPLIT_SIZE);
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

    /// Converts a raw NIO legacy charset detector value.
    private static ArchiveMetadataCharsetDetector legacyCharsetDetectorValue(Object value) {
        if (value instanceof ArchiveMetadataCharsetDetector detector) {
            return detector;
        }
        if (value instanceof Charset charset) {
            return ArchiveMetadataCharsetDetector.fixed(charset);
        }
        if (value instanceof String name) {
            return ArchiveMetadataCharsetDetector.fixed(Charset.forName(name));
        }
        throw new IllegalArgumentException(
                "Expected ArchiveMetadataCharsetDetector, Charset, or String for key: arkivo.zip.legacyCharsetDetector"
        );
    }

    /// Parses open options from archive options.
    private static @Unmodifiable Set<OpenOption> openOptions(
            ArchiveOptions archiveOptions,
            Set<? extends OpenOption> defaultOpenOptions
    ) {
        Set<OpenOption> options = archiveOptions.getOrDefault(
                ArchiveEnvironmentOptions.OPEN_OPTIONS,
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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoCommitTarget;
import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.ArkivoSourceMutationPolicy;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipEntryNameEncoding;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Stores parsed ZIP file system configuration.
@NotNullByDefault
public final class ZipArkivoFileSystemConfig {
    /// The split size value used when split output is disabled.
    public static final long NO_SPLIT_SIZE = -1L;

    /// The default open options used by read-only ZIP file systems.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_READ_OPEN_OPTIONS =
            Set.of(StandardOpenOption.READ);

    /// The default open options used by streaming ZIP writers opened from paths.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_WRITE_OPEN_OPTIONS =
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

    /// The default parsed ZIP file system configuration.
    public static final ZipArkivoFileSystemConfig DEFAULTS = new ZipArkivoFileSystemConfig(
            DEFAULT_READ_OPEN_OPTIONS,
            null,
            ZipEncryption.none(),
            NO_SPLIT_SIZE,
            ZipEntryNameEncoding.standard(),
            ArkivoFileSystemThreadSafety.CONCURRENT_READ,
            null,
            null,
            null
    );

    /// The open options used to open the backing archive path.
    private final @Unmodifiable Set<OpenOption> openOptions;

    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The encryption method used for new entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    private final long splitSize;

    /// The policy used to decode ZIP entry names when no authoritative Unicode name is available.
    private final ZipEntryNameEncoding entryNameEncoding;

    /// The requested ZIP file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// The configured edit storage override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoEditStorage editStorage;

    /// The configured commit target override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoCommitTarget commitTarget;

    /// The configured source mutation policy override, or `null` when the file system should choose a default.
    private final @Nullable ArkivoSourceMutationPolicy sourceMutationPolicy;

    /// Creates parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            long splitSize,
            ZipEntryNameEncoding entryNameEncoding,
            ArkivoFileSystemThreadSafety threadSafety,
            @Nullable ArkivoEditStorage editStorage,
            @Nullable ArkivoCommitTarget commitTarget,
            @Nullable ArkivoSourceMutationPolicy sourceMutationPolicy
    ) {
        if (splitSize != NO_SPLIT_SIZE && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive or NO_SPLIT_SIZE");
        }
        this.openOptions = normalizeOpenOptions(
                Objects.requireNonNull(openOptions, "openOptions")
        );
        this.passwordProvider = passwordProvider;
        this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        this.splitSize = splitSize;
        this.entryNameEncoding = Objects.requireNonNull(entryNameEncoding, "entryNameEncoding");
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
        this.editStorage = editStorage;
        this.commitTarget = commitTarget;
        this.sourceMutationPolicy = sourceMutationPolicy;
    }

    /// Parses ZIP file system configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        return fromEnvironment(environment, DEFAULT_READ_OPEN_OPTIONS);
    }

    /// Parses ZIP streaming writer configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromWriterEnvironment(Map<String, ?> environment) {
        return fromEnvironment(environment, DEFAULT_WRITE_OPEN_OPTIONS);
    }

    /// Parses ZIP file system configuration from an environment map with open option defaults.
    private static ZipArkivoFileSystemConfig fromEnvironment(
            Map<String, ?> environment,
            Set<? extends OpenOption> defaultOpenOptions
    ) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(defaultOpenOptions, "defaultOpenOptions");
        if (environment.isEmpty() && defaultOpenOptions.equals(DEFAULT_READ_OPEN_OPTIONS)) {
            return DEFAULTS;
        }

        ArkivoPasswordProvider passwordProvider = passwordProvider(environment);
        Set<OpenOption> openOptions = openOptions(environment, defaultOpenOptions);
        ZipEncryption defaultEncryption =
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.readOrDefault(environment, ZipEncryption.none());
        long splitSize = splitSize(environment);
        ZipEntryNameEncoding entryNameEncoding =
                ZipArkivoFileSystem.ENTRY_NAME_ENCODING.readOrDefault(
                        environment,
                        ZipEntryNameEncoding.standard()
                );
        ArkivoFileSystemThreadSafety threadSafety =
                ArkivoFileSystem.THREAD_SAFETY.readOrDefault(
                        environment,
                        ArkivoFileSystemThreadSafety.CONCURRENT_READ
                );
        ArkivoEditStorage editStorage = ArkivoFileSystem.EDIT_STORAGE.read(environment);
        ArkivoCommitTarget commitTarget = ArkivoFileSystem.COMMIT_TARGET.read(environment);
        ArkivoSourceMutationPolicy sourceMutationPolicy = ArkivoFileSystem.SOURCE_MUTATION_POLICY.read(environment);

        return new ZipArkivoFileSystemConfig(
                openOptions,
                passwordProvider,
                defaultEncryption,
                splitSize,
                entryNameEncoding,
                threadSafety,
                editStorage,
                commitTarget,
                sourceMutationPolicy
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

    /// Returns the policy used to decode ZIP entry names when no authoritative Unicode name is available.
    public ZipEntryNameEncoding entryNameEncoding() {
        return entryNameEncoding;
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

    /// Parses the password provider from an environment map.
    private static @Nullable ArkivoPasswordProvider passwordProvider(Map<String, ?> environment) {
        Object provider = environment.get(ZipArkivoFileSystem.PASSWORD_PROVIDER.key());
        Object password = environment.get(ZipArkivoFileSystem.PASSWORD.key());
        if (provider != null && password != null) {
            throw new IllegalArgumentException("passwordProvider and password cannot both be set");
        }
        if (provider != null) {
            return ZipArkivoFileSystem.PASSWORD_PROVIDER.read(environment);
        }
        if (password != null) {
            byte[] fixedPassword = ZipArkivoFileSystem.PASSWORD.read(environment);
            return fixedPassword != null ? ArkivoPasswordProvider.fixed(fixedPassword) : null;
        }
        return null;
    }

    /// Parses the split size from an environment map.
    private static long splitSize(Map<String, ?> environment) {
        Long splitSize = ZipArkivoFileSystem.SPLIT_SIZE.read(environment);
        return splitSize != null ? splitSize : NO_SPLIT_SIZE;
    }

    /// Parses open options from an environment map.
    private static @Unmodifiable Set<OpenOption> openOptions(
            Map<String, ?> environment,
            Set<? extends OpenOption> defaultOpenOptions
    ) {
        Set<OpenOption> options = ArkivoFileSystem.OPEN_OPTIONS.readOrDefault(
                environment,
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

        if (append) {
            throw new UnsupportedOperationException("ZIP archive streaming writes do not support APPEND");
        }

        if (read) {
            if (write) {
                throw new UnsupportedOperationException("ZIP archive update mode is not supported yet");
            }
            if (create || createNew || truncate) {
                throw new IllegalArgumentException(
                        "ZIP archive read open options cannot include create or truncate options"
                );
            }
            return Set.copyOf(result);
        }

        if (!write) {
            throw new IllegalArgumentException("ZIP archive write open options must include WRITE");
        }
        if (!truncate && !createNew) {
            throw new IllegalArgumentException(
                    "ZIP archive write open options must include TRUNCATE_EXISTING or CREATE_NEW"
            );
        }
        return Set.copyOf(result);
    }
}

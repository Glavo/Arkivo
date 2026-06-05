// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoPasswordProvider;
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

    /// The default archive open options used by read-only ZIP file systems.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_READ_ARCHIVE_OPEN_OPTIONS =
            Set.of(StandardOpenOption.READ);

    /// The default archive open options used by streaming ZIP writers opened from paths.
    private static final @Unmodifiable Set<OpenOption> DEFAULT_WRITE_ARCHIVE_OPEN_OPTIONS =
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

    /// The default parsed ZIP file system configuration.
    public static final ZipArkivoFileSystemConfig DEFAULTS = new ZipArkivoFileSystemConfig(
            DEFAULT_READ_ARCHIVE_OPEN_OPTIONS,
            null,
            ZipEncryption.none(),
            NO_SPLIT_SIZE,
            ZipEntryNameEncoding.standard(),
            ArkivoFileSystemThreadSafety.CONCURRENT_READ
    );

    /// The open options used to open an archive file by path.
    private final @Unmodifiable Set<OpenOption> archiveOpenOptions;

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

    /// Creates parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig(
            Set<? extends OpenOption> archiveOpenOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            long splitSize,
            ZipEntryNameEncoding entryNameEncoding,
            ArkivoFileSystemThreadSafety threadSafety
    ) {
        if (splitSize != NO_SPLIT_SIZE && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive or NO_SPLIT_SIZE");
        }
        this.archiveOpenOptions = normalizeArchiveOpenOptions(
                Objects.requireNonNull(archiveOpenOptions, "archiveOpenOptions").toArray(OpenOption[]::new)
        );
        this.passwordProvider = passwordProvider;
        this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        this.splitSize = splitSize;
        this.entryNameEncoding = Objects.requireNonNull(entryNameEncoding, "entryNameEncoding");
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
    }

    /// Parses ZIP file system configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        return fromEnvironment(environment, DEFAULT_READ_ARCHIVE_OPEN_OPTIONS);
    }

    /// Parses ZIP streaming writer configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromWriterEnvironment(Map<String, ?> environment) {
        return fromEnvironment(environment, DEFAULT_WRITE_ARCHIVE_OPEN_OPTIONS);
    }

    /// Parses ZIP file system configuration from an environment map with archive open option defaults.
    private static ZipArkivoFileSystemConfig fromEnvironment(
            Map<String, ?> environment,
            Set<? extends OpenOption> defaultArchiveOpenOptions
    ) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(defaultArchiveOpenOptions, "defaultArchiveOpenOptions");
        if (environment.isEmpty() && defaultArchiveOpenOptions.equals(DEFAULT_READ_ARCHIVE_OPEN_OPTIONS)) {
            return DEFAULTS;
        }

        ArkivoPasswordProvider passwordProvider = passwordProvider(environment);
        Set<OpenOption> archiveOpenOptions = archiveOpenOptions(environment, defaultArchiveOpenOptions);
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

        return new ZipArkivoFileSystemConfig(
                archiveOpenOptions,
                passwordProvider,
                defaultEncryption,
                splitSize,
                entryNameEncoding,
                threadSafety
        );
    }

    /// Returns the open options used to open an archive file by path.
    public @Unmodifiable Set<OpenOption> archiveOpenOptions() {
        return archiveOpenOptions;
    }

    /// Returns whether the archive file should be opened for forward-only ZIP writes.
    public boolean archiveWritable() {
        return archiveOpenOptions.contains(StandardOpenOption.WRITE);
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

    /// Parses archive file open options from an environment map.
    private static @Unmodifiable Set<OpenOption> archiveOpenOptions(
            Map<String, ?> environment,
            Set<? extends OpenOption> defaultArchiveOpenOptions
    ) {
        OpenOption[] options = ZipArkivoFileSystem.ARCHIVE_OPEN_OPTIONS.readOrDefault(
                environment,
                defaultArchiveOpenOptions.toArray(OpenOption[]::new)
        );
        return normalizeArchiveOpenOptions(options);
    }

    /// Normalizes and validates archive file open options.
    private static @Unmodifiable Set<OpenOption> normalizeArchiveOpenOptions(OpenOption[] options) {
        Objects.requireNonNull(options, "options");
        LinkedHashSet<OpenOption> result = new LinkedHashSet<>();
        for (OpenOption option : options) {
            result.add(Objects.requireNonNull(option, "option"));
        }
        if (result.isEmpty()) {
            result.add(StandardOpenOption.READ);
        }

        if (result.contains(StandardOpenOption.READ)) {
            if (result.size() != 1) {
                throw new IllegalArgumentException("ZIP archive read open options cannot be mixed with write options");
            }
            return Set.copyOf(result);
        }

        if (result.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("ZIP archive streaming writes do not support APPEND");
        }
        if (!result.contains(StandardOpenOption.WRITE)) {
            throw new IllegalArgumentException("ZIP archive write open options must include WRITE");
        }
        if (!result.contains(StandardOpenOption.TRUNCATE_EXISTING)
                && !result.contains(StandardOpenOption.CREATE_NEW)) {
            throw new IllegalArgumentException(
                    "ZIP archive write open options must include TRUNCATE_EXISTING or CREATE_NEW"
            );
        }
        return Set.copyOf(result);
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystem;
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

    /// The default parsed 7z file system configuration.
    public static final SevenZipArkivoFileSystemConfig DEFAULTS = new SevenZipArkivoFileSystemConfig(
            DEFAULT_READ_OPEN_OPTIONS,
            null,
            NO_SPLIT_SIZE,
            false,
            ArkivoFileSystemThreadSafety.CONCURRENT_READ
    );

    /// The open options used to open the backing archive path.
    private final @Unmodifiable Set<OpenOption> openOptions;

    /// The provider used to decrypt encrypted 7z content and metadata.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    private final long splitSize;

    /// Whether new 7z archives should encrypt metadata headers.
    private final boolean encryptHeaders;

    /// The requested 7z file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// Creates parsed 7z file system configuration.
    public SevenZipArkivoFileSystemConfig(
            Set<? extends OpenOption> openOptions,
            @Nullable ArkivoPasswordProvider passwordProvider,
            long splitSize,
            boolean encryptHeaders,
            ArkivoFileSystemThreadSafety threadSafety
    ) {
        if (splitSize != NO_SPLIT_SIZE && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive or NO_SPLIT_SIZE");
        }
        this.openOptions = normalizeOpenOptions(Objects.requireNonNull(openOptions, "openOptions"));
        this.passwordProvider = passwordProvider;
        this.splitSize = splitSize;
        this.encryptHeaders = encryptHeaders;
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
    }

    /// Parses 7z file system configuration from an environment map.
    public static SevenZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.isEmpty()) {
            return DEFAULTS;
        }

        return new SevenZipArkivoFileSystemConfig(
                ArkivoFileSystem.OPEN_OPTIONS.readOrDefault(environment, DEFAULT_READ_OPEN_OPTIONS),
                passwordProvider(environment),
                splitSize(environment),
                SevenZipArkivoFileSystem.ENCRYPT_HEADERS.readOrDefault(environment, false),
                ArkivoFileSystem.THREAD_SAFETY.readOrDefault(
                        environment,
                        ArkivoFileSystemThreadSafety.CONCURRENT_READ
                )
        );
    }

    /// Returns the open options used to open the backing archive path.
    public @Unmodifiable Set<OpenOption> openOptions() {
        return openOptions;
    }

    /// Returns whether the archive file should be opened for writes.
    public boolean archiveWritable() {
        return openOptions.contains(StandardOpenOption.WRITE);
    }

    /// Returns the provider used to decrypt encrypted 7z content and metadata.
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns the maximum size of each output volume, or `NO_SPLIT_SIZE` when split output is disabled.
    public long splitSize() {
        return splitSize;
    }

    /// Returns whether new 7z archives should encrypt metadata headers.
    public boolean encryptHeaders() {
        return encryptHeaders;
    }

    /// Returns the requested 7z file system thread-safety strategy.
    public ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Parses the password provider from an environment map.
    private static @Nullable ArkivoPasswordProvider passwordProvider(Map<String, ?> environment) {
        Object provider = environment.get(SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key());
        Object password = environment.get(SevenZipArkivoFileSystem.PASSWORD.key());
        if (provider != null && password != null) {
            throw new IllegalArgumentException("passwordProvider and password cannot both be set");
        }
        if (provider != null) {
            return SevenZipArkivoFileSystem.PASSWORD_PROVIDER.read(environment);
        }
        if (password != null) {
            byte[] fixedPassword = SevenZipArkivoFileSystem.PASSWORD.read(environment);
            return fixedPassword != null ? ArkivoPasswordProvider.fixed(fixedPassword) : null;
        }
        return null;
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
        if (read && (write || create || createNew || truncate)) {
            throw new UnsupportedOperationException("7z archive update mode is not implemented yet");
        }
        if (write) {
            throw new UnsupportedOperationException("7z archive write support is not implemented yet");
        }
        return Set.copyOf(result);
    }
}

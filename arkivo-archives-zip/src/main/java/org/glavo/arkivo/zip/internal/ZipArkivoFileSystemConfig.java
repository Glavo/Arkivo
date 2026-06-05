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

import java.util.Map;
import java.util.Objects;

/// Stores parsed ZIP file system configuration.
@NotNullByDefault
public final class ZipArkivoFileSystemConfig {
    /// The split size value used when split output is disabled.
    public static final long NO_SPLIT_SIZE = -1L;

    /// The default parsed ZIP file system configuration.
    public static final ZipArkivoFileSystemConfig DEFAULTS = new ZipArkivoFileSystemConfig(
            false,
            null,
            ZipEncryption.none(),
            NO_SPLIT_SIZE,
            ZipEntryNameEncoding.standard(),
            ArkivoFileSystemThreadSafety.CONCURRENT_READ
    );

    /// Whether opening by path should create a new forward-only ZIP file system.
    private final boolean create;

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
            boolean create,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            long splitSize,
            ZipEntryNameEncoding entryNameEncoding,
            ArkivoFileSystemThreadSafety threadSafety
    ) {
        if (splitSize != NO_SPLIT_SIZE && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive or NO_SPLIT_SIZE");
        }
        this.create = create;
        this.passwordProvider = passwordProvider;
        this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        this.splitSize = splitSize;
        this.entryNameEncoding = Objects.requireNonNull(entryNameEncoding, "entryNameEncoding");
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
    }

    /// Parses ZIP file system configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.isEmpty()) {
            return DEFAULTS;
        }

        ArkivoPasswordProvider passwordProvider = passwordProvider(environment);
        boolean create = ZipArkivoFileSystem.CREATE.readOrDefault(environment, false);
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
                create,
                passwordProvider,
                defaultEncryption,
                splitSize,
                entryNameEncoding,
                threadSafety
        );
    }

    /// Returns whether opening by path should create a new forward-only ZIP file system.
    public boolean create() {
        return create;
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
}

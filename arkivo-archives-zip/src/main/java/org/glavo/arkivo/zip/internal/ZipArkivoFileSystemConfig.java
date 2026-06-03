// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/// Stores parsed ZIP file system configuration.
@NotNullByDefault
public final class ZipArkivoFileSystemConfig {
    /// The default parsed ZIP file system configuration.
    public static final ZipArkivoFileSystemConfig DEFAULTS = new ZipArkivoFileSystemConfig(
            null,
            false,
            false,
            false,
            ZipEncryption.none(),
            null
    );

    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// Whether the file system should reject mutating operations.
    private final boolean readOnly;

    /// Whether the file system should create a new ZIP archive.
    private final boolean create;

    /// Whether the file system should use append-only streaming write semantics.
    private final boolean streamingWrite;

    /// The encryption method used for new entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume.
    private final @Nullable Long splitSize;

    /// Creates parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig(
            @Nullable ArkivoPasswordProvider passwordProvider,
            boolean readOnly,
            boolean create,
            boolean streamingWrite,
            ZipEncryption defaultEncryption,
            @Nullable Long splitSize
    ) {
        if (splitSize != null && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        if (readOnly && create) {
            throw new IllegalArgumentException("readOnly and create cannot both be true");
        }
        if (readOnly && streamingWrite) {
            throw new IllegalArgumentException("readOnly and streamingWrite cannot both be true");
        }
        if (streamingWrite && !create) {
            throw new IllegalArgumentException("streamingWrite requires create");
        }
        this.passwordProvider = passwordProvider;
        this.readOnly = readOnly;
        this.create = create;
        this.streamingWrite = streamingWrite;
        this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        this.splitSize = splitSize;
    }

    /// Parses ZIP file system configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.isEmpty()) {
            return DEFAULTS;
        }

        ArkivoPasswordProvider passwordProvider = passwordProvider(environment);
        boolean readOnly = booleanValue(environment, ZipArkivoFileSystem.READ_ONLY, false);
        boolean create = booleanValue(environment, ZipArkivoFileSystem.CREATE, false);
        boolean streamingWrite = booleanValue(environment, ZipArkivoFileSystem.STREAMING_WRITE, false);
        ZipEncryption defaultEncryption = typedValue(
                environment,
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION,
                ZipEncryption.class,
                ZipEncryption.none()
        );
        Long splitSize = splitSize(environment);

        return new ZipArkivoFileSystemConfig(
                passwordProvider,
                readOnly,
                create,
                streamingWrite,
                defaultEncryption,
                splitSize
        );
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns whether the file system should reject mutating operations.
    public boolean readOnly() {
        return readOnly;
    }

    /// Returns whether the file system should create a new ZIP archive.
    public boolean create() {
        return create;
    }

    /// Returns whether the file system should use append-only streaming write semantics.
    public boolean streamingWrite() {
        return streamingWrite;
    }

    /// Returns the encryption method used for new entries that do not override encryption.
    public ZipEncryption defaultEncryption() {
        return defaultEncryption;
    }

    /// Returns the maximum size of each output volume.
    public @Nullable Long splitSize() {
        return splitSize;
    }

    /// Parses the password provider from an environment map.
    private static @Nullable ArkivoPasswordProvider passwordProvider(Map<String, ?> environment) {
        Object provider = environment.get(ZipArkivoFileSystem.PASSWORD_PROVIDER);
        Object password = environment.get(ZipArkivoFileSystem.PASSWORD);
        if (provider != null && password != null) {
            throw new IllegalArgumentException("passwordProvider and password cannot both be set");
        }
        if (provider instanceof ArkivoPasswordProvider passwordProvider) {
            return passwordProvider;
        }
        if (provider != null) {
            throw new IllegalArgumentException("Expected ArkivoPasswordProvider for key: " + ZipArkivoFileSystem.PASSWORD_PROVIDER);
        }
        if (password instanceof char[] fixedPassword) {
            return ArkivoPasswordProvider.fixed(fixedPassword);
        }
        if (password != null) {
            throw new IllegalArgumentException("Expected char[] for key: " + ZipArkivoFileSystem.PASSWORD);
        }
        return null;
    }

    /// Parses a boolean value from an environment map.
    private static boolean booleanValue(Map<String, ?> environment, String key, boolean defaultValue) {
        Object value = environment.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new IllegalArgumentException("Expected Boolean for key: " + key);
    }

    /// Parses a typed value from an environment map.
    private static <T> T typedValue(Map<String, ?> environment, String key, Class<T> type, T defaultValue) {
        Object value = environment.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw new IllegalArgumentException("Expected " + type.getSimpleName() + " for key: " + key);
    }

    /// Parses the split size from an environment map.
    private static @Nullable Long splitSize(Map<String, ?> environment) {
        Object value = environment.get(ZipArkivoFileSystem.SPLIT_SIZE);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Expected Number for key: " + ZipArkivoFileSystem.SPLIT_SIZE);
    }
}

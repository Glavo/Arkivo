// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

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
    /// The default parsed ZIP file system configuration.
    public static final ZipArkivoFileSystemConfig DEFAULTS = new ZipArkivoFileSystemConfig(
            null,
            false,
            false,
            false,
            ZipEncryption.none(),
            null,
            ZipEntryNameEncoding.standard()
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

    /// The policy used to decode ZIP entry names when no authoritative Unicode name is available.
    private final ZipEntryNameEncoding entryNameEncoding;

    /// Creates parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig(
            @Nullable ArkivoPasswordProvider passwordProvider,
            boolean readOnly,
            boolean create,
            boolean streamingWrite,
            ZipEncryption defaultEncryption,
            @Nullable Long splitSize,
            ZipEntryNameEncoding entryNameEncoding
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
        this.entryNameEncoding = Objects.requireNonNull(entryNameEncoding, "entryNameEncoding");
    }

    /// Parses ZIP file system configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.isEmpty()) {
            return DEFAULTS;
        }

        ArkivoPasswordProvider passwordProvider = passwordProvider(environment);
        boolean readOnly = ZipArkivoFileSystem.READ_ONLY.readOrDefault(environment, false);
        boolean create = ZipArkivoFileSystem.CREATE.readOrDefault(environment, false);
        boolean streamingWrite = ZipArkivoFileSystem.STREAMING_WRITE.readOrDefault(environment, false);
        ZipEncryption defaultEncryption =
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.readOrDefault(environment, ZipEncryption.none());
        Long splitSize = splitSize(environment);
        ZipEntryNameEncoding entryNameEncoding =
                ZipArkivoFileSystem.ENTRY_NAME_ENCODING.readOrDefault(
                        environment,
                        ZipEntryNameEncoding.standard()
                );

        return new ZipArkivoFileSystemConfig(
                passwordProvider,
                readOnly,
                create,
                streamingWrite,
                defaultEncryption,
                splitSize,
                entryNameEncoding
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

    /// Returns the policy used to decode ZIP entry names when no authoritative Unicode name is available.
    public ZipEntryNameEncoding entryNameEncoding() {
        return entryNameEncoding;
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
            char[] fixedPassword = ZipArkivoFileSystem.PASSWORD.read(environment);
            return fixedPassword != null ? ArkivoPasswordProvider.fixed(fixedPassword) : null;
        }
        return null;
    }

    /// Parses the split size from an environment map.
    private static @Nullable Long splitSize(Map<String, ?> environment) {
        Number value = ZipArkivoFileSystem.SPLIT_SIZE.read(environment);
        if (value == null) {
            return null;
        }
        return value.longValue();
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemOpenModes;
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
            ZipEncryption.none(),
            null,
            ZipEntryNameEncoding.standard(),
            ArkivoFileSystemOpenModes.RANDOM_READ
    );

    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The encryption method used for new entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume.
    private final @Nullable Long splitSize;

    /// The policy used to decode ZIP entry names when no authoritative Unicode name is available.
    private final ZipEntryNameEncoding entryNameEncoding;

    /// The access capabilities enabled for ZIP archive storage.
    private final ArkivoFileSystemOpenModes openModes;

    /// Creates parsed ZIP file system configuration.
    public ZipArkivoFileSystemConfig(
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            @Nullable Long splitSize,
            ZipEntryNameEncoding entryNameEncoding,
            ArkivoFileSystemOpenModes openModes
    ) {
        if (splitSize != null && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        this.passwordProvider = passwordProvider;
        this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        this.splitSize = splitSize;
        this.entryNameEncoding = Objects.requireNonNull(entryNameEncoding, "entryNameEncoding");
        this.openModes = Objects.requireNonNull(openModes, "openModes");
    }

    /// Parses ZIP file system configuration from an environment map.
    public static ZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.isEmpty()) {
            return DEFAULTS;
        }

        ArkivoPasswordProvider passwordProvider = passwordProvider(environment);
        ZipEncryption defaultEncryption =
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.readOrDefault(environment, ZipEncryption.none());
        Long splitSize = splitSize(environment);
        ZipEntryNameEncoding entryNameEncoding =
                ZipArkivoFileSystem.ENTRY_NAME_ENCODING.readOrDefault(
                        environment,
                        ZipEntryNameEncoding.standard()
                );
        ArkivoFileSystemOpenModes openModes =
                ArkivoFileSystem.OPEN_MODES.readOrDefault(environment, ArkivoFileSystemOpenModes.RANDOM_READ);

        return new ZipArkivoFileSystemConfig(
                passwordProvider,
                defaultEncryption,
                splitSize,
                entryNameEncoding,
                openModes
        );
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
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

    /// Returns the access capabilities enabled for ZIP archive storage.
    public ArkivoFileSystemOpenModes openModes() {
        return openModes;
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

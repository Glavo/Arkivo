// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystemOption;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens ZIP archives as NIO file systems.
@NotNullByDefault
public final class ZipArkivoFileSystem {
    /// The environment key for an `ArkivoPasswordProvider` value.
    public static final String PASSWORD_PROVIDER = "passwordProvider";

    /// The typed environment option for an `ArkivoPasswordProvider` value.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER_OPTION =
            ArkivoFileSystemOption.of(PASSWORD_PROVIDER, ArkivoPasswordProvider.class);

    /// The environment key for a fixed `char[]` password value.
    public static final String PASSWORD = "password";

    /// The typed environment option for a fixed `char[]` password value.
    public static final ArkivoFileSystemOption<char[]> PASSWORD_OPTION =
            ArkivoFileSystemOption.of(PASSWORD, char[].class, String::toCharArray);

    /// The environment key for a `Boolean` value that requests read-only access.
    public static final String READ_ONLY = "readOnly";

    /// The typed environment option for a `Boolean` value that requests read-only access.
    public static final ArkivoFileSystemOption<Boolean> READ_ONLY_OPTION =
            ArkivoFileSystemOption.of(READ_ONLY, Boolean.class, ZipArkivoFileSystem::parseBoolean);

    /// The environment key for a `Boolean` value that requests creating a new ZIP archive.
    public static final String CREATE = "create";

    /// The typed environment option for a `Boolean` value that requests creating a new ZIP archive.
    public static final ArkivoFileSystemOption<Boolean> CREATE_OPTION =
            ArkivoFileSystemOption.of(CREATE, Boolean.class, ZipArkivoFileSystem::parseBoolean);

    /// The environment key for a `Boolean` value that requests append-only streaming write semantics.
    public static final String STREAMING_WRITE = "streamingWrite";

    /// The typed environment option for a `Boolean` value that requests append-only streaming write semantics.
    public static final ArkivoFileSystemOption<Boolean> STREAMING_WRITE_OPTION =
            ArkivoFileSystemOption.of(STREAMING_WRITE, Boolean.class, ZipArkivoFileSystem::parseBoolean);

    /// The environment key for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final String DEFAULT_ENCRYPTION = "defaultEncryption";

    /// The typed environment option for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final ArkivoFileSystemOption<ZipEncryption> DEFAULT_ENCRYPTION_OPTION =
            ArkivoFileSystemOption.of(DEFAULT_ENCRYPTION, ZipEncryption.class, ZipEncryption::of);

    /// The environment key for a `Number` value that sets the maximum size of each output volume.
    public static final String SPLIT_SIZE = "splitSize";

    /// The typed environment option for a `Number` value that sets the maximum size of each output volume.
    public static final ArkivoFileSystemOption<Number> SPLIT_SIZE_OPTION =
            ArkivoFileSystemOption.of(SPLIT_SIZE, Number.class, Long::parseLong);

    /// Creates no instances.
    private ZipArkivoFileSystem() {
    }

    /// Opens a ZIP archive file system.
    public static FileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a ZIP archive file system with environment options.
    public static FileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ZipArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a split ZIP archive file system.
    public static FileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, Map.of());
    }

    /// Opens a split ZIP archive file system with environment options.
    public static FileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig.fromEnvironment(environment);
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Parses a boolean string accepted by ZIP file system environment options.
    private static Boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("Expected boolean string: " + value);
        };
    }
}

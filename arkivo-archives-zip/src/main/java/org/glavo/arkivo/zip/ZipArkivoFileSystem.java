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
    /// The environment option for an `ArkivoPasswordProvider` value.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArkivoFileSystemOption.of("passwordProvider", ArkivoPasswordProvider.class);

    /// The environment option for a fixed `char[]` password value.
    public static final ArkivoFileSystemOption<char[]> PASSWORD =
            ArkivoFileSystemOption.of("password", char[].class, String::toCharArray);

    /// The environment option for a `Boolean` value that requests read-only access.
    public static final ArkivoFileSystemOption<Boolean> READ_ONLY =
            ArkivoFileSystemOption.of("readOnly", Boolean.class, ZipArkivoFileSystem::parseBoolean);

    /// The environment option for a `Boolean` value that requests creating a new ZIP archive.
    public static final ArkivoFileSystemOption<Boolean> CREATE =
            ArkivoFileSystemOption.of("create", Boolean.class, ZipArkivoFileSystem::parseBoolean);

    /// The environment option for a `Boolean` value that requests append-only streaming write semantics.
    public static final ArkivoFileSystemOption<Boolean> STREAMING_WRITE =
            ArkivoFileSystemOption.of("streamingWrite", Boolean.class, ZipArkivoFileSystem::parseBoolean);

    /// The environment option for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final ArkivoFileSystemOption<ZipEncryption> DEFAULT_ENCRYPTION =
            ArkivoFileSystemOption.of("defaultEncryption", ZipEncryption.class, ZipEncryption::of);

    /// The environment option for a `Number` value that sets the maximum size of each output volume.
    public static final ArkivoFileSystemOption<Number> SPLIT_SIZE =
            ArkivoFileSystemOption.of("splitSize", Number.class, Long::parseLong);

    /// The environment option for a `ZipEntryNameEncoding` value that controls entry name decoding.
    public static final ArkivoFileSystemOption<ZipEntryNameEncoding> ENTRY_NAME_ENCODING =
            ArkivoFileSystemOption.of("entryNameEncoding", ZipEntryNameEncoding.class, ZipEntryNameEncoding::parse);

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

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

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

    /// The environment key for a fixed `char[]` password value.
    public static final String PASSWORD = "password";

    /// The environment key for a `Boolean` value that requests read-only access.
    public static final String READ_ONLY = "readOnly";

    /// The environment key for a `Boolean` value that requests creating a new ZIP archive.
    public static final String CREATE = "create";

    /// The environment key for a `Boolean` value that requests append-only streaming write semantics.
    public static final String STREAMING_WRITE = "streamingWrite";

    /// The environment key for a `ZipEncryption` value used as the default encryption method for new entries.
    public static final String DEFAULT_ENCRYPTION = "defaultEncryption";

    /// The environment key for a `Number` value that sets the maximum size of each output volume.
    public static final String SPLIT_SIZE = "splitSize";

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
}

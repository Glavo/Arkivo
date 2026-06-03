// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Objects;

/// Opens ZIP archives as NIO file systems.
@NotNullByDefault
public final class ZipArkivoFileSystem {
    /// Creates no instances.
    private ZipArkivoFileSystem() {
    }

    /// Opens a ZIP archive file system.
    public static FileSystem open(Path path) throws IOException {
        return open(path, ZipArkivoFileSystemOptions.defaults());
    }

    /// Opens a ZIP archive file system with explicit file system options.
    public static FileSystem open(Path path, ZipArkivoFileSystemOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return ZipArkivoFileSystemProvider.instance().newFileSystem(path, ZipArkivoFileSystemOptions.environment(options));
    }

    /// Opens a split ZIP archive file system.
    public static FileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, ZipArkivoFileSystemOptions.defaults());
    }

    /// Opens a split ZIP archive file system with explicit file system options.
    public static FileSystem open(ArkivoVolumeSource volumes, ZipArkivoFileSystemOptions options) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/// Opens ZIP archives as NIO file systems.
@NotNullByDefault
public final class ZipArkivoFileSystem {
    /// Creates no instances.
    private ZipArkivoFileSystem() {
    }

    /// Opens a ZIP archive file system.
    public static FileSystem open(Path path) throws IOException {
        return open(path, ZipReadOptions.defaults());
    }

    /// Opens a ZIP archive file system with explicit read options.
    public static FileSystem open(Path path, ZipReadOptions options) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }

    /// Opens a split ZIP archive file system.
    public static FileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, ZipReadOptions.defaults());
    }

    /// Opens a split ZIP archive file system with explicit read options.
    public static FileSystem open(ArkivoVolumeSource volumes, ZipReadOptions options) throws IOException {
        throw new UnsupportedOperationException("ZIP archive file systems are not implemented yet");
    }
}

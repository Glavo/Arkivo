// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/// Describes the ZIP archive format support provided by Arkivo.
@NotNullByDefault
public final class ZipArkivoFormat implements ArkivoFormat {
    /// The stable ZIP format name.
    public static final String NAME = "zip";

    /// The shared ZIP format instance.
    private static final ZipArkivoFormat INSTANCE = new ZipArkivoFormat();

    /// Creates a ZIP format descriptor.
    public ZipArkivoFormat() {
    }

    /// Returns the shared ZIP format descriptor.
    public static ZipArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable ZIP format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Opens a ZIP archive file system.
    public FileSystem open(Path path) throws IOException {
        return ZipArkivoFileSystem.open(path);
    }

    /// Opens a ZIP archive file system with explicit file system options.
    public FileSystem open(Path path, ZipArkivoFileSystemOptions options) throws IOException {
        return ZipArkivoFileSystem.open(path, options);
    }

    /// Opens a split ZIP archive file system.
    public FileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return ZipArkivoFileSystem.open(volumes);
    }

    /// Opens a split ZIP archive file system with explicit file system options.
    public FileSystem open(ArkivoVolumeSource volumes, ZipArkivoFileSystemOptions options) throws IOException {
        return ZipArkivoFileSystem.open(volumes, options);
    }
}

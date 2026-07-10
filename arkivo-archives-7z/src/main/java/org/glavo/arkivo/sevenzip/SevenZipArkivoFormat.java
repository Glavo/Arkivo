// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/// Describes the 7z archive format support provided by Arkivo.
@NotNullByDefault
public final class SevenZipArkivoFormat implements ArkivoFormat {
    /// The stable 7z format name.
    public static final String NAME = "7z";

    /// The shared 7z format instance.
    private static final SevenZipArkivoFormat INSTANCE = new SevenZipArkivoFormat();

    /// Creates a 7z format descriptor.
    public SevenZipArkivoFormat() {
    }

    /// Returns the shared 7z format descriptor.
    public static SevenZipArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable 7z format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Opens a 7z archive file system.
    public ArkivoFileSystem open(Path path) throws IOException {
        return SevenZipArkivoFileSystem.open(path);
    }

    /// Opens a 7z archive file system with environment options.
    public ArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        return SevenZipArkivoFileSystem.open(path, environment);
    }

    /// Opens a read-only 7z archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public ArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return SevenZipArkivoFileSystem.open(source);
    }

    /// Opens a read-only 7z archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public ArkivoFileSystem open(ArkivoSeekableChannelSource source, Map<String, ?> environment) throws IOException {
        return SevenZipArkivoFileSystem.open(source, environment);
    }

    /// Opens a multi-volume 7z archive file system.
    public ArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return SevenZipArkivoFileSystem.open(volumes);
    }

    /// Opens a multi-volume 7z archive file system with environment options.
    public ArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        return SevenZipArkivoFileSystem.open(volumes, environment);
    }

    /// Creates a forward-only 7z file system that publishes split output to a transactional volume target.
    public ArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return SevenZipArkivoFileSystem.create(target, splitSize);
    }

    /// Creates a forward-only 7z file system over a transactional volume target with environment options.
    public ArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        return SevenZipArkivoFileSystem.create(target, splitSize, environment);
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
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

    /// Returns alternative names for the 7z format.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("sevenzip");
    }

    /// Returns common 7z archive file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("7z");
    }

    /// Returns the number of leading bytes used to identify 7z archives.
    @Override
    public int probeSize() {
        return 6;
    }

    /// Returns whether the prefix starts with the 7z archive signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        return prefix.remaining() >= 6
                && prefix.get(position) == '7'
                && prefix.get(position + 1) == 'z'
                && prefix.get(position + 2) == (byte) 0xbc
                && prefix.get(position + 3) == (byte) 0xaf
                && prefix.get(position + 4) == 0x27
                && prefix.get(position + 5) == 0x1c;
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

    /// Opens a complete-rewrite update over multi-volume input and transactional output.
    public ArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return SevenZipArkivoFileSystem.update(source, target, splitSize);
    }

    /// Opens a complete-rewrite multi-volume update with environment options.
    public ArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        return SevenZipArkivoFileSystem.update(source, target, splitSize, environment);
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

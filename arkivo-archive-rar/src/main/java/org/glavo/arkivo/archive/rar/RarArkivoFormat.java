// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoPathVolumeFormat;
import org.glavo.arkivo.archive.ArkivoVolumeFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.rar.internal.RarSplitVolumePaths;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Describes RAR4 and RAR5 archive support provided by Arkivo.
@NotNullByDefault
public final class RarArkivoFormat implements
        ArkivoPathVolumeFormat,
        ArkivoVolumeFileSystemFormat,
        ArkivoVolumeStreamingReaderFormat {
    /// The stable RAR format name.
    public static final String NAME = "rar";

    /// The shared RAR format instance.
    private static final RarArkivoFormat INSTANCE = new RarArkivoFormat();

    /// Creates a RAR format descriptor.
    public RarArkivoFormat() {
    }

    /// Returns the shared RAR format descriptor.
    public static RarArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable RAR format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common RAR archive file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("rar");
    }

    /// Returns the number of leading bytes used to identify RAR4 and RAR5 archives.
    @Override
    public int probeSize() {
        return 8;
    }

    /// Returns whether the prefix starts with a RAR4 or RAR5 archive signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 7
                || prefix.get(position) != 'R'
                || prefix.get(position + 1) != 'a'
                || prefix.get(position + 2) != 'r'
                || prefix.get(position + 3) != '!'
                || prefix.get(position + 4) != 0x1a
                || prefix.get(position + 5) != 0x07) {
            return false;
        }
        int version = Byte.toUnsignedInt(prefix.get(position + 6));
        return version == 0 || version == 1
                && prefix.remaining() >= 8
                && prefix.get(position + 7) == 0;
    }

    /// Discovers conventional modern or legacy RAR physical volume paths.
    @Override
    public @Nullable @Unmodifiable List<Path> discoverVolumePaths(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return RarSplitVolumePaths.discover(path);
    }

    /// Opens a RAR archive file system.
    @Override
    public RarArkivoFileSystem open(Path path) throws IOException {
        return RarArkivoFileSystem.open(path);
    }

    /// Opens a RAR archive file system with options.
    @Override
    public RarArkivoFileSystem open(Path path, ArchiveOptions options) throws IOException {
        return RarArkivoFileSystem.open(path, options);
    }

    /// Opens a read-only RAR archive file system directly from one owned seekable channel.
    @Override
    public RarArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return RarArkivoFileSystem.open(source);
    }

    /// Opens a read-only RAR archive file system directly from one owned seekable channel with options.
    @Override
    public RarArkivoFileSystem open(SeekableByteChannel source, ArchiveOptions options) throws IOException {
        return RarArkivoFileSystem.open(source, options);
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public RarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return RarArkivoFileSystem.open(source);
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public RarArkivoFileSystem open(ArkivoSeekableChannelSource source, ArchiveOptions options) throws IOException {
        return RarArkivoFileSystem.open(source, options);
    }

    /// Opens a multi-volume RAR archive file system.
    @Override
    public RarArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return RarArkivoFileSystem.open(volumes);
    }

    /// Opens a multi-volume RAR archive file system with options.
    @Override
    public RarArkivoFileSystem open(ArkivoVolumeSource volumes, ArchiveOptions options) throws IOException {
        return RarArkivoFileSystem.open(volumes, options);
    }

    /// Opens a streaming RAR reader from a path and discovers conventional split storage.
    @Override
    public RarArkivoStreamingReader openStreamingReader(Path path) throws IOException {
        return RarArkivoStreamingReader.open(path);
    }

    /// Opens a configured streaming RAR reader from a path and discovers conventional split storage.
    @Override
    public RarArkivoStreamingReader openStreamingReader(
            Path path,
            ArchiveOptions options
    ) throws IOException {
        return RarArkivoStreamingReader.open(path, options);
    }

    /// Opens a streaming RAR reader from a multi-volume source.
    @Override
    public RarArkivoStreamingReader openStreamingReader(ArkivoVolumeSource source) {
        return RarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming RAR reader from a multi-volume source with options.
    @Override
    public RarArkivoStreamingReader openStreamingReader(
            ArkivoVolumeSource source,
            ArchiveOptions options
    ) {
        return RarArkivoStreamingReader.open(source, options);
    }

    /// Opens a streaming RAR reader from an input stream.
    @Override
    public RarArkivoStreamingReader openStreamingReader(InputStream source) {
        return RarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming RAR reader from an input stream with options.
    @Override
    public RarArkivoStreamingReader openStreamingReader(
            InputStream source,
            ArchiveOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return RarArkivoStreamingReader.open(source, options);
    }

    /// Opens a streaming RAR reader from a readable channel.
    @Override
    public RarArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return RarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming RAR reader from a readable channel with options.
    @Override
    public RarArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return RarArkivoStreamingReader.open(source, options);
    }
}

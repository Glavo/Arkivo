// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFormat.FileSystem;
import org.glavo.arkivo.archive.ArkivoFormat.PathVolume;
import org.glavo.arkivo.archive.ArkivoFormat.VolumeFileSystem;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoFormat.VolumeStreamingWriter;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipSplitVolumePaths;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Describes Arkivo's indexed and streaming 7z support.
///
/// This descriptor opens read-only, creation, and complete-rewrite NIO file systems and forward-only streaming
/// writers. It also discovers conventional numbered volumes and creates transactional split output. The generic
/// `Archive*Options` overloads apply the 7z defaults; use the concrete 7z factories when password, compression,
/// filters, solid grouping, or header encryption must be selected.
///
/// Factory methods have the same source ownership and close-time publication contracts as
/// [SevenZipArkivoFileSystem] and [SevenZipArkivoStreamingWriter].
@NotNullByDefault
public final class SevenZipArkivoFormat implements
        PathVolume,
        VolumeFileSystem.Writable,
        VolumeStreamingWriter,
        FileSystem.Writable {
    /// The stable 7z format name.
    public static final String NAME = "7z";

    /// The shared 7z format instance.
    private static final SevenZipArkivoFormat INSTANCE = new SevenZipArkivoFormat();

    /// Creates the canonical 7z format descriptor.
    private SevenZipArkivoFormat() {
    }

    /// Returns the shared 7z format descriptor.
    ///
    /// @return the process-wide immutable descriptor
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

    /// Discovers conventional numbered 7z physical volume paths.
    @Override
    public @Nullable @Unmodifiable List<Path> discoverVolumePaths(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return SevenZipSplitVolumePaths.discover(path);
    }

    /// Opens a streaming 7z writer over an output stream.
    @Override
    public SevenZipArkivoStreamingWriter openStreamingWriter(OutputStream output) throws IOException {
        return SevenZipArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming 7z writer over an output stream with options.
    @Override
    public SevenZipArkivoStreamingWriter openStreamingWriter(
            OutputStream output,
            ArchiveCreateOptions options
    ) throws IOException {
        return SevenZipArkivoStreamingWriter.open(output, createOptions(options));
    }

    /// Opens a streaming 7z writer over a writable channel.
    @Override
    public SevenZipArkivoStreamingWriter openStreamingWriter(WritableByteChannel output) throws IOException {
        return SevenZipArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming 7z writer over a writable channel with options.
    @Override
    public SevenZipArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel output,
            ArchiveCreateOptions options
    ) throws IOException {
        return SevenZipArkivoStreamingWriter.open(output, createOptions(options));
    }

    /// Opens a split streaming 7z writer over a transactional volume target.
    @Override
    public SevenZipArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return SevenZipArkivoStreamingWriter.open(target, splitSize);
    }

    /// Opens a split streaming 7z writer over a transactional volume target with options.
    @Override
    public SevenZipArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveCreateOptions options
    ) throws IOException {
        return SevenZipArkivoStreamingWriter.open(target, splitSize, createOptions(options));
    }

    /// Opens a 7z archive file system.
    @Override
    public SevenZipArkivoFileSystem open(Path path) throws IOException {
        return SevenZipArkivoFileSystem.open(path);
    }

    /// Opens a 7z archive file system with options.
    @Override
    public SevenZipArkivoFileSystem open(Path path, ArchiveReadOptions options) throws IOException {
        return SevenZipArkivoFileSystem.open(path, new SevenZipArchiveOptions.Read(options));
    }

    /// Creates or replaces a path-backed 7z archive with common creation options.
    @Override
    public SevenZipArkivoFileSystem create(Path path, ArchiveCreateOptions options) throws IOException {
        return SevenZipArkivoFileSystem.create(path, createOptions(options));
    }

    /// Opens a path-backed 7z archive for complete-rewrite update with common options.
    @Override
    public SevenZipArkivoFileSystem update(Path path, ArchiveUpdateOptions options) throws IOException {
        return SevenZipArkivoFileSystem.update(path, updateOptions(options));
    }

    /// Opens a read-only 7z archive file system directly from one owned seekable channel.
    @Override
    public SevenZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return SevenZipArkivoFileSystem.open(source);
    }

    /// Opens a 7z archive file system directly from one owned seekable channel with options.
    @Override
    public SevenZipArkivoFileSystem open(SeekableByteChannel source, ArchiveReadOptions options) throws IOException {
        return SevenZipArkivoFileSystem.open(source, new SevenZipArchiveOptions.Read(options));
    }

    /// Opens a read-only 7z archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public SevenZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return SevenZipArkivoFileSystem.open(source);
    }

    /// Opens a 7z archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public SevenZipArkivoFileSystem open(ArkivoSeekableChannelSource source, ArchiveReadOptions options) throws IOException {
        return SevenZipArkivoFileSystem.open(source, new SevenZipArchiveOptions.Read(options));
    }

    /// Opens a multi-volume 7z archive file system.
    @Override
    public SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return SevenZipArkivoFileSystem.open(volumes);
    }

    /// Opens a multi-volume 7z archive file system with options.
    @Override
    public SevenZipArkivoFileSystem open(ArkivoVolumeSource volumes, ArchiveReadOptions options) throws IOException {
        return SevenZipArkivoFileSystem.open(volumes, new SevenZipArchiveOptions.Read(options));
    }

    /// Opens a complete-rewrite update over multi-volume input and transactional output.
    @Override
    public SevenZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return SevenZipArkivoFileSystem.update(source, target, splitSize);
    }

    /// Opens a complete-rewrite multi-volume update with options.
    @Override
    public SevenZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveUpdateOptions options
    ) throws IOException {
        return SevenZipArkivoFileSystem.update(source, target, splitSize, new SevenZipArchiveOptions.Update(
                options,
                SevenZipCompression.copy(),
                SevenZipFilterChain.EMPTY,
                SevenZipArchiveOptions.DEFAULT_SOLID_FILE_COUNT,
                false
        ));
    }

    /// Creates a forward-only 7z file system that publishes split output to a transactional volume target.
    @Override
    public SevenZipArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return SevenZipArkivoFileSystem.create(target, splitSize);
    }

    /// Creates a forward-only 7z file system over a transactional volume target with options.
    @Override
    public SevenZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveCreateOptions options
    ) throws IOException {
        return SevenZipArkivoFileSystem.create(target, splitSize, createOptions(options));
    }

    /// Applies 7z defaults to format-independent creation options.
    private static SevenZipArchiveOptions.Create createOptions(ArchiveCreateOptions options) {
        return new SevenZipArchiveOptions.Create(
                options,
                SevenZipCompression.copy(),
                SevenZipFilterChain.EMPTY,
                SevenZipArchiveOptions.DEFAULT_SOLID_FILE_COUNT,
                false
        );
    }

    /// Applies 7z defaults to format-independent update options.
    private static SevenZipArchiveOptions.Update updateOptions(ArchiveUpdateOptions options) {
        return new SevenZipArchiveOptions.Update(
                options,
                SevenZipCompression.copy(),
                SevenZipFilterChain.EMPTY,
                SevenZipArchiveOptions.DEFAULT_SOLID_FILE_COUNT,
                false
        );
    }
}

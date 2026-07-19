// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFormat.FileSystem;
import org.glavo.arkivo.archive.ArkivoFormat.PathVolume;
import org.glavo.arkivo.archive.ArkivoFormat.VolumeFileSystem;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoFormat.VolumeStreamingReader;
import org.glavo.arkivo.archive.ArkivoFormat.VolumeStreamingWriter;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.zip.internal.ZipSplitVolumePaths;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Describes Arkivo's indexed and streaming ZIP support.
///
/// This descriptor opens read-only, creation, and complete-rewrite NIO file systems, forward-only readers and writers,
/// and transactional split-volume output. Generic options retain CP437 legacy-name fallback, no password provider,
/// and no output encryption; use the concrete ZIP factories to select those features. Factory methods have the same
/// ownership, finalization, and publication contracts as [ZipArkivoFileSystem], [ZipArkivoStreamingReader], and
/// [ZipArkivoStreamingWriter].
@NotNullByDefault
public final class ZipArkivoFormat implements
        PathVolume,
        FileSystem.Writable,
        VolumeFileSystem.Writable,
        VolumeStreamingReader,
        VolumeStreamingWriter {
    /// The stable ZIP format name.
    public static final String NAME = "zip";

    /// The shared ZIP format instance.
    private static final ZipArkivoFormat INSTANCE = new ZipArkivoFormat();

    /// Creates the canonical ZIP format descriptor.
    private ZipArkivoFormat() {
    }

    /// Returns the shared ZIP format descriptor.
    ///
    /// @return the shared descriptor
    public static ZipArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable ZIP format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common ZIP-based archive file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("zip", "jar");
    }

    /// Returns the number of leading bytes used to identify ZIP archives.
    @Override
    public int probeSize() {
        return 4;
    }

    /// Returns whether the prefix starts with a ZIP archive signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 4
                || prefix.get(position) != 'P'
                || prefix.get(position + 1) != 'K') {
            return false;
        }
        int third = Byte.toUnsignedInt(prefix.get(position + 2));
        int fourth = Byte.toUnsignedInt(prefix.get(position + 3));
        return third == 3 && fourth == 4
                || third == 5 && fourth == 6
                || third == 7 && fourth == 8;
    }

    /// Discovers conventional `.z01`, `.z02`, ..., `.zip` physical volume paths.
    @Override
    public @Nullable @Unmodifiable List<Path> discoverVolumePaths(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return ZipSplitVolumePaths.discover(path);
    }

    /// Opens a ZIP archive file system.
    @Override
    public ZipArkivoFileSystem open(Path path) throws IOException {
        return ZipArkivoFileSystem.open(path);
    }

    /// Opens a ZIP archive file system with options.
    @Override
    public ZipArkivoFileSystem open(Path path, ArchiveReadOptions options) throws IOException {
        return ZipArkivoFileSystem.open(path, readOptions(options));
    }

    /// Creates a new path-backed ZIP archive file system with default ZIP behavior.
    @Override
    public ZipArkivoFileSystem create(Path path, ArchiveCreateOptions options) throws IOException {
        return ZipArkivoFileSystem.create(path, createOptions(options));
    }

    /// Opens a complete-rewrite update of an existing path-backed ZIP archive.
    @Override
    public ZipArkivoFileSystem update(Path path, ArchiveUpdateOptions options) throws IOException {
        return ZipArkivoFileSystem.update(path, updateOptions(options));
    }

    /// Opens a read-only ZIP archive file system directly from one owned seekable channel.
    @Override
    public ZipArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return ZipArkivoFileSystem.open(source);
    }

    /// Opens a ZIP archive file system directly from one owned seekable channel with options.
    @Override
    public ZipArkivoFileSystem open(SeekableByteChannel source, ArchiveReadOptions options) throws IOException {
        return ZipArkivoFileSystem.open(source, readOptions(options));
    }

    /// Opens a read-only ZIP archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public ZipArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return ZipArkivoFileSystem.open(source);
    }

    /// Opens a ZIP archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public ZipArkivoFileSystem open(ArkivoSeekableChannelSource source, ArchiveReadOptions options) throws IOException {
        return ZipArkivoFileSystem.open(source, readOptions(options));
    }

    /// Opens a split ZIP archive file system.
    @Override
    public ZipArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return ZipArkivoFileSystem.open(volumes);
    }

    /// Opens a split ZIP archive file system with options.
    @Override
    public ZipArkivoFileSystem open(ArkivoVolumeSource volumes, ArchiveReadOptions options) throws IOException {
        return ZipArkivoFileSystem.open(volumes, readOptions(options));
    }

    /// Opens a complete-rewrite update over multi-volume input and transactional output.
    @Override
    public ZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return ZipArkivoFileSystem.update(source, target, splitSize);
    }

    /// Opens a complete-rewrite multi-volume update with options.
    @Override
    public ZipArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveUpdateOptions options
    ) throws IOException {
        return ZipArkivoFileSystem.update(source, target, splitSize, updateOptions(options));
    }

    /// Creates a writable ZIP file system over a transactional volume target.
    @Override
    public ZipArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return ZipArkivoFileSystem.create(target, splitSize);
    }

    /// Creates a writable ZIP file system over a transactional volume target with options.
    @Override
    public ZipArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveCreateOptions options
    ) throws IOException {
        return ZipArkivoFileSystem.create(target, splitSize, createOptions(options));
    }

    /// Opens a streaming ZIP writer over an output stream.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(OutputStream output) {
        return ZipArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming ZIP writer over an output stream with options.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            OutputStream output,
            ArchiveCreateOptions options
    ) {
        return ZipArkivoStreamingWriter.open(output, createOptions(options));
    }

    /// Opens a streaming ZIP writer over a writable channel.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(WritableByteChannel output) {
        return ZipArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming ZIP writer over a writable channel with options.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel output,
            ArchiveCreateOptions options
    ) {
        return ZipArkivoStreamingWriter.open(output, createOptions(options));
    }

    /// Opens a split streaming ZIP writer over a transactional volume target.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return ZipArkivoStreamingWriter.open(target, splitSize);
    }

    /// Opens a split streaming ZIP writer over a transactional volume target with options.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveCreateOptions options
    ) throws IOException {
        return ZipArkivoStreamingWriter.open(target, splitSize, createOptions(options));
    }

    /// Opens a streaming ZIP reader from a path and discovers conventional split storage.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(Path path) throws IOException {
        return ZipArkivoStreamingReader.open(path);
    }

    /// Opens a configured streaming ZIP reader from a path and discovers conventional split storage.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(
            Path path,
            ArchiveReadOptions options
    ) throws IOException {
        return ZipArkivoStreamingReader.open(path, readOptions(options));
    }

    /// Opens a streaming ZIP reader from a multi-volume source.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(ArkivoVolumeSource source) throws IOException {
        return ZipArkivoStreamingReader.open(source);
    }

    /// Opens a streaming ZIP reader from a multi-volume source with options.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException {
        return ZipArkivoStreamingReader.open(source, readOptions(options));
    }

    /// Opens a streaming ZIP reader from an input stream.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(InputStream source) {
        return ZipArkivoStreamingReader.open(source);
    }

    /// Opens a streaming ZIP reader from an input stream with options.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(
            InputStream source,
            ArchiveReadOptions options
    ) {
        return ZipArkivoStreamingReader.open(source, readOptions(options));
    }

    /// Opens a streaming ZIP reader from a readable channel.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return ZipArkivoStreamingReader.open(source);
    }

    /// Opens a streaming ZIP reader from a readable channel with options.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            ArchiveReadOptions options
    ) {
        return ZipArkivoStreamingReader.open(source, readOptions(options));
    }

    /// Applies ZIP defaults to format-independent read options.
    private static ZipArchiveOptions.Read readOptions(ArchiveReadOptions options) {
        return new ZipArchiveOptions.Read(options);
    }

    /// Applies ZIP defaults to format-independent creation options.
    private static ZipArchiveOptions.Create createOptions(ArchiveCreateOptions options) {
        return new ZipArchiveOptions.Create(options, ZipEncryption.NONE);
    }

    /// Applies ZIP defaults to format-independent update options.
    private static ZipArchiveOptions.Update updateOptions(ArchiveUpdateOptions options) {
        return new ZipArchiveOptions.Update(options, ZipEncryption.NONE);
    }
}

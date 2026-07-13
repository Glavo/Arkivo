// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoPathVolumeFormat;
import org.glavo.arkivo.archive.ArkivoVolumeFileSystemFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeStreamingReaderFormat;
import org.glavo.arkivo.archive.ArkivoVolumeStreamingWriterFormat;
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
import java.util.Map;
import java.util.Objects;

/// Describes the ZIP archive format support provided by Arkivo.
@NotNullByDefault
public final class ZipArkivoFormat implements
        ArkivoPathVolumeFormat,
        ArkivoVolumeFileSystemFormat,
        ArkivoVolumeStreamingReaderFormat,
        ArkivoVolumeStreamingWriterFormat {
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
    public ArkivoFileSystem open(Path path) throws IOException {
        return ZipArkivoFileSystem.open(path);
    }

    /// Opens a ZIP archive file system with environment options.
    @Override
    public ArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        return ZipArkivoFileSystem.open(path, environment);
    }

    /// Opens a read-only ZIP archive file system directly from one owned seekable channel.
    @Override
    public ArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return ZipArkivoFileSystem.open(source);
    }

    /// Opens a ZIP archive file system directly from one owned seekable channel with environment options.
    @Override
    public ArkivoFileSystem open(SeekableByteChannel source, Map<String, ?> environment) throws IOException {
        return ZipArkivoFileSystem.open(source, environment);
    }

    /// Opens a read-only ZIP archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public ArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return ZipArkivoFileSystem.open(source);
    }

    /// Opens a ZIP archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    @Override
    public ArkivoFileSystem open(ArkivoSeekableChannelSource source, Map<String, ?> environment) throws IOException {
        return ZipArkivoFileSystem.open(source, environment);
    }

    /// Opens a split ZIP archive file system.
    @Override
    public ArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return ZipArkivoFileSystem.open(volumes);
    }

    /// Opens a split ZIP archive file system with environment options.
    @Override
    public ArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        return ZipArkivoFileSystem.open(volumes, environment);
    }

    /// Opens a complete-rewrite update over multi-volume input and transactional output.
    @Override
    public ArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return ZipArkivoFileSystem.update(source, target, splitSize);
    }

    /// Opens a complete-rewrite multi-volume update with environment options.
    @Override
    public ArkivoFileSystem update(
            ArkivoVolumeSource source,
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        return ZipArkivoFileSystem.update(source, target, splitSize, environment);
    }

    /// Creates a writable ZIP file system over a transactional volume target.
    @Override
    public ArkivoFileSystem create(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return ZipArkivoFileSystem.create(target, splitSize);
    }

    /// Creates a writable ZIP file system over a transactional volume target with environment options.
    @Override
    public ArkivoFileSystem create(
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        return ZipArkivoFileSystem.create(target, splitSize, environment);
    }

    /// Opens a streaming ZIP writer over an output stream.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(OutputStream output) {
        return ZipArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming ZIP writer over an output stream with environment options.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            OutputStream output,
            Map<String, ?> environment
    ) {
        return ZipArkivoStreamingWriter.open(output, environment);
    }

    /// Opens a streaming ZIP writer over a writable channel.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(WritableByteChannel output) {
        return ZipArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming ZIP writer over a writable channel with environment options.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            WritableByteChannel output,
            Map<String, ?> environment
    ) {
        return ZipArkivoStreamingWriter.open(output, environment);
    }

    /// Opens a split streaming ZIP writer over a transactional volume target.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return ZipArkivoStreamingWriter.open(target, splitSize);
    }

    /// Opens a split streaming ZIP writer over a transactional volume target with environment options.
    @Override
    public ZipArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        return ZipArkivoStreamingWriter.open(target, splitSize, environment);
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
            Map<String, ?> environment
    ) throws IOException {
        return ZipArkivoStreamingReader.open(path, environment);
    }

    /// Opens a streaming ZIP reader from a multi-volume source.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(ArkivoVolumeSource source) throws IOException {
        return ZipArkivoStreamingReader.open(source);
    }

    /// Opens a streaming ZIP reader from a multi-volume source with environment options.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(
            ArkivoVolumeSource source,
            Map<String, ?> environment
    ) throws IOException {
        return ZipArkivoStreamingReader.open(source, environment);
    }

    /// Opens a streaming ZIP reader from an input stream.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(InputStream source) {
        return ZipArkivoStreamingReader.open(source);
    }

    /// Opens a streaming ZIP reader from an input stream with environment options.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(
            InputStream source,
            Map<String, ?> environment
    ) {
        return ZipArkivoStreamingReader.open(source, environment);
    }

    /// Opens a streaming ZIP reader from a readable channel.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return ZipArkivoStreamingReader.open(source);
    }

    /// Opens a streaming ZIP reader from a readable channel with environment options.
    @Override
    public ZipArkivoStreamingReader openStreamingReader(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) {
        return ZipArkivoStreamingReader.open(source, environment);
    }
}

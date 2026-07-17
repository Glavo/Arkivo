// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Writes ZIP entries to a forward-only stream.
@NotNullByDefault
public abstract sealed class ZipArkivoStreamingWriter extends ArkivoStreamingWriter
        permits ZipArkivoStreamingWriterImpl {
    /// Creates a streaming ZIP writer base instance.
    protected ZipArkivoStreamingWriter() {
    }

    /// Creates a streaming ZIP writer that writes to an archive path.
    public static ZipArkivoStreamingWriter create(Path path) throws IOException {
        return create(path, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a streaming ZIP writer that writes to an archive path with options.
    public static ZipArkivoStreamingWriter create(Path path, ZipArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromCreateOptions(options);
        return ZipArkivoStreamingWriterImpl.create(path, config);
    }

    /// Opens a streaming ZIP writer over an output stream.
    public static ZipArkivoStreamingWriter open(OutputStream output) {
        return open(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a streaming ZIP writer over an output stream with options.
    public static ZipArkivoStreamingWriter open(OutputStream output, ZipArchiveOptions.Create options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), options);
    }

    /// Opens a streaming ZIP writer over a writable channel.
    public static ZipArkivoStreamingWriter open(WritableByteChannel output) {
        return open(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a streaming ZIP writer over a writable channel with options.
    public static ZipArkivoStreamingWriter open(
            WritableByteChannel output,
            ZipArchiveOptions.Create options
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromCreateOptions(options);
        return ZipArkivoStreamingWriterImpl.open(output, config);
    }

    /// Opens a split streaming ZIP writer over a transactional volume target.
    public static ZipArkivoStreamingWriter open(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return open(target, splitSize, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a split streaming ZIP writer over a transactional volume target with options.
    public static ZipArkivoStreamingWriter open(
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize < ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE
                || splitSize > ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE) {
            throw new IllegalArgumentException(
                    "splitSize must be between MINIMUM_SPLIT_SIZE and MAXIMUM_SPLIT_SIZE"
            );
        }
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromCreateOptions(options);
        return ZipArkivoStreamingWriterImpl.open(target, splitSize, config);
    }

}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Writes 7z entries through the common forward-only streaming writer API.
///
/// Stream and channel output is assembled in local seekable temporary storage because 7z finalization rewrites header
/// data. Closing the writer publishes the completed archive and closes the supplied stream or channel.
@NotNullByDefault
public abstract sealed class SevenZipArkivoStreamingWriter extends ArkivoStreamingWriter
        permits SevenZipArkivoStreamingWriterImpl {
    /// Creates a streaming 7z writer base instance.
    protected SevenZipArkivoStreamingWriter() {
    }

    /// Creates a streaming 7z writer that writes to an archive path.
    public static SevenZipArkivoStreamingWriter create(Path path) throws IOException {
        return create(path, ArchiveOptions.EMPTY);
    }

    /// Creates a streaming 7z writer that writes to an archive path with options.
    public static SevenZipArkivoStreamingWriter create(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromWriterOptions(options);
        if (!config.archiveWritable()) {
            throw new IllegalArgumentException("7z streaming writer open options must include WRITE");
        }
        return SevenZipArkivoStreamingWriterImpl.create(path, config);
    }

    /// Opens a streaming 7z writer over an owned output stream.
    public static SevenZipArkivoStreamingWriter open(OutputStream output) throws IOException {
        return open(output, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming 7z writer over an owned output stream with options.
    public static SevenZipArkivoStreamingWriter open(
            OutputStream output,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), options);
    }

    /// Opens a streaming 7z writer over an owned writable channel.
    public static SevenZipArkivoStreamingWriter open(WritableByteChannel output) throws IOException {
        return open(output, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming 7z writer over an owned writable channel with options.
    public static SevenZipArkivoStreamingWriter open(
            WritableByteChannel output,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return SevenZipArkivoStreamingWriterImpl.open(
                output,
                directOutputConfig(options)
        );
    }

    /// Opens a split streaming 7z writer over a transactional volume target.
    public static SevenZipArkivoStreamingWriter open(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return open(target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Opens a split streaming 7z writer over a transactional volume target with options.
    public static SevenZipArkivoStreamingWriter open(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        SevenZipArkivoFileSystemConfig config = directOutputConfig(options);
        return SevenZipArkivoStreamingWriterImpl.open(target, splitSize, config);
    }

    /// Parses output options whose storage behavior is determined by the factory.
    private static SevenZipArkivoFileSystemConfig directOutputConfig(ArchiveOptions options) {
        if (options.contains(ArkivoFileSystem.OPEN_OPTIONS)) {
            throw new IllegalArgumentException("7z streaming output open options are determined by the factory");
        }
        if (options.contains(SevenZipArkivoFileSystem.SPLIT_SIZE)) {
            throw new IllegalArgumentException("7z streaming output splitSize must be provided by the factory");
        }
        return SevenZipArkivoFileSystemConfig.fromWriterOptions(options);
    }

}

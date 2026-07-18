// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

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
///
/// Only one entry may be pending. Closing its `ArkivoStreamingWriter.Entry` commits it without opening a caller-writable
/// body; opening a regular-file body commits its metadata and transfers completion to that body. Another entry cannot
/// begin until the body closes successfully. Attribute views are configurable only while the entry is pending.
///
/// A successfully returned writer owns a supplied stream or channel. An `ArkivoVolumeTarget` remains caller-owned; the
/// writer owns and completes the transaction obtained from it. Writer close commits a pending entry, finalizes and
/// publishes the archive, and closes owned resources. Once close begins, entry operations stay closed after a failure;
/// another `close()` call retries incomplete finalization.
@NotNullByDefault
public abstract sealed class SevenZipArkivoStreamingWriter extends ArkivoStreamingWriter
        permits SevenZipArkivoStreamingWriterImpl {
    /// Creates a streaming 7z writer base instance.
    protected SevenZipArkivoStreamingWriter() {
    }

    /// Creates a streaming 7z writer that writes to an archive path.
    ///
    /// @param path the output archive path to create or replace
    /// @return an owned writer that publishes the archive on close
    /// @throws IOException if the output path or temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter create(Path path) throws IOException {
        return create(path, SevenZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a streaming 7z writer that writes to an archive path with options.
    ///
    /// @param path    the output archive path to create or replace
    /// @param options the output encoding, encryption, storage, and thread-safety policy
    /// @return an owned writer that publishes the archive on close
    /// @throws IOException if the output path or temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter create(Path path, SevenZipArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromCreateOptions(options);
        return SevenZipArkivoStreamingWriterImpl.create(path, config);
    }

    /// Opens a streaming 7z writer over an owned output stream.
    ///
    /// @param output the output stream transferred to the returned writer
    /// @return a writer that closes `output` after finalization
    /// @throws IOException if temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter open(OutputStream output) throws IOException {
        return open(output, SevenZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a streaming 7z writer over an owned output stream with options.
    ///
    /// @param output  the output stream transferred to the returned writer
    /// @param options the output encoding, encryption, storage, and thread-safety policy
    /// @return a writer that closes `output` after finalization
    /// @throws IOException if temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter open(
            OutputStream output,
            SevenZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), options);
    }

    /// Opens a streaming 7z writer over an owned writable channel.
    ///
    /// @param output the writable channel transferred to the returned writer
    /// @return a writer that closes `output` after finalization
    /// @throws IOException if temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter open(WritableByteChannel output) throws IOException {
        return open(output, SevenZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a streaming 7z writer over an owned writable channel with options.
    ///
    /// @param output  the writable channel transferred to the returned writer
    /// @param options the output encoding, encryption, storage, and thread-safety policy
    /// @return a writer that closes `output` after finalization
    /// @throws IOException if temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter open(
            WritableByteChannel output,
            SevenZipArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return SevenZipArkivoStreamingWriterImpl.open(
                output,
                directOutputConfig(options)
        );
    }

    /// Opens a split streaming 7z writer over a transactional volume target.
    ///
    /// @param target    the caller-owned transactional output-volume factory
    /// @param splitSize the positive maximum number of bytes per output volume
    /// @return a writer that commits all output volumes on close
    /// @throws IllegalArgumentException if `splitSize` is not positive
    /// @throws IOException if temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter open(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return open(target, splitSize, SevenZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a split streaming 7z writer over a transactional volume target with options.
    ///
    /// @param target    the caller-owned transactional output-volume factory
    /// @param splitSize the positive maximum number of bytes per output volume
    /// @param options   the output encoding, encryption, storage, and thread-safety policy
    /// @return a writer that commits all output volumes on close
    /// @throws IllegalArgumentException if `splitSize` is not positive
    /// @throws IOException if temporary seekable staging cannot be initialized
    public static SevenZipArkivoStreamingWriter open(
            ArkivoVolumeTarget target,
            long splitSize,
            SevenZipArchiveOptions.Create options
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
    private static SevenZipArkivoFileSystemConfig directOutputConfig(SevenZipArchiveOptions.Create options) {
        return SevenZipArkivoFileSystemConfig.fromCreateOptions(options);
    }

}

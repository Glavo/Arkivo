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
///
/// Only one entry may be pending. Closing its `ArkivoStreamingWriter.Entry` commits its fixed or empty body; opening a
/// caller-writable file body commits its metadata and transfers completion to the returned body. Another entry cannot
/// begin until that body closes successfully. Entry attribute views are configurable only before body opening or
/// entry close.
///
/// A successfully returned writer owns and closes a supplied stream or channel. A volume target remains caller-owned;
/// the writer owns the transaction opened from it and commits or rolls it back during close. Closing the writer commits
/// any pending entry and writes the central directory. Once close begins, entry operations stay closed after a failure;
/// another `close()` call retries incomplete finalization.
@NotNullByDefault
public abstract sealed class ZipArkivoStreamingWriter extends ArkivoStreamingWriter
        permits ZipArkivoStreamingWriterImpl {
    /// Creates a streaming ZIP writer base instance.
    protected ZipArkivoStreamingWriter() {
    }

    /// Creates a streaming ZIP writer that writes to an archive path.
    ///
    /// The destination is created or truncated during this call. The returned writer is positioned before its first
    /// entry, and the path does not contain a complete archive until writer close writes the central directory.
    ///
    /// @param path the destination archive path
    /// @return a new open streaming writer
    /// @throws NullPointerException if `path` is `null`
    /// @throws IOException if the destination cannot be prepared for writing
    public static ZipArkivoStreamingWriter create(Path path) throws IOException {
        return create(path, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a streaming ZIP writer that writes to an archive path with options.
    ///
    /// The destination is created or truncated during this call. The returned writer is positioned before its first
    /// entry, and the path does not contain a complete archive until writer close writes the central directory.
    ///
    /// @param path the destination archive path
    /// @param options the immutable creation configuration
    /// @return a new open streaming writer
    /// @throws NullPointerException if `path` or `options` is `null`
    /// @throws IOException if the destination cannot be prepared for writing
    public static ZipArkivoStreamingWriter create(Path path, ZipArchiveOptions.Create options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromCreateOptions(options);
        return ZipArkivoStreamingWriterImpl.create(path, config);
    }

    /// Opens a streaming ZIP writer over an output stream.
    ///
    /// ZIP output begins at the stream's current write location. A successful return transfers ownership to the writer,
    /// whose writes and close advance and may block on the stream.
    ///
    /// @param output the destination stream
    /// @return a new open streaming writer positioned before its first entry
    /// @throws NullPointerException if `output` is `null`
    public static ZipArkivoStreamingWriter open(OutputStream output) {
        return open(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a streaming ZIP writer over an output stream with options.
    ///
    /// ZIP output begins at the stream's current write location. A successful return transfers ownership to the writer,
    /// whose writes and close advance and may block on the stream.
    ///
    /// @param output the destination stream
    /// @param options the immutable creation configuration
    /// @return a new open streaming writer positioned before its first entry
    /// @throws NullPointerException if `output` or `options` is `null`
    public static ZipArkivoStreamingWriter open(OutputStream output, ZipArchiveOptions.Create options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), options);
    }

    /// Opens a streaming ZIP writer over a writable channel.
    ///
    /// No repositioning is attempted. For a seekable channel, output begins at and advances its current position;
    /// otherwise writes follow the channel's current output state. A successful return transfers ownership to the writer,
    /// whose writes and close may block.
    ///
    /// @param output the destination channel
    /// @return a new open streaming writer positioned before its first entry
    /// @throws NullPointerException if `output` is `null`
    public static ZipArkivoStreamingWriter open(WritableByteChannel output) {
        return open(output, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a streaming ZIP writer over a writable channel with options.
    ///
    /// No repositioning is attempted. For a seekable channel, output begins at and advances its current position;
    /// otherwise writes follow the channel's current output state. A successful return transfers ownership to the writer,
    /// whose writes and close may block.
    ///
    /// @param output the destination channel
    /// @param options the immutable creation configuration
    /// @return a new open streaming writer positioned before its first entry
    /// @throws NullPointerException if `output` or `options` is `null`
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
    ///
    /// The target remains caller-owned. This call opens a transaction owned by the writer and may block; close commits
    /// completed volumes or rolls the transaction back after a failure.
    ///
    /// @param target the caller-owned transactional destination for output volumes
    /// @param splitSize the maximum number of bytes written to each volume
    /// @return a new open streaming writer positioned before its first entry
    /// @throws NullPointerException if `target` is `null`
    /// @throws IllegalArgumentException if `splitSize` is outside [ZipArkivoFileSystem#MINIMUM_SPLIT_SIZE] through
    /// [ZipArkivoFileSystem#MAXIMUM_SPLIT_SIZE]
    /// @throws IOException if the output transaction cannot be opened
    public static ZipArkivoStreamingWriter open(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return open(target, splitSize, ZipArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a split streaming ZIP writer over a transactional volume target with options.
    ///
    /// The target remains caller-owned. This call opens a transaction owned by the writer and may block; close commits
    /// completed volumes or rolls the transaction back after a failure.
    ///
    /// @param target the caller-owned transactional destination for output volumes
    /// @param splitSize the maximum number of bytes written to each volume
    /// @param options the immutable creation configuration
    /// @return a new open streaming writer positioned before its first entry
    /// @throws NullPointerException if `target` or `options` is `null`
    /// @throws IllegalArgumentException if `splitSize` is outside [ZipArkivoFileSystem#MINIMUM_SPLIT_SIZE] through
    /// [ZipArkivoFileSystem#MAXIMUM_SPLIT_SIZE]
    /// @throws IOException if the output transaction cannot be opened
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

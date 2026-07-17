// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl;
import org.glavo.arkivo.archive.zip.internal.ZipVolumeReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Reads ZIP entries from a forward-only stream.
@NotNullByDefault
public abstract sealed class ZipArkivoStreamingReader extends ArkivoStreamingReader
        permits ZipArkivoStreamingReaderImpl {
    /// Creates a streaming ZIP reader base instance.
    protected ZipArkivoStreamingReader() {
    }

    /// Opens a streaming ZIP reader from a final archive path and discovers conventional split volumes.
    public static ZipArkivoStreamingReader open(Path path) throws IOException {
        return open(path, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a configured streaming ZIP reader from a final archive path and discovers conventional split volumes.
    public static ZipArkivoStreamingReader open(
            Path path,
            ZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return open(ZipArkivoFormat.instance().openVolumeSource(path), options);
    }

    /// Opens a streaming ZIP reader from a multi-volume source.
    public static ZipArkivoStreamingReader open(ArkivoVolumeSource source) throws IOException {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming ZIP reader from a multi-volume source with options.
    ///
    /// The returned reader owns the source and every physical volume channel it opens.
    public static ZipArkivoStreamingReader open(
            ArkivoVolumeSource source,
            ZipArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromReadOptions(options);
        ZipVolumeReadableByteChannel channel = new ZipVolumeReadableByteChannel(source);
        try {
            return new ZipArkivoStreamingReaderImpl(channel, config);
        } catch (RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error closeException) {
                if (exception != closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw exception;
        }
    }

    /// Opens a streaming ZIP reader from an input stream.
    public static ZipArkivoStreamingReader open(InputStream source) {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming ZIP reader from an input stream with options.
    public static ZipArkivoStreamingReader open(InputStream source, ZipArchiveOptions.Read options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Opens a streaming ZIP reader from a readable channel.
    public static ZipArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, ZipArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming ZIP reader from a readable channel with options.
    public static ZipArkivoStreamingReader open(
            ReadableByteChannel source,
            ZipArchiveOptions.Read options
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromReadOptions(options);
        return new ZipArkivoStreamingReaderImpl(source, config);
    }
}

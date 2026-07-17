// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.archive.tar.internal.TarCompressionStreams;
import org.glavo.arkivo.archive.tar.internal.TarArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Writes TAR entries to a forward-only stream.
///
/// Regular file bodies are staged until their final size can be written into the TAR header. Default factories use
/// temporary-file storage under the system temporary directory; overloads accepting `ArkivoEditStorage` let callers
/// select another policy. The writer owns and closes its output and body storage.
@NotNullByDefault
public abstract sealed class TarArkivoStreamingWriter extends ArkivoStreamingWriter
        permits TarArkivoStreamingWriterImpl {
    /// Creates a streaming TAR writer base instance.
    protected TarArkivoStreamingWriter() {
    }

    /// Creates a streaming TAR writer that writes to an archive path.
    public static TarArkivoStreamingWriter create(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(Files.newByteChannel(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ));
    }

    /// Creates a streaming TAR writer at an archive path with options.
    public static TarArkivoStreamingWriter create(
            Path path,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        @Nullable CompressionCodec<?> compressionCodec = options.compression();
        @Nullable ArkivoEditStorage bodyStorage = options.common().editStorage();
        WritableByteChannel archiveOutput = TarCompressionStreams.openArchiveOutput(
                Files.newByteChannel(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ),
                compressionCodec
        );
        return bodyStorage == null
                ? new TarArkivoStreamingWriterImpl(StreamChannelAdapters.outputStream(archiveOutput))
                : new TarArkivoStreamingWriterImpl(
                        StreamChannelAdapters.outputStream(archiveOutput),
                        bodyStorage
                );
    }

    /// Creates a streaming TAR writer that writes to an archive path and owns the given body storage.
    public static TarArkivoStreamingWriter create(Path path, ArkivoEditStorage bodyStorage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(Files.newByteChannel(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ), bodyStorage);
    }

    /// Creates a streaming TAR writer at an archive path with owned body storage and options.
    public static TarArkivoStreamingWriter create(
            Path path,
            ArkivoEditStorage bodyStorage,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(options, "options");
        if (options.common().editStorage() != null) {
            throw new IllegalArgumentException(
                    "TAR body storage must be provided either as an argument or an option"
            );
        }
        @Nullable CompressionCodec<?> compressionCodec = options.compression();
        return new TarArkivoStreamingWriterImpl(
                StreamChannelAdapters.outputStream(TarCompressionStreams.openArchiveOutput(
                        Files.newByteChannel(
                                path,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                        ),
                        compressionCodec
                )),
                bodyStorage
        );
    }

    /// Opens a streaming TAR writer over an output stream.
    public static TarArkivoStreamingWriter open(OutputStream output) {
        Objects.requireNonNull(output, "output");
        return open(StreamChannelAdapters.writableChannel(output));
    }

    /// Opens a streaming TAR writer over an output stream with options.
    public static TarArkivoStreamingWriter open(
            OutputStream output,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), options);
    }

    /// Opens a streaming TAR writer over an output stream and owns the given body storage.
    public static TarArkivoStreamingWriter open(OutputStream output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(StreamChannelAdapters.writableChannel(output), bodyStorage);
    }

    /// Opens a streaming TAR writer over an output stream with owned body storage and options.
    public static TarArkivoStreamingWriter open(
            OutputStream output,
            ArkivoEditStorage bodyStorage,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), bodyStorage, options);
    }

    /// Opens a streaming TAR writer over a writable channel.
    public static TarArkivoStreamingWriter open(WritableByteChannel output) {
        Objects.requireNonNull(output, "output");
        return new TarArkivoStreamingWriterImpl(StreamChannelAdapters.outputStream(output));
    }

    /// Opens a streaming TAR writer over a writable channel with options.
    public static TarArkivoStreamingWriter open(
            WritableByteChannel output,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        @Nullable CompressionCodec<?> compressionCodec = options.compression();
        @Nullable ArkivoEditStorage bodyStorage = options.common().editStorage();
        WritableByteChannel archiveOutput = TarCompressionStreams.openArchiveOutput(output, compressionCodec);
        return bodyStorage == null
                ? new TarArkivoStreamingWriterImpl(StreamChannelAdapters.outputStream(archiveOutput))
                : new TarArkivoStreamingWriterImpl(
                        StreamChannelAdapters.outputStream(archiveOutput),
                        bodyStorage
                );
    }

    /// Opens a streaming TAR writer over a writable channel and owns the given body storage.
    public static TarArkivoStreamingWriter open(WritableByteChannel output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        return new TarArkivoStreamingWriterImpl(
                StreamChannelAdapters.outputStream(output),
                Objects.requireNonNull(bodyStorage, "bodyStorage")
        );
    }

    /// Opens a streaming TAR writer over a writable channel with owned body storage and options.
    public static TarArkivoStreamingWriter open(
            WritableByteChannel output,
            ArkivoEditStorage bodyStorage,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(options, "options");
        if (options.common().editStorage() != null) {
            throw new IllegalArgumentException(
                    "TAR body storage must be provided either as an argument or an option"
            );
        }
        @Nullable CompressionCodec<?> compressionCodec = options.compression();
        return new TarArkivoStreamingWriterImpl(
                StreamChannelAdapters.outputStream(TarCompressionStreams.openArchiveOutput(
                        output,
                        compressionCodec
                )),
                bodyStorage
        );
    }

    /// Begins a pending hard link entry for the given logical archive path and target archive path.
    public final ArkivoStreamingWriter.Entry beginHardLink(String path, String target) throws IOException {
        String checkedTarget = Objects.requireNonNull(target, "target");
        return beginCustomEntry(path, checkedPath -> beginHardLinkEntry(checkedPath, checkedTarget));
    }

    /// Begins a format-specific pending hard link entry.
    protected abstract void beginHardLinkEntry(String path, String target) throws IOException;
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoStreamingWriter;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.tar.internal.TarCompressionStreams;
import org.glavo.arkivo.tar.internal.TarArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        return open(Files.newOutputStream(path));
    }

    /// Creates a streaming TAR writer at an archive path with environment options.
    public static TarArkivoStreamingWriter create(
            Path path,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        @Nullable CompressionCodec compressionCodec = TarArkivoFileSystem.COMPRESSION.read(environment);
        TarCompressionStreams.requireCompression(compressionCodec);
        return new TarArkivoStreamingWriterImpl(TarCompressionStreams.openArchiveOutput(
                Files.newOutputStream(path),
                compressionCodec
        ));
    }

    /// Creates a streaming TAR writer that writes to an archive path and owns the given body storage.
    public static TarArkivoStreamingWriter create(Path path, ArkivoEditStorage bodyStorage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(Files.newOutputStream(path), bodyStorage);
    }

    /// Creates a streaming TAR writer at an archive path with owned body storage and environment options.
    public static TarArkivoStreamingWriter create(
            Path path,
            ArkivoEditStorage bodyStorage,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(environment, "environment");
        @Nullable CompressionCodec compressionCodec = TarArkivoFileSystem.COMPRESSION.read(environment);
        TarCompressionStreams.requireCompression(compressionCodec);
        return new TarArkivoStreamingWriterImpl(
                TarCompressionStreams.openArchiveOutput(Files.newOutputStream(path), compressionCodec),
                bodyStorage
        );
    }

    /// Opens a streaming TAR writer over an output stream.
    public static TarArkivoStreamingWriter open(OutputStream output) {
        return new TarArkivoStreamingWriterImpl(Objects.requireNonNull(output, "output"));
    }

    /// Opens a streaming TAR writer over an output stream with environment options.
    public static TarArkivoStreamingWriter open(
            OutputStream output,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(environment, "environment");
        @Nullable CompressionCodec compressionCodec = TarArkivoFileSystem.COMPRESSION.read(environment);
        return new TarArkivoStreamingWriterImpl(
                TarCompressionStreams.openArchiveOutput(output, compressionCodec)
        );
    }

    /// Opens a streaming TAR writer over an output stream and owns the given body storage.
    public static TarArkivoStreamingWriter open(OutputStream output, ArkivoEditStorage bodyStorage) {
        return new TarArkivoStreamingWriterImpl(
                Objects.requireNonNull(output, "output"),
                Objects.requireNonNull(bodyStorage, "bodyStorage")
        );
    }

    /// Opens a streaming TAR writer over an output stream with owned body storage and environment options.
    public static TarArkivoStreamingWriter open(
            OutputStream output,
            ArkivoEditStorage bodyStorage,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(environment, "environment");
        @Nullable CompressionCodec compressionCodec = TarArkivoFileSystem.COMPRESSION.read(environment);
        return new TarArkivoStreamingWriterImpl(
                TarCompressionStreams.openArchiveOutput(output, compressionCodec),
                bodyStorage
        );
    }

    /// Opens a streaming TAR writer over a writable channel.
    public static TarArkivoStreamingWriter open(WritableByteChannel output) {
        Objects.requireNonNull(output, "output");
        return open(Channels.newOutputStream(output));
    }

    /// Opens a streaming TAR writer over a writable channel with environment options.
    public static TarArkivoStreamingWriter open(
            WritableByteChannel output,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(environment, "environment");
        return open(Channels.newOutputStream(output), environment);
    }

    /// Opens a streaming TAR writer over a writable channel and owns the given body storage.
    public static TarArkivoStreamingWriter open(WritableByteChannel output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        return open(Channels.newOutputStream(output), bodyStorage);
    }

    /// Opens a streaming TAR writer over a writable channel with owned body storage and environment options.
    public static TarArkivoStreamingWriter open(
            WritableByteChannel output,
            ArkivoEditStorage bodyStorage,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(environment, "environment");
        return open(Channels.newOutputStream(output), bodyStorage, environment);
    }

    /// Begins a pending hard link entry for the given logical archive path and target archive path.
    public abstract void beginHardLink(String path, String target) throws IOException;
}

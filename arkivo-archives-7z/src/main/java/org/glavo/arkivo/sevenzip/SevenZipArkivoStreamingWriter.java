// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoStreamingWriter;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoFileSystemConfig;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Map;
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
        return create(path, Map.of());
    }

    /// Creates a streaming 7z writer that writes to an archive path with environment options.
    public static SevenZipArkivoStreamingWriter create(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(environment);
        if (!config.archiveWritable()) {
            throw new IllegalArgumentException("7z streaming writer open options must include WRITE");
        }
        return SevenZipArkivoStreamingWriterImpl.create(path, config);
    }

    /// Opens a streaming 7z writer over an owned output stream.
    public static SevenZipArkivoStreamingWriter open(OutputStream output) throws IOException {
        return open(output, Map.of());
    }

    /// Opens a streaming 7z writer over an owned output stream with environment options.
    public static SevenZipArkivoStreamingWriter open(
            OutputStream output,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(environment, "environment");
        return SevenZipArkivoStreamingWriterImpl.open(
                output,
                directOutputConfig(environment)
        );
    }

    /// Opens a streaming 7z writer over an owned writable channel.
    public static SevenZipArkivoStreamingWriter open(WritableByteChannel output) throws IOException {
        return open(output, Map.of());
    }

    /// Opens a streaming 7z writer over an owned writable channel with environment options.
    public static SevenZipArkivoStreamingWriter open(
            WritableByteChannel output,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(environment, "environment");
        return SevenZipArkivoStreamingWriterImpl.open(
                output,
                directOutputConfig(environment)
        );
    }

    /// Opens a split streaming 7z writer over a transactional volume target.
    public static SevenZipArkivoStreamingWriter open(ArkivoVolumeTarget target, long splitSize) throws IOException {
        return open(target, splitSize, Map.of());
    }

    /// Opens a split streaming 7z writer over a transactional volume target with environment options.
    public static SevenZipArkivoStreamingWriter open(
            ArkivoVolumeTarget target,
            long splitSize,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(environment, "environment");
        if (splitSize <= 0L) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        SevenZipArkivoFileSystemConfig config = directOutputConfig(environment);
        return SevenZipArkivoStreamingWriterImpl.open(target, splitSize, config);
    }

    /// Parses output options whose storage behavior is determined by the factory.
    private static SevenZipArkivoFileSystemConfig directOutputConfig(Map<String, ?> environment) {
        if (environment.containsKey(ArkivoFileSystem.OPEN_OPTIONS.key())) {
            throw new IllegalArgumentException("7z streaming output open options are determined by the factory");
        }
        if (environment.containsKey(SevenZipArkivoFileSystem.SPLIT_SIZE.key())) {
            throw new IllegalArgumentException("7z streaming output splitSize must be provided by the factory");
        }
        return SevenZipArkivoFileSystemConfig.fromWriterEnvironment(environment);
    }

    /// Closes this streaming writer, publishes the completed archive, and closes owned output.
    @Override
    public abstract void close() throws IOException;
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.zip.internal.ZipArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Writes ZIP entries to a forward-only stream.
@NotNullByDefault
public abstract sealed class ZipArkivoStreamingWriter implements Closeable permits ZipArkivoStreamingWriterImpl {
    /// Creates a streaming ZIP writer base instance.
    protected ZipArkivoStreamingWriter() {
    }

    /// Creates a streaming ZIP writer that writes to an archive path.
    public static ZipArkivoStreamingWriter create(Path path) throws IOException {
        return create(path, Map.of());
    }

    /// Creates a streaming ZIP writer that writes to an archive path with environment options.
    public static ZipArkivoStreamingWriter create(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return ZipArkivoStreamingWriterImpl.create(path, config);
    }

    /// Opens a streaming ZIP writer over an output stream.
    public static ZipArkivoStreamingWriter open(OutputStream output) {
        return open(output, Map.of());
    }

    /// Opens a streaming ZIP writer over an output stream with environment options.
    public static ZipArkivoStreamingWriter open(OutputStream output, Map<String, ?> environment) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return ZipArkivoStreamingWriterImpl.open(output, config);
    }

    /// Opens a streaming ZIP writer over a writable channel.
    public static ZipArkivoStreamingWriter open(WritableByteChannel output) {
        return open(output, Map.of());
    }

    /// Opens a streaming ZIP writer over a writable channel with environment options.
    public static ZipArkivoStreamingWriter open(
            WritableByteChannel output,
            Map<String, ?> environment
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return ZipArkivoStreamingWriterImpl.open(output, config);
    }

    /// Creates a directory entry.
    public abstract void createDirectory(Path path) throws IOException;

    /// Opens an output stream for the next regular file entry.
    public abstract OutputStream openOutputStream(Path path) throws IOException;

    /// Opens a writable channel for the next regular file entry.
    public WritableByteChannel openChannel(Path path) throws IOException {
        return Channels.newChannel(openOutputStream(path));
    }

    /// Closes this streaming writer and finishes the ZIP stream.
    @Override
    public abstract void close() throws IOException;
}

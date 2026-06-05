// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.zip.internal.ZipArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.Objects;

/// Reads ZIP entries from a forward-only stream.
@NotNullByDefault
public abstract sealed class ZipArkivoStreamingReader implements Closeable permits ZipArkivoStreamingReaderImpl {
    /// Creates a streaming ZIP reader base instance.
    protected ZipArkivoStreamingReader() {
    }

    /// Opens a streaming ZIP reader from an input stream.
    public static ZipArkivoStreamingReader open(InputStream source) {
        return open(source, Map.of());
    }

    /// Opens a streaming ZIP reader from an input stream with environment options.
    public static ZipArkivoStreamingReader open(InputStream source, Map<String, ?> environment) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return new ZipArkivoStreamingReaderImpl(source, config);
    }

    /// Opens a streaming ZIP reader from a readable channel.
    public static ZipArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, Map.of());
    }

    /// Opens a streaming ZIP reader from a readable channel with environment options.
    public static ZipArkivoStreamingReader open(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);
        return new ZipArkivoStreamingReaderImpl(source, config);
    }

    /// Returns the next entry, or `null` when the ZIP stream is exhausted.
    public abstract @Nullable ZipArkivoStreamingEntry next() throws IOException;

    /// Opens a readable channel for the current entry.
    public abstract ReadableByteChannel openChannel() throws IOException;

    /// Opens an input stream for the current entry.
    public InputStream openInputStream() throws IOException {
        return Channels.newInputStream(openChannel());
    }

    /// Closes this streaming reader.
    @Override
    public abstract void close() throws IOException;
}

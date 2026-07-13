// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.internal.ArArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.Objects;

/// Reads AR entries from a forward-only stream.
@NotNullByDefault
public abstract sealed class ArArkivoStreamingReader extends ArkivoStreamingReader
        permits ArArkivoStreamingReaderImpl {
    /// Creates a streaming AR reader base instance.
    protected ArArkivoStreamingReader() {
    }

    /// Opens a streaming AR reader from an input stream.
    public static ArArkivoStreamingReader open(InputStream source) {
        return open(source, Map.of());
    }

    /// Opens a streaming AR reader from an input stream with common archive read options.
    public static ArArkivoStreamingReader open(InputStream source, Map<String, ?> environment) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return open(StreamChannelAdapters.readableChannel(source), environment);
    }

    /// Opens a streaming AR reader from a readable channel.
    public static ArArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, Map.of());
    }

    /// Opens a streaming AR reader from a readable channel with common archive read options.
    public static ArArkivoStreamingReader open(ReadableByteChannel source, Map<String, ?> environment) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return new ArArkivoStreamingReaderImpl(
                StreamChannelAdapters.inputStream(source),
                environment
        );
    }
}

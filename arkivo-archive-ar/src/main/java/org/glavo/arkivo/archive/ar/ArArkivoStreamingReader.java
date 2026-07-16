// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.internal.ArArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
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
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming AR reader from an input stream with common archive read options.
    public static ArArkivoStreamingReader open(InputStream source, ArchiveOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Opens a streaming AR reader from a readable channel.
    public static ArArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a streaming AR reader from a readable channel with common archive read options.
    public static ArArkivoStreamingReader open(ReadableByteChannel source, ArchiveOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new ArArkivoStreamingReaderImpl(
                StreamChannelAdapters.inputStream(source),
                options
        );
    }
}

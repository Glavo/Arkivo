// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.cpio.internal.CPIOArkivoStreamingReaderImpl;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads CPIO entries from a forward-only stream.
@NotNullByDefault
public abstract sealed class CPIOArkivoStreamingReader extends ArkivoStreamingReader
        permits CPIOArkivoStreamingReaderImpl {
    /// Creates a streaming CPIO reader base instance.
    protected CPIOArkivoStreamingReader() {
    }

    /// Opens a streaming CPIO reader from an input stream.
    public static CPIOArkivoStreamingReader open(InputStream source) {
        return open(source, CPIOArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a configured streaming CPIO reader from an input stream.
    public static CPIOArkivoStreamingReader open(InputStream source, CPIOArchiveOptions.Read options) {
        return new CPIOArkivoStreamingReaderImpl(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(options, "options")
        );
    }

    /// Opens a streaming CPIO reader from a readable channel.
    public static CPIOArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, CPIOArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a configured streaming CPIO reader from a readable channel.
    public static CPIOArkivoStreamingReader open(
            ReadableByteChannel source,
            CPIOArchiveOptions.Read options
    ) {
        Objects.requireNonNull(source, "source");
        return open(StreamChannelAdapters.inputStream(source), options);
    }
}

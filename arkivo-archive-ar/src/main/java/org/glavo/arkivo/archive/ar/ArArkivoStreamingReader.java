// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

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
        Objects.requireNonNull(source, "source");
        return open(StreamChannelAdapters.readableChannel(source));
    }

    /// Opens a streaming AR reader from a readable channel.
    public static ArArkivoStreamingReader open(ReadableByteChannel source) {
        Objects.requireNonNull(source, "source");
        return new ArArkivoStreamingReaderImpl(StreamChannelAdapters.inputStream(source));
    }
}

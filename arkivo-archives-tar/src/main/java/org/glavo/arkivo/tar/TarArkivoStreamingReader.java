// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoStreamingReader;
import org.glavo.arkivo.tar.internal.TarArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads TAR entries from a forward-only stream.
///
/// Entry body channels expose the declared body of regular and format-specific entry types.
@NotNullByDefault
public abstract sealed class TarArkivoStreamingReader extends ArkivoStreamingReader
        permits TarArkivoStreamingReaderImpl {
    /// Creates a streaming TAR reader base instance.
    protected TarArkivoStreamingReader() {
    }

    /// Opens a streaming TAR reader from an input stream.
    public static TarArkivoStreamingReader open(InputStream source) {
        return new TarArkivoStreamingReaderImpl(Objects.requireNonNull(source, "source"));
    }

    /// Opens a streaming TAR reader from a readable channel.
    public static TarArkivoStreamingReader open(ReadableByteChannel source) {
        Objects.requireNonNull(source, "source");
        return open(Channels.newInputStream(source));
    }
}

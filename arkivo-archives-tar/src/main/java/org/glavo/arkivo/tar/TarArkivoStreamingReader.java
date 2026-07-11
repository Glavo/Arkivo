// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoStreamingReader;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.tar.internal.TarCompressionStreams;
import org.glavo.arkivo.tar.internal.TarArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.Objects;

/// Reads TAR entries from a forward-only stream.
///
/// Entry body channels expose the declared body of regular and format-specific entry types.
/// Old GNU sparse entries and GNU PAX sparse formats 0.0, 0.1, and 1.0 are exposed as expanded logical file bodies.
/// Closing the reader closes its backing input stream or channel.
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

    /// Opens a streaming TAR reader from an input stream with environment options.
    ///
    /// `TarArkivoFileSystem.COMPRESSION` explicitly selects a compression wrapper. When the option is absent, the
    /// source is read as an uncompressed TAR stream because a forward-only source cannot reliably undo a false
    /// compression match.
    public static TarArkivoStreamingReader open(
            InputStream source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        @Nullable CompressionCodec compressionCodec = TarArkivoFileSystem.COMPRESSION.read(environment);
        return new TarArkivoStreamingReaderImpl(
                TarCompressionStreams.openArchiveInput(source, compressionCodec)
        );
    }

    /// Opens a streaming TAR reader from a readable channel.
    public static TarArkivoStreamingReader open(ReadableByteChannel source) {
        Objects.requireNonNull(source, "source");
        return open(Channels.newInputStream(source));
    }

    /// Opens a streaming TAR reader from a readable channel with environment options.
    public static TarArkivoStreamingReader open(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return open(Channels.newInputStream(source), environment);
    }
}

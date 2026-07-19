// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.tar.internal.TarArkivoStreamingReaderImpl;
import org.glavo.arkivo.archive.tar.internal.TarCompressionStreams;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.CompressionProbeResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads TAR entries from a forward-only stream.
///
/// Entry body channels expose the declared body of regular and format-specific entry types.
/// Old GNU sparse entries and GNU PAX sparse formats 0.0, 0.1, and 1.0 are exposed as expanded logical file bodies.
/// `next()` applies pending GNU and PAX metadata and positions the cursor at the next logical entry. Attributes are
/// detached snapshots. A body may be opened once; advancing closes it before consuming the next header and padding.
/// After `next()` returns `false`, no current entry exists.
///
/// A successfully returned reader owns its input stream or channel, including any compression wrapper, and closes it
/// with the reader. Closing an entry body does not close the reader. The reader is a stateful cursor; callers must
/// serialize cursor operations. Opening may block while reading a compression probe prefix; `next()` and entry-body
/// reads may block on the underlying source.
@NotNullByDefault
public abstract sealed class TarArkivoStreamingReader extends ArkivoStreamingReader
        permits TarArkivoStreamingReaderImpl {
    /// Creates a streaming TAR reader base instance.
    protected TarArkivoStreamingReader() {
    }

    /// Opens a streaming TAR reader from an input stream with automatic compression detection.
    ///
    /// @param source the stream positioned at the first compressed or uncompressed archive byte; ownership transfers
    ///               after argument validation
    /// @return a forward-only reader that closes `source` when the reader closes
    /// @throws IOException if compression probing or decoder initialization fails; setup failure closes `source`
    public static TarArkivoStreamingReader open(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        return open(StreamChannelAdapters.readableChannel(source), TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming TAR reader from an input stream with options.
    ///
    /// `TarArkivoFileSystem.COMPRESSION` explicitly selects a compression wrapper. When the option is absent, installed
    /// codecs with reliable signatures are detected without discarding bytes from the forward-only source.
    ///
    /// @param source the stream positioned at the first compressed or uncompressed archive byte
    /// @param options the compression selection, metadata decoding, and decompression resource limits
    /// @return a forward-only reader that owns and closes `source`
    /// @throws IOException if compression probing or decoder initialization fails; setup failure closes `source`
    public static TarArkivoStreamingReader open(
            InputStream source,
            TarArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.readableChannel(source), options);
    }

    /// Opens a streaming TAR reader from a readable channel with automatic compression detection.
    ///
    /// @param source the channel positioned at the first compressed or uncompressed archive byte
    /// @return a forward-only reader that owns and closes `source`
    /// @throws IOException if compression probing or decoder initialization fails; setup failure closes `source`
    public static TarArkivoStreamingReader open(ReadableByteChannel source) throws IOException {
        return open(source, TarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming TAR reader from a readable channel with options.
    ///
    /// After options validation, this method takes ownership of the source and closes it if probing or decoder
    /// setup fails.
    ///
    /// @param source the channel positioned at the first compressed or uncompressed archive byte
    /// @param options the compression selection, metadata decoding, and decompression resource limits
    /// @return a forward-only reader that owns and closes `source`
    /// @throws IOException if compression probing or decoder initialization fails
    public static TarArkivoStreamingReader open(
            ReadableByteChannel source,
            TarArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        TarCompression.Read compression = options.compression();
        @Nullable CompressionCodec<?> compressionCodec = compression instanceof TarCompression.Codec configured
                ? configured.codec()
                : null;
        ReadableByteChannel archiveSource = source;
        if (compression == TarCompression.DETECT) {
            try (CompressionProbeResult probe = CompressionFormats.probe(
                    source,
                    TarArkivoFormat.instance().probeSize(),
                    ResourceOwnership.OWNED
            )) {
                compressionCodec = TarArkivoFormat.instance().matches(probe.prefix())
                        ? null
                        : probe.format() == null ? null : probe.format().defaultCodec();
                archiveSource = probe.takeChannel();
            }
        }
        return new TarArkivoStreamingReaderImpl(
                StreamChannelAdapters.inputStream(
                        TarCompressionStreams.openArchiveInput(
                                archiveSource,
                                compressionCodec,
                                options.common().limits()
                        )
                ),
                TarArkivoFileSystem.toLegacyOptions(options)
        );
    }
}

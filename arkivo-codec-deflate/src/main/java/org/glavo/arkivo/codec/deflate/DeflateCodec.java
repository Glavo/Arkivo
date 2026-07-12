// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/// Provides raw deflate compression and decompression channels.
@NotNullByDefault
public final class DeflateCodec implements CompressionCodec {
    /// The stable raw deflate codec name.
    public static final String NAME = "deflate";

    /// The supported raw deflate operations.
    private static final CompressionCapabilities CAPABILITIES = CompressionCapabilities.of(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION
    ));

    /// Creates a raw deflate codec.
    public DeflateCodec() {
    }

    /// Returns the stable raw deflate codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the supported raw deflate operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Opens a configured raw deflate encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "deflate compression");
        return StreamCodecAdapters.openEncoder(target, ownership, EndingDeflaterOutputStream::new);
    }

    /// Opens a configured raw deflate decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "deflate decompression");
        return StreamCodecAdapters.openDecoder(source, ownership, EndingInflaterInputStream::new);
    }

    /// Finishes raw deflate output and releases the backing deflater.
    @NotNullByDefault
    private static final class EndingDeflaterOutputStream extends DeflaterOutputStream {
        /// The deflater owned by this stream.
        private final Deflater deflater;

        /// Whether the deflater has been released.
        private boolean released;

        /// Creates a raw deflate output stream.
        private EndingDeflaterOutputStream(OutputStream output) {
            this(output, new Deflater(Deflater.DEFAULT_COMPRESSION, true));
        }

        /// Creates a raw deflate output stream with the given deflater.
        private EndingDeflaterOutputStream(OutputStream output, Deflater deflater) {
            super(output, deflater);
            this.deflater = deflater;
        }

        /// Closes the stream and releases the deflater.
        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!released) {
                    released = true;
                    deflater.end();
                }
            }
        }
    }

    /// Inflates raw deflate input and releases the backing inflater.
    @NotNullByDefault
    private static final class EndingInflaterInputStream extends InflaterInputStream {
        /// The inflater owned by this stream.
        private final Inflater inflater;

        /// Whether the inflater has been released.
        private boolean released;

        /// Creates a raw deflate input stream.
        private EndingInflaterInputStream(InputStream input) {
            this(input, new Inflater(true));
        }

        /// Creates a raw deflate input stream with the given inflater.
        private EndingInflaterInputStream(InputStream input, Inflater inflater) {
            super(input, inflater);
            this.inflater = inflater;
        }

        /// Closes the stream and releases the inflater.
        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!released) {
                    released = true;
                    inflater.end();
                }
            }
        }
    }
}

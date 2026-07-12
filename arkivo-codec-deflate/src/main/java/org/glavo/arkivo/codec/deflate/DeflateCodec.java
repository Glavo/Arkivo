// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/// Provides raw deflate compression and decompression channels.
@NotNullByDefault
public final class DeflateCodec implements CompressionCodec {
    /// The stable raw deflate codec name.
    public static final String NAME = "deflate";

    /// Creates a raw deflate codec.
    public DeflateCodec() {
    }

    /// Returns the stable raw deflate codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns whether raw deflate compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether raw deflate decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Opens a raw deflate compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new EndingDeflaterOutputStream(Channels.newOutputStream(target)));
    }

    /// Opens a raw deflate decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) {
        return Channels.newChannel(new EndingInflaterInputStream(Channels.newInputStream(source)));
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

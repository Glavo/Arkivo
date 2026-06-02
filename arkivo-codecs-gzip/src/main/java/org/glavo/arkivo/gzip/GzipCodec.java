package org.glavo.arkivo.gzip;

import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// Provides gzip encoder and decoder channels.
@NotNullByDefault
public final class GzipCodec implements CompressionCodec {
    /// The stable gzip format name.
    public static final String NAME = "gzip";

    /// Creates a gzip codec.
    public GzipCodec() {
    }

    /// Returns the stable gzip format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Opens a gzip encoder that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel openEncoder(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new GZIPOutputStream(Channels.newOutputStream(target)));
    }

    /// Opens a gzip decoder that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel openDecoder(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new GZIPInputStream(Channels.newInputStream(source)));
    }
}

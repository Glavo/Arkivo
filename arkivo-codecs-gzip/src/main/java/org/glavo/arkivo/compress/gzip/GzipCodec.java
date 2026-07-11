// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.gzip;

import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// Provides gzip compression and decompression channels.
@NotNullByDefault
public final class GzipCodec implements CompressionCodec {
    /// The stable gzip codec name.
    public static final String NAME = "gzip";

    /// Creates a gzip codec.
    public GzipCodec() {
    }

    /// Returns the stable gzip codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common gzip file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("gz", "gzip");
    }

    /// Returns whether gzip compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether gzip decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Returns the number of leading bytes used to identify gzip streams.
    @Override
    public int probeSize() {
        return 2;
    }

    /// Returns whether the given prefix starts with the gzip stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        return prefix.remaining() >= 2
                && Byte.toUnsignedInt(prefix.get(position)) == 0x1f
                && Byte.toUnsignedInt(prefix.get(position + 1)) == 0x8b;
    }

    /// Opens a gzip compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new GZIPOutputStream(Channels.newOutputStream(target)));
    }

    /// Opens a gzip decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new GZIPInputStream(Channels.newInputStream(source)));
    }
}

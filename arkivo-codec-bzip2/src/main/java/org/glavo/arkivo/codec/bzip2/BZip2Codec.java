// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.bzip2.internal.BZip2InputStream;
import org.glavo.arkivo.codec.bzip2.internal.BZip2OutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;

/// Provides BZip2 compression and decompression channels.
@NotNullByDefault
public final class BZip2Codec implements CompressionCodec {
    /// The stable BZip2 codec name.
    public static final String NAME = "bzip2";

    /// Creates a BZip2 codec.
    public BZip2Codec() {
    }

    /// Returns the stable BZip2 codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns alternative stable names accepted for BZip2.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("bz2");
    }

    /// Returns common BZip2 file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("bz2", "bzip2");
    }

    /// Returns whether BZip2 compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether BZip2 decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Returns the number of leading bytes used to identify BZip2 streams.
    @Override
    public int probeSize() {
        return 4;
    }

    /// Returns whether the given prefix starts with the BZip2 stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 4) {
            return false;
        }

        int blockSize = Byte.toUnsignedInt(prefix.get(position + 3));
        return prefix.get(position) == 'B'
                && prefix.get(position + 1) == 'Z'
                && prefix.get(position + 2) == 'h'
                && blockSize >= '1'
                && blockSize <= '9';
    }

    /// Opens a BZip2 compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new BZip2OutputStream(Channels.newOutputStream(target)));
    }

    /// Opens a BZip2 decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new BZip2InputStream(Channels.newInputStream(source)));
    }
}

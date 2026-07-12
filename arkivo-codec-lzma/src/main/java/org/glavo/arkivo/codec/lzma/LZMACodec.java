// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.lzma.internal.LzmaInputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaOutputStream;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/// Provides LZMA compression and decompression channels.
@NotNullByDefault
public final class LZMACodec implements CompressionCodec {
    /// The stable LZMA codec name.
    public static final String NAME = "lzma";

    /// The default LZMA dictionary size used by this codec.
    private static final int DEFAULT_DICTIONARY_SIZE = 1 << 20;

    /// Creates an LZMA codec.
    public LZMACodec() {
    }

    /// Returns the stable LZMA codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns whether LZMA compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether LZMA decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Opens an LZMA compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(
                new LzmaOutputStream(Channels.newOutputStream(target), DEFAULT_DICTIONARY_SIZE)
        );
    }

    /// Opens an LZMA decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new LzmaInputStream(Channels.newInputStream(source)));
    }
}

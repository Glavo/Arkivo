// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.lzma;

import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMAOutputStream;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/// Provides LZMA compression and decompression channels.
@NotNullByDefault
public final class LzmaCodec implements CompressionCodec {
    /// The stable LZMA codec name.
    public static final String NAME = "lzma";

    /// The unknown uncompressed size marker used by XZ for Java for legacy `.lzma` streams.
    private static final long UNKNOWN_UNCOMPRESSED_SIZE = -1L;

    /// The default LZMA dictionary size used by this codec.
    private static final int DEFAULT_DICTIONARY_SIZE = 1 << 20;

    /// Creates an LZMA codec.
    public LzmaCodec() {
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
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(DEFAULT_DICTIONARY_SIZE);
        return Channels.newChannel(
                new LZMAOutputStream(Channels.newOutputStream(target), options, UNKNOWN_UNCOMPRESSED_SIZE)
        );
    }

    /// Opens an LZMA decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new LZMAInputStream(Channels.newInputStream(source)));
    }
}

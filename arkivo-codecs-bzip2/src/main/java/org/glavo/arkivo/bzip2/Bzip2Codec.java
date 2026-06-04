// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.bzip2;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/// Provides BZip2 compression and decompression channels.
@NotNullByDefault
public final class Bzip2Codec implements CompressionCodec {
    /// The stable BZip2 codec name.
    public static final String NAME = "bzip2";

    /// Creates a BZip2 codec.
    public Bzip2Codec() {
    }

    /// Returns the stable BZip2 codec name.
    @Override
    public String name() {
        return NAME;
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

    /// Opens a BZip2 compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new BZip2CompressorOutputStream(Channels.newOutputStream(target)));
    }

    /// Opens a BZip2 decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new BZip2CompressorInputStream(Channels.newInputStream(source)));
    }
}

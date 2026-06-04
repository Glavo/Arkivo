// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zstd;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/// Provides Zstandard compression and decompression channels.
@NotNullByDefault
public final class ZstdCodec implements CompressionCodec {
    /// The stable Zstandard codec name.
    public static final String NAME = "zstd";

    /// Creates a Zstandard codec.
    public ZstdCodec() {
    }

    /// Returns the stable Zstandard codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns whether Zstandard compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether Zstandard decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Opens a Zstandard compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new ZstdOutputStream(Channels.newOutputStream(target)));
    }

    /// Opens a Zstandard decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new ZstdInputStream(Channels.newInputStream(source)));
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.xz;

import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/// Provides XZ compression and decompression channels.
@NotNullByDefault
public final class XzCodec implements CompressionCodec {
    /// The stable XZ codec name.
    public static final String NAME = "xz";

    /// Creates an XZ codec.
    public XzCodec() {
    }

    /// Returns the stable XZ codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns whether XZ compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether XZ decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Opens an XZ compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new XZOutputStream(Channels.newOutputStream(target), new LZMA2Options()));
    }

    /// Opens an XZ decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new XZInputStream(Channels.newInputStream(source)));
    }
}

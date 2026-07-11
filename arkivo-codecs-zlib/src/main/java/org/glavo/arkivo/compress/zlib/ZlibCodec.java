// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.zlib;

import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/// Provides zlib compression and decompression channels.
@NotNullByDefault
public final class ZlibCodec implements CompressionCodec {
    /// The stable zlib codec name.
    public static final String NAME = "zlib";

    /// Creates a zlib codec.
    public ZlibCodec() {
    }

    /// Returns the stable zlib codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns whether zlib compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether zlib decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Returns the number of leading bytes used to identify zlib streams.
    @Override
    public int probeSize() {
        return 2;
    }

    /// Returns whether the given prefix starts with a valid zlib header.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 2) {
            return false;
        }

        int compressionMethodAndFlags = Byte.toUnsignedInt(prefix.get(position));
        int flags = Byte.toUnsignedInt(prefix.get(position + 1));
        return (compressionMethodAndFlags & 0x0f) == 8
                && (compressionMethodAndFlags >> 4) <= 7
                && ((compressionMethodAndFlags << 8) + flags) % 31 == 0;
    }

    /// Opens a zlib compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new DeflaterOutputStream(Channels.newOutputStream(target)));
    }

    /// Opens a zlib decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) {
        return Channels.newChannel(new InflaterInputStream(Channels.newInputStream(source)));
    }
}

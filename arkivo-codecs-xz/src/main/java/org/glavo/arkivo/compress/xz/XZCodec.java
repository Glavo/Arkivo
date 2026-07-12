// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.xz;

import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.internal.XzInputStream;
import org.glavo.arkivo.internal.XzOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/// Provides XZ compression and decompression channels.
@NotNullByDefault
public final class XZCodec implements CompressionCodec {
    /// The stable XZ codec name.
    public static final String NAME = "xz";

    /// The XZ stream header magic bytes.
    private static final byte @Unmodifiable [] HEADER_MAGIC = {
            (byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00
    };

    /// Creates an XZ codec.
    public XZCodec() {
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

    /// Returns the number of leading bytes used to identify XZ streams.
    @Override
    public int probeSize() {
        return HEADER_MAGIC.length;
    }

    /// Returns whether the given prefix starts with the XZ stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < HEADER_MAGIC.length) {
            return false;
        }

        for (int i = 0; i < HEADER_MAGIC.length; i++) {
            if (prefix.get(position + i) != HEADER_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /// Opens an XZ compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return Channels.newChannel(new XzOutputStream(Channels.newOutputStream(target)));
    }

    /// Opens an XZ decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return Channels.newChannel(new XzInputStream(Channels.newInputStream(source)));
    }
}

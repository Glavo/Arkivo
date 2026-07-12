// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.deflate64;

import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.deflate64.internal.Deflate64InputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;

/// Provides raw Deflate64 decompression channels.
@NotNullByDefault
public final class Deflate64Codec implements CompressionCodec {
    /// The stable Deflate64 codec name.
    public static final String NAME = "deflate64";

    /// Creates a Deflate64 codec.
    public Deflate64Codec() {
    }

    /// Returns the stable Deflate64 codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns alternative stable names accepted for Deflate64.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("deflate-64");
    }

    /// Returns no standalone file extensions because Deflate64 is an embedded raw stream format.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns whether Deflate64 compression is supported.
    @Override
    public boolean canCompress() {
        return false;
    }

    /// Returns whether Deflate64 decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Rejects compression because the module currently implements decompression only.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        throw new IOException("Deflate64 compression is not supported");
    }

    /// Opens a raw Deflate64 decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) {
        return Channels.newChannel(new Deflate64InputStream(Channels.newInputStream(source)));
    }
}

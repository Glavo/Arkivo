// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate64;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.deflate64.internal.Deflate64InputStream;
import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;

/// Provides raw Deflate64 decompression channels.
@NotNullByDefault
public final class Deflate64Codec implements CompressionCodec {
    /// The stable Deflate64 codec name.
    public static final String NAME = "deflate64";

    /// The supported Deflate64 operations.
    private static final CompressionCapabilities CAPABILITIES = CompressionCapabilities.of(Set.of(
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION
    ));

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

    /// Returns the supported Deflate64 operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Rejects compression because the module currently implements decompression only.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "Deflate64 compression");
        throw new IOException("Deflate64 compression is not supported");
    }

    /// Opens a configured raw Deflate64 decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "Deflate64 decompression");
        return StreamCodecAdapters.openDecoder(source, ownership, Deflate64InputStream::new);
    }
}

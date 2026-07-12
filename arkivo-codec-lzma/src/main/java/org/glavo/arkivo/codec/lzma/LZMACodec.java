// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.lzma.internal.LzmaInputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaOutputStream;
import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;

/// Provides LZMA compression and decompression channels.
@NotNullByDefault
public final class LZMACodec implements CompressionCodec {
    /// The stable LZMA codec name.
    public static final String NAME = "lzma";

    /// The supported LZMA operations.
    private static final CompressionCapabilities CAPABILITIES = CompressionCapabilities.of(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION
    ));

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

    /// Returns the supported LZMA operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Opens a configured LZMA encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "LZMA compression");
        return StreamCodecAdapters.openEncoder(
                target,
                ownership,
                output -> new LzmaOutputStream(output, DEFAULT_DICTIONARY_SIZE)
        );
    }

    /// Opens a configured LZMA decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "LZMA decompression");
        return StreamCodecAdapters.openDecoder(source, ownership, LzmaInputStream::new);
    }
}

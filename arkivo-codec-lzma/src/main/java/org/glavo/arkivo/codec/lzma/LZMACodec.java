// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOption;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.lzma.internal.LzmaChannelDecoder;
import org.glavo.arkivo.codec.lzma.internal.LzmaChannelEncoder;
import org.glavo.arkivo.codec.lzma.internal.LzmaProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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
    /// Selects the LZMA dictionary size in bytes.
    public static final CodecOption<Long> DICTIONARY_SIZE =
            CodecOption.of("lzma.dictionarySize", Long.class);

    /// The default LZMA dictionary size used by this codec.
    public static final long DEFAULT_DICTIONARY_SIZE = 1L << 20;

    /// The supported LZMA operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION,
            CompressionFeature.DIRECT_BYTE_BUFFER
    ), Set.of(DICTIONARY_SIZE), Set.of(StandardCodecOptions.MAX_OUTPUT_SIZE, StandardCodecOptions.MAX_WINDOW_SIZE));

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
        @Nullable Long requested = options.get(DICTIONARY_SIZE);
        long dictionarySize = requested != null ? requested : DEFAULT_DICTIONARY_SIZE;
        if (dictionarySize < 0L || dictionarySize > LzmaProperties.MAXIMUM_DICTIONARY_SIZE) {
            throw new IllegalArgumentException("Unsupported LZMA dictionary size: " + dictionarySize);
        }
        return new LzmaChannelEncoder(target, ownership, (int) dictionarySize);
    }

    /// Opens a configured LZMA decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "LZMA decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new LzmaChannelDecoder(source, ownership, maximumWindowSize),
                maximumOutputSize
        );
    }
}

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
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;

import org.glavo.arkivo.codec.lzma.internal.LZMADecoder;
import org.glavo.arkivo.codec.lzma.internal.LZMAEncoder;
import org.glavo.arkivo.codec.lzma.internal.LZMAProperties;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;

/// Provides transport-independent LZMA-alone compression and decompression.
@NotNullByDefault
public final class LZMACodec implements CompressionCodec {
    /// The stable LZMA codec name.
    public static final String NAME = "lzma";

    /// Selects the LZMA dictionary size in bytes.
    ///
    /// This alias identifies the same option as `LZMAOptions.DICTIONARY_SIZE`.
    public static final CodecOption<Long> DICTIONARY_SIZE = LZMAOptions.DICTIONARY_SIZE;

    /// The default LZMA dictionary size used by this codec.
    public static final long DEFAULT_DICTIONARY_SIZE = 1L << 20;

    /// The supported LZMA operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.BUFFER_COMPRESSION,
            CompressionFeature.BUFFER_DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION,
            CompressionFeature.DIRECT_BYTE_BUFFER
    ), Set.of(
            LZMAOptions.DICTIONARY_SIZE,
            LZMAOptions.LITERAL_CONTEXT_BITS,
            LZMAOptions.LITERAL_POSITION_BITS,
            LZMAOptions.POSITION_BITS,
            StandardCodecOptions.PLEDGED_SOURCE_SIZE
    ), Set.of(StandardCodecOptions.MAX_OUTPUT_SIZE, StandardCodecOptions.MAX_WINDOW_SIZE));

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

    /// Creates a configured transport-independent LZMA-alone encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "LZMA compression");
        LZMAProperties properties = LZMAProperties.fromOptions(
                options,
                LZMAOptions.DICTIONARY_SIZE,
                (int) DEFAULT_DICTIONARY_SIZE
        );
        long pledgedSourceSize = StandardCodecOptionSupport.pledgedSourceSize(options);
        return new LZMAEncoder(properties, pledgedSourceSize);
    }

    /// Opens a configured LZMA-alone encoder through the shared buffer-engine channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openEncoder(target, options, ownership);
    }

    /// Creates a configured transport-independent LZMA-alone decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "LZMA decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        CompressionDecoder decoder = new LZMADecoder(maximumWindowSize);
        return StandardCodecOptionSupport.limitOutput(decoder, maximumOutputSize);
    }

    /// Opens a configured LZMA-alone decoder through the shared buffer-engine channel adapter.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openDecoder(source, options, ownership);
    }
}
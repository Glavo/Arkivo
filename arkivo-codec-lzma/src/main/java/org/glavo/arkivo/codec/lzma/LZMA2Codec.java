// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.lzma.internal.LZMA2Decoder;
import org.glavo.arkivo.codec.lzma.internal.LZMA2Encoder;
import org.glavo.arkivo.codec.lzma.internal.LZMAProperties;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;

/// Provides raw LZMA2 compression with an externally declared dictionary size.
@NotNullByDefault
public final class LZMA2Codec implements CompressionCodec {
    /// The stable LZMA2 codec name.
    public static final String NAME = "lzma2";

    /// The default dictionary size used for LZMA2 compression.
    public static final long DEFAULT_DICTIONARY_SIZE = 1L << 20;

    /// The supported LZMA2 operations and options.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.BUFFER_COMPRESSION,
                    CompressionFeature.BUFFER_DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.DIRECT_BYTE_BUFFER
            ),
            Set.of(
                    LZMAOptions.DICTIONARY_SIZE,
                    LZMAOptions.LITERAL_CONTEXT_BITS,
                    LZMAOptions.LITERAL_POSITION_BITS,
                    LZMAOptions.POSITION_BITS
            ),
            Set.of(
                    LZMAOptions.DICTIONARY_SIZE,
                    StandardCodecOptions.MAX_OUTPUT_SIZE,
                    StandardCodecOptions.MAX_WINDOW_SIZE
            )
    );

    /// Creates an LZMA2 codec.
    public LZMA2Codec() {
    }

    /// Returns the stable LZMA2 codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the supported LZMA2 operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Creates a configured transport-independent raw LZMA2 encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "LZMA2 compression");
        LZMAProperties properties = LZMAProperties.fromOptions(
                options,
                LZMAOptions.DICTIONARY_SIZE,
                (int) DEFAULT_DICTIONARY_SIZE
        );
        return new LZMA2Encoder(properties);
    }

    /// Creates a configured transport-independent raw LZMA2 decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "LZMA2 decompression");
        if (options.get(LZMAOptions.DICTIONARY_SIZE) == null) {
            throw new IllegalArgumentException(
                    "Required LZMA2 option is missing: " + LZMAOptions.DICTIONARY_SIZE.name()
            );
        }
        LZMAProperties properties = LZMAProperties.fromOptions(
                options,
                LZMAOptions.DICTIONARY_SIZE,
                (int) DEFAULT_DICTIONARY_SIZE
        );
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        StandardCodecOptionSupport.requireWindowSize(
                maximumWindowSize,
                properties.dictionarySize()
        );
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new LZMA2Decoder(properties.dictionarySize()),
                maximumOutputSize
        );
    }

    /// Opens a configured encoder through the shared buffer-engine channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) {
        try {
            return CompressionCodec.super.openEncoder(target, options, ownership);
        } catch (IOException exception) {
            throw new AssertionError("LZMA2 buffer encoder creation unexpectedly failed", exception);
        }
    }

    /// Opens a configured decoder through the shared buffer-engine channel adapter.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openDecoder(source, options, ownership);
    }
}

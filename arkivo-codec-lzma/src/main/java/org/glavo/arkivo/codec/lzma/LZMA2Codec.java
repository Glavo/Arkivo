// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.lzma.internal.LZMA2Decoder;
import org.glavo.arkivo.codec.lzma.internal.LZMA2CompressionFormat;
import org.glavo.arkivo.codec.lzma.internal.LZMA2Encoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable raw LZMA2 configuration with externally declared model properties.
@NotNullByDefault
public final class LZMA2Codec implements CompressionCodec<LZMA2Codec> {
    /// The stable LZMA2 compression format name.
    public static final String NAME = "lzma2";

    /// The default dictionary size used for LZMA2.
    public static final int DEFAULT_DICTIONARY_SIZE = 1 << 20;

    /// The default immutable LZMA2 codec configuration.
    public static final LZMA2Codec DEFAULT =
            new LZMA2Codec(LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE));

    /// The configured externally declared LZMA properties.
    private final LZMAProperties properties;

    /// Creates the default LZMA2 codec configuration.
    public LZMA2Codec() {
        this(LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE));
    }

    /// Creates an LZMA2 codec with the requested model properties.
    public LZMA2Codec(LZMAProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /// Returns the canonical raw LZMA2 format.
    @Override
    public CompressionFormat format() {
        return LZMA2CompressionFormat.instance();
    }


    /// Returns the configured externally declared LZMA properties.
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns an immutable LZMA2 codec with the requested model properties.
    public LZMA2Codec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties) ? this : new LZMA2Codec(properties);
    }

    /// Returns an immutable LZMA2 codec with the requested dictionary size.
    public LZMA2Codec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Creates a raw LZMA2 encoder.
    @Override
    public CompressionEncoder newEncoder() {
        return new LZMA2Encoder(properties);
    }

    /// Creates a raw LZMA2 decoder with operation-scoped safety limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        limits.requireWindowSize(properties.dictionarySize());
        return CompressionDecoderSupport.limitEngineOutput(
                new LZMA2Decoder(properties.dictionarySize()),
                limits.maximumOutputSize()
        );
    }
}

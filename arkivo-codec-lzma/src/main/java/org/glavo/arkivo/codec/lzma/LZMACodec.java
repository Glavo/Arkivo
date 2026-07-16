// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.PledgedSourceSizeCodec;
import org.glavo.arkivo.codec.lzma.internal.LZMADecoder;
import org.glavo.arkivo.codec.lzma.internal.LZMAEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable LZMA-alone configuration and transport-independent engines.
@NotNullByDefault
public final class LZMACodec implements PledgedSourceSizeCodec {
    /// The stable LZMA codec name.
    public static final String NAME = "lzma";

    /// The default LZMA dictionary size used for encoding.
    public static final int DEFAULT_DICTIONARY_SIZE = 1 << 20;

    /// The default immutable LZMA-alone codec configuration.
    public static final LZMACodec DEFAULT =
            new LZMACodec(LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE));

    /// The configured LZMA model properties used for encoding.
    private final LZMAProperties properties;

    /// Creates the default LZMA-alone codec configuration.
    public LZMACodec() {
        this(LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE));
    }

    /// Creates an LZMA-alone codec with the requested model properties.
    public LZMACodec(LZMAProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /// Returns the stable LZMA codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the configured LZMA model properties used for encoding.
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns an immutable LZMA codec with the requested model properties.
    public LZMACodec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties) ? this : new LZMACodec(properties);
    }

    /// Returns an immutable LZMA codec with the requested dictionary size.
    public LZMACodec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Creates an LZMA-alone encoder with optional exact source-size metadata.
    @Override
    public CompressionEncoder newEncoder(long pledgedSourceSize) {
        requirePledgedSourceSize(pledgedSourceSize);
        return new LZMAEncoder(properties, pledgedSourceSize);
    }

    /// Creates an LZMA-alone decoder with operation-scoped safety limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return CompressionDecoderSupport.limitEngineOutput(
                new LZMADecoder(limits.maximumWindowSize()),
                limits.maximumOutputSize()
        );
    }

    /// Validates a known or unknown pledged source size.
    private static void requirePledgedSourceSize(long pledgedSourceSize) {
        if (pledgedSourceSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException(
                    "pledgedSourceSize must be non-negative or UNKNOWN_SIZE"
            );
        }
    }
}

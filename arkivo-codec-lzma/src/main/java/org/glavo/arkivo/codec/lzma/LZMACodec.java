// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.lzma.internal.LZMADecoder;
import org.glavo.arkivo.codec.lzma.internal.LZMAEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable LZMA-alone configuration and transport-independent engines.
///
/// The LZMA-alone header stores the packed literal/position properties, dictionary size, and either an exact source size
/// or the all-ones unknown-size value. When the size is unknown, the encoder writes an LZMA end marker.
///
/// Codec values are safe for concurrent use and created engines are independent mutable sessions. A known source size is
/// exact: encoding beyond it or finalizing before consuming it fails.
@NotNullByDefault
public final class LZMACodec implements CompressionCodec<LZMACodec> {
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
    ///
    /// @param properties the model properties to write to newly encoded stream headers
    /// @throws NullPointerException if {@code properties} is {@code null}
    public LZMACodec(LZMAProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /// Returns the canonical LZMA-alone format.
    @Override
    public LZMAFormat format() {
        return LZMAFormat.instance();
    }


    /// Returns the configured LZMA model properties used for encoding.
    ///
    /// @return the immutable model properties written by newly created encoders
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns an immutable LZMA codec with the requested model properties.
    ///
    /// @param properties the replacement model properties
    /// @return this codec if the properties are unchanged; otherwise, a new codec with the requested properties
    /// @throws NullPointerException if {@code properties} is {@code null}
    public LZMACodec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties) ? this : new LZMACodec(properties);
    }

    /// Returns an immutable LZMA codec with the requested dictionary size.
    ///
    /// @param dictionarySize the dictionary size to write to new stream headers, in bytes
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested size
    /// @throws IllegalArgumentException if {@code dictionarySize} is outside the supported range
    public LZMACodec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Creates an LZMA-alone encoder without a known source size.
    @Override
    public CompressionEncoder newEncoder() {
        return newEncoder(UNKNOWN_SIZE);
    }

    /// Creates an LZMA-alone encoder with optional exact source-size metadata.
    @Override
    public CompressionEncoder newEncoder(long sourceSize) {
        requireSourceSize(sourceSize);
        return new LZMAEncoder(properties, sourceSize);
    }

    /// Creates an LZMA-alone decoder with operation-scoped safety limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return CompressionDecoderSupport.limitEngineOutput(
                new LZMADecoder(limits.effectiveMaximumWindowSize()),
                limits.maximumOutputSize()
        );
    }

    /// Validates a known or unknown exact source size.
    private static void requireSourceSize(long sourceSize) {
        if (sourceSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException("sourceSize must be non-negative or UNKNOWN_SIZE");
        }
    }
}

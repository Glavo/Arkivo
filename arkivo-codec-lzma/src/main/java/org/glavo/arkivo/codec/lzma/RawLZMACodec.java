// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.PledgedSourceSizeCodec;
import org.glavo.arkivo.codec.lzma.internal.LZMARawDecoder;
import org.glavo.arkivo.codec.lzma.internal.RawLZMACompressionFormat;
import org.glavo.arkivo.codec.lzma.internal.LZMARawEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable headerless LZMA configuration for containers that carry model properties separately.
@NotNullByDefault
public final class RawLZMACodec implements PledgedSourceSizeCodec {
    /// The stable raw LZMA codec name.
    public static final String NAME = "lzma-raw";

    /// The default dictionary size used for raw LZMA.
    public static final int DEFAULT_DICTIONARY_SIZE = 1 << 20;

    /// The default immutable raw LZMA codec configuration.
    public static final RawLZMACodec DEFAULT = new RawLZMACodec(
            LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE),
            true,
            UNKNOWN_SIZE
    );

    /// The configured externally declared LZMA properties.
    private final LZMAProperties properties;

    /// Whether encoders emit an end marker.
    private final boolean endMarker;

    /// The externally declared decoded size, or UNKNOWN_SIZE.
    private final long decodedSize;

    /// Creates the default raw LZMA codec configuration.
    public RawLZMACodec() {
        this(LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE), true, UNKNOWN_SIZE);
    }

    /// Creates a validated raw LZMA codec configuration.
    private RawLZMACodec(
            LZMAProperties properties,
            boolean endMarker,
            long decodedSize
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        if (decodedSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException(
                    "decodedSize must be non-negative or UNKNOWN_SIZE"
            );
        }
        this.endMarker = endMarker;
        this.decodedSize = decodedSize;
    }

    /// Returns the canonical raw LZMA format.
    @Override
    public CompressionFormat format() {
        return RawLZMACompressionFormat.instance();
    }


    /// Returns the configured externally declared LZMA properties.
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns whether encoders emit an end marker.
    public boolean endMarker() {
        return endMarker;
    }

    /// Returns the externally declared decoded size, or UNKNOWN_SIZE.
    public long decodedSize() {
        return decodedSize;
    }

    /// Returns an immutable raw LZMA codec with the requested properties.
    public RawLZMACodec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties)
                ? this
                : new RawLZMACodec(properties, endMarker, decodedSize);
    }

    /// Returns an immutable raw LZMA codec with the requested dictionary size.
    public RawLZMACodec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Returns an immutable raw LZMA codec with the requested end-marker behavior.
    public RawLZMACodec withEndMarker(boolean endMarker) {
        return endMarker == this.endMarker
                ? this
                : new RawLZMACodec(properties, endMarker, decodedSize);
    }

    /// Returns an immutable raw LZMA codec with the externally declared decoded size.
    public RawLZMACodec withDecodedSize(long decodedSize) {
        return decodedSize == this.decodedSize
                ? this
                : new RawLZMACodec(properties, endMarker, decodedSize);
    }

    /// Creates a raw LZMA encoder with optional exact source-size metadata.
    @Override
    public CompressionEncoder newEncoder(long pledgedSourceSize) {
        requirePledgedSourceSize(pledgedSourceSize);
        return new LZMARawEncoder(properties, pledgedSourceSize, endMarker);
    }

    /// Creates a raw LZMA decoder with operation-scoped safety limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        limits.requireWindowSize(properties.dictionarySize());
        return CompressionDecoderSupport.limitEngineOutput(
                new LZMARawDecoder(properties, decodedSize, limits.maximumWindowSize()),
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

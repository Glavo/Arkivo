// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lzma.internal.LZMA2Decoder;
import org.glavo.arkivo.codec.lzma.internal.LZMA2Encoder;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable raw LZMA2 configuration with externally declared model properties.
///
/// The raw LZMA2 control stream carries resets, chunk boundaries, and literal/position properties when they change, but
/// not the dictionary size expected by an embedding container. Encoders use all configured [LZMAProperties]; decoders
/// read coded properties from the stream and use the configured dictionary size. Decoder construction checks that size
/// against operation-scoped history and memory limits.
///
/// Codec values are safe for concurrent use; every created engine owns an independent mutable control-stream session.
@NotNullByDefault
public final class LZMA2Codec implements CompressionCodec<LZMA2Codec> {
    /// The default dictionary size used for LZMA2.
    public static final int DEFAULT_DICTIONARY_SIZE = 1 << 20;

    /// The default immutable LZMA2 codec configuration.
    public static final LZMA2Codec DEFAULT =
            new LZMA2Codec(LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE));

    /// The configured externally declared LZMA properties.
    private final LZMAProperties properties;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default LZMA2 codec configuration.
    public LZMA2Codec() {
        this(LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE));
    }

    /// Creates an LZMA2 codec with the requested model properties.
    ///
    /// @param properties the model properties; the dictionary size is supplied out of band to the decoder
    /// @throws NullPointerException if {@code properties} is {@code null}
    public LZMA2Codec(LZMAProperties properties) {
        this(properties, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates a validated LZMA2 codec configuration.
    private LZMA2Codec(
            LZMAProperties properties,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        CompressionDecoderSupport.validateLimit(maximumOutputSize, "maximumOutputSize");
        CompressionDecoderSupport.validateLimit(maximumWindowSize, "maximumWindowSize");
        CompressionDecoderSupport.validateLimit(maximumMemorySize, "maximumMemorySize");
        this.maximumOutputSize = maximumOutputSize;
        this.maximumWindowSize = maximumWindowSize;
        this.maximumMemorySize = maximumMemorySize;
    }

    /// Returns the configured decoded-output limit.
    @Override
    public long maximumOutputSize() {
        return maximumOutputSize;
    }

    /// Returns the configured decoder history-window limit.
    @Override
    public long maximumWindowSize() {
        return maximumWindowSize;
    }

    /// Returns the configured decoder working-memory limit.
    @Override
    public long maximumMemorySize() {
        return maximumMemorySize;
    }

    /// Returns an immutable codec with the requested decoded-output limit.
    @Override
    public LZMA2Codec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new LZMA2Codec(properties, maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public LZMA2Codec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new LZMA2Codec(properties, maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public LZMA2Codec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new LZMA2Codec(properties, maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns the canonical raw LZMA2 format.
    @Override
    public LZMA2Format format() {
        return LZMA2Format.instance();
    }


    /// Returns the configured externally declared LZMA properties.
    ///
    /// @return the immutable model properties used by newly created engines
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns an immutable LZMA2 codec with the requested model properties.
    ///
    /// @param properties the replacement model properties
    /// @return this codec if the properties are unchanged; otherwise, a new codec with the requested properties
    /// @throws NullPointerException if {@code properties} is {@code null}
    public LZMA2Codec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties)
                ? this
                : new LZMA2Codec(properties, maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Returns an immutable LZMA2 codec with the requested dictionary size.
    ///
    /// @param dictionarySize the externally declared dictionary size, in bytes
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested size
    /// @throws IllegalArgumentException if {@code dictionarySize} is outside the supported range
    public LZMA2Codec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Creates a raw LZMA2 encoder.
    @Override
    public CompressionEncoder newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new LZMA2Encoder(properties);
    }

    /// Creates a raw LZMA2 decoder using this codec's configured limits.
    @Override
    public CompressionDecoder newDecoder() throws IOException {
        CompressionDecoderSupport.requireWindowSize(
                CompressionDecoderSupport.effectiveMaximumWindowSize(maximumWindowSize, maximumMemorySize),
                properties.dictionarySize()
        );
        return CompressionDecoderSupport.limitEngineOutput(
                new LZMA2Decoder(properties.dictionarySize()),
                maximumOutputSize
        );
    }
}

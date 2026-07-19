// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.lzma.internal.LZMARawDecoder;
import org.glavo.arkivo.codec.lzma.internal.LZMARawEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable headerless LZMA configuration for containers that carry model properties separately.
///
/// Raw payloads do not carry [LZMAProperties] or a decoded-size field. A decoder therefore uses this codec's properties
/// and either stops at an enabled end marker or at the configured decoded size. When neither boundary is available,
/// exhaustion of the surrounding input is not by itself a successful raw LZMA termination.
///
/// Codec values are safe for concurrent use and created engines are independent mutable sessions. An encoder source
/// size, when known, is exact and is checked while accepting input and during finalization.
@NotNullByDefault
public final class RawLZMACodec implements CompressionCodec<RawLZMACodec> {
    /// The default dictionary size used for raw LZMA.
    public static final int DEFAULT_DICTIONARY_SIZE = 1 << 20;

    /// The default immutable raw LZMA codec configuration.
    public static final RawLZMACodec DEFAULT = new RawLZMACodec(
            LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE),
            true,
            UNKNOWN_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE
    );

    /// The configured externally declared LZMA properties.
    private final LZMAProperties properties;

    /// Whether encoders emit an end marker.
    private final boolean endMarker;

    /// The externally declared decoded size, or UNKNOWN_SIZE.
    private final long decodedSize;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default raw LZMA codec configuration.
    public RawLZMACodec() {
        this(
                LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE),
                true,
                UNKNOWN_SIZE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE
        );
    }

    /// Creates a validated raw LZMA codec configuration.
    private RawLZMACodec(
            LZMAProperties properties,
            boolean endMarker,
            long decodedSize,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        if (decodedSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException(
                    "decodedSize must be non-negative or UNKNOWN_SIZE"
            );
        }
        this.endMarker = endMarker;
        this.decodedSize = decodedSize;
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
    public RawLZMACodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new RawLZMACodec(
                        properties,
                        endMarker,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public RawLZMACodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new RawLZMACodec(
                        properties,
                        endMarker,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public RawLZMACodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new RawLZMACodec(
                        properties,
                        endMarker,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns the canonical raw LZMA format.
    @Override
    public RawLZMAFormat format() {
        return RawLZMAFormat.instance();
    }


    /// Returns the configured externally declared LZMA properties.
    ///
    /// @return the immutable model properties used by newly created engines
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns whether encoders emit an end marker.
    ///
    /// @return {@code true} if newly created encoders terminate payloads with an end marker
    public boolean emitsEndMarker() {
        return endMarker;
    }

    /// Returns the externally declared decoded size, or UNKNOWN_SIZE.
    ///
    /// @return the exact decoded size, or {@link CompressionCodec#UNKNOWN_SIZE} if termination must use an end marker
    public long decodedSize() {
        return decodedSize;
    }

    /// Returns an immutable raw LZMA codec with the requested properties.
    ///
    /// @param properties the replacement externally declared model properties
    /// @return this codec if the properties are unchanged; otherwise, a new codec with the requested properties
    /// @throws NullPointerException if {@code properties} is {@code null}
    public RawLZMACodec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties)
                ? this
                : new RawLZMACodec(
                        properties,
                        endMarker,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable raw LZMA codec with the requested dictionary size.
    ///
    /// @param dictionarySize the replacement externally declared dictionary size, in bytes
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested size
    /// @throws IllegalArgumentException if {@code dictionarySize} is outside the supported range
    public RawLZMACodec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Returns an immutable raw LZMA codec with the requested end-marker behavior.
    ///
    /// @param endMarker whether newly created encoders emit an end marker
    /// @return this codec if the setting is unchanged; otherwise, a new codec with the requested setting
    public RawLZMACodec withEndMarker(boolean endMarker) {
        return endMarker == this.endMarker
                ? this
                : new RawLZMACodec(
                        properties,
                        endMarker,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable raw LZMA codec with the externally declared decoded size.
    ///
    /// @param decodedSize the exact decoded size, or {@link CompressionCodec#UNKNOWN_SIZE}
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested size
    /// @throws IllegalArgumentException if {@code decodedSize} is less than {@link CompressionCodec#UNKNOWN_SIZE}
    public RawLZMACodec withDecodedSize(long decodedSize) {
        return decodedSize == this.decodedSize
                ? this
                : new RawLZMACodec(
                        properties,
                        endMarker,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Creates a raw LZMA encoder using operation-scoped options.
    @Override
    public CompressionEncoder newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new LZMARawEncoder(properties, options.sourceSize(), endMarker);
    }

    /// Creates a raw LZMA decoder using this codec's configured limits.
    @Override
    public CompressionDecoder newDecoder() throws IOException {
        long effectiveMaximumWindowSize = CompressionDecoderSupport.effectiveMaximumWindowSize(
                maximumWindowSize,
                maximumMemorySize
        );
        CompressionDecoderSupport.requireWindowSize(
                effectiveMaximumWindowSize,
                properties.dictionarySize()
        );
        return CompressionDecoderSupport.limitEngineOutput(
                new LZMARawDecoder(properties, decodedSize, effectiveMaximumWindowSize),
                maximumOutputSize
        );
    }

}

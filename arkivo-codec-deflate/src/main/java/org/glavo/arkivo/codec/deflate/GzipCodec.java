// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecodingOptions;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.internal.GzipDecoder;
import org.glavo.arkivo.codec.deflate.internal.GzipEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable gzip configuration and pure Java member engines.
///
/// Each frame is one gzip member with its own header, Deflate payload, CRC-32, and decoded-size trailer. `flush`
/// exposes a decodable Deflate boundary inside the active member; `finishFrame` completes that member and preserves the
/// immutable configuration for a following member; terminal `finish` ends the complete encoding session.
///
/// Codec values are safe for concurrent use and created engines are independent mutable sessions. Decoders enforce the
/// fixed 32 KiB Deflate window and verify member trailers.
@NotNullByDefault
public final class GzipCodec
        implements CompressionCodec.LevelConfigurable<GzipCodec>,
        CompressionCodec.FlushableFramed<GzipCodec> {
    /// The minimum gzip Deflate match-search level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 0;

    /// The maximum gzip Deflate match-search level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default gzip Deflate match-search level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;

    /// The default immutable gzip codec configuration.
    public static final GzipCodec DEFAULT =
            new GzipCodec(DEFAULT_COMPRESSION_LEVEL, DeflateStrategy.DEFAULT);

    /// The fixed Deflate history-window size used by gzip members.
    private static final long DECODING_WINDOW_SIZE = 1L << 15;

    /// The configured Deflate match-search level.
    private final int compressionLevel;

    /// The configured Deflate strategy.
    private final DeflateStrategy strategy;

    /// Creates the default gzip codec configuration.
    public GzipCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, DeflateStrategy.DEFAULT);
    }

    /// Creates a validated gzip codec configuration.
    private GzipCodec(long compressionLevel, DeflateStrategy strategy) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Gzip compression level must be between 0 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /// Returns the canonical gzip format.
    @Override
    public GzipFormat format() {
        return GzipFormat.instance();
    }


    /// Returns the configured gzip Deflate match-search level.
    @Override
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns the minimum gzip Deflate match-search level.
    @Override
    public long minimumCompressionLevel() {
        return MINIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the maximum gzip Deflate match-search level.
    @Override
    public long maximumCompressionLevel() {
        return MAXIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the default gzip Deflate match-search level.
    @Override
    public long defaultCompressionLevel() {
        return DEFAULT_COMPRESSION_LEVEL;
    }

    /// Returns an immutable gzip codec with the requested match-search level.
    @Override
    public GzipCodec withCompressionLevel(long compressionLevel) {
        return compressionLevel == this.compressionLevel
                ? this
                : new GzipCodec(compressionLevel, strategy);
    }

    /// Returns the configured Deflate strategy.
    ///
    /// @return the configured Deflate strategy
    public DeflateStrategy strategy() {
        return strategy;
    }

    /// Returns an immutable gzip codec with the requested Deflate strategy.
    ///
    /// @param strategy the requested Deflate strategy
    /// @return this instance when unchanged, otherwise a codec using `strategy`
    /// @throws NullPointerException if `strategy` is `null`
    public GzipCodec withStrategy(DeflateStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return strategy == this.strategy
                ? this
                : new GzipCodec(compressionLevel, strategy);
    }


    /// Creates a flushable transport-independent gzip member encoder.
    @Override
    public CompressionEncoder.FlushableFramed newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new GzipEncoder(compressionLevel, strategy);
    }

    /// Creates a transport-independent gzip member decoder with operation-scoped limits.
    @Override
    public CompressionDecoder.Framed newDecoder(DecodingOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        options.requireWindowSize(DECODING_WINDOW_SIZE);
        return CompressionDecoderSupport.limitEngineOutput(
                new GzipDecoder(),
                options.maximumOutputSize()
        );
    }
}

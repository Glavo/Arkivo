// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.DecompressionLimits;
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
        CompressionCodec.StrategyConfigurable<GzipCodec>,
        CompressionCodec.FlushableFramed<GzipCodec> {
    /// The minimum gzip Deflate match-search level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 0;

    /// The maximum gzip Deflate match-search level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default gzip Deflate match-search level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;

    /// The default immutable gzip codec configuration.
    public static final GzipCodec DEFAULT =
            new GzipCodec(DEFAULT_COMPRESSION_LEVEL, CompressionStrategy.DEFAULT);

    /// The fixed Deflate history-window size used by gzip members.
    private static final long DECODING_WINDOW_SIZE = 1L << 15;

    /// The configured Deflate match-search level.
    private final int compressionLevel;

    /// The configured generic compression strategy.
    private final CompressionStrategy compressionStrategy;

    /// Creates the default gzip codec configuration.
    public GzipCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, CompressionStrategy.DEFAULT);
    }

    /// Creates a validated gzip codec configuration.
    private GzipCodec(long compressionLevel, CompressionStrategy compressionStrategy) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Gzip compression level must be between 0 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
        this.compressionStrategy = Objects.requireNonNull(compressionStrategy, "compressionStrategy");
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
                : new GzipCodec(compressionLevel, compressionStrategy);
    }

    /// Returns the configured generic compression strategy.
    @Override
    public CompressionStrategy compressionStrategy() {
        return compressionStrategy;
    }

    /// Returns an immutable gzip codec with the requested generic compression strategy.
    @Override
    public GzipCodec withCompressionStrategy(CompressionStrategy compressionStrategy) {
        Objects.requireNonNull(compressionStrategy, "compressionStrategy");
        return compressionStrategy == this.compressionStrategy
                ? this
                : new GzipCodec(compressionLevel, compressionStrategy);
    }


    /// Creates a flushable transport-independent gzip member encoder.
    @Override
    public CompressionEncoder.FlushableFramed newEncoder() {
        return new GzipEncoder(compressionLevel, compressionStrategy);
    }

    /// Creates a transport-independent gzip member decoder with operation-scoped limits.
    @Override
    public CompressionDecoder.Framed newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        limits.requireWindowSize(DECODING_WINDOW_SIZE);
        return CompressionDecoderSupport.limitEngineOutput(
                new GzipDecoder(),
                limits.maximumOutputSize()
        );
    }
}

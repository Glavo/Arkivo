// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
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
            new GzipCodec(
                    DEFAULT_COMPRESSION_LEVEL,
                    DeflateStrategy.DEFAULT,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE
            );

    /// The fixed Deflate history-window size used by gzip members.
    private static final long DECODING_WINDOW_SIZE = 1L << 15;

    /// The configured Deflate match-search level.
    private final int compressionLevel;

    /// The configured Deflate strategy.
    private final DeflateStrategy strategy;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default gzip codec configuration.
    public GzipCodec() {
        this(
                DEFAULT_COMPRESSION_LEVEL,
                DeflateStrategy.DEFAULT,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE
        );
    }

    /// Creates a validated gzip codec configuration.
    private GzipCodec(
            long compressionLevel,
            DeflateStrategy strategy,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Gzip compression level must be between 0 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
        this.strategy = Objects.requireNonNull(strategy, "strategy");
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
    public GzipCodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new GzipCodec(
                        compressionLevel,
                        strategy,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public GzipCodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new GzipCodec(
                        compressionLevel,
                        strategy,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public GzipCodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new GzipCodec(
                        compressionLevel,
                        strategy,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
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
                : new GzipCodec(
                        compressionLevel,
                        strategy,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
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
                : new GzipCodec(
                        compressionLevel,
                        strategy,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }


    /// Creates a flushable transport-independent gzip member encoder.
    @Override
    public CompressionEncoder.FlushableFramed newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new GzipEncoder(compressionLevel, strategy);
    }

    /// Creates a transport-independent gzip member decoder using this codec's configured limits.
    @Override
    public CompressionDecoder.Framed newDecoder() throws IOException {
        CompressionDecoderSupport.requireWindowSize(
                CompressionDecoderSupport.effectiveMaximumWindowSize(maximumWindowSize, maximumMemorySize),
                DECODING_WINDOW_SIZE
        );
        return CompressionDecoderSupport.limitEngineOutput(
                new GzipDecoder(),
                maximumOutputSize
        );
    }
}

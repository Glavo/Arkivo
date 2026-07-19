// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2ChannelEncoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2Decoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2Encoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;


/// Provides an immutable BZip2 compression configuration and creates transport-independent engines.
///
/// The compression level is the BZip2 block-size digit: level one uses 100,000-byte blocks and level nine uses
/// 900,000-byte blocks. It affects newly created encoders; decoders read the block size from each stream header.
///
/// Instances contain no stream state and are safe for concurrent use. Every [#newEncoder()] or [#newDecoder()] call
/// returns an independent, mutable engine. A completed encoder frame is one complete BZip2
/// stream, including its combined CRC trailer.
@NotNullByDefault
public final class BZip2Codec
        implements CompressionCodec.LevelConfigurable<BZip2Codec>,
        CompressionCodec.Framed<BZip2Codec> {
    /// The minimum BZip2 block-size level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 1;

    /// The maximum BZip2 block-size level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default BZip2 block-size level.
    public static final int DEFAULT_COMPRESSION_LEVEL = BZip2ChannelEncoder.DEFAULT_BLOCK_SIZE;

    /// The default immutable BZip2 codec configuration.
    public static final BZip2Codec DEFAULT = new BZip2Codec(
            DEFAULT_COMPRESSION_LEVEL,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE
    );

    /// The configured BZip2 block-size level.
    private final int compressionLevel;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default BZip2 codec configuration.
    public BZip2Codec() {
        this(DEFAULT_COMPRESSION_LEVEL, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates a validated BZip2 codec configuration.
    private BZip2Codec(
            long compressionLevel,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "BZip2 compression level must be between 1 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
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
    public BZip2Codec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new BZip2Codec(
                        compressionLevel,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public BZip2Codec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new BZip2Codec(
                        compressionLevel,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public BZip2Codec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new BZip2Codec(
                        compressionLevel,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns the canonical BZip2 format.
    @Override
    public BZip2Format format() {
        return BZip2Format.instance();
    }

    /// Returns the configured BZip2 block-size level.
    @Override
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns the minimum BZip2 block-size level.
    @Override
    public long minimumCompressionLevel() {
        return MINIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the maximum BZip2 block-size level.
    @Override
    public long maximumCompressionLevel() {
        return MAXIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the default BZip2 block-size level.
    @Override
    public long defaultCompressionLevel() {
        return DEFAULT_COMPRESSION_LEVEL;
    }

    /// Returns an immutable BZip2 codec with the requested block-size level.
    @Override
    public BZip2Codec withCompressionLevel(long compressionLevel) {
        return compressionLevel == this.compressionLevel
                ? this
                : new BZip2Codec(
                        compressionLevel,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }


    /// Creates a transport-independent BZip2 framed encoder.
    @Override
    public CompressionEncoder.Framed newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new BZip2Encoder(compressionLevel);
    }

    /// Creates a transport-independent BZip2 decoder using this codec's decoded-output limit.
    ///
    /// The output limit is enforced across the decoder session. This implementation does not reject a member based on
    /// `maximumWindowSize` or `maximumMemorySize`; it selects its working buffers from the member's 100,000- through
    /// 900,000-byte block-size digit.
    @Override
    public CompressionDecoder.Framed newDecoder() {
        return CompressionDecoderSupport.limitEngineOutput(
                new BZip2Decoder(),
                maximumOutputSize
        );
    }
}

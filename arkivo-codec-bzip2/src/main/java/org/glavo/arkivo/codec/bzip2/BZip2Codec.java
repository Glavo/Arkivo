// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2ChannelEncoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2Decoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2Encoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;


/// Provides an immutable BZip2 compression configuration and creates transport-independent engines.
@NotNullByDefault
public final class BZip2Codec
        implements CompressionCodec.LevelConfigurable<BZip2Codec>,
        CompressionCodec.Framed {
    /// The minimum BZip2 block-size level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 1;

    /// The maximum BZip2 block-size level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default BZip2 block-size level.
    public static final int DEFAULT_COMPRESSION_LEVEL = BZip2ChannelEncoder.DEFAULT_BLOCK_SIZE;

    /// The default immutable BZip2 codec configuration.
    public static final BZip2Codec DEFAULT = new BZip2Codec(DEFAULT_COMPRESSION_LEVEL);

    /// The configured BZip2 block-size level.
    private final int compressionLevel;

    /// Creates the default BZip2 codec configuration.
    public BZip2Codec() {
        this(DEFAULT_COMPRESSION_LEVEL);
    }

    /// Creates a validated BZip2 codec configuration.
    private BZip2Codec(long compressionLevel) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "BZip2 compression level must be between 1 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
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
                : new BZip2Codec(compressionLevel);
    }


    /// Creates a transport-independent BZip2 framed encoder.
    @Override
    public CompressionEncoder.Framed newEncoder() {
        return new BZip2Encoder(compressionLevel);
    }

    /// Creates a transport-independent BZip2 decoder with operation-scoped limits.
    @Override
    public CompressionDecoder.Framed newDecoder(DecompressionLimits limits) {
        return CompressionDecoderSupport.limitEngineOutput(
                new BZip2Decoder(),
                limits.maximumOutputSize()
        );
    }
}

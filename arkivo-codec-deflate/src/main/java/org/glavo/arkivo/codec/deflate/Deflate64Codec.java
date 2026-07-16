// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionLevelCodec;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.FlushableCompressionEncoder;
import org.glavo.arkivo.codec.deflate.internal.Deflate64Decoder;
import org.glavo.arkivo.codec.deflate.internal.Deflate64CompressionFormat;
import org.glavo.arkivo.codec.deflate.internal.Deflate64Encoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable raw Deflate64 configuration and pure Java buffer engines.
@NotNullByDefault
public final class Deflate64Codec implements CompressionLevelCodec {
    /// The stable Deflate64 compression format name.
    public static final String NAME = "deflate64";

    /// The minimum Deflate64 match-search level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 0;

    /// The maximum Deflate64 match-search level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default Deflate64 match-search level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;

    /// The default immutable Deflate64 codec configuration.
    public static final Deflate64Codec DEFAULT = new Deflate64Codec(DEFAULT_COMPRESSION_LEVEL);

    /// The fixed Deflate64 history-window size.
    private static final long DECODING_WINDOW_SIZE = 1L << 16;

    /// The configured match-search level.
    private final int compressionLevel;

    /// Creates the default Deflate64 codec configuration.
    public Deflate64Codec() {
        this(DEFAULT_COMPRESSION_LEVEL);
    }

    /// Creates a validated Deflate64 codec configuration.
    private Deflate64Codec(long compressionLevel) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Deflate64 compression level must be between 0 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
    }

    /// Returns the canonical raw Deflate64 format.
    @Override
    public CompressionFormat format() {
        return Deflate64CompressionFormat.instance();
    }


    /// Returns the configured Deflate64 match-search level.
    @Override
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns the minimum Deflate64 match-search level.
    @Override
    public long minimumCompressionLevel() {
        return MINIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the maximum Deflate64 match-search level.
    @Override
    public long maximumCompressionLevel() {
        return MAXIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the default Deflate64 match-search level.
    @Override
    public long defaultCompressionLevel() {
        return DEFAULT_COMPRESSION_LEVEL;
    }

    /// Returns an immutable Deflate64 codec with the requested match-search level.
    @Override
    public Deflate64Codec withCompressionLevel(long compressionLevel) {
        return compressionLevel == this.compressionLevel
                ? this
                : new Deflate64Codec(compressionLevel);
    }

    /// Creates a transport-independent raw Deflate64 encoder.
    @Override
    public FlushableCompressionEncoder newEncoder() {
        return new Deflate64Encoder(compressionLevel);
    }

    /// Creates a transport-independent raw Deflate64 decoder with operation-scoped limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        limits.requireWindowSize(DECODING_WINDOW_SIZE);
        return CompressionDecoderSupport.limitEngineOutput(
                new Deflate64Decoder(),
                limits.maximumOutputSize()
        );
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.internal.ZlibCompressionFormat;
import org.glavo.arkivo.codec.deflate.internal.ZlibDecoder;
import org.glavo.arkivo.codec.deflate.internal.ZlibEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Provides an immutable zlib configuration and pure Java stream engines.
@NotNullByDefault
public final class ZlibCodec
        implements CompressionCodec.LevelConfigurable,
        CompressionCodec.StrategyConfigurable,
        CompressionCodec.DictionaryConfigurable {
    /// The stable zlib compression format name.
    public static final String NAME = "zlib";

    /// The minimum zlib Deflate match-search level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 0;

    /// The maximum zlib Deflate match-search level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default zlib Deflate match-search level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;

    /// The default immutable zlib codec configuration.
    public static final ZlibCodec DEFAULT =
            new ZlibCodec(DEFAULT_COMPRESSION_LEVEL, CompressionStrategy.DEFAULT, null);

    /// The configured Deflate match-search level.
    private final int compressionLevel;

    /// The configured generic compression strategy.
    private final CompressionStrategy compressionStrategy;

    /// The configured preset dictionary, or null.
    private final @Nullable CompressionDictionary dictionary;

    /// Creates the default zlib codec configuration.
    public ZlibCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, CompressionStrategy.DEFAULT, null);
    }

    /// Creates a validated zlib codec configuration.
    private ZlibCodec(
            long compressionLevel,
            CompressionStrategy compressionStrategy,
            @Nullable CompressionDictionary dictionary
    ) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Zlib compression level must be between 0 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
        this.compressionStrategy = Objects.requireNonNull(compressionStrategy, "compressionStrategy");
        this.dictionary = dictionary;
    }

    /// Returns the canonical zlib format.
    @Override
    public CompressionFormat format() {
        return ZlibCompressionFormat.instance();
    }


    /// Returns the configured zlib Deflate match-search level.
    @Override
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns the minimum zlib Deflate match-search level.
    @Override
    public long minimumCompressionLevel() {
        return MINIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the maximum zlib Deflate match-search level.
    @Override
    public long maximumCompressionLevel() {
        return MAXIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the default zlib Deflate match-search level.
    @Override
    public long defaultCompressionLevel() {
        return DEFAULT_COMPRESSION_LEVEL;
    }

    /// Returns an immutable zlib codec with the requested match-search level.
    @Override
    public ZlibCodec withCompressionLevel(long compressionLevel) {
        return compressionLevel == this.compressionLevel
                ? this
                : new ZlibCodec(compressionLevel, compressionStrategy, dictionary);
    }

    /// Returns the configured generic compression strategy.
    @Override
    public CompressionStrategy compressionStrategy() {
        return compressionStrategy;
    }

    /// Returns an immutable zlib codec with the requested generic compression strategy.
    @Override
    public ZlibCodec withCompressionStrategy(CompressionStrategy compressionStrategy) {
        Objects.requireNonNull(compressionStrategy, "compressionStrategy");
        return compressionStrategy == this.compressionStrategy
                ? this
                : new ZlibCodec(compressionLevel, compressionStrategy, dictionary);
    }

    /// Returns the configured preset dictionary, or null.
    @Override
    public @Nullable CompressionDictionary dictionary() {
        return dictionary;
    }

    /// Returns an immutable zlib codec with the requested preset dictionary.
    @Override
    public ZlibCodec withDictionary(CompressionDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return dictionary == this.dictionary
                ? this
                : new ZlibCodec(compressionLevel, compressionStrategy, dictionary);
    }

    /// Returns an immutable zlib codec without a preset dictionary.
    @Override
    public ZlibCodec withoutDictionary() {
        return dictionary == null
                ? this
                : new ZlibCodec(compressionLevel, compressionStrategy, null);
    }


    /// Creates a transport-independent zlib stream encoder.
    @Override
    public CompressionEncoder.Flushable newEncoder() {
        return new ZlibEncoder(compressionLevel, dictionary, compressionStrategy);
    }

    /// Creates a transport-independent zlib stream decoder with operation-scoped limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) {
        Objects.requireNonNull(limits, "limits");
        return CompressionDecoderSupport.limitEngineOutput(
                new ZlibDecoder(limits.maximumWindowSize(), dictionary),
                limits.maximumOutputSize()
        );
    }
}

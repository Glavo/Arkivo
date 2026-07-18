// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecodingOptions;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.internal.DeflateDecoder;
import org.glavo.arkivo.codec.deflate.internal.DeflateEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable raw Deflate configuration and pure Java buffer engines.
///
/// Raw Deflate carries no dictionary identifier or content checksum, so callers must supply the exact dictionary used
/// by the encoder; a different dictionary is not always detectable.
///
/// Compression level, strategy, and dictionary changes return new codec values; instances contain no stream state and
/// are safe for concurrent use. Created engines are independent mutable sessions. Encoder `flush` emits a sync-flush
/// boundary without writing the final Deflate block, while `finish` terminates the raw stream.
@NotNullByDefault
public final class DeflateCodec
        implements CompressionCodec.LevelConfigurable<DeflateCodec>,
        CompressionCodec.DictionaryConfigurable<DeflateCodec, RawCompressionDictionary>,
        CompressionCodec.Flushable<DeflateCodec> {
    /// The minimum raw Deflate match-search level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 0;

    /// The maximum raw Deflate match-search level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default raw Deflate match-search level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;

    /// The default immutable raw Deflate codec configuration.
    public static final DeflateCodec DEFAULT =
            new DeflateCodec(DEFAULT_COMPRESSION_LEVEL, DeflateStrategy.DEFAULT, null);

    /// The fixed Deflate history-window size.
    private static final long DECODING_WINDOW_SIZE = 1L << 15;

    /// The configured match-search level.
    private final int compressionLevel;

    /// The configured Deflate strategy.
    private final DeflateStrategy strategy;

    /// The configured preset dictionary, or null.
    private final @Nullable RawCompressionDictionary dictionary;

    /// Creates the default raw Deflate codec configuration.
    public DeflateCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, DeflateStrategy.DEFAULT, null);
    }

    /// Creates a validated raw Deflate codec configuration.
    private DeflateCodec(
            long compressionLevel,
            DeflateStrategy strategy,
            @Nullable RawCompressionDictionary dictionary
    ) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Raw Deflate compression level must be between 0 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.dictionary = dictionary;
    }

    /// Returns the canonical raw Deflate format.
    @Override
    public DeflateFormat format() {
        return DeflateFormat.instance();
    }


    /// Returns the configured match-search level.
    @Override
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns the minimum raw Deflate match-search level.
    @Override
    public long minimumCompressionLevel() {
        return MINIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the maximum raw Deflate match-search level.
    @Override
    public long maximumCompressionLevel() {
        return MAXIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the default raw Deflate match-search level.
    @Override
    public long defaultCompressionLevel() {
        return DEFAULT_COMPRESSION_LEVEL;
    }

    /// Returns an immutable codec with the requested match-search level.
    @Override
    public DeflateCodec withCompressionLevel(long compressionLevel) {
        return compressionLevel == this.compressionLevel
                ? this
                : new DeflateCodec(compressionLevel, strategy, dictionary);
    }

    /// Returns the configured Deflate strategy.
    ///
    /// @return the configured Deflate strategy
    public DeflateStrategy strategy() {
        return strategy;
    }

    /// Returns an immutable codec with the requested Deflate strategy.
    ///
    /// @param strategy the requested Deflate strategy
    /// @return this instance when unchanged, otherwise a codec using `strategy`
    /// @throws NullPointerException if `strategy` is `null`
    public DeflateCodec withStrategy(DeflateStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return strategy == this.strategy
                ? this
                : new DeflateCodec(compressionLevel, strategy, dictionary);
    }

    /// Returns the configured preset dictionary, or null.
    @Override
    public @Nullable RawCompressionDictionary dictionary() {
        return dictionary;
    }

    /// Returns an immutable codec with the requested preset dictionary.
    @Override
    public DeflateCodec withDictionary(RawCompressionDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return dictionary == this.dictionary
                ? this
                : new DeflateCodec(compressionLevel, strategy, dictionary);
    }

    /// Returns an immutable codec without a preset dictionary.
    @Override
    public DeflateCodec withoutDictionary() {
        return dictionary == null
                ? this
                : new DeflateCodec(compressionLevel, strategy, null);
    }

    /// Creates a transport-independent raw Deflate encoder.
    @Override
    public CompressionEncoder.Flushable newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new DeflateEncoder(compressionLevel, dictionary, strategy);
    }

    /// Creates a transport-independent raw Deflate decoder with operation-scoped limits.
    @Override
    public CompressionDecoder newDecoder(DecodingOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        options.requireWindowSize(DECODING_WINDOW_SIZE);
        return CompressionDecoderSupport.limitEngineOutput(
                new DeflateDecoder(dictionary),
                options.maximumOutputSize()
        );
    }
}

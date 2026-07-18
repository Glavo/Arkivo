// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecodingOptions;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.internal.ZlibDecoder;
import org.glavo.arkivo.codec.deflate.internal.ZlibEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Provides an immutable zlib configuration and pure Java stream engines.
///
/// Zlib wraps Deflate with a two-byte header, an optional preset-dictionary identifier, and an Adler-32 content trailer.
/// A configured dictionary is used immediately by encoders and may satisfy a decoder request before it is exposed to
/// the caller. Otherwise a dictionary-aware decoder reports the header identifier and waits for a matching
/// [ZlibDictionary].
///
/// Codec instances are immutable and safe for concurrent use; created engines are independent mutable sessions.
/// Encoder `flush` reaches a nonterminal sync boundary, while `finish` terminates Deflate and writes the Adler-32
/// trailer.
@NotNullByDefault
public final class ZlibCodec
        implements CompressionCodec.LevelConfigurable<ZlibCodec>,
        CompressionCodec.DictionaryConfigurable<ZlibCodec, ZlibDictionary>,
        CompressionCodec.Flushable<ZlibCodec> {
    /// The minimum zlib Deflate match-search level.
    public static final int MINIMUM_COMPRESSION_LEVEL = 0;

    /// The maximum zlib Deflate match-search level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 9;

    /// The default zlib Deflate match-search level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;

    /// The default immutable zlib codec configuration.
    public static final ZlibCodec DEFAULT =
            new ZlibCodec(DEFAULT_COMPRESSION_LEVEL, DeflateStrategy.DEFAULT, null);

    /// The configured Deflate match-search level.
    private final int compressionLevel;

    /// The configured Deflate strategy.
    private final DeflateStrategy strategy;

    /// The configured preset dictionary, or null.
    private final @Nullable ZlibDictionary dictionary;

    /// Creates the default zlib codec configuration.
    public ZlibCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, DeflateStrategy.DEFAULT, null);
    }

    /// Creates a validated zlib codec configuration.
    private ZlibCodec(
            long compressionLevel,
            DeflateStrategy strategy,
            @Nullable ZlibDictionary dictionary
    ) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Zlib compression level must be between 0 and 9: " + compressionLevel
            );
        }
        this.compressionLevel = Math.toIntExact(compressionLevel);
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.dictionary = dictionary;
    }

    /// Returns the canonical zlib format.
    @Override
    public ZlibFormat format() {
        return ZlibFormat.instance();
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
                : new ZlibCodec(compressionLevel, strategy, dictionary);
    }

    /// Returns the configured Deflate strategy.
    ///
    /// @return the configured Deflate strategy
    public DeflateStrategy strategy() {
        return strategy;
    }

    /// Returns an immutable zlib codec with the requested Deflate strategy.
    ///
    /// @param strategy the requested Deflate strategy
    /// @return this instance when unchanged, otherwise a codec using `strategy`
    /// @throws NullPointerException if `strategy` is `null`
    public ZlibCodec withStrategy(DeflateStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return strategy == this.strategy
                ? this
                : new ZlibCodec(compressionLevel, strategy, dictionary);
    }

    /// Returns the configured preset dictionary, or null.
    @Override
    public @Nullable ZlibDictionary dictionary() {
        return dictionary;
    }

    /// Returns an immutable zlib codec with the requested preset dictionary.
    @Override
    public ZlibCodec withDictionary(ZlibDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return dictionary == this.dictionary
                ? this
                : new ZlibCodec(compressionLevel, strategy, dictionary);
    }

    /// Returns an immutable zlib codec without a preset dictionary.
    @Override
    public ZlibCodec withoutDictionary() {
        return dictionary == null
                ? this
                : new ZlibCodec(compressionLevel, strategy, null);
    }


    /// Creates a transport-independent zlib stream encoder.
    @Override
    public CompressionEncoder.Flushable newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new ZlibEncoder(compressionLevel, dictionary, strategy);
    }

    /// Creates an unrestricted dictionary-aware zlib stream decoder.
    @Override
    public CompressionDecoder.DictionaryAware<ZlibDictionary, ZlibDictionaryRequest> newDecoder() {
        return newDecoder(DecodingOptions.DEFAULT);
    }

    /// Creates a dictionary-aware zlib stream decoder with operation-scoped limits.
    @Override
    public CompressionDecoder.DictionaryAware<ZlibDictionary, ZlibDictionaryRequest> newDecoder(
            DecodingOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return CompressionDecoderSupport.limitEngineOutput(
                new ZlibDecoder(options.effectiveMaximumWindowSize(), dictionary),
                options.maximumOutputSize()
        );
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.internal.ZlibDecoder;
import org.glavo.arkivo.codec.deflate.internal.ZlibEncoder;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
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
            new ZlibCodec(
                    DEFAULT_COMPRESSION_LEVEL,
                    DeflateStrategy.DEFAULT,
                    null,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE
            );

    /// The configured Deflate match-search level.
    private final int compressionLevel;

    /// The configured Deflate strategy.
    private final DeflateStrategy strategy;

    /// The configured preset dictionary, or null.
    private final @Nullable ZlibDictionary dictionary;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default zlib codec configuration.
    public ZlibCodec() {
        this(
                DEFAULT_COMPRESSION_LEVEL,
                DeflateStrategy.DEFAULT,
                null,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE
        );
    }

    /// Creates a validated zlib codec configuration.
    private ZlibCodec(
            long compressionLevel,
            DeflateStrategy strategy,
            @Nullable ZlibDictionary dictionary,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
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
    public ZlibCodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new ZlibCodec(
                        compressionLevel,
                        strategy,
                        dictionary,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public ZlibCodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new ZlibCodec(
                        compressionLevel,
                        strategy,
                        dictionary,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public ZlibCodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new ZlibCodec(
                        compressionLevel,
                        strategy,
                        dictionary,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
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
                : new ZlibCodec(
                        compressionLevel,
                        strategy,
                        dictionary,
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

    /// Returns an immutable zlib codec with the requested Deflate strategy.
    ///
    /// @param strategy the requested Deflate strategy
    /// @return this instance when unchanged, otherwise a codec using `strategy`
    /// @throws NullPointerException if `strategy` is `null`
    public ZlibCodec withStrategy(DeflateStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return strategy == this.strategy
                ? this
                : new ZlibCodec(
                        compressionLevel,
                        strategy,
                        dictionary,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
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
                : new ZlibCodec(
                        compressionLevel,
                        strategy,
                        dictionary,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable zlib codec without a preset dictionary.
    @Override
    public ZlibCodec withoutDictionary() {
        return dictionary == null
                ? this
                : new ZlibCodec(
                        compressionLevel,
                        strategy,
                        null,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }


    /// Creates a transport-independent zlib stream encoder.
    @Override
    public CompressionEncoder.Flushable newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new ZlibEncoder(compressionLevel, dictionary, strategy);
    }

    /// Creates a dictionary-aware zlib stream decoder using this codec's configured limits.
    @Override
    public CompressionDecoder.DictionaryAware<ZlibDictionary, ZlibDictionaryRequest> newDecoder() {
        return CompressionDecoderSupport.limitEngineOutput(
                new ZlibDecoder(
                        CompressionDecoderSupport.effectiveMaximumWindowSize(
                                maximumWindowSize,
                                maximumMemorySize
                        ),
                        dictionary
                ),
                maximumOutputSize
        );
    }
}

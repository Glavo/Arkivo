// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lzip.internal.LzipDecoder;
import org.glavo.arkivo.codec.lzip.internal.LzipEncoder;
import org.glavo.arkivo.codec.lzip.internal.LzipSupport;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable lzip configuration and transport-independent member engines.
///
/// The selected dictionary size must have an exact one-byte lzip header representation and is used by encoders.
/// Decoders obtain the dictionary size from each member header and reject it when the configured history or memory
/// limit is smaller.
///
/// Codec values contain no member state and are safe for concurrent use. Each created engine is a mutable session in
/// which one frame is one complete lzip member, including its CRC-32 and size trailer.
@NotNullByDefault
public final class LzipCodec implements CompressionCodec.Framed<LzipCodec> {
    /// The lzip default dictionary size corresponding to the reference encoder's level six.
    public static final int DEFAULT_DICTIONARY_SIZE = 8 * 1024 * 1024;

    /// The smallest dictionary representable by a lzip member header.
    public static final int MINIMUM_DICTIONARY_SIZE = 4 * 1024;

    /// The largest dictionary representable by a lzip member header.
    public static final int MAXIMUM_DICTIONARY_SIZE = 512 * 1024 * 1024;

    /// The default immutable lzip codec configuration.
    public static final LzipCodec DEFAULT = new LzipCodec(
            DEFAULT_DICTIONARY_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE
    );

    /// The dictionary size written to each encoded lzip member.
    private final int dictionarySize;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default lzip codec configuration.
    public LzipCodec() {
        this(DEFAULT_DICTIONARY_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates an lzip codec with an exactly representable dictionary size.
    ///
    /// @param dictionarySize the dictionary size to write to each member, in bytes
    /// @throws IllegalArgumentException if {@code dictionarySize} has no exact lzip header representation
    public LzipCodec(int dictionarySize) {
        this(dictionarySize, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates a validated lzip codec configuration.
    private LzipCodec(
            int dictionarySize,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        LzipSupport.encodeDictionarySize(dictionarySize);
        this.dictionarySize = dictionarySize;
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
    public LzipCodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new LzipCodec(
                        dictionarySize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public LzipCodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new LzipCodec(
                        dictionarySize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public LzipCodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new LzipCodec(
                        dictionarySize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns the canonical lzip format.
    @Override
    public LzipFormat format() {
        return LzipFormat.instance();
    }

    /// Returns the dictionary size written to newly encoded members.
    ///
    /// @return the dictionary size, in bytes
    public int dictionarySize() {
        return dictionarySize;
    }

    /// Returns an immutable lzip codec with the requested exactly representable dictionary size.
    ///
    /// @param dictionarySize the dictionary size to write to each member, in bytes
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested size
    /// @throws IllegalArgumentException if {@code dictionarySize} has no exact lzip header representation
    public LzipCodec withDictionarySize(int dictionarySize) {
        return dictionarySize == this.dictionarySize
                ? this
                : new LzipCodec(
                        dictionarySize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Creates a frame-capable lzip member encoder.
    @Override
    public CompressionEncoder.Framed newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new LzipEncoder(dictionarySize, options);
    }

    /// Creates a frame-capable lzip member decoder using this codec's configured limits.
    @Override
    public CompressionDecoder.Framed newDecoder() throws IOException {
        return CompressionDecoderSupport.limitEngineOutput(
                new LzipDecoder(
                        CompressionDecoderSupport.effectiveMaximumWindowSize(
                                maximumWindowSize,
                                maximumMemorySize
                        ),
                        maximumMemorySize
                ),
                maximumOutputSize
        );
    }
}

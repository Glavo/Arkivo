// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.deflate.internal.DeflateDecoder;
import org.glavo.arkivo.codec.deflate.internal.DeflateEncoder;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Set;

/// Provides pure Java raw Deflate buffer engines and shared channel adapters.
///
/// Raw Deflate carries no dictionary identifier or content checksum, so callers must supply the exact dictionary used
/// by the encoder; a different dictionary is not always detectable.
@NotNullByDefault
public final class DeflateCodec implements CompressionCodec {
    /// The stable raw Deflate codec name.
    public static final String NAME = "deflate";

    /// The default immutable raw Deflate codec configuration.
    public static final DeflateCodec DEFAULT = new DeflateCodec();

    /// The supported raw Deflate operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.FLUSH,
                    CompressionFeature.DICTIONARY,
                    CompressionFeature.DIRECT_BYTE_BUFFER,
                    CompressionFeature.BUFFER_COMPRESSION,
                    CompressionFeature.BUFFER_DECOMPRESSION
            ),
            Set.of(
                    StandardCodecOptions.COMPRESSION_LEVEL,
                    StandardCodecOptions.COMPRESSION_STRATEGY,
                    StandardCodecOptions.DICTIONARY
            ),
            Set.of(
                    StandardCodecOptions.DICTIONARY,
                    StandardCodecOptions.MAX_OUTPUT_SIZE,
                    StandardCodecOptions.MAX_WINDOW_SIZE
            )
    );

    /// The fixed Deflate history-window size.
    private static final long DECODING_WINDOW_SIZE = 1L << 15;

    /// Creates a raw Deflate codec.
    public DeflateCodec() {
    }

    /// Returns the stable raw Deflate codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the supported raw Deflate operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the minimum raw Deflate match-search level.
    @Override
    public long minimumCompressionLevel() {
        return 0L;
    }

    /// Returns the maximum raw Deflate match-search level.
    @Override
    public long maximumCompressionLevel() {
        return 9L;
    }

    /// Returns the default raw Deflate match-search level.
    @Override
    public long defaultCompressionLevel() {
        return 6L;
    }

    /// Creates a configured transport-independent raw Deflate encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "Deflate compression");
        return new DeflateEncoder(
                compressionLevel(options),
                options.get(StandardCodecOptions.DICTIONARY),
                StandardCodecOptionSupport.compressionStrategy(options)
        );
    }

    /// Creates a configured transport-independent raw Deflate decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "Deflate decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, DECODING_WINDOW_SIZE);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new DeflateDecoder(options.get(StandardCodecOptions.DICTIONARY)),
                maximumOutputSize
        );
    }


    /// Resolves and validates the compression level for one encoder engine.
    private int compressionLevel(CodecOptions options) {
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level < minimumCompressionLevel() || level > maximumCompressionLevel()) {
            throw new IllegalArgumentException("Raw Deflate compression level is out of range");
        }
        return Math.toIntExact(level);
    }
}

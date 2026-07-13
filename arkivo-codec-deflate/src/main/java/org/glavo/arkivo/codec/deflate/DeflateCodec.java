// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.deflate.internal.DeflateChannelDecoder;
import org.glavo.arkivo.codec.deflate.internal.DeflateChannelEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;
import java.util.zip.Deflater;

/// Provides raw deflate compression and decompression channels.
///
/// Raw Deflate carries no dictionary identifier or content checksum, so callers must supply the exact dictionary used
/// by the encoder; a different dictionary is not always detectable.
@NotNullByDefault
public final class DeflateCodec implements CompressionCodec {
    /// The stable raw deflate codec name.
    public static final String NAME = "deflate";

    /// The supported raw deflate operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.FLUSH,
                    CompressionFeature.DICTIONARY,
                    CompressionFeature.DIRECT_BYTE_BUFFER
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

    /// Creates a raw deflate codec.
    public DeflateCodec() {
    }

    /// Returns the stable raw deflate codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the supported raw deflate operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the minimum JDK deflate compression level.
    @Override
    public long minimumCompressionLevel() {
        return Deflater.NO_COMPRESSION;
    }

    /// Returns the maximum JDK deflate compression level.
    @Override
    public long maximumCompressionLevel() {
        return Deflater.BEST_COMPRESSION;
    }

    /// Returns the default JDK deflate compression level.
    @Override
    public long defaultCompressionLevel() {
        return Deflater.DEFAULT_COMPRESSION;
    }

    /// Opens a configured raw deflate encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "deflate compression");
        return new DeflateChannelEncoder(
                target,
                ownership,
                compressionLevel(options),
                options.get(StandardCodecOptions.DICTIONARY),
                StandardCodecOptionSupport.compressionStrategy(options)
        );
    }

    /// Opens a configured raw deflate decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "deflate decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, DECODING_WINDOW_SIZE);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new DeflateChannelDecoder(source, ownership, options.get(StandardCodecOptions.DICTIONARY)),
                maximumOutputSize
        );
    }

    /// Resolves and validates the compression level for one encoder context.
    private int compressionLevel(CodecOptions options) {
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level != defaultCompressionLevel()
                && (level < minimumCompressionLevel() || level > maximumCompressionLevel())) {
            throw new IllegalArgumentException("Raw deflate compression level is out of range");
        }
        return Math.toIntExact(level);
    }
}

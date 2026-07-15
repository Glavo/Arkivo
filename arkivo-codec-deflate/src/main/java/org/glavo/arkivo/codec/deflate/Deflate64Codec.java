// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.deflate.internal.Deflate64Decoder;
import org.glavo.arkivo.codec.deflate.internal.Deflate64Encoder;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;

/// Provides pure Java raw Deflate64 buffer engines and blocking channel adapters.
@NotNullByDefault
public final class Deflate64Codec implements CompressionCodec {
    /// The stable Deflate64 codec name.
    public static final String NAME = "deflate64";

    /// The supported Deflate64 operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION,
            CompressionFeature.FLUSH,
            CompressionFeature.DIRECT_BYTE_BUFFER,
            CompressionFeature.BUFFER_COMPRESSION,
            CompressionFeature.BUFFER_DECOMPRESSION
    ), Set.of(StandardCodecOptions.COMPRESSION_LEVEL), Set.of(StandardCodecOptions.MAX_OUTPUT_SIZE, StandardCodecOptions.MAX_WINDOW_SIZE));

    /// The fixed Deflate64 history-window size.
    private static final long DECODING_WINDOW_SIZE = 1L << 16;

    /// Creates a Deflate64 codec.
    public Deflate64Codec() {
    }

    /// Returns the stable Deflate64 codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns alternative stable names accepted for Deflate64.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("deflate-64");
    }

    /// Returns no standalone file extensions because Deflate64 is an embedded raw stream format.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns the supported Deflate64 operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the minimum Deflate64 match-search level.
    @Override
    public long minimumCompressionLevel() {
        return 0L;
    }

    /// Returns the maximum Deflate64 match-search level.
    @Override
    public long maximumCompressionLevel() {
        return 9L;
    }

    /// Returns the default Deflate64 match-search level.
    @Override
    public long defaultCompressionLevel() {
        return 6L;
    }

    /// Creates a configured transport-independent raw Deflate64 encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "Deflate64 compression");
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level < minimumCompressionLevel() || level > maximumCompressionLevel()) {
            throw new IllegalArgumentException("Deflate64 compression level must be between 0 and 9: " + level);
        }
        return new Deflate64Encoder((int) level);
    }

    /// Opens the transport-independent Deflate64 encoder through the shared blocking channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openEncoder(target, options, ownership);
    }

    /// Creates a configured transport-independent raw Deflate64 decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "Deflate64 decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, DECODING_WINDOW_SIZE);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(new Deflate64Decoder(), maximumOutputSize);
    }

    /// Opens the transport-independent Deflate64 decoder through the shared blocking channel adapter.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openDecoder(source, options, ownership);
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.gzip.internal.GzipChannelDecoder;
import org.glavo.arkivo.codec.gzip.internal.GzipChannelEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;

/// Provides gzip compression and decompression channels.
@NotNullByDefault
public final class GzipCodec implements CompressionCodec {
    /// The stable gzip codec name.
    public static final String NAME = "gzip";

    /// The supported gzip operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.FLUSH,
                    CompressionFeature.DIRECT_BYTE_BUFFER,
                    CompressionFeature.CONCATENATED_FRAMES
            ),
            Set.of(StandardCodecOptions.COMPRESSION_LEVEL),
            Set.of(StandardCodecOptions.MAX_OUTPUT_SIZE, StandardCodecOptions.MAX_WINDOW_SIZE)
    );

    /// The fixed Deflate history-window size used by gzip members.
    private static final long DECODING_WINDOW_SIZE = 1L << 15;

    /// Creates a gzip codec.
    public GzipCodec() {
    }

    /// Returns the stable gzip codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common gzip file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("gz", "gzip");
    }

    /// Returns the supported gzip operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the minimum JDK gzip compression level.
    @Override
    public long minimumCompressionLevel() {
        return Deflater.NO_COMPRESSION;
    }

    /// Returns the maximum JDK gzip compression level.
    @Override
    public long maximumCompressionLevel() {
        return Deflater.BEST_COMPRESSION;
    }

    /// Returns the default JDK gzip compression level.
    @Override
    public long defaultCompressionLevel() {
        return Deflater.DEFAULT_COMPRESSION;
    }

    /// Returns the number of leading bytes used to identify gzip streams.
    @Override
    public int probeSize() {
        return 2;
    }

    /// Returns whether the given prefix starts with the gzip stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        return prefix.remaining() >= 2
                && Byte.toUnsignedInt(prefix.get(position)) == 0x1f
                && Byte.toUnsignedInt(prefix.get(position + 1)) == 0x8b;
    }

    /// Opens a configured gzip encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "gzip compression");
        return new GzipChannelEncoder(target, ownership, compressionLevel(options));
    }

    /// Opens a configured gzip decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "gzip decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, DECODING_WINDOW_SIZE);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new GzipChannelDecoder(source, ownership),
                maximumOutputSize
        );
    }

    /// Resolves and validates the compression level for one encoder context.
    private int compressionLevel(CodecOptions options) {
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level != defaultCompressionLevel()
                && (level < minimumCompressionLevel() || level > maximumCompressionLevel())) {
            throw new IllegalArgumentException("Gzip compression level is out of range");
        }
        return Math.toIntExact(level);
    }
}

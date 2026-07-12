// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zlib;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.zlib.internal.ZlibChannelDecoder;
import org.glavo.arkivo.codec.zlib.internal.ZlibChannelEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;
import java.util.zip.Deflater;

/// Provides zlib compression and decompression channels.
@NotNullByDefault
public final class ZlibCodec implements CompressionCodec {
    /// The stable zlib codec name.
    public static final String NAME = "zlib";

    /// The supported zlib operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.FLUSH,
                    CompressionFeature.DIRECT_BYTE_BUFFER
            ),
            Set.of(StandardCodecOptions.COMPRESSION_LEVEL),
            Set.of(StandardCodecOptions.MAX_OUTPUT_SIZE)
    );

    /// Creates a zlib codec.
    public ZlibCodec() {
    }

    /// Returns the stable zlib codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the supported zlib operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the minimum JDK zlib compression level.
    @Override
    public long minimumCompressionLevel() {
        return Deflater.NO_COMPRESSION;
    }

    /// Returns the maximum JDK zlib compression level.
    @Override
    public long maximumCompressionLevel() {
        return Deflater.BEST_COMPRESSION;
    }

    /// Returns the default JDK zlib compression level.
    @Override
    public long defaultCompressionLevel() {
        return Deflater.DEFAULT_COMPRESSION;
    }

    /// Returns the number of leading bytes used to identify zlib streams.
    @Override
    public int probeSize() {
        return 2;
    }

    /// Returns whether the given prefix starts with a valid zlib header.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 2) {
            return false;
        }

        int compressionMethodAndFlags = Byte.toUnsignedInt(prefix.get(position));
        int flags = Byte.toUnsignedInt(prefix.get(position + 1));
        return (compressionMethodAndFlags & 0x0f) == 8
                && (compressionMethodAndFlags >> 4) <= 7
                && ((compressionMethodAndFlags << 8) + flags) % 31 == 0;
    }

    /// Opens a configured zlib encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
        ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "zlib compression");
        return new ZlibChannelEncoder(target, ownership, compressionLevel(options));
    }

    /// Opens a configured zlib decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
        ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "zlib decompression");
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new ZlibChannelDecoder(source, ownership),
                maximumOutputSize
        );
    }

    /// Resolves and validates the compression level for one encoder context.
    private int compressionLevel(CodecOptions options) {
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level != defaultCompressionLevel()
                && (level < minimumCompressionLevel() || level > maximumCompressionLevel())) {
            throw new IllegalArgumentException("Zlib compression level is out of range");
        }
        return Math.toIntExact(level);
    }
}

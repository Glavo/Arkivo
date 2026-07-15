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
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.deflate.internal.ZlibDecoder;
import org.glavo.arkivo.codec.deflate.internal.ZlibEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;

/// Provides pure Java zlib stream buffer engines and blocking channel adapters.
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

    /// Returns the minimum zlib Deflate match-search level.
    @Override
    public long minimumCompressionLevel() {
        return 0L;
    }

    /// Returns the maximum zlib Deflate match-search level.
    @Override
    public long maximumCompressionLevel() {
        return 9L;
    }

    /// Returns the default zlib Deflate match-search level.
    @Override
    public long defaultCompressionLevel() {
        return 6L;
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

    /// Creates a configured transport-independent zlib stream encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "zlib compression");
        return new ZlibEncoder(
                compressionLevel(options),
                options.get(StandardCodecOptions.DICTIONARY),
                StandardCodecOptionSupport.compressionStrategy(options)
        );
    }

    /// Opens the transport-independent zlib encoder through the shared blocking channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openEncoder(target, options, ownership);
    }

    /// Creates a configured transport-independent zlib stream decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "zlib decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new ZlibDecoder(
                        maximumWindowSize,
                        options.get(StandardCodecOptions.DICTIONARY)
                ),
                maximumOutputSize
        );
    }

    /// Opens the transport-independent zlib decoder through the shared blocking channel adapter.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openDecoder(source, options, ownership);
    }

    /// Resolves and validates the compression level for one encoder context.
    private int compressionLevel(CodecOptions options) {
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level < minimumCompressionLevel() || level > maximumCompressionLevel()) {
            throw new IllegalArgumentException("Zlib compression level is out of range");
        }
        return Math.toIntExact(level);
    }
}

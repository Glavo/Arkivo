// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

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
import org.glavo.arkivo.codec.bzip2.internal.BZip2Decoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2Encoder;
import org.glavo.arkivo.codec.bzip2.internal.BZip2ChannelEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;

/// Provides transport-independent BZip2 compression and decompression.
@NotNullByDefault
public final class BZip2Codec implements CompressionCodec {
    /// The stable BZip2 codec name.
    public static final String NAME = "bzip2";

    /// The supported BZip2 operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.BUFFER_COMPRESSION,
            CompressionFeature.BUFFER_DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION,
            CompressionFeature.DIRECT_BYTE_BUFFER,
            CompressionFeature.MULTI_FRAME,
            CompressionFeature.CONCATENATED_FRAMES
    ), Set.of(StandardCodecOptions.COMPRESSION_LEVEL), Set.of(StandardCodecOptions.MAX_OUTPUT_SIZE));

    /// Creates a BZip2 codec.
    public BZip2Codec() {
    }

    /// Returns the stable BZip2 codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns alternative stable names accepted for BZip2.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("bz2");
    }

    /// Returns common BZip2 file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("bz2", "bzip2");
    }

    /// Returns the supported BZip2 operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the minimum BZip2 block-size level.
    @Override
    public long minimumCompressionLevel() {
        return 1L;
    }

    /// Returns the maximum BZip2 block-size level.
    @Override
    public long maximumCompressionLevel() {
        return 9L;
    }

    /// Returns the default BZip2 block-size level.
    @Override
    public long defaultCompressionLevel() {
        return BZip2ChannelEncoder.DEFAULT_BLOCK_SIZE;
    }

    /// Returns the number of leading bytes used to identify BZip2 streams.
    @Override
    public int probeSize() {
        return 4;
    }

    /// Returns whether the given prefix starts with the BZip2 stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 4) {
            return false;
        }

        int blockSize = Byte.toUnsignedInt(prefix.get(position + 3));
        return prefix.get(position) == 'B'
                && prefix.get(position + 1) == 'Z'
                && prefix.get(position + 2) == 'h'
                && blockSize >= '1'
                && blockSize <= '9';
    }

    /// Creates a configured transport-independent BZip2 encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "BZip2 compression");
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level < minimumCompressionLevel() || level > maximumCompressionLevel()) {
            throw new IllegalArgumentException("BZip2 compression level must be between 1 and 9: " + level);
        }
        return new BZip2Encoder((int) level);
    }

    /// Opens a configured BZip2 encoder through the shared buffer-engine channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openEncoder(target, options, ownership);
    }

    /// Creates a configured transport-independent BZip2 decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "BZip2 decompression");
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        CompressionDecoder decoder = new BZip2Decoder();
        return StandardCodecOptionSupport.limitOutput(decoder, maximumOutputSize);
    }

    /// Opens a configured BZip2 decoder through the shared buffer-engine channel adapter.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openDecoder(source, options, ownership);
    }
}

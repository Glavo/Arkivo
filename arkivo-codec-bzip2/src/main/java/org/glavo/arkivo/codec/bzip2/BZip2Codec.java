// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.bzip2.internal.BZip2InputStream;
import org.glavo.arkivo.codec.bzip2.internal.BZip2OutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;

/// Provides BZip2 compression and decompression channels.
@NotNullByDefault
public final class BZip2Codec implements CompressionCodec {
    /// The stable BZip2 codec name.
    public static final String NAME = "bzip2";

    /// The supported BZip2 operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION,
            CompressionFeature.DIRECT_BYTE_BUFFER
    ), Set.of(StandardCodecOptions.COMPRESSION_LEVEL), Set.of());

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
        return BZip2OutputStream.DEFAULT_BLOCK_SIZE;
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

    /// Opens a configured BZip2 encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "BZip2 compression");
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level < minimumCompressionLevel() || level > maximumCompressionLevel()) {
            throw new IllegalArgumentException("BZip2 compression level must be between 1 and 9: " + level);
        }
        return new BZip2OutputStream(target, ownership, (int) level);
    }

    /// Opens a configured BZip2 decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "BZip2 decompression");
        return new BZip2InputStream(source, ownership);
    }
}

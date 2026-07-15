// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.deflate.internal.GzipDecoder;
import org.glavo.arkivo.codec.deflate.internal.GzipEncoder;
import org.glavo.arkivo.codec.spi.CodecChannelAdapters;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Provides pure Java gzip member buffer engines and concatenated-member channel adapters.
@NotNullByDefault
public final class GzipCodec implements CompressionCodec {
    /// The stable gzip codec name.
    public static final String NAME = "gzip";

    /// The default immutable gzip codec configuration.
    public static final GzipCodec DEFAULT = new GzipCodec();

    /// The supported gzip operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.FLUSH,
                    CompressionFeature.DIRECT_BYTE_BUFFER,
                    CompressionFeature.BUFFER_COMPRESSION,
                    CompressionFeature.BUFFER_DECOMPRESSION,
                    CompressionFeature.MULTI_FRAME,
                    CompressionFeature.CONCATENATED_FRAMES
            ),
            Set.of(StandardCodecOptions.COMPRESSION_LEVEL, StandardCodecOptions.COMPRESSION_STRATEGY),
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

    /// Returns the minimum gzip Deflate match-search level.
    @Override
    public long minimumCompressionLevel() {
        return 0L;
    }

    /// Returns the maximum gzip Deflate match-search level.
    @Override
    public long maximumCompressionLevel() {
        return 9L;
    }

    /// Returns the default gzip Deflate match-search level.
    @Override
    public long defaultCompressionLevel() {
        return 6L;
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

    /// Creates a configured transport-independent gzip member encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "gzip compression");
        return new GzipEncoder(
                compressionLevel(options),
                StandardCodecOptionSupport.compressionStrategy(options)
        );
    }

    /// Opens the transport-independent gzip encoder through the shared blocking channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openEncoder(target, options, ownership);
    }

    /// Creates a configured transport-independent gzip member decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "gzip decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, DECODING_WINDOW_SIZE);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(new GzipDecoder(), maximumOutputSize);
    }

    /// Opens a configured gzip decoder over the source channel.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "gzip decompression");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, DECODING_WINDOW_SIZE);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                CodecChannelAdapters.openDecoder(source, ownership, true, GzipDecoder::new),
                maximumOutputSize
        );
    }

    /// Resolves and validates the compression level for one encoder context.
    private int compressionLevel(CodecOptions options) {
        @Nullable Long requested = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long level = requested != null ? requested : defaultCompressionLevel();
        if (level < minimumCompressionLevel() || level > maximumCompressionLevel()) {
            throw new IllegalArgumentException("Gzip compression level is out of range");
        }
        return Math.toIntExact(level);
    }
}

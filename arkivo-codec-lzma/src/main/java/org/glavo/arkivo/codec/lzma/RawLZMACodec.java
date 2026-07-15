// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

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
import org.glavo.arkivo.codec.lzma.internal.LZMAProperties;

import org.glavo.arkivo.codec.lzma.internal.LZMARawDecoder;
import org.glavo.arkivo.codec.lzma.internal.LZMARawEncoder;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;

/// Provides headerless LZMA compression for containers that carry model properties separately.
@NotNullByDefault
public final class RawLZMACodec implements CompressionCodec {
    /// The stable raw LZMA codec name.
    public static final String NAME = "lzma-raw";

    /// The default dictionary size used for raw LZMA compression.
    public static final long DEFAULT_DICTIONARY_SIZE = 1L << 20;

    /// The supported raw LZMA operations and options.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.BUFFER_COMPRESSION,
                    CompressionFeature.BUFFER_DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.DIRECT_BYTE_BUFFER
            ),
            Set.of(
                    LZMAOptions.DICTIONARY_SIZE,
                    LZMAOptions.LITERAL_CONTEXT_BITS,
                    LZMAOptions.LITERAL_POSITION_BITS,
                    LZMAOptions.POSITION_BITS,
                    LZMAOptions.END_MARKER,
                    StandardCodecOptions.PLEDGED_SOURCE_SIZE
            ),
            Set.of(
                    LZMAOptions.DICTIONARY_SIZE,
                    LZMAOptions.LITERAL_CONTEXT_BITS,
                    LZMAOptions.LITERAL_POSITION_BITS,
                    LZMAOptions.POSITION_BITS,
                    LZMAOptions.DECODED_SIZE,
                    StandardCodecOptions.MAX_OUTPUT_SIZE,
                    StandardCodecOptions.MAX_WINDOW_SIZE
            )
    );

    /// Creates a raw LZMA codec.
    public RawLZMACodec() {
    }

    /// Returns the stable raw LZMA codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the alternate raw LZMA name.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("raw-lzma");
    }

    /// Returns no file extensions because raw LZMA has no self-describing container.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns the supported raw LZMA operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Creates a configured transport-independent raw LZMA encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) {
        options.requireSupported(CAPABILITIES.compressionOptions(), "raw LZMA compression");
        LZMAProperties properties = LZMAProperties.fromOptions(
                options,
                LZMAOptions.DICTIONARY_SIZE,
                (int) DEFAULT_DICTIONARY_SIZE
        );
        long pledgedSourceSize = StandardCodecOptionSupport.pledgedSourceSize(options);
        @Nullable Boolean requestedEndMarker = options.get(LZMAOptions.END_MARKER);
        return new LZMARawEncoder(
                properties,
                pledgedSourceSize,
                requestedEndMarker == null || requestedEndMarker
        );
    }

    /// Opens a configured raw LZMA encoder through the shared buffer-engine channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openEncoder(target, options, ownership);
    }

    /// Creates a configured transport-independent raw LZMA decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "raw LZMA decompression");
        if (options.get(LZMAOptions.DICTIONARY_SIZE) == null) {
            throw new IllegalArgumentException(
                    "Required raw LZMA option is missing: " + LZMAOptions.DICTIONARY_SIZE.name()
            );
        }
        LZMAProperties properties = LZMAProperties.fromOptions(
                options,
                LZMAOptions.DICTIONARY_SIZE,
                (int) DEFAULT_DICTIONARY_SIZE
        );
        @Nullable Long requestedDecodedSize = options.get(LZMAOptions.DECODED_SIZE);
        long decodedSize = requestedDecodedSize != null ? requestedDecodedSize : UNKNOWN_SIZE;
        if (decodedSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException("Raw LZMA decoded size must not be negative");
        }
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        CompressionDecoder decoder = new LZMARawDecoder(properties, decodedSize, maximumWindowSize);
        return StandardCodecOptionSupport.limitOutput(decoder, maximumOutputSize);
    }

    /// Opens a configured raw LZMA decoder through the shared buffer-engine channel adapter.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openDecoder(source, options, ownership);
    }
}
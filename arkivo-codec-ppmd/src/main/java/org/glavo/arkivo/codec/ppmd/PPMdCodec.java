// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.ppmd.internal.PPMd7ChannelDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;

/// Provides raw PPMd7 decompression configured by explicit model and output-size parameters.
@NotNullByDefault
public final class PPMdCodec implements CompressionCodec {
    /// The stable PPMd codec name.
    public static final String NAME = "ppmd";

    /// The supported PPMd operations and options.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.DIRECT_BYTE_BUFFER
            ),
            Set.of(),
            Set.of(
                    PPMdCodecOptions.MAXIMUM_ORDER,
                    PPMdCodecOptions.MEMORY_SIZE,
                    PPMdCodecOptions.DECODED_SIZE,
                    StandardCodecOptions.MAX_OUTPUT_SIZE
            )
    );

    /// Creates a PPMd codec.
    public PPMdCodec() {
    }

    /// Returns the stable PPMd codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the PPMd7 alias used by 7z containers.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("ppmd7");
    }

    /// Returns no standalone extension because PPMd7 is an embedded raw stream format.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns the supported PPMd operations and required operation options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Rejects compression because this module currently implements PPMd7 decompression only.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) {
        throw new UnsupportedOperationException("PPMd compression is not supported");
    }

    /// Opens a configured raw PPMd7 decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "PPMd decompression");
        long maximumOrder = requiredNonNegative(options, PPMdCodecOptions.MAXIMUM_ORDER);
        long memorySize = requiredNonNegative(options, PPMdCodecOptions.MEMORY_SIZE);
        long decodedSize = requiredNonNegative(options, PPMdCodecOptions.DECODED_SIZE);
        if (maximumOrder < 2L || maximumOrder > 64L) {
            throw new IllegalArgumentException("PPMd maximum order must be between 2 and 64: " + maximumOrder);
        }
        if (memorySize < 1L << 11 || memorySize > 256L << 20) {
            throw new IllegalArgumentException("PPMd memory size must be between 2 KiB and 256 MiB: " + memorySize);
        }
        @Nullable Long maximumOutputSize = options.get(StandardCodecOptions.MAX_OUTPUT_SIZE);
        if (maximumOutputSize != null) {
            if (maximumOutputSize < 0L) {
                throw new IllegalArgumentException("maximum output size must not be negative");
            }
            if (decodedSize > maximumOutputSize) {
                throw new DecompressionLimitException(maximumOutputSize);
            }
        }
        return new PPMd7ChannelDecoder(
                source,
                ownership,
                (int) maximumOrder,
                memorySize,
                decodedSize
        );
    }

    /// Returns one required non-negative long option.
    private static long requiredNonNegative(CodecOptions options, org.glavo.arkivo.codec.CodecOption<Long> option) {
        @Nullable Long value = options.get(option);
        if (value == null) {
            throw new IllegalArgumentException("Required PPMd option is missing: " + option.name());
        }
        if (value < 0L) {
            throw new IllegalArgumentException(option.name() + " must not be negative: " + value);
        }
        return value;
    }
}

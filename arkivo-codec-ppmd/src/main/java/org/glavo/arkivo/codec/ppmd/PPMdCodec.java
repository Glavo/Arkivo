// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.ppmd.internal.PPMd7Decoder;
import org.glavo.arkivo.codec.ppmd.internal.PPMd7Encoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Set;

/// Provides pure Java raw PPMd7 buffer engines and shared channel adapters with configurable model parameters.
@NotNullByDefault
public final class PPMdCodec implements CompressionCodec {
    /// The stable PPMd codec name.
    public static final String NAME = "ppmd";

    /// The maximum context order used by default for compression.
    private static final long DEFAULT_MAXIMUM_ORDER = 6L;

    /// The model arena size used by default for compression.
    private static final long DEFAULT_MEMORY_SIZE = 16L << 20;

    /// The supported PPMd operations and options.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.DIRECT_BYTE_BUFFER,
                    CompressionFeature.BUFFER_COMPRESSION,
                    CompressionFeature.BUFFER_DECOMPRESSION
            ),
            Set.of(
                    PPMdCodecOptions.MAXIMUM_ORDER,
                    PPMdCodecOptions.MEMORY_SIZE
            ),
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

    /// Creates a configured transport-independent raw PPMd7 encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "PPMd compression");
        long maximumOrder = selectedNonNegative(
                options,
                PPMdCodecOptions.MAXIMUM_ORDER,
                DEFAULT_MAXIMUM_ORDER
        );
        long memorySize = selectedNonNegative(
                options,
                PPMdCodecOptions.MEMORY_SIZE,
                DEFAULT_MEMORY_SIZE
        );
        validateMaximumOrder(maximumOrder);
        validateMemorySize(memorySize);
        return new PPMd7Encoder((int) maximumOrder, memorySize);
    }

    /// Opens the transport-independent raw PPMd7 encoder through the shared blocking channel adapter.
    @Override
    public CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openEncoder(target, options, ownership);
    }

    /// Creates a configured transport-independent exactly sized raw PPMd7 decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "PPMd decompression");
        long maximumOrder = requiredNonNegative(options, PPMdCodecOptions.MAXIMUM_ORDER);
        long memorySize = requiredNonNegative(options, PPMdCodecOptions.MEMORY_SIZE);
        long decodedSize = requiredNonNegative(options, PPMdCodecOptions.DECODED_SIZE);
        validateMaximumOrder(maximumOrder);
        validateMemorySize(memorySize);
        validateOutputLimit(options, decodedSize);
        return new PPMd7Decoder((int) maximumOrder, memorySize, decodedSize);
    }

    /// Opens the transport-independent raw PPMd7 decoder through the shared blocking channel adapter.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        return CompressionCodec.super.openDecoder(source, options, ownership);
    }

    /// Validates the externally declared decoded size against an optional output limit.
    private static void validateOutputLimit(CodecOptions options, long decodedSize) throws DecompressionLimitException {
        @Nullable Long maximumOutputSize = options.get(StandardCodecOptions.MAX_OUTPUT_SIZE);
        if (maximumOutputSize == null) {
            return;
        }
        if (maximumOutputSize < 0L) {
            throw new IllegalArgumentException("maximum output size must not be negative");
        }
        if (decodedSize > maximumOutputSize) {
            throw new DecompressionLimitException(maximumOutputSize);
        }
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

    /// Returns a selected non-negative option or its operation default.
    private static long selectedNonNegative(
            CodecOptions options,
            org.glavo.arkivo.codec.CodecOption<Long> option,
            long defaultValue
    ) {
        @Nullable Long value = options.get(option);
        if (value == null) {
            return defaultValue;
        }
        if (value < 0L) {
            throw new IllegalArgumentException(option.name() + " must not be negative: " + value);
        }
        return value;
    }

    /// Validates a PPMd maximum context order.
    private static void validateMaximumOrder(long maximumOrder) {
        if (maximumOrder < 2L || maximumOrder > 64L) {
            throw new IllegalArgumentException("PPMd maximum order must be between 2 and 64: " + maximumOrder);
        }
    }

    /// Validates a PPMd model arena size.
    private static void validateMemorySize(long memorySize) {
        if (memorySize < 1L << 11 || memorySize > 256L << 20) {
            throw new IllegalArgumentException("PPMd memory size must be between 2 KiB and 256 MiB: " + memorySize);
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.ppmd.internal.PPMd7Decoder;
import org.glavo.arkivo.codec.ppmd.internal.PPMd7Encoder;
import org.glavo.arkivo.codec.ppmd.internal.PPMdCompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable raw PPMd7 configuration with externally declared model parameters and decoded size.
@NotNullByDefault
public final class PPMdCodec implements CompressionCodec {
    /// The stable PPMd codec name.
    public static final String NAME = "ppmd";

    /// The default maximum context order used for compression.
    public static final int DEFAULT_MAXIMUM_ORDER = 6;

    /// The default model arena size used for compression.
    public static final long DEFAULT_MEMORY_SIZE = 16L << 20;

    /// The default immutable PPMd7 codec configuration.
    public static final PPMdCodec DEFAULT =
            new PPMdCodec(DEFAULT_MAXIMUM_ORDER, DEFAULT_MEMORY_SIZE, UNKNOWN_SIZE);

    /// The configured maximum context order.
    private final int maximumOrder;

    /// The configured model arena size.
    private final long memorySize;

    /// The externally declared decoded size, or UNKNOWN_SIZE.
    private final long decodedSize;

    /// Creates the default raw PPMd7 codec configuration.
    public PPMdCodec() {
        this(DEFAULT_MAXIMUM_ORDER, DEFAULT_MEMORY_SIZE, UNKNOWN_SIZE);
    }

    /// Creates a validated raw PPMd7 codec configuration.
    private PPMdCodec(long maximumOrder, long memorySize, long decodedSize) {
        validateMaximumOrder(maximumOrder);
        validateMemorySize(memorySize);
        if (decodedSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException(
                    "decodedSize must be non-negative or UNKNOWN_SIZE"
            );
        }
        this.maximumOrder = Math.toIntExact(maximumOrder);
        this.memorySize = memorySize;
        this.decodedSize = decodedSize;
    }

    /// Returns the canonical raw PPMd7 format.
    @Override
    public CompressionFormat format() {
        return PPMdCompressionFormat.instance();
    }

    /// Returns the configured maximum context order.
    public int maximumOrder() {
        return maximumOrder;
    }

    /// Returns the configured model arena size.
    public long memorySize() {
        return memorySize;
    }

    /// Returns the externally declared decoded size, or UNKNOWN_SIZE.
    public long decodedSize() {
        return decodedSize;
    }

    /// Returns an immutable PPMd codec with the requested maximum context order.
    public PPMdCodec withMaximumOrder(long maximumOrder) {
        return maximumOrder == this.maximumOrder
                ? this
                : new PPMdCodec(maximumOrder, memorySize, decodedSize);
    }

    /// Returns an immutable PPMd codec with the requested model arena size.
    public PPMdCodec withMemorySize(long memorySize) {
        return memorySize == this.memorySize
                ? this
                : new PPMdCodec(maximumOrder, memorySize, decodedSize);
    }

    /// Returns an immutable PPMd codec with the externally declared decoded size.
    public PPMdCodec withDecodedSize(long decodedSize) {
        return decodedSize == this.decodedSize
                ? this
                : new PPMdCodec(maximumOrder, memorySize, decodedSize);
    }

    /// Creates a raw PPMd7 encoder.
    @Override
    public CompressionEncoder newEncoder() throws IOException {
        return new PPMd7Encoder(maximumOrder, memorySize);
    }

    /// Creates an exactly sized raw PPMd7 decoder with operation-scoped limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        if (decodedSize == UNKNOWN_SIZE) {
            throw new IllegalStateException(
                    "Raw PPMd decompression requires an externally declared decoded size"
            );
        }
        long maximumOutputSize = limits.maximumOutputSize();
        if (maximumOutputSize >= 0L && decodedSize > maximumOutputSize) {
            throw new DecompressionLimitException(maximumOutputSize);
        }
        return new PPMd7Decoder(maximumOrder, memorySize, decodedSize);
    }

    /// Validates a PPMd maximum context order.
    private static void validateMaximumOrder(long maximumOrder) {
        if (maximumOrder < 2L || maximumOrder > 64L) {
            throw new IllegalArgumentException(
                    "PPMd maximum order must be between 2 and 64: " + maximumOrder
            );
        }
    }

    /// Validates a PPMd model arena size.
    private static void validateMemorySize(long memorySize) {
        if (memorySize < 1L << 11 || memorySize > 256L << 20) {
            throw new IllegalArgumentException(
                    "PPMd memory size must be between 2 KiB and 256 MiB: " + memorySize
            );
        }
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.ppmd.internal.PPMd7Decoder;
import org.glavo.arkivo.codec.ppmd.internal.PPMd7Encoder;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable raw PPMd7 configuration with externally declared model parameters and decoded size.
///
/// A raw PPMd7 payload carries neither its maximum context order nor its model-arena size. It also has no intrinsic end
/// marker used by this API, so decoder construction requires an exact nonnegative decoded size. These values must come
/// from the embedding container.
///
/// Codec instances are safe for concurrent use. Each created engine owns an independent mutable probability model.
/// Decoder construction checks model memory and declared output against the operation-scoped limits before allocating
/// the model.
@NotNullByDefault
public final class PPMdCodec implements CompressionCodec<PPMdCodec> {
    /// The default maximum context order used for compression.
    public static final int DEFAULT_MAXIMUM_ORDER = 6;

    /// The default model arena size used for compression.
    public static final long DEFAULT_MEMORY_SIZE = 16L << 20;

    /// The default immutable PPMd7 codec configuration.
    public static final PPMdCodec DEFAULT =
            new PPMdCodec(
                    DEFAULT_MAXIMUM_ORDER,
                    DEFAULT_MEMORY_SIZE,
                    UNKNOWN_SIZE,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE
            );

    /// The configured maximum context order.
    private final int maximumOrder;

    /// The configured model arena size.
    private final long memorySize;

    /// The externally declared decoded size, or UNKNOWN_SIZE.
    private final long decodedSize;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default raw PPMd7 codec configuration.
    public PPMdCodec() {
        this(
                DEFAULT_MAXIMUM_ORDER,
                DEFAULT_MEMORY_SIZE,
                UNKNOWN_SIZE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE
        );
    }

    /// Creates a validated raw PPMd7 codec configuration.
    private PPMdCodec(
            int maximumOrder,
            long memorySize,
            long decodedSize,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        validateMaximumOrder(maximumOrder);
        validateMemorySize(memorySize);
        if (decodedSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException(
                    "decodedSize must be non-negative or UNKNOWN_SIZE"
            );
        }
        this.maximumOrder = maximumOrder;
        this.memorySize = memorySize;
        this.decodedSize = decodedSize;
        CompressionDecoderSupport.validateLimit(maximumOutputSize, "maximumOutputSize");
        CompressionDecoderSupport.validateLimit(maximumWindowSize, "maximumWindowSize");
        CompressionDecoderSupport.validateLimit(maximumMemorySize, "maximumMemorySize");
        this.maximumOutputSize = maximumOutputSize;
        this.maximumWindowSize = maximumWindowSize;
        this.maximumMemorySize = maximumMemorySize;
    }

    /// Returns the configured decoded-output limit.
    @Override
    public long maximumOutputSize() {
        return maximumOutputSize;
    }

    /// Returns the configured decoder history-window limit.
    @Override
    public long maximumWindowSize() {
        return maximumWindowSize;
    }

    /// Returns the configured decoder working-memory limit.
    @Override
    public long maximumMemorySize() {
        return maximumMemorySize;
    }

    /// Returns an immutable codec with the requested decoded-output limit.
    @Override
    public PPMdCodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new PPMdCodec(
                        maximumOrder,
                        memorySize,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public PPMdCodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new PPMdCodec(
                        maximumOrder,
                        memorySize,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public PPMdCodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new PPMdCodec(
                        maximumOrder,
                        memorySize,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns the canonical raw PPMd7 format.
    @Override
    public PPMdFormat format() {
        return PPMdFormat.instance();
    }

    /// Returns the configured maximum context order.
    ///
    /// @return the maximum order, from {@code 2} through {@code 64}
    public int maximumOrder() {
        return maximumOrder;
    }

    /// Returns the configured model arena size.
    ///
    /// @return the model arena size, in bytes
    public long memorySize() {
        return memorySize;
    }

    /// Returns the externally declared decoded size, or UNKNOWN_SIZE.
    ///
    /// @return the exact decoded size, or {@link CompressionCodec#UNKNOWN_SIZE} if it has not been supplied
    public long decodedSize() {
        return decodedSize;
    }

    /// Returns an immutable PPMd codec with the requested maximum context order.
    ///
    /// @param maximumOrder the replacement context order, from {@code 2} through {@code 64}
    /// @return this codec if the order is unchanged; otherwise, a new codec with the requested order
    /// @throws IllegalArgumentException if {@code maximumOrder} is outside the supported range
    public PPMdCodec withMaximumOrder(int maximumOrder) {
        return maximumOrder == this.maximumOrder
                ? this
                : new PPMdCodec(
                        maximumOrder,
                        memorySize,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable PPMd codec with the requested model arena size.
    ///
    /// @param memorySize the replacement model arena size, from 2 KiB through 256 MiB
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested arena size
    /// @throws IllegalArgumentException if {@code memorySize} is outside the supported range
    public PPMdCodec withMemorySize(long memorySize) {
        return memorySize == this.memorySize
                ? this
                : new PPMdCodec(
                        maximumOrder,
                        memorySize,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable PPMd codec with the externally declared decoded size.
    ///
    /// @param decodedSize the exact decoded size, or {@link CompressionCodec#UNKNOWN_SIZE}
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested decoded size
    /// @throws IllegalArgumentException if {@code decodedSize} is less than {@link CompressionCodec#UNKNOWN_SIZE}
    public PPMdCodec withDecodedSize(long decodedSize) {
        return decodedSize == this.decodedSize
                ? this
                : new PPMdCodec(
                        maximumOrder,
                        memorySize,
                        decodedSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Creates a raw PPMd7 encoder.
    @Override
    public CompressionEncoder newEncoder(EncodingOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return new PPMd7Encoder(maximumOrder, memorySize);
    }

    /// Creates an exactly sized raw PPMd7 decoder using this codec's configured limits.
    @Override
    public CompressionDecoder newDecoder() throws IOException {
        if (decodedSize == UNKNOWN_SIZE) {
            throw new IllegalStateException(
                    "Raw PPMd decompression requires an externally declared decoded size"
            );
        }
        CompressionDecoderSupport.requireMemorySize(maximumMemorySize, memorySize);
        if (maximumOutputSize >= 0L && decodedSize > maximumOutputSize) {
            throw new DecompressionOutputLimitException(maximumOutputSize);
        }
        return new PPMd7Decoder(maximumOrder, memorySize, decodedSize);
    }

    /// Validates a PPMd maximum context order.
    private static void validateMaximumOrder(int maximumOrder) {
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

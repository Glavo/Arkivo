// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lz4.internal.LZ4BlockDecoder;
import org.glavo.arkivo.codec.lz4.internal.LZ4BlockEncoder;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable configuration for one headerless, bounded LZ4 block.
///
/// Raw LZ4 blocks do not carry compressed or decoded sizes. The configured maximum therefore defines both the
/// allocation boundary and the end-of-input contract used by stream adapters.
///
/// Codec values are safe for concurrent use; each created engine owns one mutable block operation. Decoder construction
/// rejects a history limit below the maximum LZ4 match offset and a memory limit below the buffers required by the
/// configured block bound.
@NotNullByDefault
public final class LZ4BlockCodec implements CompressionCodec<LZ4BlockCodec> {
    /// Maximum source size supported by the standard LZ4 block bound calculation.
    public static final int MAXIMUM_SUPPORTED_BLOCK_SIZE = 0x7e00_0000;

    /// Default maximum decoded raw block size.
    public static final int DEFAULT_MAXIMUM_BLOCK_SIZE = LZ4BlockSize.MIB_4.byteSize();

    /// Default immutable raw LZ4 block configuration.
    public static final LZ4BlockCodec DEFAULT = new LZ4BlockCodec(
            DEFAULT_MAXIMUM_BLOCK_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE,
            UNLIMITED_SIZE
    );

    /// Configured maximum decoded block size.
    private final int maximumBlockSize;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default bounded raw LZ4 block configuration.
    public LZ4BlockCodec() {
        this(DEFAULT_MAXIMUM_BLOCK_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates a validated raw block configuration.
    private LZ4BlockCodec(
            long maximumBlockSize,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        if (maximumBlockSize <= 0L || maximumBlockSize > MAXIMUM_SUPPORTED_BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "maximumBlockSize must be between 1 and " + MAXIMUM_SUPPORTED_BLOCK_SIZE
            );
        }
        this.maximumBlockSize = Math.toIntExact(maximumBlockSize);
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
    public LZ4BlockCodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new LZ4BlockCodec(
                        maximumBlockSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public LZ4BlockCodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new LZ4BlockCodec(
                        maximumBlockSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public LZ4BlockCodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new LZ4BlockCodec(
                        maximumBlockSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns the canonical raw LZ4 block format.
    @Override
    public LZ4BlockFormat format() {
        return LZ4BlockFormat.instance();
    }

    /// Returns the maximum accepted decoded raw block size.
    ///
    /// @return the positive decoded-size bound in bytes
    public int maximumBlockSize() {
        return maximumBlockSize;
    }

    /// Returns an immutable raw block codec with the requested decoded-size bound.
    ///
    /// @param maximumBlockSize the positive bound, no greater than [#MAXIMUM_SUPPORTED_BLOCK_SIZE]
    /// @return this codec when the bound is unchanged, otherwise a new immutable configuration
    /// @throws IllegalArgumentException if the bound is outside the supported range
    public LZ4BlockCodec withMaximumBlockSize(long maximumBlockSize) {
        return maximumBlockSize == this.maximumBlockSize
                ? this
                : new LZ4BlockCodec(
                        maximumBlockSize,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns the standard maximum encoded size for one raw LZ4 block.
    @Override
    public long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0L) {
            throw new IllegalArgumentException("sourceSize must not be negative");
        }
        if (sourceSize > MAXIMUM_SUPPORTED_BLOCK_SIZE) {
            return UNKNOWN_SIZE;
        }
        return sourceSize + sourceSize / 255L + 16L;
    }

    /// Creates a transport-independent encoder for one bounded raw LZ4 block.
    @Override
    public CompressionEncoder newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new LZ4BlockEncoder(maximumBlockSize);
    }

    /// Creates a transport-independent raw LZ4 block decoder using this codec's configured limits.
    @Override
    public CompressionDecoder newDecoder() throws IOException {
        long effectiveMaximumWindowSize = CompressionDecoderSupport.effectiveMaximumWindowSize(
                maximumWindowSize,
                maximumMemorySize
        );
        CompressionDecoderSupport.requireWindowSize(effectiveMaximumWindowSize, 65_535L);
        return CompressionDecoderSupport.limitEngineOutput(
                new LZ4BlockDecoder(
                        maximumBlockSize,
                        Math.toIntExact(maxCompressedSize(maximumBlockSize)),
                        effectiveMaximumWindowSize,
                        maximumMemorySize
                ),
                maximumOutputSize
        );
    }
}

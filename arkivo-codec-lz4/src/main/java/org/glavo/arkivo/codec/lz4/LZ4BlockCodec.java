// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.lz4.internal.LZ4BlockDecoder;
import org.glavo.arkivo.codec.lz4.internal.LZ4BlockEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
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
    public static final LZ4BlockCodec DEFAULT = new LZ4BlockCodec(DEFAULT_MAXIMUM_BLOCK_SIZE);

    /// Configured maximum decoded block size.
    private final int maximumBlockSize;

    /// Creates the default bounded raw LZ4 block configuration.
    public LZ4BlockCodec() {
        this(DEFAULT_MAXIMUM_BLOCK_SIZE);
    }

    /// Creates a validated raw block configuration.
    private LZ4BlockCodec(long maximumBlockSize) {
        if (maximumBlockSize <= 0L || maximumBlockSize > MAXIMUM_SUPPORTED_BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "maximumBlockSize must be between 1 and " + MAXIMUM_SUPPORTED_BLOCK_SIZE
            );
        }
        this.maximumBlockSize = Math.toIntExact(maximumBlockSize);
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
                : new LZ4BlockCodec(maximumBlockSize);
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
    public CompressionEncoder newEncoder() {
        return new LZ4BlockEncoder(maximumBlockSize);
    }

    /// Creates a transport-independent raw LZ4 block decoder with operation-scoped limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        limits.requireWindowSize(65_535L);
        return CompressionDecoderSupport.limitEngineOutput(
                new LZ4BlockDecoder(
                        maximumBlockSize,
                        Math.toIntExact(maxCompressedSize(maximumBlockSize)),
                        limits.effectiveMaximumWindowSize(),
                        limits.maximumMemorySize()
                ),
                limits.maximumOutputSize()
        );
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.compress.internal.UnixCompressDecoder;
import org.glavo.arkivo.codec.compress.internal.UnixCompressEncoder;
import org.glavo.arkivo.codec.compress.internal.UnixCompressSupport;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable Unix compress configuration and transport-independent LZW engines.
@NotNullByDefault
public final class UnixCompressCodec implements CompressionCodec<UnixCompressCodec> {
    /// The smallest code width accepted by the Unix compress format.
    public static final int MINIMUM_CODE_WIDTH = 9;

    /// The largest code width accepted by the portable Unix compress format.
    public static final int MAXIMUM_CODE_WIDTH = 16;

    /// The code-width limit used by the default codec.
    public static final int DEFAULT_MAXIMUM_CODE_WIDTH = MAXIMUM_CODE_WIDTH;

    /// Whether streams produced by the default codec permit dictionary clear codes.
    public static final boolean DEFAULT_BLOCK_MODE = true;

    /// The default immutable Unix compress codec configuration.
    public static final UnixCompressCodec DEFAULT =
            new UnixCompressCodec(DEFAULT_MAXIMUM_CODE_WIDTH, DEFAULT_BLOCK_MODE);

    /// The largest LZW code width written to the stream header.
    private final int maximumCodeWidth;

    /// Whether the stream header enables dictionary clear codes.
    private final boolean blockMode;

    /// Creates the default Unix compress codec configuration.
    public UnixCompressCodec() {
        this(DEFAULT_MAXIMUM_CODE_WIDTH, DEFAULT_BLOCK_MODE);
    }

    /// Creates a Unix compress codec with explicit header parameters.
    public UnixCompressCodec(int maximumCodeWidth, boolean blockMode) {
        UnixCompressSupport.requireMaximumCodeWidth(maximumCodeWidth);
        this.maximumCodeWidth = maximumCodeWidth;
        this.blockMode = blockMode;
    }

    /// Returns the canonical Unix compress format.
    @Override
    public UnixCompressFormat format() {
        return UnixCompressFormat.instance();
    }

    /// Returns the largest LZW code width written to encoded stream headers.
    public int maximumCodeWidth() {
        return maximumCodeWidth;
    }

    /// Returns whether encoded streams permit dictionary clear codes.
    public boolean blockMode() {
        return blockMode;
    }

    /// Returns an immutable codec with the requested maximum LZW code width.
    public UnixCompressCodec withMaximumCodeWidth(int maximumCodeWidth) {
        return maximumCodeWidth == this.maximumCodeWidth
                ? this
                : new UnixCompressCodec(maximumCodeWidth, blockMode);
    }

    /// Returns an immutable codec with the requested block-mode header policy.
    public UnixCompressCodec withBlockMode(boolean blockMode) {
        return blockMode == this.blockMode
                ? this
                : new UnixCompressCodec(maximumCodeWidth, blockMode);
    }

    /// Returns a safe upper bound for one encoded Unix compress stream.
    @Override
    public long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0L) {
            throw new IllegalArgumentException("sourceSize must not be negative");
        }
        if (sourceSize == 0L) {
            return UnixCompressSupport.HEADER_SIZE;
        }

        long maximumAlignmentBits = 0L;
        for (int width = MINIMUM_CODE_WIDTH; width < maximumCodeWidth; width++) {
            maximumAlignmentBits += 7L * width;
        }
        try {
            long codeBits = Math.multiplyExact(sourceSize, maximumCodeWidth);
            long totalBits = Math.addExact(codeBits, maximumAlignmentBits);
            long payloadBytes = Math.floorDiv(Math.addExact(totalBits, 7L), Byte.SIZE);
            return Math.addExact(UnixCompressSupport.HEADER_SIZE, payloadBytes);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /// Creates a transport-independent Unix compress encoder.
    @Override
    public CompressionEncoder newEncoder() {
        return new UnixCompressEncoder(maximumCodeWidth, blockMode);
    }

    /// Creates a transport-independent Unix compress decoder with operation-scoped limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return CompressionDecoderSupport.limitEngineOutput(
                new UnixCompressDecoder(limits),
                limits.maximumOutputSize()
        );
    }
}

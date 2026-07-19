// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.compress.internal.UnixCompressDecoder;
import org.glavo.arkivo.codec.compress.internal.UnixCompressEncoder;
import org.glavo.arkivo.codec.compress.internal.UnixCompressSupport;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable Unix compress configuration and transport-independent LZW engines.
///
/// The maximum code width and block-mode flag are written to new `.Z` headers and govern encoding. Decoders instead
/// read these parameters from each input header, then check the resulting LZW table and working-memory requirements
/// against this codec's configured limits.
///
/// Codec instances are safe for concurrent use and contain no stream state. Each created engine represents one mutable
/// `.Z` stream session and is not safe for concurrent use.
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
            new UnixCompressCodec(
                    DEFAULT_MAXIMUM_CODE_WIDTH,
                    DEFAULT_BLOCK_MODE,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE
            );

    /// The largest LZW code width written to the stream header.
    private final int maximumCodeWidth;

    /// Whether the stream header enables dictionary clear codes.
    private final boolean blockMode;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default Unix compress codec configuration.
    public UnixCompressCodec() {
        this(
                DEFAULT_MAXIMUM_CODE_WIDTH,
                DEFAULT_BLOCK_MODE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE,
                UNLIMITED_SIZE
        );
    }

    /// Creates a Unix compress codec with explicit header parameters.
    ///
    /// @param maximumCodeWidth the largest LZW code width, from [#MINIMUM_CODE_WIDTH] through
    /// [#MAXIMUM_CODE_WIDTH]
    /// @param blockMode whether encoded streams permit dictionary clear codes
    /// @throws IllegalArgumentException if `maximumCodeWidth` is outside the supported range
    public UnixCompressCodec(int maximumCodeWidth, boolean blockMode) {
        this(maximumCodeWidth, blockMode, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
    }

    /// Creates a validated Unix compress codec configuration.
    private UnixCompressCodec(
            int maximumCodeWidth,
            boolean blockMode,
            long maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) {
        UnixCompressSupport.requireMaximumCodeWidth(maximumCodeWidth);
        this.maximumCodeWidth = maximumCodeWidth;
        this.blockMode = blockMode;
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
    public UnixCompressCodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : new UnixCompressCodec(
                        maximumCodeWidth,
                        blockMode,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public UnixCompressCodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : new UnixCompressCodec(
                        maximumCodeWidth,
                        blockMode,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public UnixCompressCodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : new UnixCompressCodec(
                        maximumCodeWidth,
                        blockMode,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns the canonical Unix compress format.
    @Override
    public UnixCompressFormat format() {
        return UnixCompressFormat.instance();
    }

    /// Returns the largest LZW code width written to encoded stream headers.
    ///
    /// @return the configured width from [#MINIMUM_CODE_WIDTH] through [#MAXIMUM_CODE_WIDTH]
    public int maximumCodeWidth() {
        return maximumCodeWidth;
    }

    /// Returns whether encoded streams permit dictionary clear codes.
    ///
    /// @return `true` when block mode is written to new stream headers
    public boolean blockMode() {
        return blockMode;
    }

    /// Returns an immutable codec with the requested maximum LZW code width.
    ///
    /// @param maximumCodeWidth the largest encoded LZW code width
    /// @return this codec when the width is unchanged, otherwise a new immutable configuration
    /// @throws IllegalArgumentException if `maximumCodeWidth` is outside the supported range
    public UnixCompressCodec withMaximumCodeWidth(int maximumCodeWidth) {
        return maximumCodeWidth == this.maximumCodeWidth
                ? this
                : new UnixCompressCodec(
                        maximumCodeWidth,
                        blockMode,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
    }

    /// Returns an immutable codec with the requested block-mode header policy.
    ///
    /// @param blockMode whether encoded streams permit dictionary clear codes
    /// @return this codec when the policy is unchanged, otherwise a new immutable configuration
    public UnixCompressCodec withBlockMode(boolean blockMode) {
        return blockMode == this.blockMode
                ? this
                : new UnixCompressCodec(
                        maximumCodeWidth,
                        blockMode,
                        maximumOutputSize,
                        maximumWindowSize,
                        maximumMemorySize
                );
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
    public CompressionEncoder newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new UnixCompressEncoder(maximumCodeWidth, blockMode);
    }

    /// Creates a transport-independent Unix compress decoder using this codec's configured limits.
    @Override
    public CompressionDecoder newDecoder() throws IOException {
        return CompressionDecoderSupport.limitEngineOutput(
                new UnixCompressDecoder(
                        CompressionDecoderSupport.effectiveMaximumWindowSize(
                                maximumWindowSize,
                                maximumMemorySize
                        ),
                        maximumMemorySize
                ),
                maximumOutputSize
        );
    }
}

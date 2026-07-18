// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecodingOptions;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;

/// Validates public ByteBuffer operations and drives the codec's mandatory buffer engines.
@NotNullByDefault
public final class ByteBufferCodecSupport {
    /// Creates no instances.
    private ByteBufferCodecSupport() {
    }

    /// Compresses remaining source bytes into a dynamically growing heap buffer.
    ///
    /// @param codec  the immutable codec configuration
    /// @param source the source buffer consumed from its current position to its limit
    /// @return a new heap buffer positioned at zero with its limit at the encoded size
    /// @throws IOException if encoding fails
    public static ByteBuffer compressAllocating(
            CompressionCodec<?> codec,
            ByteBuffer source
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        return DirectByteBufferCodecSupport.compressAllocating(codec, source);
    }

    /// Decompresses remaining source bytes into a dynamically growing bounded heap buffer.
    ///
    /// @param codec   the immutable codec configuration
    /// @param source  the source buffer consumed from its current position to its limit
    /// @param options the parameters for this decoding operation
    /// @return a new heap buffer positioned at zero with its limit at the decoded size
    /// @throws IOException              if the complete encoding cannot be decoded within the configured limits
    /// @throws IllegalArgumentException if the output limit is not finite or exceeds {@link Integer#MAX_VALUE}
    public static ByteBuffer decompressAllocating(
            CompressionCodec<?> codec,
            ByteBuffer source,
            DecodingOptions options
    ) throws IOException {
        int maximumOutputSize = allocatingMaximumOutputSize(options);
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        return DirectByteBufferCodecSupport.decompressAllocating(
                codec,
                source,
                maximumOutputSize,
                options
        );
    }

    /// Decompresses one frame into a dynamically growing bounded heap buffer.
    ///
    /// @param codec   the immutable codec configuration
    /// @param source  the source buffer beginning with one frame
    /// @param options the parameters for this decoding operation
    /// @return a new heap buffer positioned at zero with its limit at the decoded frame size
    /// @throws IOException              if the frame cannot be decoded within the limits
    /// @throws IllegalArgumentException if the output limit is not finite or exceeds {@link Integer#MAX_VALUE}
    public static ByteBuffer decompressFrameAllocating(
            CompressionCodec<?> codec,
            ByteBuffer source,
            DecodingOptions options
    ) throws IOException {
        int maximumOutputSize = allocatingMaximumOutputSize(options);
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        return DirectByteBufferCodecSupport.decompressFrameAllocating(
                codec,
                source,
                maximumOutputSize,
                options
        );
    }

    /// Compresses all remaining source bytes into a fixed caller-owned target.
    ///
    /// @param codec  the immutable codec configuration
    /// @param source the source buffer advanced by consumed uncompressed bytes
    /// @param target the distinct writable target advanced by produced encoded bytes
    /// @throws IOException if encoding fails
    public static void compress(
            CompressionCodec<?> codec,
            ByteBuffer source,
            ByteBuffer target
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        validateBuffers(source, target);
        DirectByteBufferCodecSupport.compress(codec, source, target);
    }

    /// Decompresses all remaining source bytes into a fixed caller-owned target.
    ///
    /// @param codec   the immutable codec configuration
    /// @param source  the source buffer advanced by consumed compressed bytes
    /// @param target  the distinct writable target advanced by produced decoded bytes
    /// @param options the parameters for this decoding operation
    /// @throws IOException if the complete encoding cannot be decoded within the configured limits
    public static void decompress(
            CompressionCodec<?> codec,
            ByteBuffer source,
            ByteBuffer target,
            DecodingOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        validateBuffers(source, target);
        DirectByteBufferCodecSupport.decompress(codec, source, target, options);
    }

    /// Decompresses one complete frame into a fixed caller-owned target.
    ///
    /// @param codec   the immutable codec configuration
    /// @param source  the source buffer beginning with one frame and advanced only through that frame
    /// @param target  the distinct writable target advanced by produced decoded bytes
    /// @param options the parameters for this decoding operation
    /// @throws IOException if the frame cannot be decoded within the configured limits
    public static void decompressFrame(
            CompressionCodec<?> codec,
            ByteBuffer source,
            ByteBuffer target,
            DecodingOptions options
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        validateBuffers(source, target);
        DirectByteBufferCodecSupport.decompressFrame(codec, source, target, options);
    }

    /// Returns the finite output bound required by allocating decompression.
    private static int allocatingMaximumOutputSize(DecodingOptions options) {
        Objects.requireNonNull(options, "options");
        long maximumOutputSize = options.maximumOutputSize();
        if (maximumOutputSize < 0L || maximumOutputSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Allocating decompression requires maximumOutputSize between zero and "
                            + Integer.MAX_VALUE
            );
        }
        return (int) maximumOutputSize;
    }

    /// Validates source and target buffer constraints shared by fixed-buffer operations.
    private static void validateBuffers(ByteBuffer source, ByteBuffer target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source == target) {
            throw new IllegalArgumentException("source and target must be different buffers");
        }
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
    }
}

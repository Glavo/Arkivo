// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionLimits;
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
    public static ByteBuffer compressAllocating(
            CompressionCodec codec,
            ByteBuffer source
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        return DirectByteBufferCodecSupport.compressAllocating(codec, source);
    }

    /// Decompresses remaining source bytes into a dynamically growing bounded heap buffer.
    public static ByteBuffer decompressAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            DecompressionLimits limits
    ) throws IOException {
        int maximumOutputSize = allocatingMaximumOutputSize(limits);
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        return DirectByteBufferCodecSupport.decompressAllocating(
                codec,
                source,
                maximumOutputSize,
                limits
        );
    }

    /// Decompresses one frame into a dynamically growing bounded heap buffer.
    public static ByteBuffer decompressFrameAllocating(
            CompressionCodec codec,
            ByteBuffer source,
            DecompressionLimits limits
    ) throws IOException {
        int maximumOutputSize = allocatingMaximumOutputSize(limits);
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        return DirectByteBufferCodecSupport.decompressFrameAllocating(
                codec,
                source,
                maximumOutputSize,
                limits
        );
    }

    /// Compresses all remaining source bytes into a fixed caller-owned target.
    public static void compress(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        validateBuffers(source, target);
        DirectByteBufferCodecSupport.compress(codec, source, target);
    }

    /// Decompresses all remaining source bytes into a fixed caller-owned target.
    public static void decompress(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(limits, "limits");
        validateBuffers(source, target);
        DirectByteBufferCodecSupport.decompress(codec, source, target, limits);
    }

    /// Decompresses one complete frame into a fixed caller-owned target.
    public static void decompressFrame(
            CompressionCodec codec,
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(limits, "limits");
        validateBuffers(source, target);
        DirectByteBufferCodecSupport.decompressFrame(codec, source, target, limits);
    }

    /// Returns the finite output bound required by allocating decompression.
    private static int allocatingMaximumOutputSize(DecompressionLimits limits) {
        Objects.requireNonNull(limits, "limits");
        long maximumOutputSize = limits.maximumOutputSize();
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

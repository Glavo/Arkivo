// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines immutable parameters for one seekable encoding operation.
///
/// A seekable encoder divides the logical source into independently decodable frames no larger than
/// `maximumFrameSize` and appends the format-specific index required for random access. Smaller frames reduce the
/// amount of unrelated data decoded for a small read, while larger frames generally improve compression ratio. An
/// explicit `finishFrame` operation may end a frame before this automatic size boundary.
///
/// `sourceSize` describes the complete logical source rather than an individual frame. Implementations may reject
/// finalization when the accepted byte count differs from a known source size.
///
/// @param sourceSize       the exact logical source size, or [CompressionCodec#UNKNOWN_SIZE] when unknown
/// @param maximumFrameSize the positive maximum number of uncompressed bytes in one independently decodable frame
@NotNullByDefault
public record SeekableEncodingOptions(long sourceSize, int maximumFrameSize) {
    /// The default maximum frame size, balancing random-access amplification and compression ratio.
    public static final int DEFAULT_MAXIMUM_FRAME_SIZE = 1024 * 1024;

    /// The default options, with an unknown source size and one-mebibyte frames.
    public static final SeekableEncodingOptions DEFAULT =
            new SeekableEncodingOptions(CompressionCodec.UNKNOWN_SIZE, DEFAULT_MAXIMUM_FRAME_SIZE);

    /// Validates the logical source metadata and frame-size policy.
    ///
    /// @throws IllegalArgumentException if `sourceSize` is below [CompressionCodec#UNKNOWN_SIZE] or
    ///                                  `maximumFrameSize` is not positive
    public SeekableEncodingOptions {
        if (sourceSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("sourceSize must be non-negative or UNKNOWN_SIZE");
        }
        if (maximumFrameSize <= 0) {
            throw new IllegalArgumentException("maximumFrameSize must be positive");
        }
    }

    /// Creates options with an unknown source size and the requested maximum frame size.
    ///
    /// @param maximumFrameSize the positive maximum uncompressed frame size
    /// @return options carrying the requested maximum frame size
    /// @throws IllegalArgumentException if `maximumFrameSize` is not positive
    public static SeekableEncodingOptions ofMaximumFrameSize(int maximumFrameSize) {
        return maximumFrameSize == DEFAULT_MAXIMUM_FRAME_SIZE
                ? DEFAULT
                : new SeekableEncodingOptions(CompressionCodec.UNKNOWN_SIZE, maximumFrameSize);
    }

    /// Returns a copy carrying the requested exact logical source size.
    ///
    /// @param sourceSize the exact non-negative logical size, or [CompressionCodec#UNKNOWN_SIZE]
    /// @return this instance when unchanged, otherwise options carrying `sourceSize`
    /// @throws IllegalArgumentException if `sourceSize` is below [CompressionCodec#UNKNOWN_SIZE]
    public SeekableEncodingOptions withSourceSize(long sourceSize) {
        return sourceSize == this.sourceSize
                ? this
                : new SeekableEncodingOptions(sourceSize, maximumFrameSize);
    }

    /// Returns a copy carrying the requested maximum frame size.
    ///
    /// @param maximumFrameSize the positive maximum uncompressed frame size
    /// @return this instance when unchanged, otherwise options carrying `maximumFrameSize`
    /// @throws IllegalArgumentException if `maximumFrameSize` is not positive
    public SeekableEncodingOptions withMaximumFrameSize(int maximumFrameSize) {
        return maximumFrameSize == this.maximumFrameSize
                ? this
                : new SeekableEncodingOptions(sourceSize, maximumFrameSize);
    }
}

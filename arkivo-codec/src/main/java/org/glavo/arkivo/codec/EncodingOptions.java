// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines immutable parameters for one encoding operation.
///
/// `sourceSize` is exact operation metadata. Every codec accepts it, but an implementation may ignore it when the
/// format cannot record it and the algorithm cannot use it. Implementations that use the value may reject an encoding
/// whose actual input size differs from it. For a framed encoder, the value describes only the first frame; following
/// frames begin with an unknown source size.
///
/// @param sourceSize the exact uncompressed input size, or [CompressionCodec#UNKNOWN_SIZE] when unknown
@NotNullByDefault
public record EncodingOptions(long sourceSize) {
    /// The default options, which do not supply an exact source size.
    public static final EncodingOptions DEFAULT = new EncodingOptions(CompressionCodec.UNKNOWN_SIZE);

    /// Validates all operation parameters.
    ///
    /// @throws IllegalArgumentException if `sourceSize` is less than [CompressionCodec#UNKNOWN_SIZE]
    public EncodingOptions {
        if (sourceSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("sourceSize must be non-negative or UNKNOWN_SIZE");
        }
    }

    /// Creates options carrying the exact uncompressed source size.
    ///
    /// @param sourceSize the exact non-negative input size, or [CompressionCodec#UNKNOWN_SIZE] when unknown
    /// @return encoding options carrying `sourceSize`
    /// @throws IllegalArgumentException if `sourceSize` is less than [CompressionCodec#UNKNOWN_SIZE]
    public static EncodingOptions ofSourceSize(long sourceSize) {
        return sourceSize == CompressionCodec.UNKNOWN_SIZE ? DEFAULT : new EncodingOptions(sourceSize);
    }

    /// Returns options carrying the requested exact source size.
    ///
    /// @param sourceSize the exact non-negative input size, or [CompressionCodec#UNKNOWN_SIZE] when unknown
    /// @return this instance when unchanged, otherwise options carrying `sourceSize`
    /// @throws IllegalArgumentException if `sourceSize` is less than [CompressionCodec#UNKNOWN_SIZE]
    public EncodingOptions withSourceSize(long sourceSize) {
        return sourceSize == this.sourceSize ? this : ofSourceSize(sourceSize);
    }
}

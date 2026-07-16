// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.spi.CodecChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Creates encoders that can receive an exact uncompressed source size as operation-scoped metadata.
@NotNullByDefault
public interface PledgedSourceSizeCodec extends CompressionCodec {
    /// Creates an encoder for a source with the requested exact byte count.
    ///
    /// `pledgedSourceSize` may be `CompressionCodec.UNKNOWN_SIZE` when the size is not known before encoding starts.
    CompressionEncoder newEncoder(long pledgedSourceSize) throws IOException;

    /// Opens an encoder context with exact uncompressed source-size metadata.
    default CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            long pledgedSourceSize,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return CodecChannelAdapters.openEncoder(
                target,
                ownership,
                () -> newEncoder(pledgedSourceSize)
        );
    }

    /// Opens an encoder with exact source-size metadata and retains target ownership.
    default CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            long pledgedSourceSize
    ) throws IOException {
        return openEncoder(target, pledgedSourceSize, ChannelOwnership.RETAIN);
    }

    /// Creates an encoder without a known source size.
    @Override
    default CompressionEncoder newEncoder() throws IOException {
        return newEncoder(UNKNOWN_SIZE);
    }
}

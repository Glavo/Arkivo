// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies an independently discoverable compression codec capability.
@NotNullByDefault
public enum CompressionFeature {
    /// The codec can encode uncompressed input.
    COMPRESSION,

    /// The codec can decode compressed input.
    DECOMPRESSION,

    /// The codec provides bounded one-shot ByteBuffer compression.
    ONE_SHOT_COMPRESSION,

    /// The codec provides bounded one-shot ByteBuffer decompression.
    ONE_SHOT_DECOMPRESSION,

    /// The codec can force pending data to a decodable boundary without ending the frame.
    FLUSH,

    /// The codec accepts external dictionary content.
    DICTIONARY,

    /// The codec has a specialized direct-ByteBuffer data path.
    DIRECT_BYTE_BUFFER,

    /// One encoder context can emit multiple independent frames.
    MULTI_FRAME,

    /// The decoder accepts concatenated frames and exposes validated boundaries to incremental callers.
    CONCATENATED_FRAMES
}

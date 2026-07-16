// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.zstd.internal.ZstdFrameHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Selects the physical framing used for Zstandard compressed data.
@NotNullByDefault
public enum ZstdFrameFormat {
    /// Uses the standard four-byte Zstandard frame magic and permits skippable frames while decoding.
    STANDARD,

    /// Omits the standard frame magic and requires explicit format selection while decoding.
    MAGICLESS;

    /// Parses one frame header without changing the source buffer state.
    public ZstdFrameInfo frameInfo(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.parse(source, this == MAGICLESS);
    }

    /// Returns one complete frame's compressed size without changing the source buffer state.
    public long frameCompressedSize(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.frameCompressedSize(source, this == MAGICLESS);
    }
}

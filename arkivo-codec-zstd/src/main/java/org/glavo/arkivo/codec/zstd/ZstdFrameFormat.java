// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.zstd.internal.ZstdFrameHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Selects the physical framing used for Zstandard compressed data.
///
/// Frame inspection methods are read-only with respect to the supplied buffer. They require the complete bytes needed
/// for the requested header or frame-size calculation and report malformed or truncated structures as I/O errors.
@NotNullByDefault
public enum ZstdFrameFormat {
    /// Uses the standard four-byte Zstandard frame magic and permits skippable frames while decoding.
    STANDARD,

    /// Omits the standard frame magic and requires explicit format selection while decoding.
    MAGICLESS;

    /// Parses one frame header without changing the source buffer state.
    ///
    /// @param source the buffer whose remaining bytes begin with the header
    /// @return immutable metadata parsed according to this physical format
    /// @throws IOException if the header is truncated, malformed, or incompatible with this format
    /// @throws NullPointerException if {@code source} is {@code null}
    public ZstdFrameInfo frameInfo(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.parse(source, this == MAGICLESS);
    }

    /// Returns one complete frame's compressed size without changing the source buffer state.
    ///
    /// @param source the buffer whose remaining bytes contain the complete frame
    /// @return the complete frame size in bytes, including header, blocks, and any checksum
    /// @throws IOException if the frame is truncated, malformed, or incompatible with this format
    /// @throws NullPointerException if {@code source} is {@code null}
    public long frameCompressedSize(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.frameCompressedSize(source, this == MAGICLESS);
    }
}

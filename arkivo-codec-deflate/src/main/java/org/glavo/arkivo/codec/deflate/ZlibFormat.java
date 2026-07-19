// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Describes the discoverable zlib compression format.
///
/// This immutable descriptor recognizes a valid Deflate compression method, supported window code, and FCHECK value in
/// the two-byte zlib header without changing the supplied buffer. Payload and trailer validity are established only by
/// decoding.
@NotNullByDefault
public final class ZlibFormat implements CompressionFormat {
    /// The stable zlib format name.
    public static final String NAME = "zlib";

    /// The canonical zlib format instance.
    private static final ZlibFormat INSTANCE = new ZlibFormat();

    /// Creates the canonical zlib format descriptor.
    private ZlibFormat() {
    }

    /// Returns the canonical zlib format instance.
    ///
    /// @return the shared immutable format descriptor
    public static ZlibFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable zlib format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the number of leading bytes used to identify zlib streams.
    @Override
    public int probeSize() {
        return 2;
    }

    /// Returns whether the given prefix starts with a valid zlib header.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 2) {
            return false;
        }

        int compressionMethodAndFlags = Byte.toUnsignedInt(prefix.get(position));
        int flags = Byte.toUnsignedInt(prefix.get(position + 1));
        return (compressionMethodAndFlags & 0x0f) == 8
                && (compressionMethodAndFlags >> 4) <= 7
                && ((compressionMethodAndFlags << 8) + flags) % 31 == 0;
    }

    /// Returns the default immutable zlib codec.
    @Override
    public ZlibCodec defaultCodec() {
        return ZlibCodec.DEFAULT;
    }
}

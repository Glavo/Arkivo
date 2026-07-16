// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Describes the discoverable zlib compression format.
@NotNullByDefault
public final class ZlibCompressionFormat implements CompressionFormat {
    /// The canonical zlib format instance.
    private static final ZlibCompressionFormat INSTANCE = new ZlibCompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public ZlibCompressionFormat() {
    }

    /// Returns the canonical zlib format instance.
    public static ZlibCompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical zlib format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable zlib format name.
    @Override
    public String name() {
        return ZlibCodec.NAME;
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
    public CompressionCodec<?> defaultCodec() {
        return ZlibCodec.DEFAULT;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;

/// Describes the discoverable gzip compression format.
///
/// This immutable descriptor recognizes the two-byte gzip magic without changing the supplied buffer. It does not
/// validate the compression-method byte, optional header fields, payload, or trailer.
@NotNullByDefault
public final class GzipFormat implements CompressionFormat {
    /// The stable gzip format name.
    public static final String NAME = "gzip";

    /// The canonical gzip format instance.
    private static final GzipFormat INSTANCE = new GzipFormat();

    /// Creates the canonical Gzip format descriptor.
    private GzipFormat() {
    }

    /// Returns the canonical gzip format instance.
    ///
    /// @return the shared immutable format descriptor
    public static GzipFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable gzip format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common gzip file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("gz", "gzip");
    }

    /// Returns the number of leading bytes used to identify gzip streams.
    @Override
    public int probeSize() {
        return 2;
    }

    /// Returns whether the given prefix starts with the gzip stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        return prefix.remaining() >= 2
                && Byte.toUnsignedInt(prefix.get(position)) == 0x1f
                && Byte.toUnsignedInt(prefix.get(position + 1)) == 0x8b;
    }

    /// Returns the default immutable gzip codec.
    @Override
    public GzipCodec defaultCodec() {
        return GzipCodec.DEFAULT;
    }
}

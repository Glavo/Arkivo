// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;

/// Describes the discoverable gzip compression format.
@NotNullByDefault
public final class GzipCompressionFormat implements CompressionFormat {
    /// The canonical gzip format instance.
    private static final GzipCompressionFormat INSTANCE = new GzipCompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public GzipCompressionFormat() {
    }

    /// Returns the canonical gzip format instance.
    public static GzipCompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical gzip format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable gzip format name.
    @Override
    public String name() {
        return GzipCodec.NAME;
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
    public CompressionCodec<?> defaultCodec() {
        return GzipCodec.DEFAULT;
    }
}

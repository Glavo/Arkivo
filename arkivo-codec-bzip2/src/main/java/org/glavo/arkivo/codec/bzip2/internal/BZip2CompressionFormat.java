// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;

/// Describes the discoverable BZip2 compression format.
@NotNullByDefault
public final class BZip2CompressionFormat implements CompressionFormat {
    /// The canonical BZip2 format instance.
    private static final BZip2CompressionFormat INSTANCE = new BZip2CompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public BZip2CompressionFormat() {
    }

    /// Returns the canonical BZip2 format instance.
    public static BZip2CompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical BZip2 format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable BZip2 format name.
    @Override
    public String name() {
        return BZip2Codec.NAME;
    }

    /// Returns alternative stable names accepted for BZip2.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("bz2");
    }

    /// Returns common BZip2 file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("bz2", "bzip2");
    }

    /// Returns the number of leading bytes used to identify BZip2 streams.
    @Override
    public int probeSize() {
        return 4;
    }

    /// Returns whether the given prefix starts with the BZip2 stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < 4) {
            return false;
        }

        int blockSize = Byte.toUnsignedInt(prefix.get(position + 3));
        return prefix.get(position) == 'B'
                && prefix.get(position + 1) == 'Z'
                && prefix.get(position + 2) == 'h'
                && blockSize >= '1'
                && blockSize <= '9';
    }

    /// Returns the default immutable BZip2 codec.
    @Override
    public CompressionCodec defaultCodec() {
        return BZip2Codec.DEFAULT;
    }
}

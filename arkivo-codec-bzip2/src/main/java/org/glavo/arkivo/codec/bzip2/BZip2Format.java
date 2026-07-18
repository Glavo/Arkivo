// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;

/// Describes the discoverable BZip2 compression format.
///
/// This descriptor is immutable and safe for concurrent use. Prefix matching recognizes `BZh` followed by a valid
/// block-size digit without changing the supplied buffer; it does not validate the remainder of the stream.
@NotNullByDefault
public final class BZip2Format implements CompressionFormat {
    /// The stable BZip2 format name.
    public static final String NAME = "bzip2";

    /// The canonical BZip2 format instance.
    private static final BZip2Format INSTANCE = new BZip2Format();

    /// Creates a service-discoverable BZip2 format descriptor.
    public BZip2Format() {
    }

    /// Returns the canonical BZip2 format instance.
    ///
    /// @return the shared immutable format descriptor
    public static BZip2Format instance() {
        return INSTANCE;
    }

    /// Returns the stable BZip2 format name.
    @Override
    public String name() {
        return NAME;
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
    public BZip2Codec defaultCodec() {
        return BZip2Codec.DEFAULT;
    }
}

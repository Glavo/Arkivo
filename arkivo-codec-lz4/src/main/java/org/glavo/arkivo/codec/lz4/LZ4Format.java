// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/// Describes the discoverable standard LZ4 frame format.
@NotNullByDefault
public final class LZ4Format implements CompressionFormat {
    /// The stable standard LZ4 frame format name.
    public static final String NAME = "lz4";

    /// Standard LZ4 frame magic in little-endian byte order.
    public static final long FRAME_MAGIC = 0x184d_2204L;

    /// Lowest standard LZ4 skippable-frame magic value.
    public static final long FIRST_SKIPPABLE_FRAME_MAGIC = 0x184d_2a50L;

    /// Highest standard LZ4 skippable-frame magic value.
    public static final long LAST_SKIPPABLE_FRAME_MAGIC = 0x184d_2a5fL;

    /// Canonical LZ4 frame format instance.
    private static final LZ4Format INSTANCE = new LZ4Format();

    /// Creates a classpath-discoverable LZ4 frame format descriptor.
    public LZ4Format() {
    }

    /// Returns the canonical LZ4 frame service provider.
    public static LZ4Format provider() {
        return INSTANCE;
    }

    /// Returns the canonical LZ4 frame format instance.
    public static LZ4Format instance() {
        return INSTANCE;
    }

    /// Returns the stable standard LZ4 frame format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns alternative stable names accepted for the LZ4 frame format.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("lz4-frame");
    }

    /// Returns common LZ4 frame file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("lz4");
    }

    /// Returns the number of leading bytes used to identify an LZ4 frame.
    @Override
    public int probeSize() {
        return Integer.BYTES;
    }

    /// Returns whether the prefix starts with a standard or skippable LZ4 frame magic value.
    @Override
    public boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.remaining() < Integer.BYTES) {
            return false;
        }
        long magic = readUnsignedInt(prefix, prefix.position());
        return magic == FRAME_MAGIC || isSkippableFrameMagic(magic);
    }

    /// Returns whether an unsigned 32-bit value identifies an LZ4 skippable frame.
    public static boolean isSkippableFrameMagic(long magic) {
        return magic >= FIRST_SKIPPABLE_FRAME_MAGIC && magic <= LAST_SKIPPABLE_FRAME_MAGIC;
    }

    /// Returns the default immutable LZ4 frame codec.
    @Override
    public LZ4Codec defaultCodec() {
        return LZ4Codec.DEFAULT;
    }

    /// Reads an unsigned little-endian integer without changing the buffer state.
    private static long readUnsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(buffer.get(offset))
                        | Byte.toUnsignedInt(buffer.get(offset + 1)) << 8
                        | Byte.toUnsignedInt(buffer.get(offset + 2)) << 16
                        | Byte.toUnsignedInt(buffer.get(offset + 3)) << 24
        );
    }
}

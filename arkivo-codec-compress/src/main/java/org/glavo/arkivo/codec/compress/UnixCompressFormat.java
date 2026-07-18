// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/// Describes the discoverable Unix compress (`.Z`) stream format.
@NotNullByDefault
public final class UnixCompressFormat implements CompressionFormat {
    /// The stable Unix compress format name.
    public static final String NAME = "compress";

    /// The first byte of the Unix compress stream signature.
    public static final int MAGIC_FIRST_BYTE = 0x1f;

    /// The second byte of the Unix compress stream signature.
    public static final int MAGIC_SECOND_BYTE = 0x9d;

    /// The canonical Unix compress format instance.
    private static final UnixCompressFormat INSTANCE = new UnixCompressFormat();

    /// Creates a service-discoverable Unix compress format descriptor.
    public UnixCompressFormat() {
    }

    /// Returns the canonical Unix compress format instance.
    public static UnixCompressFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable Unix compress format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common alternative names for Unix compress streams.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("z", "unix-compress");
    }

    /// Returns common Unix compress and compressed-TAR file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("Z", "taz");
    }

    /// Returns the complete fixed-header size requested for format probing.
    @Override
    public int probeSize() {
        return 3;
    }

    /// Returns whether the supplied prefix starts with the Unix compress signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        int position = prefix.position();
        return prefix.remaining() >= 2
                && Byte.toUnsignedInt(prefix.get(position)) == MAGIC_FIRST_BYTE
                && Byte.toUnsignedInt(prefix.get(position + 1)) == MAGIC_SECOND_BYTE;
    }

    /// Returns the default immutable Unix compress codec.
    @Override
    public UnixCompressCodec defaultCodec() {
        return UnixCompressCodec.DEFAULT;
    }
}

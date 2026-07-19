// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.zstd.internal.ZstdFrameHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;

/// Describes the discoverable standard Zstandard compression format.
///
/// This immutable descriptor recognizes standard and skippable four-byte frame magic values without changing the
/// supplied buffer. It deliberately does not recognize magicless frames, which require explicit
/// [ZstdFrameFormat#MAGICLESS] selection.
@NotNullByDefault
public final class ZstdFormat implements CompressionFormat {
    /// The stable Zstandard format name.
    public static final String NAME = "zstd";

    /// The canonical Zstandard format instance.
    private static final ZstdFormat INSTANCE = new ZstdFormat();

    /// Creates the canonical Zstandard format descriptor.
    private ZstdFormat() {
    }

    /// Returns the canonical Zstandard format instance.
    ///
    /// @return the canonical format instance
    public static ZstdFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable Zstandard format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common Zstandard file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("zst", "zstd");
    }

    /// Returns the number of leading bytes used to identify standard Zstandard streams.
    @Override
    public int probeSize() {
        return 4;
    }

    /// Returns whether the prefix starts with a standard Zstandard frame or skippable-frame signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        return ZstdFrameHeader.hasFrameMagic(prefix);
    }

    /// Returns the default immutable Zstandard codec.
    @Override
    public ZstdCodec defaultCodec() {
        return ZstdCodec.DEFAULT;
    }
}

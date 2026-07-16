// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;

/// Describes the discoverable standard Zstandard compression format.
@NotNullByDefault
public final class ZstdCompressionFormat implements CompressionFormat {
    /// The canonical Zstandard format instance.
    private static final ZstdCompressionFormat INSTANCE = new ZstdCompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public ZstdCompressionFormat() {
    }

    /// Returns the canonical Zstandard format instance.
    public static ZstdCompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical Zstandard format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable Zstandard format name.
    @Override
    public String name() {
        return ZstdCodec.NAME;
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
    public CompressionCodec<?> defaultCodec() {
        return ZstdCodec.DEFAULT;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Describes the discoverable raw LZMA compression format.
@NotNullByDefault
public final class RawLZMAFormat implements CompressionFormat {
    /// The stable raw LZMA format name.
    public static final String NAME = "lzma-raw";

    /// The canonical raw LZMA format instance.
    private static final RawLZMAFormat INSTANCE = new RawLZMAFormat();

    /// Creates a classpath-discoverable raw LZMA format descriptor.
    public RawLZMAFormat() {
    }

    /// Returns the canonical raw LZMA service provider.
    public static RawLZMAFormat provider() {
        return INSTANCE;
    }

    /// Returns the canonical raw LZMA format instance.
    public static RawLZMAFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable raw LZMA format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the alternate raw LZMA name.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("raw-lzma");
    }

    /// Returns no file extensions because raw LZMA has no self-describing container.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns the default immutable raw LZMA codec.
    @Override
    public RawLZMACodec defaultCodec() {
        return RawLZMACodec.DEFAULT;
    }
}

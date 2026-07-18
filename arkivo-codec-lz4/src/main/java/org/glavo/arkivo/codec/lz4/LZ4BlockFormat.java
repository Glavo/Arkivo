// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Describes the headerless LZ4 block format whose sizes are supplied out of band.
@NotNullByDefault
public final class LZ4BlockFormat implements CompressionFormat {
    /// The stable raw LZ4 block format name.
    public static final String NAME = "lz4-block";

    /// Canonical raw LZ4 block format instance.
    private static final LZ4BlockFormat INSTANCE = new LZ4BlockFormat();

    /// Creates a service-discoverable raw LZ4 block format descriptor.
    public LZ4BlockFormat() {
    }

    /// Returns the canonical raw LZ4 block format instance.
    public static LZ4BlockFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable raw LZ4 block format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns alternative stable names accepted for raw LZ4 blocks.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("lz4-raw");
    }

    /// Returns no extensions because raw LZ4 blocks have no self-describing file representation.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns the default immutable raw LZ4 block codec.
    @Override
    public LZ4BlockCodec defaultCodec() {
        return LZ4BlockCodec.DEFAULT;
    }
}

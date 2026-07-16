// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.deflate.Deflate64Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Describes the discoverable raw Deflate64 compression format.
@NotNullByDefault
public final class Deflate64CompressionFormat implements CompressionFormat {
    /// The canonical raw Deflate64 format instance.
    private static final Deflate64CompressionFormat INSTANCE = new Deflate64CompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public Deflate64CompressionFormat() {
    }

    /// Returns the canonical raw Deflate64 format instance.
    public static Deflate64CompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical raw Deflate64 format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable raw Deflate64 format name.
    @Override
    public String name() {
        return Deflate64Codec.NAME;
    }

    /// Returns alternative stable names accepted for Deflate64.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("deflate-64");
    }

    /// Returns no standalone file extensions because Deflate64 is an embedded raw stream format.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns the default immutable raw Deflate64 codec.
    @Override
    public CompressionCodec defaultCodec() {
        return Deflate64Codec.DEFAULT;
    }
}

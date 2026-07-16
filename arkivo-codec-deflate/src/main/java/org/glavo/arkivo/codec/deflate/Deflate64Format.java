// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Describes the discoverable raw Deflate64 compression format.
@NotNullByDefault
public final class Deflate64Format implements CompressionFormat {
    /// The stable raw Deflate64 format name.
    public static final String NAME = "deflate64";

    /// The canonical raw Deflate64 format instance.
    private static final Deflate64Format INSTANCE = new Deflate64Format();

    /// Creates a stateless format descriptor for service discovery.
    public Deflate64Format() {
    }

    /// Returns the canonical raw Deflate64 format instance.
    public static Deflate64Format instance() {
        return INSTANCE;
    }

    /// Returns the stable raw Deflate64 format name.
    @Override
    public String name() {
        return NAME;
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
    public Deflate64Codec defaultCodec() {
        return Deflate64Codec.DEFAULT;
    }
}

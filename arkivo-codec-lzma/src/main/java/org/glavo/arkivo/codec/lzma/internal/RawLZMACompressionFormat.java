// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Describes the discoverable raw LZMA compression format.
@NotNullByDefault
public final class RawLZMACompressionFormat implements CompressionFormat {
    /// The canonical raw LZMA format instance.
    private static final RawLZMACompressionFormat INSTANCE = new RawLZMACompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public RawLZMACompressionFormat() {
    }

    /// Returns the canonical raw LZMA format instance.
    public static RawLZMACompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical raw LZMA format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable raw LZMA format name.
    @Override
    public String name() {
        return RawLZMACodec.NAME;
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
    public CompressionCodec<?> defaultCodec() {
        return RawLZMACodec.DEFAULT;
    }
}

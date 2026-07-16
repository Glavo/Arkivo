// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Describes the discoverable raw PPMd7 compression format.
@NotNullByDefault
public final class PPMdCompressionFormat implements CompressionFormat {
    /// The canonical raw PPMd7 format instance.
    private static final PPMdCompressionFormat INSTANCE = new PPMdCompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public PPMdCompressionFormat() {
    }

    /// Returns the canonical raw PPMd7 format instance.
    public static PPMdCompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical raw PPMd7 format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable raw PPMd7 format name.
    @Override
    public String name() {
        return PPMdCodec.NAME;
    }

    /// Returns the PPMd7 alias used by 7z containers.
    @Override
    public @Unmodifiable List<String> aliases() {
        return List.of("ppmd7");
    }

    /// Returns no standalone extension because PPMd7 is an embedded raw stream format.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of();
    }

    /// Returns the default immutable raw PPMd7 codec.
    @Override
    public CompressionCodec<?> defaultCodec() {
        return PPMdCodec.DEFAULT;
    }
}

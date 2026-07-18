// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Describes the registered raw PPMd7 compression format.
///
/// Headerless PPMd7 has no fixed signature or standalone file extension and requires model parameters and decoded size
/// from its embedding container. This immutable descriptor is therefore intended for explicit selection.
@NotNullByDefault
public final class PPMdFormat implements CompressionFormat {
    /// The stable raw PPMd7 format name.
    public static final String NAME = "ppmd";

    /// The canonical raw PPMd7 format instance.
    private static final PPMdFormat INSTANCE = new PPMdFormat();

    /// Creates a service-discoverable PPMd format descriptor.
    public PPMdFormat() {
    }

    /// Returns the canonical raw PPMd7 format instance.
    ///
    /// @return the canonical format instance
    public static PPMdFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable raw PPMd7 format name.
    @Override
    public String name() {
        return NAME;
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
    public PPMdCodec defaultCodec() {
        return PPMdCodec.DEFAULT;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the discoverable raw Deflate compression format.
@NotNullByDefault
public final class DeflateFormat implements CompressionFormat {
    /// The stable raw Deflate format name.
    public static final String NAME = "deflate";

    /// The canonical raw Deflate format instance.
    private static final DeflateFormat INSTANCE = new DeflateFormat();

    /// Creates a stateless format descriptor for service discovery.
    public DeflateFormat() {
    }

    /// Returns the canonical raw Deflate format instance.
    public static DeflateFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable raw Deflate format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the default immutable raw Deflate codec.
    @Override
    public DeflateCodec defaultCodec() {
        return DeflateCodec.DEFAULT;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the discoverable raw Deflate compression format.
@NotNullByDefault
public final class DeflateCompressionFormat implements CompressionFormat {
    /// The canonical raw Deflate format instance.
    private static final DeflateCompressionFormat INSTANCE = new DeflateCompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public DeflateCompressionFormat() {
    }

    /// Returns the canonical raw Deflate format instance.
    public static DeflateCompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical raw Deflate format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable raw Deflate format name.
    @Override
    public String name() {
        return DeflateCodec.NAME;
    }

    /// Returns the default immutable raw Deflate codec.
    @Override
    public CompressionCodec defaultCodec() {
        return DeflateCodec.DEFAULT;
    }
}

// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the discoverable raw LZMA2 compression format.
@NotNullByDefault
public final class LZMA2CompressionFormat implements CompressionFormat {
    /// The canonical raw LZMA2 format instance.
    private static final LZMA2CompressionFormat INSTANCE = new LZMA2CompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public LZMA2CompressionFormat() {
    }

    /// Returns the canonical raw LZMA2 format instance.
    public static LZMA2CompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical raw LZMA2 format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable raw LZMA2 format name.
    @Override
    public String name() {
        return LZMA2Codec.NAME;
    }

    /// Returns the default immutable raw LZMA2 codec.
    @Override
    public CompressionCodec defaultCodec() {
        return LZMA2Codec.DEFAULT;
    }
}

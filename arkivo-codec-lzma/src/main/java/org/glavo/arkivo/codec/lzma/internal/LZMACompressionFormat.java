// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.lzma.LZMACodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the discoverable LZMA-alone compression format.
@NotNullByDefault
public final class LZMACompressionFormat implements CompressionFormat {
    /// The canonical LZMA-alone format instance.
    private static final LZMACompressionFormat INSTANCE = new LZMACompressionFormat();

    /// Creates a stateless format descriptor for service discovery.
    public LZMACompressionFormat() {
    }

    /// Returns the canonical LZMA-alone format instance.
    public static LZMACompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical LZMA-alone format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable LZMA-alone format name.
    @Override
    public String name() {
        return LZMACodec.NAME;
    }

    /// Returns the default immutable LZMA-alone codec.
    @Override
    public CompressionCodec<?> defaultCodec() {
        return LZMACodec.DEFAULT;
    }
}

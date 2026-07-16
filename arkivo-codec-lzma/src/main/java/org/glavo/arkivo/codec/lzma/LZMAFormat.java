// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the discoverable LZMA-alone compression format.
@NotNullByDefault
public final class LZMAFormat implements CompressionFormat {
    /// The stable LZMA-alone format name.
    public static final String NAME = "lzma";

    /// The canonical LZMA-alone format instance.
    private static final LZMAFormat INSTANCE = new LZMAFormat();

    /// Creates a stateless format descriptor for service discovery.
    public LZMAFormat() {
    }

    /// Returns the canonical LZMA-alone format instance.
    public static LZMAFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable LZMA-alone format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the default immutable LZMA-alone codec.
    @Override
    public LZMACodec defaultCodec() {
        return LZMACodec.DEFAULT;
    }
}

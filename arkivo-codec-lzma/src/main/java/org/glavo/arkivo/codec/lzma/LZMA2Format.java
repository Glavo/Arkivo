// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the registered raw LZMA2 compression format.
///
/// Raw LZMA2 has no reliable fixed signature and does not carry its dictionary size. This immutable descriptor is
/// intended for explicit selection by a container that supplies the required properties.
@NotNullByDefault
public final class LZMA2Format implements CompressionFormat {
    /// The stable raw LZMA2 format name.
    public static final String NAME = "lzma2";

    /// The canonical raw LZMA2 format instance.
    private static final LZMA2Format INSTANCE = new LZMA2Format();

    /// Creates the canonical LZMA2 format descriptor.
    private LZMA2Format() {
    }

    /// Returns the canonical raw LZMA2 format instance.
    ///
    /// @return the canonical format instance
    public static LZMA2Format instance() {
        return INSTANCE;
    }

    /// Returns the stable raw LZMA2 format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the default immutable raw LZMA2 codec.
    @Override
    public LZMA2Codec defaultCodec() {
        return LZMA2Codec.DEFAULT;
    }
}

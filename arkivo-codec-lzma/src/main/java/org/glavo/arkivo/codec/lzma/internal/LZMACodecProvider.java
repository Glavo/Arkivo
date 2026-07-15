// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.lzma.LZMACodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default LZMA-alone codec through compression codec service discovery.
@NotNullByDefault
public final class LZMACodecProvider implements CompressionCodecProvider {
    /// Creates a stateless LZMA-alone codec provider.
    public LZMACodecProvider() {
    }

    /// Returns the default immutable LZMA-alone codec.
    @Override
    public LZMACodec defaultCodec() {
        return LZMACodec.DEFAULT;
    }
}
// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default LZMA2 codec through compression codec service discovery.
@NotNullByDefault
public final class LZMA2CodecProvider implements CompressionCodecProvider {
    /// Creates a stateless LZMA2 codec provider.
    public LZMA2CodecProvider() {
    }

    /// Returns the default immutable LZMA2 codec.
    @Override
    public LZMA2Codec defaultCodec() {
        return LZMA2Codec.DEFAULT;
    }
}
// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default Zstandard codec through compression codec service discovery.
@NotNullByDefault
public final class ZstdCodecProvider implements CompressionCodecProvider {
    /// Creates a stateless Zstandard codec provider.
    public ZstdCodecProvider() {
    }

    /// Returns the default immutable Zstandard codec.
    @Override
    public ZstdCodec defaultCodec() {
        return ZstdCodec.DEFAULT;
    }
}
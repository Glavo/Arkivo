// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default zlib codec through compression codec service discovery.
@NotNullByDefault
public final class ZlibCodecProvider implements CompressionCodecProvider {
    /// Creates a stateless zlib codec provider.
    public ZlibCodecProvider() {
    }

    /// Returns the default immutable zlib codec.
    @Override
    public ZlibCodec defaultCodec() {
        return ZlibCodec.DEFAULT;
    }
}
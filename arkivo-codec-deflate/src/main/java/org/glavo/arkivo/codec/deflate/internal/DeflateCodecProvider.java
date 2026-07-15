// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default raw Deflate codec through compression codec service discovery.
@NotNullByDefault
public final class DeflateCodecProvider implements CompressionCodecProvider {
    /// Creates a stateless raw Deflate codec provider.
    public DeflateCodecProvider() {
    }

    /// Returns the default immutable raw Deflate codec.
    @Override
    public DeflateCodec defaultCodec() {
        return DeflateCodec.DEFAULT;
    }
}
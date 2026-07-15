// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default gzip codec through compression codec service discovery.
@NotNullByDefault
public final class GzipCodecProvider implements CompressionCodecProvider {
    /// Creates a stateless gzip codec provider.
    public GzipCodecProvider() {
    }

    /// Returns the default immutable gzip codec.
    @Override
    public GzipCodec defaultCodec() {
        return GzipCodec.DEFAULT;
    }
}
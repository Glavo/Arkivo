// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.deflate.Deflate64Codec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default Deflate64 codec through compression codec service discovery.
@NotNullByDefault
public final class Deflate64CodecProvider implements CompressionCodecProvider {
    /// Creates a stateless Deflate64 codec provider.
    public Deflate64CodecProvider() {
    }

    /// Returns the default immutable Deflate64 codec.
    @Override
    public Deflate64Codec defaultCodec() {
        return Deflate64Codec.DEFAULT;
    }
}
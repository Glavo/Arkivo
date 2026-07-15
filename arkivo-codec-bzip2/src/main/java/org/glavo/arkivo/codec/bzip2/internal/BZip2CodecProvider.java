// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default BZip2 codec through compression codec service discovery.
@NotNullByDefault
public final class BZip2CodecProvider implements CompressionCodecProvider {
    /// Creates a stateless BZip2 codec provider.
    public BZip2CodecProvider() {
    }

    /// Returns the default immutable BZip2 codec.
    @Override
    public BZip2Codec defaultCodec() {
        return BZip2Codec.DEFAULT;
    }
}
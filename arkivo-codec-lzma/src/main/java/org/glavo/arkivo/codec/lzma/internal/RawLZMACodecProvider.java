// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default raw LZMA codec through compression codec service discovery.
@NotNullByDefault
public final class RawLZMACodecProvider implements CompressionCodecProvider {
    /// Creates a stateless raw LZMA codec provider.
    public RawLZMACodecProvider() {
    }

    /// Returns the default immutable raw LZMA codec.
    @Override
    public RawLZMACodec defaultCodec() {
        return RawLZMACodec.DEFAULT;
    }
}